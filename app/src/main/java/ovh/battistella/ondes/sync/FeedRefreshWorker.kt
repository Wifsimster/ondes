package ovh.battistella.ondes.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.download.DownloadManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Periodically refreshes every subscribed feed in the background and posts a
 * notification for any newly published episodes.
 */
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PodcastRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadManager: DownloadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Inside the try: a failure to read the preferences used to escape
            // doWork entirely, which WorkManager can only read as a crash.
            val settings = settingsRepository.settings.first()
            if (!settings.backgroundRefresh) return Result.success()

            repository.refreshSubscriptions()
            // Everything still owed an announcement, not just what this cycle
            // happened to insert — a foreground refresh may have got there first.
            val pending = repository.pendingNewEpisodes()

            // Auto-download fresh episodes for subscriptions that opted in
            // (DownloadManager already honours the Wi-Fi-only constraint).
            pending.forEach { batch ->
                if (repository.getPodcastOnce(batch.feedUrl)?.autoDownload == true) {
                    batch.episodes.forEach { downloadManager.enqueue(it.id) }
                }
            }
            if (settings.newEpisodeNotifications) {
                NewEpisodeNotifier.notify(appContext, batches = pending)
            }
            // Cleared whether or not anything was actually posted — with
            // notifications switched off (or denied at the OS level) the backlog
            // would otherwise grow until the day they are turned back on, and
            // arrive as every episode since install in one burst.
            repository.clearPendingNotifications(pending.flatMap { it.episodes.map(EpisodeEntity::id) })

            // Stamp the cycle so Settings can show when the app last managed to
            // check — a schedule the system has quietly stopped running is
            // otherwise invisible.
            settingsRepository.setLastRefreshAt(System.currentTimeMillis())
            Result.success()
        } catch (c: CancellationException) {
            // A stop (e.g. constraints lost) is not a failure — never re-run it as
            // if the refresh itself errored (issue P1-13).
            throw c
        } catch (t: Throwable) {
            // Give the network a few backed-off retries, then stop so a persistently
            // failing feed doesn't retry forever. Failure is not terminal for
            // periodic work: WorkManager re-enqueues it for the next interval.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        /** Backed-off retries before a failing refresh cycle gives up until next tick. */
        const val MAX_ATTEMPTS = 3
    }
}
