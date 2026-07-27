package com.lifetrack.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TxnType { DEBIT, CREDIT }
enum class TxnSource { SMS, MANUAL }

/** Categories are split by side of the ledger so income lists don't fill up with expense buckets. */
enum class CategoryKind { EXPENSE, INCOME }

/**
 * Why a transaction is excluded from totals. The distinction matters: the transfer detector may
 * revise its OWN decisions on every rescan, but must never overwrite the user's.
 */
enum class ExclusionSource {
    /** Not excluded. */
    NONE,

    /** The user flipped the switch by hand. Permanent until they flip it back. */
    USER,

    /** Paired with an opposite leg by the transfer detector. Re-evaluated on each rescan. */
    AUTO_TRANSFER,

    /** The SMS text itself said "to self" / "own a/c". Weak evidence, re-evaluated on rescan. */
    SMS_HINT,

    /**
     * A credit-card BILL PAYMENT ("your payment of Rs.X for Card XX1234 has been credited",
     * "Rs.X debited towards Credit Card XX1234 payment"). This is neither income nor a fresh
     * expense: it's money settling a card balance for purchases that (should) already be logged
     * individually at time of purchase. Counting the bill payment too would double-count that
     * spending -- or worse, count it as income, since bank phrasing narrates it from the card's
     * side ("credited" = the card's balance went down, not money entering your life).
     */
    CARD_BILL_PAYMENT,

    /** Belongs to a tracked credit card -- lives in its own separate view, not in Overall. */
    CREDIT_CARD
}

/**
 * Who assigned a transaction's category. Mirrors [ExclusionSource]'s reasoning: the merchant
 * keyword classifier makes low-confidence guesses and must never be able to overwrite a
 * choice the user made, or one already assigned by a learned rule.
 */
enum class CategorySource {
    /** categoryId is null. */
    NONE,

    /** The user picked it directly (one-off override). */
    USER,

    /** Applied by a learned merchant_rules entry (matchKey -> category). */
    RULE,

    /** Guessed by the keyword dictionary because nothing else had categorized it. */
    KEYWORD_AUTO
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#4CAF50",
    /** Monthly budget in rupees; null = no budget set */
    val monthlyBudget: Double? = null,
    /** Custom emoji icon for this category */
    val iconEmoji: String? = null,
    /** EXPENSE buckets and INCOME sources are kept in separate lists. */
    val kind: CategoryKind = CategoryKind.EXPENSE
)

@Entity(
    tableName = "transactions",
    indices = [Index("categoryId"), Index("timestamp"), Index("matchKey")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val type: TxnType,
    /** Display name of merchant, e.g. "SWIGGY" */
    val merchant: String,
    /** Normalized key used for auto-categorization rules: upi id if present else merchant name */
    val matchKey: String,
    val upiId: String? = null,
    val bank: String? = null,
    val note: String? = null,
    val categoryId: Long? = null,
    /** true when user manually overrode this single txn; rules won't touch it */
    val manualOverride: Boolean = false,
    /** true if this transaction should be hidden from dashboard charts and totals */
    val isExcluded: Boolean = false,
    /** Who decided [isExcluded]. Guards the user's choice against the auto-detector. */
    val exclusionSource: ExclusionSource = ExclusionSource.NONE,
    /** Shared id linking the two legs of a detected self-transfer; null when unpaired. */
    val transferGroupId: String? = null,
    /** Who assigned [categoryId]. See [CategorySource]. */
    val categorySource: CategorySource = CategorySource.NONE,
    /** Set when this transaction belongs to a tracked credit card. See ExclusionSource.CREDIT_CARD. */
    val creditCardId: Long? = null,
    val timestamp: Long,
    val source: TxnSource = TxnSource.MANUAL,
    val rawSms: String? = null
)

/** Learned rule: any txn whose matchKey equals [matchKey] gets [categoryId] automatically. */
@Entity(tableName = "merchant_rules", indices = [Index(value = ["matchKey"], unique = true)])
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchKey: String,
    val categoryId: Long
)
