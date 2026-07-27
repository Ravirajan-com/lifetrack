package com.lifetrack.app.data.repo

import android.content.Context
import android.provider.Telephony
import com.lifetrack.app.classification.MerchantClassifier
import com.lifetrack.app.creditcard.CreditCardEntity
import com.lifetrack.app.creditcard.CreditCardMatcher
import com.lifetrack.app.creditcard.CreditCardStatementEntity
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.entity.CategorySource
import com.lifetrack.app.data.db.entity.ExclusionSource
import com.lifetrack.app.data.db.entity.MerchantRuleEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.sms.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpenseRepository private constructor(private val context: Context) {

    private val dao = AppDatabase.get(context).expenseDao()
    private val cardDao = AppDatabase.get(context).creditCardDao()

    /**
     * New SMS txn arrives.
     *
     * Categorization priority: a learned rule (exact matchKey, the user set this up before)
     * beats a keyword guess (generic dictionary match). If neither applies, the transaction goes
     * in uncategorized, same as before.
     */
    suspend fun ingestSmsTransaction(txn: TransactionEntity) {
        val rule = dao.ruleFor(txn.matchKey)
        val categorized = when {
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

        // If this transaction references a card we're already tracking, it belongs to that
        // card's own view entirely -- excluded from Overall, whichever side of the credit-card
        // lifecycle it is (a purchase, or the eventual bill payment).
        val last4 = txn.rawSms?.let { CreditCardMatcher.extractLast4(it) }
        val card = last4?.let { cardDao.cardByLast4(it) }
        val toInsert = if (card != null) {
            categorized.copy(
                creditCardId = card.id,
                isExcluded = true,
                exclusionSource = ExclusionSource.CREDIT_CARD
            )
        } else {
            categorized
        }
        dao.insertTxn(toInsert)
    }

    /**
     * Single entry point for one raw incoming SMS, used by both the live receiver and the inbox
     * backfill. A statement-generated SMS is NOT a transaction (SmsParser correctly rejects it --
     * no money moved) but it's also not nothing: it registers the card and marks a cycle
     * boundary. That has to be checked independently of SmsParser, since SmsParser only ever
     * sees "is this a transaction or not", not "is this some other kind of bank message worth
     * recording".
     */
    suspend fun processSms(body: String, timestamp: Long): Boolean {
        CreditCardMatcher.extractStatementInfo(body)?.let { info ->
            recordStatement(info, timestamp, body)
            return false
        }
        val txn = SmsParser.parse(body, timestamp) ?: return false
        ingestSmsTransaction(txn)
        return true
    }

    private suspend fun recordStatement(info: com.lifetrack.app.creditcard.StatementInfo, timestamp: Long, rawSms: String) {
        val cardId = cardDao.getOrCreateCard(
            name = "${info.bank ?: "Card"} •${info.last4}",
            last4 = info.last4,
            bank = info.bank
        )
        if (cardId <= 0) return
        cardDao.insertStatement(
            CreditCardStatementEntity(
                cardId = cardId,
                statementDate = timestamp,
                totalDue = info.totalDue,
                rawSms = rawSms
            )
        )
    }

    /**
     * User categorizes a txn, with a merchant-rule confirmation flow instead of an always-on
     * toggle.
     *
     * The rule (matchKey -> category) is ALWAYS created, so future incoming transactions from
     * this merchant auto-categorize -- that's forward-looking and non-destructive, no reason to
     * gate it behind a confirmation. Only rewriting the merchant's OTHER, already-existing
     * transactions is destructive enough to ask about, which is what [applyToExisting] controls.
     * The current transaction's own category is always set directly, regardless.
     */
    suspend fun categorizeAndLearn(txn: TransactionEntity, categoryId: Long, applyToExisting: Boolean) {
        dao.upsertRule(MerchantRuleEntity(matchKey = txn.matchKey, categoryId = categoryId))
        dao.overrideTxnCategory(txn.id, categoryId)
        if (applyToExisting) {
            dao.applyRuleToExisting(txn.matchKey, categoryId)
        }
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
                val body = it.getString(bodyIdx) ?: continue
                if (processSms(body, it.getLong(dateIdx))) imported++
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

    /**
     * Retroactively links existing transactions to credit cards that weren't registered yet
     * when those transactions were first ingested (e.g. a card's first-ever statement arrives
     * after several of its purchases were already imported as ordinary, counted spend).
     * Safe to re-run -- only touches rows with no card link yet.
     */
    suspend fun rematchCreditCardTransactions(): Int = withContext(Dispatchers.IO) {
        var matched = 0
        for (txn in cardDao.unlinkedTxnsWithSms()) {
            val last4 = CreditCardMatcher.extractLast4(txn.rawSms ?: "") ?: continue
            val card = cardDao.cardByLast4(last4) ?: continue
            cardDao.tagTxnToCard(txn.id, card.id)
            matched++
        }
        matched
    }

    companion object {
        @Volatile private var instance: ExpenseRepository? = null
        fun get(context: Context): ExpenseRepository =
            instance ?: synchronized(this) {
                instance ?: ExpenseRepository(context.applicationContext).also { instance = it }
            }
    }
}
