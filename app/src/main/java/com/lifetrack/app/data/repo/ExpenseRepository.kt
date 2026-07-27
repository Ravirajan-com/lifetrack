package com.lifetrack.app.data.repo

import android.content.Context
import android.provider.Telephony
import com.lifetrack.app.classification.MerchantClassifier
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.entity.CategorySource
import com.lifetrack.app.data.db.entity.MerchantRuleEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.sms.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpenseRepository private constructor(private val context: Context) {

    private val dao = AppDatabase.get(context).expenseDao()

    /**
     * New SMS txn arrives.
     *
     * Categorization priority: a learned rule (exact matchKey, the user set this up before)
     * beats a keyword guess (generic dictionary match). If neither applies, the transaction goes
     * in uncategorized, same as before.
     */
    suspend fun ingestSmsTransaction(txn: TransactionEntity) {
        val rule = dao.ruleFor(txn.matchKey)
        val toInsert = when {
            rule != null -> txn.copy(categoryId = rule.categoryId, categorySource = CategorySource.RULE)
            else -> {
                val guess = MerchantClassifier.classify(txn.merchant, txn.rawSms)
                if (guess != null) {
                    val catId = dao.getOrCreateCategory(guess.name, guess.colorHex, guess.emoji)
                    txn.copy(categoryId = catId, categorySource = CategorySource.KEYWORD_AUTO)
                } else {
                    txn
                }
            }
        }
        dao.insertTxn(toInsert)
    }

    /**
     * User categorizes a txn.
     * @param learnRule true  -> "all from this merchant/UPI = this category" (writes rule,
     *                          re-categorizes past txns, auto-applies to future ones)
     *                  false -> one-off override for just this txn
     */
    suspend fun categorize(txn: TransactionEntity, categoryId: Long, learnRule: Boolean) {
        if (learnRule) {
            dao.upsertRule(MerchantRuleEntity(matchKey = txn.matchKey, categoryId = categoryId))
            dao.applyRuleToExisting(txn.matchKey, categoryId)
        } else {
            dao.overrideTxnCategory(txn.id, categoryId)
        }
    }

    /**
     * One-time import of existing SMS inbox after permission is granted.
     * @param days if Int.MAX_VALUE, imports all messages; else imports messages from last [days] days.
     * Returns number of transactions imported.
     */
    suspend fun backfillFromInbox(days: Int = 90): Int = withContext(Dispatchers.IO) {
        val since = if (days == Int.MAX_VALUE) 0L else System.currentTimeMillis() - days * 86_400_000L
        var imported = 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC"
        )
        cursor?.use {
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val txn = SmsParser.parse(it.getString(bodyIdx), it.getLong(dateIdx)) ?: continue
                ingestSmsTransaction(txn)
                imported++
            }
        }
        imported
    }

    /** Result of a keyword-classification sweep over already-imported, still-uncategorized txns. */
    data class ClassificationResult(val totalTagged: Int, val byCategory: Map<String, Int>)

    /**
     * Retroactive sweep: tries the keyword dictionary against every transaction that has NO
     * category yet (categoryId IS NULL). Safe to re-run any time -- it only ever touches rows
     * that are still uncategorized, and [ExpenseDao.applyKeywordCategory] double-checks that at
     * the SQL layer too, so a race with a manual categorization can't cause a clobber.
     */
    suspend fun classifyUncategorized(): ClassificationResult = withContext(Dispatchers.IO) {
        val candidates = dao.uncategorizedDebitTxnsSync()
        val counts = mutableMapOf<String, Int>()

        for (txn in candidates) {
            val guess = MerchantClassifier.classify(txn.merchant, txn.rawSms) ?: continue
            val catId = dao.getOrCreateCategory(guess.name, guess.colorHex, guess.emoji)
            dao.applyKeywordCategory(txn.id, catId)
            counts[guess.name] = (counts[guess.name] ?: 0) + 1
        }

        ClassificationResult(totalTagged = counts.values.sum(), byCategory = counts)
    }

    /** Undo every keyword-sourced categorization in one shot. Never touches USER or RULE rows. */
    suspend fun undoKeywordAutoTags(): Int = withContext(Dispatchers.IO) {
        dao.resetKeywordAutoTags()
    }

    companion object {
        @Volatile private var instance: ExpenseRepository? = null
        fun get(context: Context): ExpenseRepository =
            instance ?: synchronized(this) {
                instance ?: ExpenseRepository(context.applicationContext).also { instance = it }
            }
    }
}
