package com.lifetrack.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lifetrack.app.data.db.entity.CategoryEntity
import com.lifetrack.app.data.db.entity.CategoryKind
import com.lifetrack.app.data.db.entity.MerchantRuleEntity
import com.lifetrack.app.data.db.entity.MonthlySummaryEntity
import com.lifetrack.app.data.db.entity.TagEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnTagCrossRef
import kotlinx.coroutines.flow.Flow

data class CategorySpend(val categoryId: Long?, val name: String?, val colorHex: String?, val total: Double)
data class BankSpend(val name: String, val total: Double)
data class DailySpend(val epochDay: Long, val total: Double)

/** One bucket of the Trends chart -- a day, week, or month, per the caller's chosen format. */
data class PeriodTotals(val period: String, val spend: Double, val income: Double)

/** One month of live totals, computed from raw transactions. */
data class MonthTotal(
    val yearMonth: String,
    val spend: Double,
    val income: Double,
    val txnCount: Int
)

/** One month's visit count for a merchant, used to draw the history bar chart. */
data class MonthlyVisitCount(val yearMonth: String, val count: Int)

/** Flattened txn <-> tag edge, so a list screen can look up tags without N queries. */
data class TxnTagLink(
    val txnId: Long,
    val tagId: Long,
    val name: String,
    val colorHex: String
)

@Dao
interface ExpenseDao {

    // --- categories ---
    // Used by manual "new category" and by backup/restore, which both supply a specific id and
    // rely on REPLACE-by-primary-key semantics (e.g. restoring a backup's own ids intact).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(c: CategoryEntity): Long

    // Used only by getOrCreateCategory below. IGNORE (not REPLACE) is required here: with the
    // (name, kind) unique index now in place, REPLACE would delete-then-reinsert on a conflict,
    // silently changing the row's id out from under any transaction or merchant rule already
    // pointing at it. IGNORE simply no-ops on conflict, and getOrCreateCategory re-looks-up the
    // existing row's real id in that case.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoryIfAbsent(c: CategoryEntity): Long

