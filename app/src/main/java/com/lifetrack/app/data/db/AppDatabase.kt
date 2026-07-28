package com.lifetrack.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lifetrack.app.data.db.dao.ExpenseDao
import com.lifetrack.app.data.db.dao.GoalDao
import com.lifetrack.app.data.db.dao.GymDao
import com.lifetrack.app.creditcard.CreditCardDao
import com.lifetrack.app.creditcard.CreditCardEntity
import com.lifetrack.app.creditcard.CreditCardStatementEntity
import com.lifetrack.app.data.db.entity.CategoryEntity
import com.lifetrack.app.data.db.entity.ExerciseEntity
import com.lifetrack.app.data.db.entity.GoalCompletionEntity
import com.lifetrack.app.data.db.entity.GoalEntity
import com.lifetrack.app.data.db.entity.MerchantRuleEntity
import com.lifetrack.app.data.db.entity.MonthlySummaryEntity
import com.lifetrack.app.data.db.entity.SessionEntity
import com.lifetrack.app.data.db.entity.SetLogEntity
import com.lifetrack.app.data.db.entity.TagEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnTagCrossRef
import com.lifetrack.app.data.db.entity.WorkoutCategoryEntity

@Database(
    entities = [
        CategoryEntity::class, TransactionEntity::class, MerchantRuleEntity::class,
        TagEntity::class, TxnTagCrossRef::class, MonthlySummaryEntity::class,
        WorkoutCategoryEntity::class, ExerciseEntity::class,
        SessionEntity::class, SetLogEntity::class,
        GoalEntity::class, GoalCompletionEntity::class,
        CreditCardEntity::class, CreditCardStatementEntity::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun gymDao(): GymDao
    abstract fun goalDao(): GoalDao
    abstract fun creditCardDao(): CreditCardDao

    companion object {

        /**
         * v4 -> v5: tags, txn<->tag links, and archived monthly rollups.
         *
         * Index names must match Room's generated convention exactly (`index_<table>_<col>`)
         * or the identity-hash validation fails at open time.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`colorHex` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `txn_tags` (" +
                        "`txnId` INTEGER NOT NULL, " +
                        "`tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`txnId`, `tagId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_tags_txnId` ON `txn_tags` (`txnId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_tags_tagId` ON `txn_tags` (`tagId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `monthly_summaries` (" +
                        "`yearMonth` TEXT NOT NULL, " +
                        "`totalSpend` REAL NOT NULL, " +
                        "`totalIncome` REAL NOT NULL, " +
                        "`txnCount` INTEGER NOT NULL, " +
                        "`archivedAt` INTEGER NOT NULL, " +
                        "`archivePath` TEXT, " +
                        "PRIMARY KEY(`yearMonth`))"
                )
            }
        }

        /**
         * v5 -> v6: income/expense split on categories, plus explicit provenance for exclusions.
         *
         * Existing excluded rows are backfilled as SMS_HINT rather than USER on purpose. The old
         * code could not distinguish the two, and the overwhelming majority of those exclusions
         * came from the SMS keyword heuristic (which wrongly matched every NEFT/IMPS/RTGS payment)
         * or from the old mirror-matcher. Calling them SMS_HINT lets the new detector re-judge
         * them; calling them USER would freeze those mistakes in place forever.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'EXPENSE'")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `exclusionSource` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `transferGroupId` TEXT DEFAULT NULL")
                db.execSQL("UPDATE `transactions` SET `exclusionSource` = 'SMS_HINT' WHERE `isExcluded` = 1")
            }
        }

        /**
         * v6 -> v7: categorySource provenance, mirroring exclusionSource.
         *
         * Existing categorized rows are backfilled by inferring the most likely origin from
         * fields that already existed: manualOverride=1 -> USER (that flag only gets set by the
         * one-off "categorize this single transaction" path), manualOverride=0 with a category
         * already set -> RULE (that's the "apply to all past+future from this merchant" path).
         * Nothing here is guessed by keywords -- KEYWORD_AUTO only ever appears going forward,
         * from this version onward, so old data can't be mistaken for a keyword guess.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `categorySource` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL(
                    "UPDATE `transactions` SET `categorySource` = 'USER' " +
                        "WHERE `categoryId` IS NOT NULL AND `manualOverride` = 1"
                )
                db.execSQL(
                    "UPDATE `transactions` SET `categorySource` = 'RULE' " +
                        "WHERE `categoryId` IS NOT NULL AND `manualOverride` = 0"
                )
            }
        }

        /**
         * v7 -> v8: tracked credit cards, their statement history, and a link column on
         * transactions. New enum case (CREDIT_CARD) needs no migration -- exclusionSource is
         * stored as plain TEXT with no CHECK constraint (see Converters.kt), so a new Kotlin
         * constant is automatically forward/backward compatible.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `credit_cards` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`lastFourDigits` TEXT NOT NULL, " +
                        "`bank` TEXT, " +
                        "`colorHex` TEXT NOT NULL, " +
                        "`emoji` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_cards_lastFourDigits` ON `credit_cards` (`lastFourDigits`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `credit_card_statements` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cardId` INTEGER NOT NULL, " +
                        "`statementDate` INTEGER NOT NULL, " +
                        "`totalDue` REAL, " +
                        "`rawSms` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_statements_cardId` ON `credit_card_statements` (`cardId`)")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `creditCardId` INTEGER DEFAULT NULL")
            }
        }

        /**
         * v8 -> v9: Transaction deduplication. Adds a unique index on (rawSms, timestamp)
         * to prevent duplicate ingestion during inbox backfills or data restores.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_rawSms_timestamp` " +
                        "ON `transactions` (`rawSms`, `timestamp`)"
                )
            }
        }

        /**
         * v9 -> v10: Robust deduplication. Switches the unique index to (timestamp, amount, type)
         * to catch duplicates even if rawSms is missing (legacy backup) or slightly different.
         * Cleans up existing duplicates before creating the index.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Remove the old, weaker index.
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_rawSms_timestamp`")
                
                // 2. Cleanup existing duplicates: keep the row with the most info (prefer RULE/USER source).
                // If info is equal, keep the one with the smallest id.
                db.execSQL("""
                    DELETE FROM transactions 
                    WHERE id NOT IN (
                        SELECT id FROM (
                            SELECT id, ROW_NUMBER() OVER (
                                PARTITION BY timestamp, amount, type 
                                ORDER BY 
                                    CASE categorySource 
                                        WHEN 'USER' THEN 1 
                                        WHEN 'RULE' THEN 2 
                                        WHEN 'KEYWORD_AUTO' THEN 3 
                                        ELSE 4 
                                    END ASC,
                                    id ASC
                            ) as rn
                            FROM transactions
                        ) WHERE rn = 1
                    )
                """.trimIndent())

                // 3. Create the new, stronger unique index.
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_timestamp_amount_type` " +
                        "ON `transactions` (`timestamp`, `amount`, `type`)"
                )
            }
        }

        /**
         * v10 -> v11: closes duplication gaps and implements Gym/Goal deduplication.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- categories: merge duplicates ---
                db.execSQL(
                    """UPDATE transactions SET categoryId = (
                           SELECT MIN(c2.id) FROM categories c2
                           JOIN categories c1 ON c1.name = c2.name AND c1.kind = c2.kind
                           WHERE c1.id = transactions.categoryId
                       )
                       WHERE categoryId IS NOT NULL"""
                )
                db.execSQL(
                    """UPDATE merchant_rules SET categoryId = (
                           SELECT MIN(c2.id) FROM categories c2
                           JOIN categories c1 ON c1.name = c2.name AND c1.kind = c2.kind
                           WHERE c1.id = merchant_rules.categoryId
                       )"""
                )
                db.execSQL("DELETE FROM categories WHERE id NOT IN (SELECT MIN(id) FROM categories GROUP BY name, kind)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name_kind` ON `categories` (`name`, `kind`)")

                // --- credit_card_statements: dedupe ---
                db.execSQL("DELETE FROM credit_card_statements WHERE id NOT IN (SELECT MIN(id) FROM credit_card_statements GROUP BY cardId, statementDate)")
                db.execSQL("DROP INDEX IF EXISTS `index_credit_card_statements_cardId`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_card_statements_cardId_statementDate` ON `credit_card_statements` (`cardId`, `statementDate`)")

                // --- gym: dedupe categories and exercises ---
                db.execSQL("DELETE FROM workout_categories WHERE id NOT IN (SELECT MIN(id) FROM workout_categories GROUP BY name)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_categories_name` ON `workout_categories` (`name`)")
                
                db.execSQL("DELETE FROM exercises WHERE id NOT IN (SELECT MIN(id) FROM exercises GROUP BY categoryId, name)")
                db.execSQL("DROP INDEX IF EXISTS `index_exercises_categoryId`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_categoryId_name` ON `exercises` (`categoryId`, `name`)")

                // --- goals: dedupe ---
                db.execSQL("DELETE FROM goals WHERE id NOT IN (SELECT MIN(id) FROM goals GROUP BY title)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_goals_title` ON `goals` (`title`)")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "lifetrack.db"
                )
                    .addMigrations(
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        /**
         * Reclaims the disk space freed by a purge. SQLite keeps deleted pages in the file and
         * reuses them later, so DELETE alone never shrinks lifetrack.db.
         * Cannot run inside a transaction.
         */
        fun vacuum(context: Context) {
            get(context).openHelper.writableDatabase.execSQL("VACUUM")
        }
    }
}
