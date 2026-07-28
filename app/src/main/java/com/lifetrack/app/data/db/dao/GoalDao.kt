package com.lifetrack.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lifetrack.app.data.db.entity.GoalCompletionEntity
import com.lifetrack.app.data.db.entity.GoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGoal(g: GoalEntity): Long

    @Update suspend fun updateGoal(g: GoalEntity)

    @Query("SELECT * FROM goals WHERE title = :title LIMIT 1")
    suspend fun goalByTitle(title: String): GoalEntity?

    @Query("UPDATE goals SET active = 0 WHERE id = :id") suspend fun archiveGoal(id: Long)

    /** Goals visible on a given day: recurring ones + that day's specific ones. */
    @Query(
        """SELECT * FROM goals WHERE active = 1
           AND (isRecurring = 1 OR specificDate = :epochDay)"""
    )
    fun goalsForDay(epochDay: Long): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE active = 1")
    fun allActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals")
    suspend fun allGoalsSync(): List<GoalEntity>

    @Query("SELECT * FROM goal_completions")
    suspend fun allCompletionsSync(): List<GoalCompletionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markDone(c: GoalCompletionEntity)

    @Query("DELETE FROM goal_completions WHERE goalId = :goalId AND epochDay = :epochDay")
    suspend fun unmark(goalId: Long, epochDay: Long)

    @Query("SELECT * FROM goal_completions WHERE epochDay = :epochDay")
    fun completionsOn(epochDay: Long): Flow<List<GoalCompletionEntity>>

    /** Days in a row (ending today) this goal was completed – computed in repo from this list. */
    @Query("SELECT epochDay FROM goal_completions WHERE goalId = :goalId ORDER BY epochDay DESC")
    suspend fun completionDays(goalId: Long): List<Long>
}
