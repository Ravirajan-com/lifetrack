package com.lifetrack.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lifetrack.app.data.db.entity.ExerciseEntity
import com.lifetrack.app.data.db.entity.SessionEntity
import com.lifetrack.app.data.db.entity.SetLogEntity
import com.lifetrack.app.data.db.entity.WorkoutCategoryEntity
import kotlinx.coroutines.flow.Flow

data class ExerciseProgressPoint(val date: Long, val maxWeight: Double, val totalVolume: Double)
data class PersonalBest(val exerciseName: String, val weight: Double)
data class CategoryFrequency(val categoryName: String, val colorHex: String, val count: Int)
data class SessionWithCategory(val sessionId: Long, val categoryName: String, val colorHex: String, val date: Long)

@Dao
interface GymDao {

    // --- setup ---
    @Insert suspend fun insertCategory(c: WorkoutCategoryEntity): Long
    @Query("SELECT * FROM workout_categories ORDER BY name") fun categories(): Flow<List<WorkoutCategoryEntity>>
    @Query("SELECT * FROM workout_categories") suspend fun allCategoriesSync(): List<WorkoutCategoryEntity>
    @Query("DELETE FROM workout_categories WHERE id = :id") suspend fun deleteCategory(id: Long)

    @Insert suspend fun insertExercise(e: ExerciseEntity): Long
    @Update suspend fun updateExercise(e: ExerciseEntity)
    @Query("SELECT * FROM exercises WHERE categoryId = :catId") fun exercisesOf(catId: Long): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercises") suspend fun allExercisesSync(): List<ExerciseEntity>
    @Query("DELETE FROM exercises WHERE id = :id") suspend fun deleteExercise(id: Long)

    // --- sessions ---
    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Query("SELECT * FROM sessions WHERE date = :date AND categoryId = :catId LIMIT 1")
    suspend fun getSession(date: Long, catId: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY date DESC") fun allSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions") suspend fun allSessionsSync(): List<SessionEntity>

    @Insert suspend fun insertSet(s: SetLogEntity): Long
    @Query("DELETE FROM set_logs WHERE sessionId = :sessionId AND exerciseId = :exerciseId")
    suspend fun clearSetsForExercise(sessionId: Long, exerciseId: Long)

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId")
    fun setsOf(sessionId: Long): Flow<List<SetLogEntity>>
    @Query("SELECT * FROM set_logs") suspend fun allSetsSync(): List<SetLogEntity>

    // --- dashboard ---
    
    @Query(
        """SELECT wc.name AS categoryName, wc.colorHex AS colorHex, COUNT(s.id) AS count 
           FROM workout_categories wc LEFT JOIN sessions s ON wc.id = s.categoryId 
           GROUP BY wc.id ORDER BY count DESC"""
    )
    fun getWorkoutFrequency(): Flow<List<CategoryFrequency>>

    @Query(
        """SELECT s.id AS sessionId, wc.name AS categoryName, wc.colorHex AS colorHex, s.date AS date 
           FROM sessions s JOIN workout_categories wc ON s.categoryId = wc.id 
           ORDER BY s.date DESC"""
    )
    fun getSessionHistoryWithCategories(): Flow<List<SessionWithCategory>>

    @Query(
        """SELECT e.name AS exerciseName, MAX(sl.weightKg) AS weight 
           FROM exercises e JOIN set_logs sl ON e.id = sl.exerciseId 
           GROUP BY e.id ORDER BY weight DESC LIMIT 10"""
    )
    fun getPersonalBests(): Flow<List<PersonalBest>>

    @Query("SELECT COUNT(*) FROM sessions WHERE date BETWEEN :start AND :end")
    fun getMonthlyWorkoutCount(start: Long, end: Long): Flow<Int>

    @Query("SELECT SUM(reps * weightKg) FROM set_logs sl JOIN sessions s ON sl.sessionId = s.id WHERE s.date BETWEEN :start AND :end")
    fun getMonthlyVolume(start: Long, end: Long): Flow<Double?>

    @Query(
        """SELECT s.date AS date, MAX(sl.weightKg) AS maxWeight, SUM(sl.weightKg * sl.reps) AS totalVolume
           FROM set_logs sl JOIN sessions s ON s.id = sl.sessionId
           WHERE sl.exerciseId = :exerciseId
           GROUP BY s.id ORDER BY s.date"""
    )
    fun progressFor(exerciseId: Long): Flow<List<ExerciseProgressPoint>>

    @Query(
        """SELECT sl.* FROM set_logs sl
           JOIN sessions s ON s.id = sl.sessionId
           WHERE sl.exerciseId = :exerciseId AND sl.sessionId != :excludeSession
           AND s.date = (
              SELECT MAX(s2.date) FROM sessions s2
              JOIN set_logs sl2 ON sl2.sessionId = s2.id
              WHERE sl2.exerciseId = :exerciseId AND s2.id != :excludeSession
           )
           ORDER BY sl.setNumber"""
    )
    suspend fun lastSetsFor(exerciseId: Long, excludeSession: Long): List<SetLogEntity>
}
