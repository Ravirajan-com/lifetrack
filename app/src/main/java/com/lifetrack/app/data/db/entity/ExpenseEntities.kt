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
    SMS_HINT
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
