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

        runCatching { setForeground(foregroundInfo()) }

        repository.updateDownload(episodeId, DownloadState.DOWNLOADING, 0, null)

        val target = DownloadFiles.target(applicationContext, episodeId)
        val part = DownloadFiles.partFile(applicationContext, episodeId)
        val meta = DownloadFiles.metaFile(applicationContext, episodeId)
        part.parentFile?.mkdirs()

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
                throw c
            } catch (p: PermanentHttpException) {
                discardPartial(part, meta)
                repository.updateDownload(episodeId, DownloadState.FAILED, 0, null)
                Result.failure()
            } catch (io: IOException) {
                coroutineContext.ensureActive()
                if (runAttemptCount < MAX_ATTEMPTS) {
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
            val appending = response.code == HTTP_PARTIAL_CONTENT && resumeFrom != null
            val startAt = if (appending) resumeFrom.offset else 0L
            val body = response.body ?: throw IOException("empty response body")
            val total = body.contentLength().takeIf { it > 0 }?.plus(startAt) ?: 0L

            PartialDownload(
                validator = response.header("ETag") ?: response.header("Last-Modified"),
                totalBytes = total,
            ).write(meta)

            var downloaded = startAt
            var lastReported = -1
            body.byteStream().use { input ->
                FileOutputStream(part, appending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
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
            if (total > 0 && downloaded != total) {
                throw IOException("truncated download: $downloaded/$total bytes")
            }
        }
    }

    private data class ResumePoint(val offset: Long, val validator: String)

    private fun discardPartial(part: File, meta: File) {
        part.delete()
        meta.delete()
    }

    private fun promote(part: File, target: File) {
        target.delete()
        if (!part.renameTo(target)) {
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

    private class PermanentHttpException(code: Int) : IOException("HTTP $code")

    companion object {
        const val KEY_EPISODE_ID = "episode_id"

        private const val MAX_ATTEMPTS = 3

        private const val HTTP_PARTIAL_CONTENT = 206
    }
}
