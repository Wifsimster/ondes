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
            val settings = settingsRepository.settings.first()
            if (!settings.backgroundRefresh) return Result.success()

            repository.refreshSubscriptions()
            val pending = repository.pendingNewEpisodes()

            pending.forEach { batch ->
                if (repository.getPodcastOnce(batch.feedUrl)?.autoDownload == true) {
                    batch.episodes.forEach { downloadManager.enqueue(it.id) }
                }
            }
            if (settings.newEpisodeNotifications) {
                NewEpisodeNotifier.notify(appContext, batches = pending)
            }
            repository.clearPendingNotifications(pending.flatMap { it.episodes.map(EpisodeEntity::id) })

            settingsRepository.setLastRefreshAt(System.currentTimeMillis())
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
