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
        GoalEntity::class, GoalCompletionEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun gymDao(): GymDao
    abstract fun goalDao(): GoalDao

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

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "lifetrack.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    // Safety net while you're still iterating on the schema. Room prefers a real
                    // migration when one exists and only wipes when no path is found.
                    // DELETE THIS LINE before you ship / start keeping data you care about.
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