    @Query("SELECT * FROM categories ORDER BY name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY name")
    fun categoriesByKind(kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun categoriesSync(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name AND kind = :kind LIMIT 1")
    suspend fun categoryByNameAndKind(name: String, kind: CategoryKind): CategoryEntity?

    /**
     * Finds an existing category by (name, kind) or creates it. Used by the merchant classifier
     * so re-running it never produces duplicate "Food & Dining" categories.
     *
     * Race-safe: two independent callers racing to create the same (name, kind) at app startup
     * (e.g. default-category seeding and merchant classification both deciding "Bills" doesn't
     * exist yet) can no longer both win -- the DB's unique index plus IGNORE means whichever
     * insert loses the race gets ignored, not silently duplicated, and the relookup below finds
     * the row the winner created.
     */
    @androidx.room.Transaction
    suspend fun getOrCreateCategory(
        name: String,
        colorHex: String,
        emoji: String?,
        kind: CategoryKind = CategoryKind.EXPENSE
    ): Long {
        categoryByNameAndKind(name, kind)?.let { return it.id }
        val id = insertCategoryIfAbsent(CategoryEntity(name = name, colorHex = colorHex, iconEmoji = emoji, kind = kind))
        return if (id > 0) id else (categoryByNameAndKind(name, kind)?.id ?: -1L)
    }

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE categories SET name = :name, colorHex = :colorHex, iconEmoji = :emoji WHERE id = :id")
    suspend fun updateCategoryMeta(id: Long, name: String, colorHex: String, emoji: String?)

    /** Detach transactions from a category so they fall back into the Inbox instead of dangling. */
    @Query("UPDATE transactions SET categoryId = NULL, manualOverride = 0 WHERE categoryId = :id")
    suspend fun detachTxnsFromCategory(id: Long)

    @Query("DELETE FROM merchant_rules WHERE categoryId = :id")
    suspend fun deleteRulesForCategory(id: Long)

    @androidx.room.Transaction
    suspend fun deleteCategoryCascade(id: Long) {
        deleteRulesForCategory(id)
        detachTxnsFromCategory(id)
        deleteCategory(id)
    }

    // --- transactions ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTxn(t: TransactionEntity): Long

    @Update
    suspend fun updateTxn(t: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTxn(id: Long)

    @Query("DELETE FROM txn_tags WHERE txnId = :id")
    suspend fun deleteTagLinksForTxn(id: Long)

    /** Removes a single transaction and its tag links. Does not touch categories or rules. */
    @androidx.room.Transaction
    suspend fun deleteTxnCascade(id: Long) {
        deleteTagLinksForTxn(id)
        deleteTxn(id)
    }

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun txnsBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE categoryId IS NULL AND type = 'DEBIT' ORDER BY timestamp DESC")
    fun uncategorized(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE categoryId IS NULL AND type = 'DEBIT' ORDER BY timestamp DESC LIMIT 5000")
    suspend fun uncategorizedDebitTxnsSync(): List<TransactionEntity>

    /** Guarded by categoryId IS NULL so a keyword guess can never clobber a real category. */
    @Query("UPDATE transactions SET categoryId = :catId, categorySource = 'KEYWORD_AUTO' WHERE id = :txnId AND categoryId IS NULL")
    suspend fun applyKeywordCategory(txnId: Long, catId: Long)

    /** Reverts every keyword guess back to uncategorized. Never touches USER or RULE rows. */
    @Query("UPDATE transactions SET categoryId = NULL, categorySource = 'NONE' WHERE categorySource = 'KEYWORD_AUTO'")
    suspend fun resetKeywordAutoTags(): Int

    @Query("SELECT * FROM transactions")
    suspend fun allTxnsSync(): List<TransactionEntity>

    /** Assign category to one txn only (manual override). */
    @Query("UPDATE transactions SET categoryId = :catId, manualOverride = 1, categorySource = 'USER' WHERE id = :txnId")
    suspend fun overrideTxnCategory(txnId: Long, catId: Long)

    @Query("UPDATE transactions SET isExcluded = :excluded WHERE id = :txnId")
    suspend fun updateExclusion(txnId: Long, excluded: Boolean)

    /** User toggle: records that the human decided, so the auto-detector won't override it. */
    @Query("UPDATE transactions SET isExcluded = :excluded, exclusionSource = 'USER' WHERE id = :txnId")
    suspend fun setUserExclusion(txnId: Long, excluded: Boolean)

    @Query("UPDATE transactions SET isExcluded = 1, exclusionSource = 'AUTO_TRANSFER', transferGroupId = :group WHERE id = :txnId")
    suspend fun markAutoTransfer(txnId: Long, group: String)

    /** Undo a previous auto-exclusion that no longer holds. Never touches USER rows. */
    @Query("UPDATE transactions SET isExcluded = 0, exclusionSource = 'NONE', transferGroupId = NULL WHERE id = :txnId AND exclusionSource = 'AUTO_TRANSFER'")
    suspend fun clearAutoTransfer(txnId: Long)

    /** Candidate legs for pairing: not user-excluded, within the window. */
    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to AND exclusionSource != 'USER' ORDER BY timestamp")
    suspend fun pairingCandidates(from: Long, to: Long): List<TransactionEntity>

    /** Apply a rule to all past txns with same key that weren't manually overridden. */
    @Query("UPDATE transactions SET categoryId = :catId, categorySource = 'RULE' WHERE matchKey = :key AND manualOverride = 0")
    suspend fun applyRuleToExisting(key: String, catId: Long)

    // --- rules (the "learning") ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(r: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules WHERE matchKey = :key LIMIT 1")
    suspend fun ruleFor(key: String): MerchantRuleEntity?

    @Query("SELECT * FROM merchant_rules")
    fun rules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules")
    suspend fun allRulesSync(): List<MerchantRuleEntity>

    // --- dashboard aggregations ---
    @Query(
        """SELECT t.categoryId AS categoryId, c.name AS name, c.colorHex AS colorHex, SUM(t.amount) AS total
           FROM transactions t LEFT JOIN categories c ON c.id = t.categoryId
           WHERE t.type = 'DEBIT' AND t.isExcluded = 0 AND t.timestamp >= :from AND t.timestamp < :to
           GROUP BY t.categoryId ORDER BY total DESC"""
    )
    fun spendByCategory(from: Long, to: Long): Flow<List<CategorySpend>>

    @Query(
        """SELECT t.bank AS name, SUM(t.amount) AS total
           FROM transactions t
           WHERE t.type = 'DEBIT' AND t.isExcluded = 0 AND t.bank IS NOT NULL AND t.timestamp >= :from AND t.timestamp < :to
           GROUP BY t.bank ORDER BY total DESC"""
    )
    fun spendByBank(from: Long, to: Long): Flow<List<BankSpend>>

    /**
     * Daily spend keyed by LOCAL epoch day.
     *
     * Previously this was `timestamp / 86400000`, which is the *UTC* epoch day. In IST (UTC+5:30)
     * that pushed every transaction before 05:30 local time onto the previous calendar day, so the
     * heatmap (which looks up `LocalDate.toEpochDay()`) silently mis-bucketed them.
     */
    @Query(
        """SELECT CAST(strftime('%s', t.timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) / 86400 AS epochDay,
                  SUM(t.amount) AS total
           FROM transactions t
           WHERE t.type = 'DEBIT' AND t.isExcluded = 0 AND t.timestamp >= :from AND t.timestamp < :to
           GROUP BY epochDay ORDER BY epochDay"""
    )
    fun dailySpend(from: Long, to: Long): Flow<List<DailySpend>>

    /**
     * Spend and income grouped by day, week, or month, per [fmt] -- an SQLite strftime format
     * string ("%Y-%m-%d", "%Y-%W", or "%Y-%m") chosen by the caller depending on which
     * granularity the Trends page is showing. Same 'localtime' correction as dailySpend above:
     * grouping in UTC would push pre-05:30-IST transactions onto the wrong calendar day/week/month.
     */
    @Query(
        """SELECT strftime(:fmt, t.timestamp / 1000, 'unixepoch', 'localtime') AS period,
                  SUM(CASE WHEN t.type = 'DEBIT'  AND t.isExcluded = 0 THEN t.amount ELSE 0 END) AS spend,
                  SUM(CASE WHEN t.type = 'CREDIT' AND t.isExcluded = 0 THEN t.amount ELSE 0 END) AS income
           FROM transactions t
           WHERE t.timestamp >= :from AND t.timestamp < :to
           GROUP BY period ORDER BY period"""
    )
    fun trendsByPeriod(fmt: String, from: Long, to: Long): Flow<List<PeriodTotals>>

    /**
     * Same as trendsByPeriod, but for WEEK: groups by the Monday of each week rather than a
     * SQLite week-number format, specifically so the returned period string is a plain ISO date
     * ("2026-07-21") that Kotlin's LocalDate.parse can read back exactly like DAY's period does --
     * SQLite's own %W week-number format doesn't map back to an unambiguous date on its own.
     * The expression: weekday(0=Sun..6=Sat) converted to a Monday-based offset, subtracted from
     * the date, lands on that week's Monday regardless of which day of the week a transaction fell on.
     */
    @Query(
        """SELECT date(
                      t.timestamp / 1000, 'unixepoch', 'localtime',
                      '-' || ((CAST(strftime('%w', t.timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) + 6) % 7) || ' days'
                  ) AS period,
                  SUM(CASE WHEN t.type = 'DEBIT'  AND t.isExcluded = 0 THEN t.amount ELSE 0 END) AS spend,
                  SUM(CASE WHEN t.type = 'CREDIT' AND t.isExcluded = 0 THEN t.amount ELSE 0 END) AS income
           FROM transactions t
           WHERE t.timestamp >= :from AND t.timestamp < :to
           GROUP BY period ORDER BY period"""
    )
    fun trendsByWeek(from: Long, to: Long): Flow<List<PeriodTotals>>

    /**
     * Every transaction in an exact time range, most recent first -- backs the Trends page's
     * "Review <period>" drill-down. The caller computes [from]/[to] as the real millisecond
     * boundaries of whichever day/week/month was tapped (java.time, not a parsed period string),
     * so this stays a plain, exact range query regardless of granularity.
     */
    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun txnsInRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='DEBIT' AND isExcluded = 0 AND timestamp >= :from AND timestamp < :to")
    fun totalSpend(from: Long, to: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='CREDIT' AND isExcluded = 0 AND timestamp >= :from AND timestamp < :to")
    fun totalIncome(from: Long, to: Long): Flow<Double>

    @Query(
        """SELECT t.categoryId AS categoryId, c.name AS name, c.colorHex AS colorHex, SUM(t.amount) AS total
           FROM transactions t LEFT JOIN categories c ON c.id = t.categoryId
           WHERE t.type = 'CREDIT' AND t.isExcluded = 0 AND t.timestamp >= :from AND t.timestamp < :to
           GROUP BY t.categoryId ORDER BY total DESC"""
    )
    fun incomeByCategory(from: Long, to: Long): Flow<List<CategorySpend>>

    /** Sum of per-category limits. Used as the fallback when no overall budget is set. */
    @Query("SELECT COALESCE(SUM(monthlyBudget),0) FROM categories")
    fun categoryBudgetSum(): Flow<Double>

    @Query("UPDATE categories SET monthlyBudget = :budget WHERE id = :id")
    suspend fun updateCategoryBudget(id: Long, budget: Double?)

    @Query("UPDATE transactions SET note = :note WHERE id = :txnId")
    suspend fun updateTxnNote(txnId: Long, note: String)

    // --- merchant history (visits) ------------------------------------------
    /** How many OTHER transactions (excluding this one) share this merchant/UPI key. Used to
     *  ask "apply to N other transactions too?" instead of silently rewriting history. */
    @Query("SELECT COUNT(*) FROM transactions WHERE matchKey = :key AND id != :excludeId")
    suspend fun countOtherTxnsForMatchKey(key: String, excludeId: Long): Int

    /** Total visits for this merchant, including this transaction -- the badge shown in the UI. */
    @Query("SELECT COUNT(*) FROM transactions WHERE matchKey = :key")
    fun countTxnsForMatchKeyFlow(key: String): Flow<Int>

    @Query("SELECT * FROM transactions WHERE matchKey = :key ORDER BY timestamp DESC")
    fun txnsForMatchKey(key: String): Flow<List<TransactionEntity>>

    @Query(
        """SELECT strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime') AS yearMonth,
                  COUNT(*) AS count
           FROM transactions WHERE matchKey = :key
           GROUP BY yearMonth ORDER BY yearMonth"""
    )
    fun visitsByMonth(key: String): Flow<List<MonthlyVisitCount>>

    @Query("SELECT DISTINCT bank FROM transactions WHERE bank IS NOT NULL ORDER BY bank")
    fun allBanks(): Flow<List<String>>

    @Query("SELECT timestamp FROM transactions ORDER BY timestamp DESC")
    fun allTransactionTimes(): Flow<List<Long>>

    // --- tags ------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(t: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name")
    fun tags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun tagsSync(): List<TagEntity>

    @Query("SELECT id FROM tags WHERE name = :name LIMIT 1")
    suspend fun tagIdByName(name: String): Long?

    @Query("UPDATE tags SET name = :name, colorHex = :colorHex WHERE id = :id")
    suspend fun updateTag(id: Long, name: String, colorHex: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("DELETE FROM txn_tags WHERE tagId = :tagId")
    suspend fun unlinkTagEverywhere(tagId: Long)

    @androidx.room.Transaction
    suspend fun deleteTagCascade(id: Long) {
        unlinkTagEverywhere(id)
        deleteTag(id)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTag(ref: TxnTagCrossRef)

    @Query("DELETE FROM txn_tags WHERE txnId = :txnId")
    suspend fun clearTagsForTxn(txnId: Long)

    @androidx.room.Transaction
    suspend fun setTagsForTxn(txnId: Long, tagIds: List<Long>) {
        clearTagsForTxn(txnId)
        tagIds.forEach { linkTag(TxnTagCrossRef(txnId, it)) }
    }

    @Query(
        """SELECT tt.txnId AS txnId, g.id AS tagId, g.name AS name, g.colorHex AS colorHex
           FROM txn_tags tt JOIN tags g ON g.id = tt.tagId"""
    )
    fun allTxnTagLinks(): Flow<List<TxnTagLink>>

    @Query(
        """SELECT tt.txnId AS txnId, g.id AS tagId, g.name AS name, g.colorHex AS colorHex
           FROM txn_tags tt JOIN tags g ON g.id = tt.tagId"""
    )
    suspend fun allTxnTagLinksSync(): List<TxnTagLink>

    @Query("SELECT g.* FROM tags g JOIN txn_tags tt ON tt.tagId = g.id WHERE tt.txnId = :txnId ORDER BY g.name")
    fun tagsForTxn(txnId: Long): Flow<List<TagEntity>>

    @Query("DELETE FROM txn_tags WHERE txnId NOT IN (SELECT id FROM transactions)")
    suspend fun pruneOrphanTagLinks()

    // --- search ----------------------------------------------------------------
    /**
     * Full-text-ish search across merchant, note, bank, tag name and category name, plus an
     * optional exact amount match (so typing "450" finds the ₹450 txn as well as "MERCHANT450").
     *
     * Every filter is null-tolerant so a single query backs the whole search UI.
     */
    @Query(
        """SELECT DISTINCT t.* FROM transactions t
           LEFT JOIN txn_tags tt ON tt.txnId = t.id
           LEFT JOIN tags g ON g.id = tt.tagId
           LEFT JOIN categories c ON c.id = t.categoryId
           WHERE (
                 :text = ''
                 OR t.merchant LIKE '%' || :text || '%'
                 OR IFNULL(t.note, '')  LIKE '%' || :text || '%'
                 OR IFNULL(t.bank, '')  LIKE '%' || :text || '%'
                 OR IFNULL(g.name, '')  LIKE '%' || :text || '%'
                 OR IFNULL(c.name, '')  LIKE '%' || :text || '%'
                 OR (:amount IS NOT NULL AND t.amount = :amount)
           )
             AND (:categoryId IS NULL OR t.categoryId = :categoryId)
             AND (:tagId IS NULL OR tt.tagId = :tagId)
             AND (:type IS NULL OR t.type = :type)
             AND (:from IS NULL OR t.timestamp >= :from)
             AND (:to IS NULL OR t.timestamp < :to)
             AND (:includeExcluded = 1 OR t.isExcluded = 0)
           ORDER BY t.timestamp DESC
           LIMIT 400"""
    )
    fun search(
        text: String,
        amount: Double?,
        categoryId: Long?,
        tagId: Long?,
        type: String?,
        from: Long?,
        to: Long?,
        includeExcluded: Boolean
    ): Flow<List<TransactionEntity>>

    // --- annual view -----------------------------------------------------------
    /** Live per-month totals in LOCAL time, for every month still held as raw transactions. */
    @Query(
        """SELECT strftime('%Y-%m', t.timestamp / 1000, 'unixepoch', 'localtime') AS yearMonth,
                  COALESCE(SUM(CASE WHEN t.type = 'DEBIT'  THEN t.amount ELSE 0 END), 0) AS spend,
                  COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE 0 END), 0) AS income,
                  COUNT(*) AS txnCount
           FROM transactions t
           WHERE t.isExcluded = 0
           GROUP BY yearMonth
           ORDER BY yearMonth"""
    )
    fun monthlyTotals(): Flow<List<MonthTotal>>

    @Query("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type = :type AND isExcluded = 0 AND timestamp >= :from AND timestamp < :to")
    fun totalByType(type: String, from: Long, to: Long): Flow<Double>

    // --- archive & purge -------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthlySummary(s: MonthlySummaryEntity)

    @Query("SELECT * FROM monthly_summaries ORDER BY yearMonth")
    fun monthlySummaries(): Flow<List<MonthlySummaryEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp < :before ORDER BY timestamp")
    suspend fun txnsBefore(before: Long): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp < :before")
    suspend fun countTxnsBefore(before: Long): Int

    @Query("DELETE FROM transactions WHERE timestamp < :before")
    suspend fun deleteTxnsBefore(before: Long): Int
}
