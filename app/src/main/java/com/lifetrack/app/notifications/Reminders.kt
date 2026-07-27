package com.lifetrack.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lifetrack.app.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

const val CHANNEL_GOALS = "goal_reminders"

fun ensureNotificationChannel(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_GOALS, "Goal reminders", NotificationManager.IMPORTANCE_HIGH)
    )
}

/**
 * Runs periodically; notifies for each active goal due today whose reminder time has
 * arrived and which isn't completed yet. Simple + battery friendly for a skeleton.
 * (Later: switch to AlarmManager exact alarms per goal for minute-precise reminders.)
 */
class GoalReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val context = applicationContext
        ensureNotificationChannel(context)
        val dao = AppDatabase.get(context).goalDao()
        val today = LocalDate.now().toEpochDay()
        val now = LocalTime.now()

        runBlocking {
            val done = mutableSetOf<Long>()
            // Flows -> one-shot snapshots for the worker
            val goals = dao.goalsForDay(today).first()
            dao.completionsOn(today).first().forEach { done += it.goalId }

            goals.filter { g ->
                g.reminderHour != null && g.id !in done &&
                    now >= LocalTime.of(g.reminderHour, g.reminderMinute ?: 0)
            }.forEach { g ->
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notif = NotificationCompat.Builder(context, CHANNEL_GOALS)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("Goal reminder")
                    .setContentText(g.title)
                    .setAutoCancel(true)
                    .build()
                nm.notify(g.id.toInt(), notif)
            }
        }
        return Result.success()
    }
}

object ReminderScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<GoalReminderWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(Duration.between(LocalDateTime.now(), LocalDateTime.now().plusMinutes(1)))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "goal_reminders", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
