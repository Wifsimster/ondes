package ovh.battistella.ondes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.download.DownloadManager
import ovh.battistella.ondes.download.DownloadNotifications
import ovh.battistella.ondes.sync.FeedRefreshScheduler
import ovh.battistella.ondes.sync.NewEpisodeNotifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class OndesApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var downloadManager: DownloadManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Coil shares the app's single tuned [OkHttpClient] (one connection pool /
     * timeout policy for feeds, downloads and artwork) instead of spinning up
     * its own, with explicit memory + disk cache budgets for cover art.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()
        NewEpisodeNotifier.createChannel(this)
        DownloadNotifications.createChannel(this)
        scheduleBackgroundRefresh()
        // Clear any downloaded audio no episode references any more (unsubscribed
        // podcasts, cancelled-at-the-finish strands, stale .part files).
        downloadManager.sweepOrphans()
    }

    /** Honour the saved background-refresh preference on every launch. */
    private fun scheduleBackgroundRefresh() {
        appScope.launch {
            val settings = settingsRepository.settings.first()
            FeedRefreshScheduler.apply(this@OndesApp, settings.backgroundRefresh)
        }
    }
}
