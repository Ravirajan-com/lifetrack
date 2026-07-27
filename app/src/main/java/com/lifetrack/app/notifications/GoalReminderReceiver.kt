package com.lifetrack.app.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lifetrack.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class GoalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val goalId = intent.getLongExtra("goal_id", -1L)
        if (goalId == -1L) return

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.get(context)
            val dao = db.goalDao()
            val today = LocalDate.now().toEpochDay()
            
            // Check if goal is still active and not completed today
            val goals = dao.goalsForDay(today).first()
            val done = dao.completionsOn(today).first().map { it.goalId }.toSet()
            
            val goal = goals.find { it.id == goalId }
            if (goal != null && goal.id !in done) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notif = NotificationCompat.Builder(context, CHANNEL_GOALS)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("Goal Reminder")
                    .setContentText(goal.title)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                nm.notify(goal.id.toInt(), notif)
            }
        }
    }
}
