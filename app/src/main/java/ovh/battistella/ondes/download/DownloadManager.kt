package ovh.battistella.ondes.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val snackbar: SnackbarController,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(episodeId: String) {
        scope.launch {
            repository.updateDownload(episodeId, DownloadState.QUEUED, 0, null)
            // Read the Wi-Fi-only preference fresh, per enqueue, rather than off a
            // cached field populated by an async collect. That field could still
            // hold its default when WorkManager cold-starts the process for an
            // auto-download, silently downloading over metered data against the
            // user's explicit choice (issue P0-6).
            val wifiOnly = settingsRepository.settings.first().wifiOnlyDownloads
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
                .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episodeId))
                .setConstraints(constraints)
                // Expedited so a user-triggered download starts promptly and runs as
                // a foreground worker (less likely to be OS-killed mid-stream); falls
                // back to a normal request when the app is out of expedited quota.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request)
        }
    }

    fun cancel(episodeId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
        scope.launch {
            repository.updateDownload(episodeId, DownloadState.NONE, 0, null)
            // Remove any bytes already on disk. Before the download completes there
            // is no localFilePath in the DB, so delete the deterministic target and
            // its partial sibling — otherwise a cancelled-just-after-finish download
            // stranded its file with no way to remove it (issue P1-14).
            runCatching {
                DownloadFiles.target(context, episodeId).delete()
                DownloadFiles.partFile(context, episodeId).delete()
                DownloadFiles.metaFile(context, episodeId).delete()
            }
        }
    }

    /**
     * Delete the local file for an episode. [showUndo] offers a re-download via a
     * snackbar for user-initiated deletes; automatic delete-when-finished passes
     * false so it stays silent.
     */
    fun deleteDownload(episodeId: String, localPath: String?, showUndo: Boolean = true) {
        localPath?.let { runCatching { File(it).delete() } }
        // Also clear the deterministic artifacts, covering a stored path that has
        // drifted from the current naming scheme and any leftover partial file.
        runCatching {
            DownloadFiles.target(context, episodeId).delete()
            DownloadFiles.partFile(context, episodeId).delete()
            DownloadFiles.metaFile(context, episodeId).delete()
        }
        scope.launch { repository.updateDownload(episodeId, DownloadState.NONE, 0, null) }
        if (showUndo) {
            // Deleting only removes the local file; "Undo" simply re-downloads it.
            snackbar.show(
                text = context.getString(R.string.download_deleted),
                actionLabel = context.getString(R.string.undo),
                onAction = { enqueue(episodeId) },
            )
        }
    }

    /**
     * Delete audio files in the downloads directory that no episode still points
     * at — cancelled-at-the-finish-line strands, files from unsubscribed
     * podcasts, and `.part`/`.meta` leftovers from downloads that were abandoned
     * rather than merely interrupted (issue P1-14).
     *
     * Partial files belonging to a download that is still QUEUED or DOWNLOADING
     * are spared: WorkManager will re-run that job and resume from those exact
     * bytes (opt. 5), so sweeping them would throw away the transfer this
     * process was killed in the middle of.
     */
    fun sweepOrphans() {
        scope.launch {
            val dir = DownloadFiles.dir(context)
            val files = dir.listFiles() ?: return@launch
            val kept = repository.getDownloadedOnce()
                .mapNotNull { it.localFilePath }
                .toHashSet()
            val pendingNames = repository.getPendingDownloadIds()
                .flatMapTo(HashSet()) { DownloadFiles.partialNames(it) }
            files.asSequence()
                .filter { it.isFile && DownloadFiles.isDownloadArtifact(it) }
                .filter { it.absolutePath !in kept }
                .filterNot { DownloadFiles.isPartialArtifact(it) && it.name in pendingNames }
                .forEach { runCatching { it.delete() } }
        }
    }

    private fun workName(episodeId: String) = "download_$episodeId"
}
