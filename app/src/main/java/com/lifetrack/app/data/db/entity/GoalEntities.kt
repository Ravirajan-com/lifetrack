package com.lifetrack.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "goals", indices = [Index(value = ["title"], unique = true)])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** true = repeats every day; false = only on [specificDate] */
    val isRecurring: Boolean,
    /** epoch-day (LocalDate.toEpochDay) when not recurring */
    val specificDate: Long? = null,
    /** reminder time of day; null = no reminder */
    val reminderHour: Int? = null,
    val reminderMinute: Int? = null,
    val active: Boolean = true
)

/** A goal checked off on a particular day. */
@Entity(
    tableName = "goal_completions",
    indices = [Index(value = ["goalId", "epochDay"], unique = true)]
)
data class GoalCompletionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val epochDay: Long
)
