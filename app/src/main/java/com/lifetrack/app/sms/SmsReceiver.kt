package com.lifetrack.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.lifetrack.app.data.repo.ExpenseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Multipart SMS arrive as chunks from the same sender; join them.
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val txn = SmsParser.parse(body, ts) ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ExpenseRepository.get(context).ingestSmsTransaction(txn)
            } finally {
                pending.finish()
            }
        }
    }
}
