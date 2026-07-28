package com.lifetrack.app.sms

import com.lifetrack.app.data.db.entity.ExclusionSource
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnSource
import com.lifetrack.app.data.db.entity.TxnType
import java.util.Locale

/**
 * Parses Indian bank / UPI transaction SMS into a TransactionEntity.
 *
 * ROUND 3 -- VALIDATED AGAINST A REAL PHONE'S FULL SMS HISTORY
 * ---------------------------------------------------------------
 * Rounds 1-2 were validated against a real spam/ham corpus and a real (but foreign-currency)
 * bank SMS corpus. Neither exercised actual Indian rupee bank phrasing at real volume. This
 * round was validated directly against ~12,700 real rupee-mentioning SMS from one person's
 * phone (SMS Backup & Restore export), and it surfaced one dominant bug plus several smaller
 * real ones that neither prior dataset could have caught:
 *
 *   THE DOMINANT BUG: accountRefRegex required TWO OR MORE mask characters before the last
 *   digits ("XX1234", "xxxx1234"). This person's bank (HDFC) masks with a SINGLE asterisk
 *   ("A/C *8061"), which never satisfied the old {2,} minimum. This alone was responsible for
 *   roughly 40% of all real transaction SMS being wrongly rejected as "no structural evidence" --
 *   by far the largest single accuracy loss found across all three rounds. Fixed by lowering the
 *   minimum to one mask character ({1,}).
 *
 *   Before this round: ~27% of real rupee-mentioning SMS correctly recognized.
 *   After this round:  ~82% correctly recognized, with ZERO new false positives on the full
 *   2,267-message real spam/ham corpus from round 2 (re-run after every single change below).
 *
 * See the comment above each regex for what specifically it fixes and why.
 */
object SmsParser {

    // ------------------------------------------------------------------ amount

