package com.lifetrack.app.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class LifeTrackBackup(
    /** v3 adds credit cards and goal completion tracking. */
    val version: Int = 3,
    val timestamp: Long = System.currentTimeMillis(),
    val expenses: ExpenseBackup,
    val gym: GymBackup,
    val goals: GoalBackup,
    val creditCards: CreditCardBackup? = null,
)

@Serializable
data class ExpenseBackup(
    val categories: List<CategoryEntry>,
    val transactions: List<TransactionEntry>,
    val rules: List<RuleEntry>,
    val tags: List<TagEntry> = emptyList(),
)

@Serializable
data class TagEntry(
    val name: String,
    val colorHex: String,
)

@Serializable
data class CategoryEntry(
    val name: String,
    val colorHex: String,
    val monthlyBudget: Double?,
    val iconEmoji: String? = null,
    val kind: String = "EXPENSE",
)

@Serializable
data class TransactionEntry(
    val amount: Double,
    val type: String, // DEBIT/CREDIT
    val merchant: String,
    val matchKey: String,
    val upiId: String?,
    val bank: String?,
    val note: String?,
    val categoryName: String?,
    val manualOverride: Boolean,
    val isExcluded: Boolean,
    val timestamp: Long,
    val source: String,
    val rawSms: String? = null,
    val categorySource: String = "NONE",
    val exclusionSource: String = "NONE",
    val transferGroupId: String? = null,
    /** Tag names; resolved back to ids on restore. */
    val tags: List<String> = emptyList(),
)

@Serializable
data class RuleEntry(
    val matchKey: String,
    val categoryName: String,
)

@Serializable
data class GymBackup(
    val categories: List<WorkoutCategoryEntry>,
    val exercises: List<ExerciseEntry>,
    val sessions: List<SessionEntry>,
    val setLogs: List<SetLogEntry>,
)

@Serializable
data class WorkoutCategoryEntry(
    val name: String,
    val colorHex: String,
)

@Serializable
data class ExerciseEntry(
    val categoryName: String,
    val name: String,
    val targetSets: Int,
    val targetReps: Int,
)

@Serializable
data class SessionEntry(
    val categoryName: String,
    val date: Long,
    val note: String?,
)

@Serializable
data class SetLogEntry(
    val sessionDate: Long,
    val categoryName: String, // To help find the correct session
    val exerciseName: String,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double,
)

@Serializable
data class GoalBackup(
    val goals: List<GoalEntry>,
    val completions: List<CompletionEntry>,
)

@Serializable
data class GoalEntry(
    val title: String,
    val isRecurring: Boolean,
    val specificDate: Long?,
    val reminderHour: Int?,
    val reminderMinute: Int?,
    val active: Boolean,
)

@Serializable
data class CompletionEntry(
    val goalTitle: String,
    val epochDay: Long,
)

@Serializable
data class CreditCardBackup(
    val cards: List<CreditCardEntry>,
    val statements: List<StatementEntry>
)

@Serializable
data class CreditCardEntry(
    val name: String,
    val lastFourDigits: String,
    val bank: String?,
    val colorHex: String,
    val emoji: String,
    val createdAt: Long
)

@Serializable
data class StatementEntry(
    val lastFourDigits: String,
    val statementDate: Long,
    val totalDue: Double?,
    val rawSms: String?
)
