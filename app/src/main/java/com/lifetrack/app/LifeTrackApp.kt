package com.lifetrack.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.notifications.ReminderScheduler
import com.lifetrack.app.notifications.ensureNotificationChannel
import com.lifetrack.app.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LifeTrackApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        ReminderScheduler.schedule(this)
        scheduleSync()

        // Seed default expense categories. getOrCreateCategory is idempotent per category name,
        // so this is safe to run on every launch -- unlike the old "only if the whole table is
        // empty" check, which raced against merchant classification's own getOrCreateCategory
        // calls whenever both ran near app startup, occasionally creating two "Bills" rows (one
        // from each coroutine, each seeing the category didn't exist yet at the moment it
        // checked). Per-category idempotency plus the DB's unique index closes that race for good.
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@LifeTrackApp).expenseDao()
            listOf(
                "Food" to "#FF7043", "Groceries" to "#66BB6A", "Transport" to "#42A5F5",
                "Shopping" to "#AB47BC", "Bills" to "#FFA726", "Entertainment" to "#EC407A",
                "Health" to "#26A69A", "Other" to "#78909C"
            ).forEach { (name, color) ->
                dao.getOrCreateCategory(name, color, null)
            }
        }
    }

    private fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(12, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "drive_sync",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