    private val amountRegex = Regex(
        """(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------ verbs
    // Whole-word only (\b...\b). Bare "credit"/"debit" are deliberately absent -- see history
    // below. "trx"/"txn", "approved", "successful" cover card-alert phrasing with no other verb.
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
    // "card ending 4321", "account XX1234", "A/C *8061" (single-character masking -- see the
    // ROUND 3 note above; this is the fix for the dominant real bug), and bare-digit "Card 6902".
    private val accountRefRegex = Regex(
        """\b(?:a/?c|account|card)\b[^.\n]{0,20}?(?:[xX*]{1,}\d{2,6}|ending\s+\d{2,6}|no\.?\s*\d{4,}|\d{3,6}\b)""",
        RegexOption.IGNORE_CASE
    )
    // "UPI Ref 12345", "Ref No 12345" AND the bare "UPI:12345" form some banks use with no
    // literal word "Ref" at all.
    private val refNoRegex = Regex(
        """\b(?:UPI\s*[:\-]?\s*Ref|Ref\.?\s*No|Txn\s*ID|Transaction\s*ID|RRN|UPI\s*[:\-]\s*\d{6,})\b""",
        RegexOption.IGNORE_CASE
    )
    // Allow up to two filler words between the lead-in and "bal/limit" ("available account
    // balance", "avl card bal"), and accept "remaining"/"updated" as lead-ins too ("Remaining
    // balance: Rs.0", "updated credit balance is Rs.X") alongside "avl"/"available".
    private val balanceRegex = Regex(
        """\b(?:avl\.?|available|remaining|updated)\s+(?:\w+\s+){0,2}(?:bal(?:ance)?|limit)\b""",
        RegexOption.IGNORE_CASE
    )
    // Some real card products (seen: "slice") confirm each purchase with NO masked account
    // number, balance, or reference number at all -- just "card transaction of Rs.X on MERCHANT
    // was successful". Specific enough to stand alone as evidence, alongside the anchors above.
    private val cardTxnTemplateRegex = Regex(
        """\bcard\s+transaction\s+of\b.{0,60}?\b(?:on|at)\b.{0,60}?\bsuccessful\b""",
        RegexOption.IGNORE_CASE
    )
    // A BNPL/credit-line REPAYMENT confirmation ("your repayment of Rs.X is successful") can
    // likewise arrive with no other anchor at all. See repaymentRegex below for why this is
    // treated as a settlement (excluded), not a fresh expense -- this counts it as structural
    // evidence only; type/exclusion handling happens separately.
    private val repaymentRegex = Regex(
        """\brepayment\s+of\b.{0,40}?\b(?:is\s+successful|received|credited)\b""",
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
     * balance should already be logged separately at time of purchase. Counting the lump-sum
     * payment on top would either log it as income (wrong) or double-count spending that's
     * already itemized elsewhere (also wrong).
     *
     * Deliberately does NOT match an ordinary account credit/debit ("A/c credited with Rs.5000")
     * -- it requires "payment" and "credit card" (or "for card") to appear together.
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
    private val promoBlockRegex = Regex(
        """\b(?:claim|voucher|cashback\s*offer|pre[\s-]?approved|lifetime\s*free|""" +
            """download\s*(?:the\s*)?app|click\s*here|t\s*&\s*c\s*apply|hurry|""" +
            """limited\s*period|congratulations|lucky\s*draw|refer\s*(?:&|and)\s*earn|""" +
            """unsubscribe|reply\s*stop|winner|you'?ve\s*won|eligible\s*for|apply\s*now|""" +
            """flat\s*\d+%\s*off|\d+%\s*discount|sale\s*is\s*live|install\s*(?:the\s*)?app|""" +
            """reminder)\b""",
        RegexOption.IGNORE_CASE
    )

    // A payment REQUEST (UPI collect) describes nothing that has happened yet -- it's asking the
    // user to approve a future debit. Both "has requested Rs.X from you" and "is requesting
    // payment of Rs.X" phrasings are used by different banks/apps for the same kind of message.
    private val pendingRequestRegex = Regex(
        """\bhas\s+requested\b.{0,30}?\bfrom\s+you\b""" +
            """|\brequested\s+money\s+from\s+you\b""" +
            """|\bon\s+approving\s+the\s+request\b""" +
            """|\bto\s+authorize\s+(?:the\s+)?debit\b""" +
            """|\b(?:is\s+)?requesting\s+payment\b""",
        RegexOption.IGNORE_CASE
    )

    // A credit-LIMIT grant ("approved for a Rs.10000 limit") is a capacity increase, not a
    // transaction -- nothing was spent. Tightly scoped to "approved for ... limit" as one clause
    // so it does NOT catch "Txn ... is Approved. Avl limit Rs.X" (two separate clauses, a real
    // purchase where "limit" is just balance-info trailing a completed, unrelated approval).
    private val limitGrantRegex = Regex(
        """\bapproved\s+for\s+(?:an?\s+)?(?:rs\.?\s*[\d,]+\s+)?(?:credit\s+)?limit\b""",
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

    // Same-message dual narration ("X is debited for Rs.Y and credited to Z") describes ONE
    // outgoing UPI transfer from two angles -- the debit describes the user's account, the
    // credit describes the payee. Without this, both verbs matching near the amount fell into
    // "ambiguous" and the message was dropped -- this was the single most common Indian Bank UPI
    // debit format in the round-3 dataset (~900 real messages).
    private val debitThenCreditToRegex = Regex("""\bdebited\b.{0,80}?\band\s+credited\s+to\b""", RegexOption.IGNORE_CASE)
    private val creditThenDebitFromRegex = Regex("""\bcredited\b.{0,80}?\band\s+debited\s+(?:from|to)\b""", RegexOption.IGNORE_CASE)

    // "Money Transfer:Rs.X from HDFC Bank A/c **8061 to PAYEE" -- a real completed transfer
    // described as a noun-phrase LABEL, with no debit/credit verb anywhere in the message at all.
    private val moneyTransferRegex = Regex("""\bmoney\s+transfer\b""", RegexOption.IGNORE_CASE)

    // Only a message that DELIVERS a code is an OTP message. The old check was `"otp" in body`,
    // a bare substring match, which wrongly rejected real transaction alerts that merely mention
    // OTP in passing ("...spent...without PIN/OTP", a security notice, not a code delivery).
    private val otpDeliveryRegex = Regex(
        """\byour\s+otp\b|\botp\s+(?:is|for)\b|\bone[\s-]?time\s*password\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * How close a verb must be to the amount match to count as describing THAT amount.
     *
     * Was 45, then 90 (round 2, validated against the real spam/ham corpus at this width: zero
     * new false positives). The template/label checks above (cardTxnTemplateRegex,
     * moneyTransferRegex, etc.) are separate, unbounded-distance overrides used ONLY for very
     * specific, low-ambiguity phrasings -- they do not weaken this general window.
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
            vpaRegex.containsMatchIn(body) ||
            cardTxnTemplateRegex.containsMatchIn(body) ||
            repaymentRegex.containsMatchIn(body)

    fun parse(body: String, timestamp: Long): TransactionEntity? {
        // Hard vetoes first: no amount of other evidence overrides these.
        if (otpDeliveryRegex.containsMatchIn(body)) return null
        if (promoBlockRegex.containsMatchIn(body)) return null
        if (declinedRegex.containsMatchIn(body)) return null
        if (pendingRequestRegex.containsMatchIn(body)) return null
        if (limitGrantRegex.containsMatchIn(body)) return null

        val amountMatch = amountRegex.find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val amountRange = amountMatch.groups[1]!!.range

        // The verb must describe THIS amount, not just appear somewhere in the message -- except
        // for a small set of unambiguous templates/labels that are strong enough on their own
        // regardless of distance from the amount (merchant names can be long/padded).
        val isDebit = nearAmount(body, amountRange, debitVerbRegex)
        val isCredit = nearAmount(body, amountRange, creditVerbRegex)
        val type = when {
            cardTxnTemplateRegex.containsMatchIn(body) -> TxnType.DEBIT
            moneyTransferRegex.containsMatchIn(body) -> TxnType.DEBIT
            repaymentRegex.containsMatchIn(body) -> TxnType.DEBIT
            debitThenCreditToRegex.containsMatchIn(body) -> TxnType.DEBIT
            creditThenDebitFromRegex.containsMatchIn(body) -> TxnType.CREDIT
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
        // A card bill payment or a BNPL/credit-line repayment: same category of event, a
        // settlement for spending that should already be logged individually elsewhere.
        val isSettlement = cardBillPaymentRegex.containsMatchIn(body) || repaymentRegex.containsMatchIn(body)

        return TransactionEntity(
            amount = amount,
            // A settlement is never income, regardless of which verb the bank used to phrase it
            // -- force DEBIT so a mis-toggled "Exclude from totals" defaults to understating
            // spend rather than fabricating income.
            type = if (isSettlement) TxnType.DEBIT else type,
            merchant = merchant,
            matchKey = matchKey,
            upiId = upiId,
            bank = bank,
            isExcluded = looksSelf || isSettlement,
            exclusionSource = when {
                isSettlement -> ExclusionSource.CARD_BILL_PAYMENT
                looksSelf -> ExclusionSource.SMS_HINT
                else -> ExclusionSource.NONE
            },
            timestamp = timestamp,
            source = TxnSource.SMS,
            rawSms = body
        )
    }
}
