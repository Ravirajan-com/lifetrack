package com.lifetrack.app.data.repo

import android.content.Context
import com.lifetrack.app.data.backup.*
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupRepository private constructor(context: Context) {

    private val db = AppDatabase.get(context)
    private val expenseDao = db.expenseDao()
    private val cardDao = db.creditCardDao()
    private val gymDao = db.gymDao()
    private val goalDao = db.goalDao()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportAll(): String = withContext(Dispatchers.IO) {
        val expenseCategories = expenseDao.categoriesSync()
        val txns = expenseDao.allTxnsSync()
        val rules = expenseDao.allRulesSync()
        val tags = expenseDao.tagsSync()
        val tagsByTxn = expenseDao.allTxnTagLinksSync().groupBy({ it.txnId }, { it.name })

        val cards = cardDao.cardsSync()
        val statements = cardDao.allStatementsSync()

        val gymCategories = gymDao.allCategoriesSync()
        val exercises = gymDao.allExercisesSync()
        val sessions = gymDao.allSessionsSync()
        val sets = gymDao.allSetsSync()

        val goals = goalDao.allGoalsSync()
        val completions = goalDao.allCompletionsSync()

        val backup = LifeTrackBackup(
            expenses = ExpenseBackup(
                categories = expenseCategories.map {
                    CategoryEntry(it.name, it.colorHex, it.monthlyBudget, it.iconEmoji, it.kind.name)
                },
                transactions = txns.map { t ->
                    TransactionEntry(
                        amount = t.amount, type = t.type.name, merchant = t.merchant,
                        matchKey = t.matchKey, upiId = t.upiId, bank = t.bank, note = t.note,
                        categoryName = expenseCategories.find { it.id == t.categoryId }?.name,
                        manualOverride = t.manualOverride, isExcluded = t.isExcluded,
                        timestamp = t.timestamp, source = t.source.name,
                        rawSms = t.rawSms,
                        tags = tagsByTxn[t.id].orEmpty()
                    )
                },
                rules = rules.map { r ->
                    RuleEntry(r.matchKey, expenseCategories.find { it.id == r.categoryId }?.name ?: "")
                },
                tags = tags.map { TagEntry(it.name, it.colorHex) }
            ),
            gym = GymBackup(
                categories = gymCategories.map { WorkoutCategoryEntry(it.name, it.colorHex) },
                exercises = exercises.map { e ->
                    ExerciseEntry(
                        categoryName = gymCategories.find { it.id == e.categoryId }?.name ?: "",
                        name = e.name, targetSets = e.targetSets, targetReps = e.targetReps
                    )
                },
                sessions = sessions.map { s ->
                    SessionEntry(
                        categoryName = gymCategories.find { it.id == s.categoryId }?.name ?: "",
                        date = s.date, note = s.note
                    )
                },
                setLogs = sets.map { sl ->
                    val session = sessions.find { it.id == sl.sessionId }
                    val category = gymCategories.find { it.id == session?.categoryId }
                    val exercise = exercises.find { it.id == sl.exerciseId }
                    SetLogEntry(
                        sessionDate = session?.date ?: 0L,
                        categoryName = category?.name ?: "",
                        exerciseName = exercise?.name ?: "",
                        setNumber = sl.setNumber, reps = sl.reps, weightKg = sl.weightKg
                    )
                }
            ),
            goals = GoalBackup(
                goals = goals.map { GoalEntry(it.title, it.isRecurring, it.specificDate, it.reminderHour, it.reminderMinute, it.active) },
                completions = completions.map { c ->
                    CompletionEntry(goals.find { it.id == c.goalId }?.title ?: "", c.epochDay)
                }
            ),
            creditCards = CreditCardBackup(
                cards = cards.map { 
                    CreditCardEntry(it.name, it.lastFourDigits, it.bank, it.colorHex, it.emoji, it.createdAt) 
                },
                statements = statements.map { st ->
                    val card = cards.find { it.id == st.cardId }
                    StatementEntry(card?.lastFourDigits ?: "", st.statementDate, st.totalDue, st.rawSms)
                }
            )
        )

        json.encodeToString(LifeTrackBackup.serializer(), backup)
    }

    suspend fun importAll(jsonStr: String): Unit = withContext(Dispatchers.IO) {
        val backup = json.decodeFromString(LifeTrackBackup.serializer(), jsonStr)
        
        // --- Credit Cards ---
        val cardMap = mutableMapOf<String, Long>()
        backup.creditCards?.let { cc ->
            for (c in cc.cards) {
                cardMap[c.lastFourDigits] = cardDao.getOrCreateCard(c.name, c.lastFourDigits, c.bank)
                cardDao.updateCard(cardMap[c.lastFourDigits]!!, c.name, c.colorHex, c.emoji)
            }
            for (st in cc.statements) {
                val cardId = cardMap[st.lastFourDigits]
                if (cardId != null) {
                    cardDao.insertStatement(com.lifetrack.app.creditcard.CreditCardStatementEntity(
                        cardId = cardId,
                        statementDate = st.statementDate,
                        totalDue = st.totalDue,
                        rawSms = st.rawSms
                    ))
                }
            }
        }

        // --- Expenses ---
        val expenseCatMap = mutableMapOf<String, Long>()
        for (it in backup.expenses.categories) {
            val id = expenseDao.getOrCreateCategory(
                name = it.name,
                colorHex = it.colorHex,
                emoji = it.iconEmoji,
                kind = runCatching { CategoryKind.valueOf(it.kind) }.getOrDefault(CategoryKind.EXPENSE)
            )
            expenseCatMap[it.name] = id
        }

        val tagMap = mutableMapOf<String, Long>()
        for (t in backup.expenses.tags) {
            val id = expenseDao.insertTag(TagEntity(name = t.name, colorHex = t.colorHex))
                .takeIf { it > 0L } ?: expenseDao.tagIdByName(t.name)
            if (id != null) tagMap[t.name] = id
        }
        
        for (t in backup.expenses.transactions) {
            val cardId = t.rawSms?.let { com.lifetrack.app.creditcard.CreditCardMatcher.extractLast4(it) }?.let { cardMap[it] }
            
            expenseDao.insertTxn(TransactionEntity(
                amount = t.amount,
                type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.DEBIT),
                merchant = t.merchant,
                matchKey = t.matchKey,
                upiId = t.upiId,
                bank = t.bank,
                note = t.note,
                categoryId = t.categoryName?.let { expenseCatMap[it] },
                manualOverride = t.manualOverride,
                isExcluded = t.isExcluded,
                timestamp = t.timestamp,
                source = runCatching { TxnSource.valueOf(t.source) }.getOrDefault(TxnSource.MANUAL),
                rawSms = t.rawSms,
                creditCardId = cardId
            ))
        }
        
        for (r in backup.expenses.rules) {
            expenseCatMap[r.categoryName]?.let { catId ->
                expenseDao.upsertRule(MerchantRuleEntity(matchKey = r.matchKey, categoryId = catId))
            }
        }

        // --- Gym ---
        val gymCatMap = mutableMapOf<String, Long>()
        for (it in backup.gym.categories) {
            val id = gymDao.insertCategory(WorkoutCategoryEntity(name = it.name, colorHex = it.colorHex))
                .takeIf { it > 0L } ?: gymDao.categoryByName(it.name)?.id ?: -1L
            if (id > 0) gymCatMap[it.name] = id
        }
        
        val exerciseMap = mutableMapOf<Pair<String, String>, Long>()
        for (e in backup.gym.exercises) {
            val catId = gymCatMap[e.categoryName]
            if (catId != null) {
                val id = gymDao.insertExercise(ExerciseEntity(categoryId = catId, name = e.name, targetSets = e.targetSets, targetReps = e.targetReps))
                    .takeIf { it > 0L } ?: gymDao.exerciseByName(catId, e.name)?.id ?: -1L
                if (id > 0) exerciseMap[e.categoryName to e.name] = id
            }
        }
        
        val sessionMap = mutableMapOf<Pair<Long, String>, Long>()
        for (s in backup.gym.sessions) {
            val catId = gymCatMap[s.categoryName]
            if (catId != null) {
                val existing = gymDao.getSession(s.date, catId)
                sessionMap[s.date to s.categoryName] = existing?.id ?: gymDao.insertSession(SessionEntity(categoryId = catId, date = s.date, note = s.note))
            }
        }
        
        for (sl in backup.gym.setLogs) {
            val sessionId = sessionMap[sl.sessionDate to sl.categoryName]
            val exerciseId = exerciseMap[sl.categoryName to sl.exerciseName]
            if (sessionId != null && exerciseId != null) {
                gymDao.insertSet(
                    SetLogEntity(
                        sessionId = sessionId, 
                        exerciseId = exerciseId, 
                        setNumber = sl.setNumber, 
                        reps = sl.reps, 
                        weightKg = sl.weightKg,
                    )
                )
            }
        }

        // --- Goals ---
        val goalMap = mutableMapOf<String, Long>()
        for (g in backup.goals.goals) {
            val id = goalDao.insertGoal(GoalEntity(title = g.title, isRecurring = g.isRecurring, specificDate = g.specificDate, reminderHour = g.reminderHour, reminderMinute = g.reminderMinute, active = g.active))
                .takeIf { it > 0L } ?: goalDao.goalByTitle(g.title)?.id ?: -1L
            if (id > 0) goalMap[g.title] = id
        }
        
        for (c in backup.goals.completions) {
            val goalId = goalMap[c.goalTitle]
            if (goalId != null) {
                goalDao.markDone(GoalCompletionEntity(goalId = goalId, epochDay = c.epochDay))
            }
        }
    }

    companion object {
        @Volatile private var instance: BackupRepository? = null
        fun get(context: Context): BackupRepository =
            instance ?: synchronized(this) {
                instance ?: BackupRepository(context.applicationContext).also { instance = it }
            }
    }
}
