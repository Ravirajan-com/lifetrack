package com.lifetrack.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Free-form label, orthogonal to category. A txn has one category but many tags. */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#78909C"
)

@Entity(
    tableName = "txn_tags",
    primaryKeys = ["txnId", "tagId"],
    indices = [Index("txnId"), Index("tagId")]
)
data class TxnTagCrossRef(
    val txnId: Long,
    val tagId: Long
)

/**
 * Frozen rollup for a month whose raw transactions have been archived to a file and deleted.
 *
 * This is what makes "back it up locally then clear it" survivable: the Year view reads live
 * months from `transactions` and purged months from here, so your history chart never develops
 * holes just because you reclaimed space.
 */
@Entity(tableName = "monthly_summaries")
data class MonthlySummaryEntity(
    /** ISO year-month, e.g. "2026-07". */
    @PrimaryKey val yearMonth: String,
    val totalSpend: Double,
    val totalIncome: Double,
    val txnCount: Int,
    val archivedAt: Long,
    /** Where the raw JSON went, so you can find it later. */
    val archivePath: String? = null
)
