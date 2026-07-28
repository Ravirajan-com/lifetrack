package com.lifetrack.app.creditcard

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tracked credit card. Auto-created the first time a statement-generated SMS is seen for a
 * given masked number (see CreditCardMatcher) -- that's a safe signal specifically because only
 * real credit cards get statement SMS; a debit card never does, so this can't accidentally get
 * created from an ordinary debit-card purchase.
 */
@Entity(tableName = "credit_cards", indices = [Index(value = ["lastFourDigits"], unique = true)])
data class CreditCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Used to match future SMS to this card. Not globally unique across banks in theory, but
     *  collisions in practice are vanishingly rare for one person's own set of cards. */
    val lastFourDigits: String,
    val bank: String? = null,
    val colorHex: String = "#7E57C2",
    val emoji: String = "💳",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A lightweight cycle-boundary marker, created each time a statement-generated SMS arrives for
 * a tracked card. totalDue is reference info only -- see the doc comment on StatementInfo for
 * why the running balance is computed from actual transactions instead of this field.
 */
@Entity(tableName = "credit_card_statements", indices = [Index(value = ["cardId", "statementDate"], unique = true)])
data class CreditCardStatementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val statementDate: Long,
    val totalDue: Double? = null,
    val rawSms: String? = null
)
