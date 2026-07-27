package com.lifetrack.app.ui.goals

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.entity.GoalCompletionEntity
import com.lifetrack.app.data.db.entity.GoalEntity
import com.lifetrack.app.notifications.ReminderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@kotlinx.coroutines.ExperimentalCoroutinesApi
class GoalsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).goalDao()
    
    val selectedDate = MutableStateFlow(LocalDate.now())

    val goals = selectedDate.flatMapLatest { date ->
        dao.goalsForDay(date.toEpochDay())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val completions = selectedDate.flatMapLatest { date ->
        dao.completionsOn(date.toEpochDay())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _streaks = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val streaks: StateFlow<Map<Long, Int>> = _streaks

    fun addGoal(title: String, recurring: Boolean, date: LocalDate, reminderHour: Int?, reminderMinute: Int?) =
        viewModelScope.launch {
            val id = dao.insertGoal(
                GoalEntity(
                    title = title,
                    isRecurring = recurring,
                    specificDate = if (recurring) null else date.toEpochDay(),
                    reminderHour = reminderHour,
                    reminderMinute = reminderMinute
                )
            )
            if (reminderHour != null) {
                ReminderManager.schedule(getApplication(), GoalEntity(id = id, title = title, isRecurring = recurring, reminderHour = reminderHour, reminderMinute = reminderMinute))
            }
        }

    fun toggle(goal: GoalEntity, done: Boolean) = viewModelScope.launch {
        val date = selectedDate.value.toEpochDay()
        if (done) dao.markDone(GoalCompletionEntity(goalId = goal.id, epochDay = date))
        else dao.unmark(goal.id, date)
        refreshStreak(goal.id)
    }

    fun archive(goal: GoalEntity) = viewModelScope.launch {
        dao.archiveGoal(goal.id)
        ReminderManager.cancel(getApplication(), goal.id)
    }

    fun refreshStreak(goalId: Long) = viewModelScope.launch {
        val days = dao.completionDays(goalId).toSet()
        val today = LocalDate.now().toEpochDay()
        var streak = 0
        var d = today
        // streak counts consecutive days ending today (or yesterday if today not done yet)
        if (d !in days) d -= 1
        while (d in days) { streak++; d-- }
        _streaks.value = _streaks.value + (goalId to streak)
    }
}
