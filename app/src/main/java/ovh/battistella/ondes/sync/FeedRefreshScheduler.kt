package ovh.battistella.ondes.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules (or cancels) the periodic background feed refresh. */
object FeedRefreshScheduler {

    private const val WORK_NAME = "feed_refresh"

    /**
     * How often the refresh asks to run, and how late in each interval it may be
     * placed.
     *
     * Asking hourly rather than every three hours is not about checking feeds
     * more eagerly — an unchanged feed answers 304 and costs almost nothing.
     * It is about how the system rations background work: an app the user hasn't
     * opened drifts into a standby bucket that grants a job a handful of windows
     * a day, and a three-hour request that misses its window waits three more
     * hours for the next one. More requested windows mean more chances that one
     * of them is granted before the user gives up and opens the app.
     */
    private const val INTERVAL_MINUTES = 60L
    private const val FLEX_MINUTES = 15L

    /** First backoff step after a failed cycle; doubles from there. */
    private const val BACKOFF_MINUTES = 5L

    /** Turn the periodic refresh on or off to match the user's preference. */
    fun apply(context: Context, enabled: Boolean) {
        if (enabled) schedule(context) else cancel(context)
    }

    private fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
            FLEX_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            // The default backoff starts at 30 seconds, which re-fires a failed
            // cycle three times inside a minute — all three likely to hit the
            // same unreachable network. Minutes apart, a retry can actually find
            // different conditions.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE (not KEEP) so a changed interval or constraint from an app
            // update actually takes effect instead of being frozen at whatever was
            // first scheduled (issue P2). It keeps the existing period's start, so
            // re-applying this on every launch does not push the next run out.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
