package ovh.battistella.ondes.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules (or cancels) the periodic background feed refresh. */
object FeedRefreshScheduler {

    private const val WORK_NAME = "feed_refresh"
    private const val INTERVAL_HOURS = 3L

    /** Turn the periodic refresh on or off to match the user's preference. */
    fun apply(context: Context, enabled: Boolean) {
        if (enabled) schedule(context) else cancel(context)
    }

    private fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE (not KEEP) so a changed interval or constraint from an app
            // update actually takes effect instead of being frozen at whatever was
            // first scheduled (issue P2).
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
