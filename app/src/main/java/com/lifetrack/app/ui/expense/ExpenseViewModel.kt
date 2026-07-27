package com.lifetrack.app.ui.expense

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.dao.CategorySpend
import com.lifetrack.app.data.db.dao.TxnTagLink
import com.lifetrack.app.data.db.entity.CategoryEntity
import com.lifetrack.app.data.db.entity.CategoryKind
import com.lifetrack.app.data.db.entity.ExclusionSource
import com.lifetrack.app.data.db.entity.TagEntity
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnType
import com.lifetrack.app.data.prefs.BudgetPrefs
import com.lifetrack.app.data.repo.ArchiveRepository
import com.lifetrack.app.data.repo.ArchiveResult
import com.lifetrack.app.data.repo.ExpenseRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One row of the Categories card: every category, whether or not it has spend this month. */
data class CategoryRow(
    val category: CategoryEntity?,          // null == the "Uncategorized" bucket
    val name: String,
    val colorHex: String?,
    val emoji: String?,
    val spend: Double,
    val budget: Double?
) {
    val id: Long get() = category?.id ?: -1L
}

data class SearchFilters(
    val text: String = "",
    val categoryId: Long? = null,
    val tagId: Long? = null,
    val type: TxnType? = null,
    val from: Long? = null,
    val to: Long? = null,
    val includeExcluded: Boolean = false
) {
    val isActive: Boolean
        get() = text.isNotBlank() || categoryId != null || tagId != null ||
            type != null || from != null || to != null || includeExcluded
}

/** One bar in the year chart. [archived] means the raw rows are gone and this is a frozen rollup. */
data class MonthBar(
    val month: Int,
    val spend: Double,
    val income: Double,
    val txnCount: Int,
    val archived: Boolean
)

data class YearUiState(
    val year: Int = LocalDate.now().year,
    val months: List<MonthBar> = emptyList(),
    val totalSpend: Double = 0.0,
    val totalIncome: Double = 0.0,
    val avgMonthlySpend: Double = 0.0,
    val busiestMonth: Int? = null
)

