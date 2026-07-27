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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(c: CategoryEntity): Long

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
     */
    @androidx.room.Transaction
    suspend fun getOrCreateCategory(
        name: String,
        colorHex: String,
        emoji: String?,
        kind: CategoryKind = CategoryKind.EXPENSE
    ): Long {
        categoryByNameAndKind(name, kind)?.let { return it.id }
        return upsertCategory(CategoryEntity(name = name, colorHex = colorHex, iconEmoji = emoji, kind = kind))
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
