package com.lifetrack.app.data.repo

import android.content.Context
import android.provider.Telephony
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.entity.MerchantRuleEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.sms.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpenseRepository private constructor(private val context: Context) {

    private val dao = AppDatabase.get(context).expenseDao()

    /** New SMS txn arrives -> apply learned rule if one exists, then insert. */
    suspend fun ingestSmsTransaction(txn: TransactionEntity) {
        val rule = dao.ruleFor(txn.matchKey)
        dao.insertTxn(if (rule != null) txn.copy(categoryId = rule.categoryId) else txn)
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

    companion object {
        @Volatile private var instance: ExpenseRepository? = null
        fun get(context: Context): ExpenseRepository =
            instance ?: synchronized(this) {
                instance ?: ExpenseRepository(context.applicationContext).also { instance = it }
            }
    }
}
