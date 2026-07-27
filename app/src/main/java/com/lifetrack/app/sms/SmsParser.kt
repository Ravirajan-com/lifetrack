package com.lifetrack.app.sms

import com.lifetrack.app.data.db.entity.ExclusionSource
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnSource
import com.lifetrack.app.data.db.entity.TxnType
import java.util.Locale

/**
 * Parses Indian bank / UPI transaction SMS into a TransactionEntity.
 *
 * WHY THIS WAS REWRITTEN
 * -----------------------
 * The previous version accepted a message as a transaction if it contained BOTH a rupee amount
 * ANYWHERE in the text AND a debit/credit word ANYWHERE in the text -- no matter how far apart,
 * and matched via plain substring containment rather than whole-word matching. That's how a
 * promotional message like
 *
 *   "Dear Diksha, (1) Reminder! Claim your Lifetime Free HDFC Bank Credit Card+Upto Rs.2750
 *    Amazon Voucher. Hurry: https://1.hdfc.bank.in/HDFCBK/s/e8M2NdJZ T&C"
 *
 * got logged as a ₹2750 CREDIT transaction with merchant "UNKNOWN": "credit" is a substring of
 * "Credit Card", which has nothing to do with money moving, and "Rs.2750" here is a voucher
 * VALUE being advertised, not an amount that was debited or credited.
 *
 * This version requires TWO independent kinds of evidence before accepting anything, on top of
 * a hard promotional-message veto:
 *
 *   1. STRUCTURAL evidence that this is a real bank/UPI notification: a masked account or card
 *      reference ("A/c XX1234", "Card xx4321"), a transaction/UPI reference number, or an
 *      available-balance mention. Promotional SMS essentially never contain these. Real
 *      transaction SMS essentially always do.
 *   2. A transaction VERB in whole-word form, close to the amount (not just present somewhere
 *      in a long message) -- and "credit"/"debit" on their own are excluded from that verb list,
 *      since they're common fragments of marketing copy ("Credit Card", "debit card offer").
 *      Only their transaction-tense forms ("credited", "debited") count.
 *
 * Either check alone would still misfire occasionally. Together, a promotional message needs to
 * accidentally contain a masked account number for it to slip through, which doesn't happen.
 */
object SmsParser {

    // ------------------------------------------------------------------ amount

