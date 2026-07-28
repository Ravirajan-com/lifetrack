package com.lifetrack.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A muscle group or workout type, e.g. "Chest", "Legs", "Push" */
@Entity(tableName = "workout_categories", indices = [Index(value = ["name"], unique = true)])
data class WorkoutCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#FF9A6C" // Default ember
)

/** An exercise variation belonging to a category, e.g. "Bench Press" under "Chest" */
@Entity(tableName = "exercises", indices = [Index(value = ["categoryId", "name"], unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    val targetSets: Int = 3,
    val targetReps: Int = 10
)

/** One actual workout performed on a date for a given category. */
@Entity(tableName = "sessions", indices = [Index("categoryId"), Index("date")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    /** epoch day start millis */
    val date: Long,
    val note: String? = null
)

/** A logged set: session + exercise + setNumber -> reps x weight */
@Entity(tableName = "set_logs", indices = [Index("sessionId"), Index("exerciseId")])
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double
)
