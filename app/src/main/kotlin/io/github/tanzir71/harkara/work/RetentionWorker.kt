package io.github.tanzir71.harkara.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.core.RetentionPolicy
import java.time.Instant

/** WorkManager job shell for local retention cleanup. */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? HarkaraApplication ?: return Result.failure()
        val now = Instant.now()
        val database = app.container.database
        database.postDao().purgeExpired(now.toEpochMilli())
        database.dedupeSeenDao().purgeOlderThan(RetentionPolicy().dedupeCutoff(now).toEpochMilli())
        return Result.success()
    }
}