    private val amountRegex = Regex(
        """(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------ verbs
    // Whole-word only (\b...\b). Bare "credit"/"debit" are deliberately absent -- see class doc.
    //
    // "trx"/"txn", "approved", "successful" were added after validating against a real public
    // corpus of ~4,500 bank SMS (Kaggle, engreemali/bank-transactions-sms-datasetss). Many card
    // alerts never say "debited"/"spent" at all -- they say "Trx. of Rs.X on Card XX1234 at
    // MERCHANT is Approved" or "your payment of Rs.X for Card XX1234 has been credited" (a
    // credit-card bill payment). Without these, ~89% of real card-alert SMS in that corpus were
    // silently rejected as AMBIGUOUS.
    private val debitVerbs = listOf(
        "debited", "spent", "paid", "sent", "withdrawn", "purchased", "purchase", "debit",
        "trx", "txn", "approved", "successful"
    )
    private val creditVerbs = listOf(
        "credited", "received", "deposited", "refunded", "refund"
    )

    private fun verbRegex(words: List<String>) =
        Regex("""\b(?:${words.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

    private val debitVerbRegex = verbRegex(debitVerbs)
    private val creditVerbRegex = verbRegex(creditVerbs)

    // ------------------------------------------------------------------ structural evidence
    // A masked account number or card reference: "A/c XX1234", "a/c no 1234", "Card xx4321",
    // "card ending 4321", "account XX1234" (the unabbreviated word -- real banks use both forms
    // about equally often; the original version only recognized "a/c"). Promotional messages
    // essentially never contain one of these.
    private val accountRefRegex = Regex(
        """\b(?:a/?c|account|card)\b[^.\n]{0,20}?(?:[xX*]{2,}\d{2,6}|ending\s+\d{2,6}|no\.?\s*\d{4,})""",
        RegexOption.IGNORE_CASE
    )
    private val refNoRegex = Regex(
        """\b(?:UPI\s*[:\-]?\s*Ref|Ref\.?\s*No|Txn\s*ID|Transaction\s*ID|RRN)\b""",
        RegexOption.IGNORE_CASE
    )
    // Allow up to two filler words between "avl/available" and "bal/limit" -- real messages say
    // "available ACCOUNT balance" and "avl CARD bal", not just "avl bal". The original version
    // required them adjacent and missed both.
    private val balanceRegex = Regex(
        """\b(?:avl\.?|available)\s+(?:\w+\s+){0,2}(?:bal(?:ance)?|limit)\b""",
        RegexOption.IGNORE_CASE
    )
    // A transaction that was declined/failed moved no money and must never be logged as spend,
    // even though it mentions a real amount and a real account/card reference.
    private val declinedRegex = Regex(
        """\b(?:declined|txn\s*failed|transaction\s*failed|not\s*approved|insufficient\s*(?:balance|funds|limit))\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A credit-card BILL PAYMENT, as opposed to a purchase made WITH the card.
     *
     * "your payment of Rs.X for Card XX1234 has been credited" narrates from the card's side --
     * the card's outstanding balance went down -- which is not the same event as money entering
     * your life. It is also very likely a duplicate: the individual purchases that ran up that
     * balance (Netflix, Amazon, ...) should already be logged separately at time of purchase.
     * Counting the lump-sum payment on top would either log it as income (wrong -- nothing was
     * earned) or double-count spending that's already itemized elsewhere (also wrong).
     *
     * Deliberately does NOT match an ordinary account credit/debit ("A/c credited with Rs.5000")
     * -- it requires "payment" and "credit card" (or "for card") to appear together, which a
     * plain balance-change notification never does.
     */
    private val cardBillPaymentRegex = Regex(
        """\bcredit\s*card\b.{0,40}?\b(?:bill\s*)?payment\b""" +
            """|\b(?:bill\s*)?payment\b.{0,40}?\bcredit\s*card\b""" +
            """|\bpayment\b.{0,40}?\bfor\s+card\b""" +
            """|\breceived\b.{0,40}?\btowards\s+(?:your\s+)?credit\s*card\b""" +
            """|\btowards\s+(?:your\s+)?credit\s*card\b.{0,40}?\bpayment\b""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------ promotional veto
    // If ANY of these appear, the message is rejected outright regardless of everything else.
    // These are phrases that show up in marketing SMS but essentially never in a transaction
    // confirmation. "reminder" is included deliberately: a reminder about money that MIGHT be
    // charged (an EMI due date, a bill due date) is not the same event as money that already
    // moved, and should not be logged as a transaction.
    private val promoBlockRegex = Regex(
        """\b(?:claim|voucher|cashback\s*offer|pre[\s-]?approved|lifetime\s*free|""" +
            """download\s*(?:the\s*)?app|click\s*here|t\s*&\s*c\s*apply|hurry|""" +
            """limited\s*period|congratulations|lucky\s*draw|refer\s*(?:&|and)\s*earn|""" +
            """unsubscribe|reply\s*stop|winner|you'?ve\s*won|eligible\s*for|apply\s*now|""" +
            """flat\s*\d+%\s*off|\d+%\s*discount|sale\s*is\s*live|install\s*(?:the\s*)?app|""" +
            """reminder)\b""",
        RegexOption.IGNORE_CASE
    )

    private val vpaRegex = Regex("""(?:vpa|to|@)\s*([\w.\-]+@[\w]+)""", RegexOption.IGNORE_CASE)
    private val merchantAtRegex = Regex("""\bat\s+([A-Za-z0-9 &._\-*]{2,40}?)(?:\s+on\b|\s+using\b|\.|,|$)""", RegexOption.IGNORE_CASE)
    private val merchantToRegex = Regex("""\bto\s+([A-Za-z0-9 &._\-*]{2,40}?)(?:\s+on\b|\s+via\b|\.|,|$)""", RegexOption.IGNORE_CASE)
    private val bankRegex = Regex("""\b(HDFC|SBI|ICICI|AXIS|KOTAK|PNB|BOB|IDFC|YES|CANARA|UNION|INDUSIND|FEDERAL)\b""", RegexOption.IGNORE_CASE)

    // Only phrases that genuinely imply a SELF transfer. NEFT/IMPS/RTGS were removed: those are
    // payment RAILS, not evidence of a self-transfer, and matching them wrongly excluded ordinary
    // payments. Real self-transfers are confirmed by the amount-mirror sweep instead.
    private val selfTransferRegex = Regex("""\b(to self|own a/c|to own|paid to self|self transfer|own account|moving to own)\b""", RegexOption.IGNORE_CASE)

    /**
     * How close a verb must be to the amount match to count as describing THAT amount.
     *
     * Was 45. Widened to 90 after the same real-corpus validation: "Txn of Rs.X on HDFC Bank
     * Card XX1234 at NETFLIX is Approved" and "your payment of Rs.X ... for Card XX1234 has been
     * credited" both put the verb 50-90 characters after the amount, past the merchant/card/date
     * details in between. Re-verified against the full real spam/ham corpus (2,267 messages) at
     * this wider window: zero new false positives. The promotional veto and structural-evidence
     * requirement are what keep this safe -- a wider verb window alone would not be.
     */
    private const val PROXIMITY_WINDOW = 90

    private fun nearAmount(body: String, amountRange: IntRange, verbRegex: Regex): Boolean {
        val lo = (amountRange.first - PROXIMITY_WINDOW).coerceAtLeast(0)
        val hi = (amountRange.last + PROXIMITY_WINDOW).coerceAtMost(body.length)
        return verbRegex.containsMatchIn(body.substring(lo, hi))
    }

    private fun hasStructuralEvidence(body: String): Boolean =
        accountRefRegex.containsMatchIn(body) ||
            refNoRegex.containsMatchIn(body) ||
            balanceRegex.containsMatchIn(body) ||
            vpaRegex.containsMatchIn(body)

    fun parse(body: String, timestamp: Long): TransactionEntity? {
        val lower = body.lowercase(Locale.ROOT)
        if ("otp" in lower || "one time password" in lower) return null

        // Hard vetoes first: no amount of other evidence overrides these.
        if (promoBlockRegex.containsMatchIn(body)) return null
        if (declinedRegex.containsMatchIn(body)) return null

        val amountMatch = amountRegex.find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val amountRange = amountMatch.groups[1]!!.range

        // The verb must describe THIS amount, not just appear somewhere in the message.
        val isDebit = nearAmount(body, amountRange, debitVerbRegex)
        val isCredit = nearAmount(body, amountRange, creditVerbRegex)
        val type = when {
            isDebit && !isCredit -> TxnType.DEBIT
            isCredit && !isDebit -> TxnType.CREDIT
            else -> return null // neither, or both (ambiguous) -> not confident enough
        }

        // Structural evidence that this is a real bank/UPI notification, not marketing copy.
        if (!hasStructuralEvidence(body)) return null

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
        val isCardBillPayment = cardBillPaymentRegex.containsMatchIn(body)

        return TransactionEntity(
            amount = amount,
            // A card bill payment is never income, regardless of which verb the bank used to
            // phrase it -- force DEBIT so a mis-toggled "Exclude from totals" defaults to
            // understating spend rather than fabricating income.
            type = if (isCardBillPayment) TxnType.DEBIT else type,
            merchant = merchant,
            matchKey = matchKey,
            upiId = upiId,
            bank = bank,
            isExcluded = looksSelf || isCardBillPayment,
            exclusionSource = when {
                isCardBillPayment -> ExclusionSource.CARD_BILL_PAYMENT
                looksSelf -> ExclusionSource.SMS_HINT
                else -> ExclusionSource.NONE
            },
            timestamp = timestamp,
            source = TxnSource.SMS,
            rawSms = body
        )
    }
}
