package com.lifetrack.app.creditcard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifetrack.app.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CreditCardDao {

    // --- cards ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCard(c: CreditCardEntity): Long

    @Query("SELECT * FROM credit_cards WHERE lastFourDigits = :last4 LIMIT 1")
    suspend fun cardByLast4(last4: String): CreditCardEntity?

    /** Registers a card the first time its last4 is seen; returns the existing id if already known. */
    @androidx.room.Transaction
    suspend fun getOrCreateCard(name: String, last4: String, bank: String?): Long {
        cardByLast4(last4)?.let { return it.id }
        val id = insertCard(CreditCardEntity(name = name, lastFourDigits = last4, bank = bank))
        // IGNORE conflict strategy returns -1 on a race; look it up if so.
        return if (id > 0) id else (cardByLast4(last4)?.id ?: -1L)
    }

    @Query("SELECT * FROM credit_cards ORDER BY name")
    fun cards(): Flow<List<CreditCardEntity>>

    @Query("SELECT * FROM credit_cards ORDER BY name")
    suspend fun cardsSync(): List<CreditCardEntity>

    @Query("UPDATE credit_cards SET name = :name, colorHex = :colorHex, emoji = :emoji WHERE id = :id")
    suspend fun updateCard(id: Long, name: String, colorHex: String, emoji: String)

    @Query("DELETE FROM credit_cards WHERE id = :id")
    suspend fun deleteCard(id: Long)

    @Query("DELETE FROM credit_card_statements WHERE cardId = :id")
    suspend fun deleteStatementsForCard(id: Long)

    /** Detaches this card's transactions back into ordinary Overall-counted spend. */
    @Query("UPDATE transactions SET creditCardId = NULL, isExcluded = 0, exclusionSource = 'NONE' WHERE creditCardId = :id")
    suspend fun detachTxnsFromCard(id: Long)

    @androidx.room.Transaction
    suspend fun deleteCardCascade(id: Long) {
        detachTxnsFromCard(id)
        deleteStatementsForCard(id)
        deleteCard(id)
    }

    // --- statements ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatement(s: CreditCardStatementEntity): Long

    @Query("SELECT * FROM credit_card_statements WHERE cardId = :cardId ORDER BY statementDate DESC")
    fun statementsForCard(cardId: Long): Flow<List<CreditCardStatementEntity>>

    @Query("SELECT * FROM credit_card_statements WHERE cardId = :cardId ORDER BY statementDate DESC LIMIT 1")
    suspend fun latestStatement(cardId: Long): CreditCardStatementEntity?

    @Query("SELECT * FROM credit_card_statements")
    suspend fun allStatementsSync(): List<CreditCardStatementEntity>

    // --- transactions on a card ---
    @Query("SELECT * FROM transactions WHERE creditCardId = :cardId ORDER BY timestamp DESC")
    fun txnsForCard(cardId: Long): Flow<List<TransactionEntity>>

    /**
     * Current running balance for the card's OPEN cycle: everything since its most recent
     * statement (or, if it has none yet, everything since the card was registered). Computed
     * from actual transaction flow -- purchases add, the eventual bill payment subtracts -- not
     * from a scraped "Total due" string. See CreditCardMatcher's doc comment for why.
     *
     * Excludes exclusionSource = 'CARD_BILL_PAYMENT' rows. A bill payment (whichever of the
     * bank's two messages for it happens to reach this table -- the debit-side "Sent to Card
     * Ending X" alert, or the card-side "Payment received towards your Credit Card" confirmation)
     * pays DOWN what you owe; it was never itself a purchase accumulating against this cycle, so
     * summing it in here as if it were spend would overstate this cycle's total by the payment
     * amount every time.
     */
    @Query(
        """SELECT COALESCE(SUM(amount),0) FROM transactions
           WHERE creditCardId = :cardId AND type = 'DEBIT' AND timestamp > :since
             AND exclusionSource != 'CARD_BILL_PAYMENT'"""
    )
    suspend fun cycleSpendSync(cardId: Long, since: Long): Double

    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE creditCardId = :cardId AND timestamp > :since
             AND exclusionSource != 'CARD_BILL_PAYMENT'"""
    )
    suspend fun cycleTxnCount(cardId: Long, since: Long): Int

    // --- tagging / retroactive sweep ---
    @Query("UPDATE transactions SET creditCardId = :cardId, isExcluded = 1, exclusionSource = 'CREDIT_CARD' WHERE id = :txnId")
    suspend fun tagTxnToCard(txnId: Long, cardId: Long)

    /** Every transaction not yet linked to a card -- candidates for the retroactive sweep. */
    @Query("SELECT * FROM transactions WHERE creditCardId IS NULL AND rawSms IS NOT NULL")
    suspend fun unlinkedTxnsWithSms(): List<TransactionEntity>
}
