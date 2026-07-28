package com.lifetrack.app.creditcard

/**
 * Parsed fields from a credit-card STATEMENT-GENERATED SMS. Not a transaction.
 *
 * Deliberately has NO due-date field. The due date doesn't change what counts as this cycle's
 * spend vs. next cycle's -- only the STATEMENT date does that. totalDue is kept only as an
 * optional reference figure to display; it is never used to compute "what you currently owe".
 * That number comes from summing actual purchase/payment transactions instead (see
 * CreditCardDao.currentCycleFlow), since real statement SMS have variants where this field is
 * missing or unparseable ("Pay by Nil", or the due amount sitting behind a link rather than in
 * the message body) -- a scraped total-due string is not something the running balance should
 * depend on.
 */
data class StatementInfo(
    val last4: String,
    val bank: String?,
    val totalDue: Double?
)

/**
 * Extracts credit-card identity and statement data from raw SMS text.
 *
 * WHY THIS IS A SEPARATE FILE FROM SmsParser
 * --------------------------------------------
 * A statement-generated SMS ("HDFC Bank Credit Card XX1860 Statement: Total due amt: Rs.1,468.00
 * Min due amt: Rs.200.00 Due by:08-06-2023") is correctly rejected by SmsParser as NOT a
 * transaction -- no money moved, it's a bill notice. That's the right call for SmsParser, but it
 * means these messages never reach [SmsParser.parse] as anything other than null, and the
 * repository needs a SEPARATE check to catch them before giving up on a message. This object is
 * that separate check, plus the shared "which card does this message reference" extractor used
 * for tagging ordinary purchase/payment transactions to a registered card too.
 *
 * SCOPE: only cards that reference an explicit masked number ("Card XX1234", "Card ending 4321")
 * are supported. Some credit-line products (seen in testing: "slice") never mention a masked
 * card number at all -- there's nothing to match on, so those are out of scope for this specific
 * per-card tracking system and keep using the existing settlement-exclusion handling in
 * SmsParser instead.
 */
object CreditCardMatcher {

    // Matches "Credit Card XX1234", "Card xx4321", "Card ending 4321", "Card ending with XX0121",
    // and generic "A/c *8061" phrasings often used for cards.
    private val cardRefRegex = Regex(
        """(?:credit\s*card|card|a/?c)\b[^.\n]{0,20}?(?:[Xx*]{1,}(\d{3,6})|ending\s+(?:with\s+)?[Xx*]{0,4}\s*(\d{3,6}))""",
        RegexOption.IGNORE_CASE
    )

    // Requires an actual due-amount phrase or explicit "generated" wording -- NOT just any
    // mention of the word "statement" near "credit card". A message like "...select Credit
    // Cards tab to view statement details" mentions both words but isn't a real bill event;
    // this trigger correctly ignores it because it has neither a due amount nor "generated".
    private val statementTriggerRegex = Regex(
        """\btotal\s*due\b|\bmin\.?\s*due\b|\bstatement\s*generated\b|\bstatement\s+of\b.{0,20}?\bgenerated\b""",
        RegexOption.IGNORE_CASE
    )
    private val totalDueRegex = Regex("""total\s*due(?:\s*amt)?[:\s]*(?:rs\.?|inr)?\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val bankRegex = Regex("""\b(HDFC|SBI|ICICI|AXIS|KOTAK|PNB|BOB|IDFC|YES|CANARA|UNION|INDUSIND|FEDERAL)\b""", RegexOption.IGNORE_CASE)

    /** The last 4 digits of whatever masked card this message references, if any. */
    fun extractLast4(body: String): String? {
        val m = cardRefRegex.find(body) ?: return null
        val digits = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return digits.takeLast(4).ifEmpty { null }
    }

    /**
     * Recognizes a real statement-generated SMS and pulls out what it can. totalDue is optional
     * reference info only -- see the doc comment on [StatementInfo] for why it's never load-
     * bearing. The one thing every variant reliably provides is simply THAT a statement fired,
     * which is what the caller uses as the cycle boundary (via the SMS timestamp).
     */
    fun extractStatementInfo(body: String): StatementInfo? {
        if (!statementTriggerRegex.containsMatchIn(body)) return null
        val last4 = extractLast4(body) ?: return null // no card ref -> can't attribute it, skip
        return StatementInfo(
            last4 = last4,
            bank = bankRegex.find(body)?.value?.uppercase(),
            totalDue = totalDueRegex.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        )
    }
}
