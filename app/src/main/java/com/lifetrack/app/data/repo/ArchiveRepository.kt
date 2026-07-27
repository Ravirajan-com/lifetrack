package com.lifetrack.app.data.repo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.entity.MonthlySummaryEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Serializable
data class ArchivedTxn(
    val amount: Double,
    val type: String,
    val merchant: String,
    val matchKey: String,
    val upiId: String?,
    val bank: String?,
    val note: String?,
    val categoryName: String?,
    val tags: List<String>,
    val manualOverride: Boolean,
    val isExcluded: Boolean,
    val timestamp: Long,
    val source: String
)

@Serializable
data class TransactionArchive(
    val version: Int = 1,
    val createdAt: Long,
    /** Exclusive upper bound of the archived range, as epoch millis. */
    val cutoff: Long,
    val count: Int,
    val transactions: List<ArchivedTxn>
)

sealed interface ArchiveResult {
    data class Success(val count: Int, val path: String, val monthsRolledUp: Int) : ArchiveResult
    data object NothingToArchive : ArchiveResult
    data class Failed(val reason: String) : ArchiveResult
}

/**
 * Export-then-purge, for keeping the live database small.
 *
 * Order matters and is deliberate:
 *   1. read the rows,
 *   2. write the file and verify it landed,
 *   3. write monthly rollups so the Year view keeps working,
 *   4. only then delete,
 *   5. VACUUM to actually give the space back.
 *
 * If step 2 fails, nothing is deleted.
 */
class ArchiveRepository private constructor(private val context: Context) {

    private val dao = AppDatabase.get(context).expenseDao()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** How many transactions sit strictly before [before], and roughly how much space they hold. */
    suspend fun preview(before: LocalDate): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val cutoff = before.atStartOfDay(zone).toInstant().toEpochMilli()
        val count = dao.countTxnsBefore(cutoff)
        val dbBytes = context.getDatabasePath("lifetrack.db").let { if (it.exists()) it.length() else 0L }
        count to dbBytes
    }

    suspend fun archiveAndPurge(before: LocalDate, deleteAfterExport: Boolean): ArchiveResult =
        withContext(Dispatchers.IO) {
            val cutoff = before.atStartOfDay(zone).toInstant().toEpochMilli()

            val txns = dao.txnsBefore(cutoff)
            if (txns.isEmpty()) return@withContext ArchiveResult.NothingToArchive

            val categoryNames = dao.categoriesSync().associate { it.id to it.name }
            // One pass over the link table rather than a query per transaction.
            val tagsByTxn: Map<Long, List<String>> =
                dao.allTxnTagLinksSync().groupBy({ it.txnId }, { it.name })

            val archive = TransactionArchive(
                createdAt = System.currentTimeMillis(),
                cutoff = cutoff,
                count = txns.size,
                transactions = txns.map { t ->
                    ArchivedTxn(
                        amount = t.amount,
                        type = t.type.name,
                        merchant = t.merchant,
                        matchKey = t.matchKey,
                        upiId = t.upiId,
                        bank = t.bank,
                        note = t.note,
                        categoryName = t.categoryId?.let { categoryNames[it] },
                        tags = tagsByTxn[t.id].orEmpty(),
                        manualOverride = t.manualOverride,
                        isExcluded = t.isExcluded,
                        timestamp = t.timestamp,
                        source = t.source.name
                    )
                }
            )

            val name = "lifetrack-archive-before-$before.json"
            val body = json.encodeToString(TransactionArchive.serializer(), archive)

            val path = runCatching { writeToDownloads(name, body) }
                .getOrElse { return@withContext ArchiveResult.Failed(it.message ?: "Could not write the archive file") }

            // Roll up every affected month BEFORE deleting, or the Year view loses those bars.
            val months = txns.groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) }
            months.forEach { (ym, rows) ->
                val active = rows.filter { !it.isExcluded }
                dao.upsertMonthlySummary(
                    MonthlySummaryEntity(
                        yearMonth = ym.toString(),
                        totalSpend = active.filter { it.type == TxnType.DEBIT }.sumOf { it.amount },
                        totalIncome = active.filter { it.type == TxnType.CREDIT }.sumOf { it.amount },
                        txnCount = rows.size,
                        archivedAt = System.currentTimeMillis(),
                        archivePath = path
                    )
                )
            }

            if (deleteAfterExport) {
                dao.deleteTxnsBefore(cutoff)
                dao.pruneOrphanTagLinks()
                runCatching { AppDatabase.vacuum(context) }
            }

            ArchiveResult.Success(count = txns.size, path = path, monthsRolledUp = months.size)
        }

    /**
     * Writes to the shared Downloads collection on API 29+ (no permission needed there).
     * On older devices falls back to the app's own external files dir, which also needs no
     * permission but is less discoverable — the returned path tells the user where to look.
     */
    private fun writeToDownloads(fileName: String, contents: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LifeTrack")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Downloads folder rejected the file")
            resolver.openOutputStream(uri)?.use { it.write(contents.toByteArray()) }
                ?: error("Could not open the archive file for writing")
            return "Downloads/LifeTrack/$fileName"
        }

        val dir = File(context.getExternalFilesDir(null), "archives").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(contents)
        return file.absolutePath
    }

    companion object {
        @Volatile private var instance: ArchiveRepository? = null
        fun get(context: Context): ArchiveRepository =
            instance ?: synchronized(this) {
                instance ?: ArchiveRepository(context.applicationContext).also { instance = it }
            }
    }
}
