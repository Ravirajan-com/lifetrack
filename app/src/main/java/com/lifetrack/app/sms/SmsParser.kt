package com.lifetrack.app.sms

import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.ExclusionSource
import com.lifetrack.app.data.db.entity.TxnSource
import com.lifetrack.app.data.db.entity.TxnType
import java.util.Locale

/**
 * Parses Indian bank / UPI transaction SMS into a TransactionEntity.
 * Handles common formats from HDFC, SBI, ICICI, Axis, Kotak, and UPI apps, e.g.:
 *  "Rs.450.00 debited from A/c XX1234 on 12-07-26 to VPA swiggy@icici (UPI Ref ...)"
 *  "INR 1,250.00 spent on HDFC Bank Card xx4321 at AMAZON on ..."
 *  "Your a/c XX1234 is credited with Rs.5000.00 on ..."
 * Returns null if the SMS doesn't look like a transaction (OTP, promo, etc).
 */
object SmsParser {

    private val amountRegex = Regex(
        """(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val debitWords = listOf("debited", "spent", "paid", "sent", "withdrawn", "purchase", "debit")
    private val creditWords = listOf("credited", "received", "deposited", "refund", "credit")

    private val vpaRegex = Regex("""(?:vpa|to|@)\s*([\w.\-]+@[\w]+)""", RegexOption.IGNORE_CASE)
    private val merchantAtRegex = Regex("""\bat\s+([A-Za-z0-9 &._\-*]{2,40}?)(?:\s+on\b|\s+using\b|\.|,|$)""", RegexOption.IGNORE_CASE)
    private val merchantToRegex = Regex("""\bto\s+([A-Za-z0-9 &._\-*]{2,40}?)(?:\s+on\b|\s+via\b|\.|,|$)""", RegexOption.IGNORE_CASE)
    private val bankRegex = Regex("""\b(HDFC|SBI|ICICI|AXIS|KOTAK|PNB|BOB|IDFC|YES|CANARA|UNION|INDUSIND|FEDERAL)\b""", RegexOption.IGNORE_CASE)
    // Only phrases that genuinely imply a SELF transfer. NEFT/IMPS/RTGS were removed: those are
    // payment RAILS, not evidence of a self-transfer, and matching them wrongly excluded ordinary
    // payments. Real self-transfers are confirmed by the amount-mirror sweep instead.
    private val selfTransferRegex = Regex("""\b(to self|own a/c|to own|paid to self|self transfer|own account|moving to own)\b""", RegexOption.IGNORE_CASE)

    /** Cheap pre-filter: skip OTPs and promos before regex work. */
    private fun looksLikeTxn(body: String): Boolean {
        val lower = body.lowercase(Locale.ROOT)
        if ("otp" in lower || "one time password" in lower) return false
        if (!amountRegex.containsMatchIn(body)) return false
        return (debitWords + creditWords).any { it in lower }
    }

    fun parse(body: String, timestamp: Long): TransactionEntity? {
        if (!looksLikeTxn(body)) return null

        val amountStr = amountRegex.find(body)?.groupValues?.get(1) ?: return null
        val amount = amountStr.replace(",", "").toDoubleOrNull() ?: return null

        val lower = body.lowercase(Locale.ROOT)
        val type = when {
            debitWords.any { it in lower } -> TxnType.DEBIT
            creditWords.any { it in lower } -> TxnType.CREDIT
            else -> return null
        }

        val upiId = vpaRegex.find(body)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val merchantRaw = merchantAtRegex.find(body)?.groupValues?.get(1)
            ?: merchantToRegex.find(body)?.groupValues?.get(1)
            ?: upiId?.substringBefore("@")
            ?: "Unknown"
        val merchant = merchantRaw.trim().uppercase(Locale.ROOT)

        // The key the auto-categorizer learns on: prefer stable UPI id, else merchant name.
        val matchKey = (upiId ?: merchant).lowercase(Locale.ROOT).trim()

        val bank = bankRegex.find(body)?.value?.uppercase(Locale.ROOT)
        // Weak signal only. Excluded now, but tagged SMS_HINT so the sweep can overturn it.
        val looksSelf = selfTransferRegex.containsMatchIn(body)

        return TransactionEntity(
            amount = amount,
            type = type,
            merchant = merchant,
            matchKey = matchKey,
            upiId = upiId,
            bank = bank,
            isExcluded = looksSelf,
            exclusionSource = if (looksSelf) ExclusionSource.SMS_HINT else ExclusionSource.NONE,
            timestamp = timestamp,
            source = TxnSource.SMS,
            rawSms = body
        )
    }
}
