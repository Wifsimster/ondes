package ovh.battistella.ondes.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.repository.PodcastRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

@HiltWorker
class EpisodeDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PodcastRepository,
    private val client: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        val episode = repository.getEpisode(episodeId) ?: return Result.failure()

        // Run as a foreground/expedited worker so the OS is unlikely to kill us
        // mid-stream and leave the row stuck at DOWNLOADING. Promotion can be
        // refused (e.g. background-start limits); fall back to a normal worker.
        runCatching { setForeground(foregroundInfo()) }

        repository.updateDownload(episodeId, DownloadState.DOWNLOADING, 0, null)

        val target = DownloadFiles.target(applicationContext, episodeId)
        val part = DownloadFiles.partFile(applicationContext, episodeId)
        val meta = DownloadFiles.metaFile(applicationContext, episodeId)
        part.parentFile?.mkdirs()

        // All the blocking socket/file I/O runs on Dispatchers.IO — the default
        // CoroutineWorker dispatcher is Dispatchers.Default, whose small pool
        // should not be tied up on long streaming reads (issue P1-12).
        return withContext(Dispatchers.IO) {
            try {
                streamToPart(episode.audioUrl, part, meta, episodeId)
                promote(part, target)
                meta.delete()
                repository.updateDownload(
                    episodeId, DownloadState.DOWNLOADED, 100, target.absolutePath,
                )
                Result.success()
            } catch (c: CancellationException) {
                // Cooperative stop (constraint lost, or the user cancelled). The
                // partial file and its validator are deliberately KEPT so
                // WorkManager's re-run resumes from where this one stopped instead
                // of re-downloading over metered data (opt. 5); a user-initiated
                // cancel deletes them in DownloadManager.cancel(). Don't write a
                // misleading FAILED here (issue P1-11).
                throw c
            } catch (p: PermanentHttpException) {
                // A 4xx status — retrying the same request won't help, so fail fast.
                discardPartial(part, meta)
                repository.updateDownload(episodeId, DownloadState.FAILED, 0, null)
                Result.failure()
            } catch (io: IOException) {
                // A dropped connection is transient. Distinguish a genuine network
                // failure from a stop: if the worker was stopped, ensureActive()
                // rethrows CancellationException rather than recording a
                // misleading FAILED (issues P1-11, P1-12).
                coroutineContext.ensureActive()
                if (runAttemptCount < MAX_ATTEMPTS) {
                    // Keep the bytes already on disk; the retry resumes them.
                    repository.updateDownload(episodeId, DownloadState.QUEUED, 0, null)
                    Result.retry()
                } else {
                    discardPartial(part, meta)
                    repository.updateDownload(episodeId, DownloadState.FAILED, 0, null)
                    Result.failure()
                }
            } catch (t: Throwable) {
                discardPartial(part, meta)
                repository.updateDownload(episodeId, DownloadState.FAILED, 0, null)
                Result.failure()
            }
        }
    }

    /**
     * Stream the episode into [part], resuming an earlier attempt when possible,
     * and report progress.
     *
     * A partial file is only resumed against the validator recorded in [meta]:
     * the request carries `If-Range`, so a server whose file has since changed
     * (a rotated or re-encoded enclosure) answers `200` with the whole body and
     * the stale bytes are dropped rather than silently spliced onto new ones
     * (opt. 5). Throws on any non-success status, and on a truncated body (bytes
     * on disk ≠ Content-Length) so a clean early close can't masquerade as a
     * complete download (issue P0-5).
     */
    private suspend fun streamToPart(url: String, part: File, meta: File, episodeId: String) {
        val resumeFrom = PartialDownload.read(meta)?.validator
            ?.takeIf { part.isFile && part.length() > 0 }
            ?.let { validator -> ResumePoint(part.length(), validator) }

        val request = Request.Builder().url(url).apply {
            resumeFrom?.let {
                header("Range", "bytes=${it.offset}-")
                header("If-Range", it.validator)
            }
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.isTransient()) throw IOException("transient HTTP ${response.code}")
                throw PermanentHttpException(response.code)
            }
            // 206 means the server honoured the range; anything else (including a
            // 200 because the validator no longer matches) restarts from zero.
            val appending = response.code == HTTP_PARTIAL_CONTENT && resumeFrom != null
            val startAt = if (appending) resumeFrom.offset else 0L
            val body = response.body ?: throw IOException("empty response body")
            val total = body.contentLength().takeIf { it > 0 }?.plus(startAt) ?: 0L

            // Record what these bytes belong to, so the next attempt can decide
            // whether resuming them is safe.
            PartialDownload(
                validator = response.header("ETag") ?: response.header("Last-Modified"),
                totalBytes = total,
            ).write(meta)

            var downloaded = startAt
            var lastReported = -1
            body.byteStream().use { input ->
                FileOutputStream(part, /* append = */ appending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Cooperative cancellation: CoroutineWorker cancels this
                        // coroutine when WorkManager stops the job, so we stop
                        // pulling bytes at the next chunk boundary rather than
                        // streaming on over metered data. A fully stalled socket is
                        // backstopped by the OkHttp read timeout.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt()
                            if (pct != lastReported && pct % 5 == 0) {
                                lastReported = pct
                                repository.updateDownload(
                                    episodeId, DownloadState.DOWNLOADING, pct, null,
                                )
                            }
                        }
                    }
                    output.flush()
                }
            }
            // Guard against a truncated transfer: a clean early close leaves a
            // short file that must NOT be promoted to a valid download.
            if (total > 0 && downloaded != total) {
                throw IOException("truncated download: $downloaded/$total bytes")
            }
        }
    }

    /** Where a resumed request should pick up, and the file it belongs to. */
    private data class ResumePoint(val offset: Long, val validator: String)

    private fun discardPartial(part: File, meta: File) {
        part.delete()
        meta.delete()
    }

    /** Atomically publish the verified partial file as the final download. */
    private fun promote(part: File, target: File) {
        target.delete()
        if (!part.renameTo(target)) {
            // Rename can fail across some filesystems; fall back to copy+delete.
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext, DownloadNotifications.CHANNEL_ID,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.downloading))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(DownloadNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun Response.isTransient(): Boolean = code == 429 || code in 500..599

    /** A non-retryable HTTP status (typically 4xx); fail fast instead of backing off. */
    private class PermanentHttpException(code: Int) : IOException("HTTP $code")

    companion object {
        const val KEY_EPISODE_ID = "episode_id"

        /** Total tries (initial + retries) before a download is marked FAILED. */
        private const val MAX_ATTEMPTS = 3

        private const val HTTP_PARTIAL_CONTENT = 206
    }
}