data class DashboardUiState(
    val totalSpend: Double = 0.0,
    val totalIncome: Double = 0.0,
    /** Effective budget: the overall budget if set, else the sum of per-category limits. */
    val totalBudget: Double = 0.0,
    /** True when [totalBudget] came from an explicit overall budget rather than a category sum. */
    val budgetIsExplicit: Boolean = false,
    val safeToSpendToday: Double = 0.0,
    val withinBudgetStreak: Int = 0,
    val noSpendStreak: Int = 0,
    val dailyHeatmap: Map<Long, Double> = emptyMap(),
    val categoryRows: List<CategoryRow> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).expenseDao()
    private val repo = ExpenseRepository.get(app)
    private val budgetPrefs = BudgetPrefs.get(app)
    private val archive = ArchiveRepository.get(app)

    private val zone = ZoneId.systemDefault()
    
    val selectedMonth = MutableStateFlow(YearMonth.now())

    val availableMonths = dao.allTransactionTimes().map { times ->
        val months = times.asSequence().map { 
            YearMonth.from(Instant.ofEpochMilli(it).atZone(zone).toLocalDate())
        }.distinct().toMutableList()
        val now = YearMonth.now()
        if (!months.contains(now)) months.add(0, now)
        // Ensure current month is always first as requested
        months.sortedByDescending { it }.let { sorted ->
            val result = sorted.toMutableList()
            if (result.contains(now)) {
                result.remove(now)
                result.add(0, now)
            }
            result
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, listOf(YearMonth.now()))

    private val timeRange = selectedMonth.map { month ->
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        start to end
    }

    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Only EXPENSE buckets, for the expense pickers. */
    val expenseCategories = dao.categoriesByKind(CategoryKind.EXPENSE.name)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Only INCOME sources: Salary, Freelance, etc. */
    val incomeCategories = dao.categoriesByKind(CategoryKind.INCOME.name)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val monthTxns = timeRange.flatMapLatest { (start, end) ->
        dao.txnsBetween(start, end)
    }
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val uncategorized = dao.uncategorized().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val spendByCategory = timeRange.flatMapLatest { (start, end) ->
        dao.spendByCategory(start, end)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalSpend = timeRange.flatMapLatest { (start, end) ->
        dao.totalSpend(start, end)
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val totalIncome = timeRange.flatMapLatest { (start, end) ->
        dao.totalIncome(start, end)
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val incomeByCategory = timeRange.flatMapLatest { (start, end) ->
        dao.incomeByCategory(start, end)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Sum of per-category limits. */
    val categoryBudgetSum = dao.categoryBudgetSum().stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    /** User-set overall monthly cap; null when they haven't set one. */
    val overallBudget: StateFlow<Double?> = budgetPrefs.overallMonthlyBudget

    /**
     * The budget the dashboard actually uses.
     *
     * Before, this was ONLY the sum of category limits — so setting a budget did nothing visible
     * until every category had one, and there was no way to express "I want to spend at most
     * X this month" at all.
     */
    val totalBudget = combine(overallBudget, categoryBudgetSum) { overall, sum ->
        overall ?: sum
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    /** Every category + the uncategorized bucket, so budgets are always reachable from the UI. */
    val categoryRows = combine(categories, spendByCategory) { cats, spends ->
        val spendById = spends.associateBy { it.categoryId }
        val rows = cats.map { c ->
            CategoryRow(
                category = c,
                name = c.name,
                colorHex = c.colorHex,
                emoji = c.iconEmoji,
                spend = spendById[c.id]?.total ?: 0.0,
                budget = c.monthlyBudget
            )
        }.toMutableList()

        // Spend with no category still needs a visible home.
        spendById[null]?.let { orphan ->
            rows += CategoryRow(
                category = null,
                name = "Uncategorized",
                colorHex = null,
                emoji = null,
                spend = orphan.total,
                budget = null
            )
        }

        // Spenders first (descending), then unused categories alphabetically.
        rows.sortedWith(
            compareByDescending<CategoryRow> { it.spend > 0.0 }
                .thenByDescending { it.spend }
                .thenBy { it.name.lowercase() }
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dailySpendingHeatmap = timeRange.flatMapLatest { (start, end) ->
        dao.dailySpend(start, end)
    }.map { list ->
        list.associate { it.epochDay to it.total }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val safeToSpendToday = combine(totalBudget, totalSpend, selectedMonth) { budget, spent, month ->
        if (budget <= 0) return@combine 0.0
        val now = LocalDate.now()
        if (month != YearMonth.from(now)) return@combine 0.0
        
        val daysInMonth = month.lengthOfMonth()
        val daysPassed = now.dayOfMonth - 1
        val daysLeft = daysInMonth - daysPassed
        
        val remaining = (budget - spent).coerceAtLeast(0.0)
        remaining / daysLeft
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val withinBudgetStreak = combine(dailySpendingHeatmap, totalBudget, selectedMonth) { heatmap, budget, month ->
        val now = LocalDate.now()
        if (month != YearMonth.from(now)) return@combine 0
        
        val dailyBudget = budget / month.lengthOfMonth()
        if (dailyBudget <= 0) return@combine 0

        var streak = 0
        var checkDate = now // Start from today
        
        while (checkDate.month == now.month) {
            val spend = heatmap[checkDate.toEpochDay()] ?: 0.0
            if (spend <= dailyBudget) {
                streak++
                checkDate = checkDate.minusDays(1)
            } else {
                break
            }
        }
        streak
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val noSpendStreak = combine(dailySpendingHeatmap, selectedMonth) { heatmap, month ->
        val now = LocalDate.now()
        if (month != YearMonth.from(now)) return@combine 0
        
        var streak = 0
        var checkDate = now
        // If today has spending, check if streak ended yesterday
        if ((heatmap[checkDate.toEpochDay()] ?: 0.0) > 0.0) checkDate = checkDate.minusDays(1)
        
        while (checkDate.month == now.month) {
            val spend = heatmap[checkDate.toEpochDay()] ?: 0.0
            if (spend <= 0.0) {
                streak++
                checkDate = checkDate.minusDays(1)
            } else {
                break
            }
        }
        streak
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // Consolidated state to minimize UI recompositions (reduces lag)
    val uiState = combine(
        totalSpend, totalIncome, totalBudget, safeToSpendToday, withinBudgetStreak,
        noSpendStreak, dailySpendingHeatmap, categoryRows, overallBudget
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        DashboardUiState(
            totalSpend = args[0] as Double,
            totalIncome = args[1] as Double,
            totalBudget = args[2] as Double,
            safeToSpendToday = args[3] as Double,
            withinBudgetStreak = args[4] as Int,
            noSpendStreak = args[5] as Int,
            dailyHeatmap = args[6] as Map<Long, Double>,
            categoryRows = args[7] as List<CategoryRow>,
            budgetIsExplicit = args[8] != null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    val allBanks = dao.allBanks().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedBank = MutableStateFlow<String?>(null)

    val filteredTxns = combine(monthTxns, selectedCategoryId, selectedBank) { txns, catId, bank ->
        txns.filter { 
            ((catId == null) || (it.categoryId == catId)) && ((bank == null) || (it.bank == bank))
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ================================ TAGS ==================================

    val tags = dao.tags().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** txnId -> its tags, so list rows don't each fire their own query. */
    val tagsByTxn: StateFlow<Map<Long, List<TxnTagLink>>> = dao.allTxnTagLinks()
        .map { links -> links.groupBy { it.txnId } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    fun addTag(name: String, colorHex: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isNotEmpty()) dao.insertTag(TagEntity(name = clean, colorHex = colorHex))
    }

    fun renameTag(id: Long, name: String, colorHex: String) = viewModelScope.launch {
        dao.updateTag(id, name.trim(), colorHex)
    }

    fun deleteTag(id: Long) = viewModelScope.launch { dao.deleteTagCascade(id) }

    fun setTagsForTxn(txnId: Long, tagIds: List<Long>) = viewModelScope.launch {
        dao.setTagsForTxn(txnId, tagIds)
    }

    /** Create-if-missing, then attach. Lets the user type a new tag inline. */
    fun addTagToTxn(txnId: Long, name: String, colorHex: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        val id = dao.insertTag(TagEntity(name = clean, colorHex = colorHex))
            .takeIf { it > 0L } ?: dao.tagIdByName(clean) ?: return@launch
        dao.linkTag(com.lifetrack.app.data.db.entity.TxnTagCrossRef(txnId, id))
    }

    // ================================ SEARCH ================================

    private val _searchFilters = MutableStateFlow(SearchFilters())
    val searchFilters: StateFlow<SearchFilters> = _searchFilters

    val searchResults = _searchFilters.flatMapLatest { f ->
        val text = f.text.trim()
        dao.search(
            text = text,
            // "450" should find the ₹450 txn, not just merchants with 450 in the name.
            amount = text.replace(",", "").toDoubleOrNull(),
            categoryId = f.categoryId,
            tagId = f.tagId,
            type = f.type?.name,
            from = f.from,
            to = f.to,
            includeExcluded = f.includeExcluded
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchTotals = searchResults.map { list ->
        val out = list.filter { it.type == TxnType.DEBIT }.sumOf { it.amount }
        val inn = list.filter { it.type == TxnType.CREDIT }.sumOf { it.amount }
        Triple(list.size, out, inn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0, 0.0, 0.0))

    fun updateSearch(transform: (SearchFilters) -> SearchFilters) {
        _searchFilters.value = transform(_searchFilters.value)
    }

    fun clearSearch() { _searchFilters.value = SearchFilters() }

    // ============================== YEAR VIEW ===============================

    val selectedYear = MutableStateFlow(LocalDate.now().year)

    private val yearRange = selectedYear.map { y ->
        val start = LocalDate.of(y, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(y + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        start to end
    }

    /**
     * Live months come from raw transactions; months whose rows were archived away come from
     * the frozen summaries. Live always wins if both exist for the same month.
     */
    val yearView = combine(
        dao.monthlyTotals(), dao.monthlySummaries(), selectedYear
    ) { live, archived, year ->
        val prefix = "%04d-".format(year)
        val byMonth = linkedMapOf<Int, MonthBar>()

        archived.filter { it.yearMonth.startsWith(prefix) }.forEach { a ->
            val m = a.yearMonth.substringAfter('-').toIntOrNull() ?: return@forEach
            byMonth[m] = MonthBar(m, a.totalSpend, a.totalIncome, a.txnCount, archived = true)
        }
        live.filter { it.yearMonth.startsWith(prefix) }.forEach { l ->
            val m = l.yearMonth.substringAfter('-').toIntOrNull() ?: return@forEach
            byMonth[m] = MonthBar(m, l.spend, l.income, l.txnCount, archived = false)
        }

        val bars = (1..12).map { m ->
            byMonth[m] ?: MonthBar(m, 0.0, 0.0, 0, archived = false)
        }
        val active = bars.filter { it.txnCount > 0 }
        YearUiState(
            year = year,
            months = bars,
            totalSpend = bars.sumOf { it.spend },
            totalIncome = bars.sumOf { it.income },
            avgMonthlySpend = if (active.isEmpty()) 0.0 else active.sumOf { it.spend } / active.size,
            busiestMonth = active.maxByOrNull { it.spend }?.month
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YearUiState())

    /** Category split for the whole selected year — reuses the month query with a wider range. */
    val yearCategorySpend = yearRange.flatMapLatest { (start, end) ->
        dao.spendByCategory(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableYears = dao.allTransactionTimes().map { times ->
        val years = times.asSequence()
            .map { Instant.ofEpochMilli(it).atZone(zone).year }
            .distinct().toMutableSet()
        years += LocalDate.now().year
        years.sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.Lazily, listOf(LocalDate.now().year))

    // ============================ ARCHIVE & PURGE ===========================

    private val _archiveStatus = MutableStateFlow<String?>(null)
    val archiveStatus: StateFlow<String?> = _archiveStatus

    private val _archivePreview = MutableStateFlow<Pair<Int, Long>?>(null)
    /** (transactions older than the cutoff, current lifetrack.db size in bytes) */
    val archivePreview: StateFlow<Pair<Int, Long>?> = _archivePreview

    fun previewArchive(before: LocalDate) = viewModelScope.launch {
        _archivePreview.value = archive.preview(before)
    }

    fun archiveAndPurge(before: LocalDate, deleteAfterExport: Boolean) = viewModelScope.launch {
        _archiveStatus.value = "Working…"
        _archiveStatus.value = when (val r = archive.archiveAndPurge(before, deleteAfterExport)) {
            is ArchiveResult.Success ->
                if (deleteAfterExport)
                    "Saved ${r.count} transactions to ${r.path} and cleared them. " +
                        "${r.monthsRolledUp} month(s) kept as summaries."
                else
                    "Saved ${r.count} transactions to ${r.path}. Nothing was deleted."
            ArchiveResult.NothingToArchive -> "Nothing older than $before to archive."
            is ArchiveResult.Failed -> "Failed: ${r.reason}"
        }
        _archivePreview.value = archive.preview(before)
    }

    fun clearArchiveStatus() { _archiveStatus.value = null }

    private val _importResult = MutableStateFlow<Int?>(null)
    val importResult: StateFlow<Int?> = _importResult

    fun categorize(txn: TransactionEntity, categoryId: Long, learnRule: Boolean) =
        viewModelScope.launch { repo.categorize(txn, categoryId, learnRule) }

    fun toggleExclusion(txnId: Long, excluded: Boolean) =
        viewModelScope.launch { dao.setUserExclusion(txnId, excluded) }

    fun addCategory(
        name: String,
        colorHex: String,
        budget: Double? = null,
        emoji: String? = null,
        kind: CategoryKind = CategoryKind.EXPENSE
    ) = viewModelScope.launch {
        dao.upsertCategory(
            CategoryEntity(
                name = name, colorHex = colorHex, monthlyBudget = budget,
                iconEmoji = emoji, kind = kind
            )
        )
    }

    fun addManualTxn(
        amount: Double,
        merchant: String,
        categoryId: Long?,
        bank: String? = null,
        type: TxnType = TxnType.DEBIT,
        timestamp: Long = System.currentTimeMillis(),
        note: String? = null
    ) = viewModelScope.launch {
        val key = merchant.lowercase().trim()
        dao.insertTxn(
            TransactionEntity(
                amount = amount, type = type, merchant = merchant.uppercase(),
                matchKey = key, categoryId = categoryId, bank = bank,
                note = note?.takeIf { it.isNotBlank() },
                timestamp = timestamp
            )
        )
    }

    fun setBudget(categoryId: Long, budget: Double?) = viewModelScope.launch {
        dao.updateCategoryBudget(categoryId, budget?.takeIf { it > 0.0 })
    }

    /** Overall monthly cap. Pass null to clear it and fall back to the category sum. */
    fun setOverallBudget(budget: Double?) {
        budgetPrefs.setOverallMonthlyBudget(budget)
    }

    fun updateCategory(id: Long, name: String, colorHex: String, emoji: String?) =
        viewModelScope.launch { dao.updateCategoryMeta(id, name.trim(), colorHex, emoji?.takeIf { it.isNotBlank() }) }

    fun deleteCategory(id: Long) = viewModelScope.launch { dao.deleteCategoryCascade(id) }

    fun updateNote(txnId: Long, note: String) = viewModelScope.launch {
        dao.updateTxnNote(txnId, note)
    }

    private val _transferSweep = MutableStateFlow<String?>(null)
    val transferSweepStatus: StateFlow<String?> = _transferSweep

    /**
     * Self-transfer sweep. Explicit, idempotent, and provenance-aware.
     *
     * The old version ran on every database emission, excluding rows and thereby triggering the
     * next emission -- a refresh loop -- and it could never undo a bad guess or respect a manual
     * override. This version:
     *   - runs only when the user asks (or right after an SMS import),
     *   - pairs each DEBIT with the nearest opposite-bank CREDIT of equal amount within the window,
     *   - marks both legs AUTO_TRANSFER with a shared group id,
     *   - releases any previous AUTO_TRANSFER leg that no longer has a partner,
     *   - never touches a row the user excluded or un-excluded by hand.
     */
    fun runTransferSweep(windowMinutes: Long = 180) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val from = now - 400L * 86_400_000L   // ~13 months of history
        val rows = dao.pairingCandidates(from, now + 86_400_000L)

        val windowMs = windowMinutes * 60_000L
        val debits = rows.filter { it.type == TxnType.DEBIT }.sortedBy { it.timestamp }
        val credits = rows.filter { it.type == TxnType.CREDIT }.toMutableList()

        val pairedIds = HashSet<Long>()
        var paired = 0

        for (d in debits) {
            // Nearest credit of the same amount, different bank, inside the window, not yet used.
            val match = credits
                .filter { c ->
                    c.id !in pairedIds &&
                        c.amount == d.amount &&
                        kotlin.math.abs(c.timestamp - d.timestamp) <= windowMs &&
                        (d.bank == null || c.bank == null || !d.bank.equals(c.bank, true))
                }
                .minByOrNull { kotlin.math.abs(it.timestamp - d.timestamp) }
                ?: continue

            val group = "tg_${d.id}_${match.id}"
            dao.markAutoTransfer(d.id, group)
            dao.markAutoTransfer(match.id, group)
            pairedIds += d.id
            pairedIds += match.id
            paired++
        }

        // Anything the detector had excluded before but didn't re-pair this run gets released.
        rows.filter { it.exclusionSource == ExclusionSource.AUTO_TRANSFER && it.id !in pairedIds }
            .forEach { dao.clearAutoTransfer(it.id) }

        _transferSweep.value =
            if (paired == 0) "No self-transfers found." else "Excluded $paired transfer pair(s)."
    }

    fun clearTransferSweepStatus() { _transferSweep.value = null }

    fun importFromInbox() = viewModelScope.launch {
        _importResult.value = repo.backfillFromInbox(days = 90)
        runTransferSweep()
    }

    fun importAllFromInbox() = viewModelScope.launch {
        _importResult.value = repo.backfillFromInbox(days = Int.MAX_VALUE)
    }
}
