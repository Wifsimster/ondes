package ovh.battistella.ondes.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object FeedRefreshScheduler {

    private const val WORK_NAME = "feed_refresh"

    private const val INTERVAL_MINUTES = 60L
    private const val FLEX_MINUTES = 15L

    private const val BACKOFF_MINUTES = 5L

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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
