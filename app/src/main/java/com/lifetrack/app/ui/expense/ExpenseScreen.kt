@file:OptIn(ExperimentalMaterial3Api::class)
package com.lifetrack.app.ui.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.toColorInt
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifetrack.app.data.db.dao.TxnTagLink
import com.lifetrack.app.data.db.entity.CategoryEntity
import com.lifetrack.app.data.db.entity.CategorySource
import com.lifetrack.app.data.db.entity.TransactionEntity
import com.lifetrack.app.data.db.entity.TxnType
import com.lifetrack.app.ui.common.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.lifetrack.app.ui.theme.Ink
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun ExpenseScreen(vm: ExpenseViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showTrends by remember { mutableStateOf(false) }
    var detailing by remember { mutableStateOf<TransactionEntity?>(null) }
    var showAddTxn by remember { mutableStateOf(value = false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var showBudgets by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    val pendingCount = vm.uncategorized.collectAsState().value.size

    Scaffold(
        floatingActionButton = {
            if (!showTrends && tab < 2) {
                ExtendedFloatingActionButton(
                    onClick = { showAddTxn = true },
                    containerColor = Ink.mint,
                    contentColor = Ink.bg,
                    icon = { Icon(Icons.Default.Add, "Add") },
                    text = { Text("Add") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showTrends) {
                TrendsTab(vm, onBack = { showTrends = false })
                return@Column
            }
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = Ink.mint,
            ) {
                listOf("Overview", "Activity", "Year", "Cards", "Inbox").forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            val label = if ((i == 4) && (pendingCount > 0)) "$t · $pendingCount" else t
                            Text(label)
                        },
                        selectedContentColor = Ink.mint,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (tab == 0 || tab == 1) {
                MonthPicker(vm)
            }
            when (tab) {
                0 -> Dashboard(
                    vm = vm,
                    onAddCategory = { showAddCategory = true },
                    onOpenBudgets = { showBudgets = true },
                    onOpenTrends = { showTrends = true },
                    onEditCategory = { editingCategory = it }
                )
                1 -> ActivityTab(vm) { detailing = it }
                2 -> YearTab(vm) { showArchive = true }
                3 -> CreditCardsTab(vm)
                4 -> InboxTab(vm) { detailing = it }
            }
        }
    }

    if (showAddTxn) {
        ModalBottomSheet(onDismissRequest = { showAddTxn = false }) {
            AddTransactionSheet(vm) { showAddTxn = false }
        }
    }

    if (showAddCategory) {
        ModalBottomSheet(onDismissRequest = { showAddCategory = false }) {
            AddCategorySheet(vm) { showAddCategory = false }
        }
    }

    if (showBudgets) {
        ModalBottomSheet(onDismissRequest = { showBudgets = false }) {
            BudgetSheet(vm) { showBudgets = false }
        }
    }

    if (showArchive) {
        ModalBottomSheet(onDismissRequest = { showArchive = false }) {
            ArchiveSheet(vm) { showArchive = false }
        }
    }

    editingCategory?.let { cat ->
        ModalBottomSheet(onDismissRequest = { editingCategory = null }) {
            EditCategorySheet(vm, cat) { editingCategory = null }
        }
    }

    detailing?.let { txn ->
        ModalBottomSheet(
            onDismissRequest = { detailing = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            TransactionDetailsSheet(vm, txn, onAddCategory = { showAddCategory = true }) { detailing = null }
        }
    }
}

@Composable
private fun MonthPicker(vm: ExpenseViewModel) {
    val months by vm.availableMonths.collectAsState()
    val selected by vm.selectedMonth.collectAsState()
    val fmt = remember { DateTimeFormatter.ofPattern("MMM yyyy") }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
    ) {
        items(months) { m ->
            val isSelected = m == selected
            val isCurrent = m == YearMonth.now()
            
            FilterChip(
                selected = isSelected,
                onClick = { vm.selectedMonth.value = m },
                label = { 
                    Text(
                        if (isCurrent) "Current Month" else m.format(fmt),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ) 
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Ink.mint.copy(alpha = 0.2f),
                    selectedLabelColor = Ink.mint
                )
            )
        }
    }
}

@Composable
private fun Dashboard(
    vm: ExpenseViewModel,
    onAddCategory: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenTrends: () -> Unit,
    onEditCategory: (CategoryEntity) -> Unit
) {
    val uiState by vm.uiState.collectAsState()
    val selectedMonth by vm.selectedMonth.collectAsState()

    var editingBudgetFor by remember { mutableStateOf<CategoryEntity?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Main Hero: Budget Progress
                InkCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "MONTHLY SPENDING",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ink.textDim,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onOpenBudgets, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Default.Tune, null, Modifier.size(16.dp), tint = Ink.mint)
                                Spacer(Modifier.width(6.dp))
                                Text("Budget", color = Ink.mint, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "₹%,.0f".format(uiState.totalSpend),
                                style = MaterialTheme.typography.displayLarge,
                                color = if (uiState.totalBudget > 0 && uiState.totalSpend > uiState.totalBudget) Ink.danger else Ink.text
                            )
                            if (uiState.totalBudget > 0) {
                                Text(
                                    " / ₹%,.0f".format(uiState.totalBudget),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    color = Ink.textDim
                                )
                            }
                        }
                        if (uiState.totalBudget > 0) {
                            Spacer(Modifier.height(16.dp))
                            val fraction = (uiState.totalSpend / uiState.totalBudget).toFloat().coerceIn(0f, 1.2f)
                            SlimProgress(
                                fraction = fraction.coerceAtMost(1f),
                                accent = if (fraction > 1f) Ink.danger else Ink.mint
                            )
                            Spacer(Modifier.height(8.dp))
                            val balance = (uiState.totalBudget - uiState.totalSpend).coerceAtLeast(0.0)
                            Text(
                                if (uiState.totalSpend > uiState.totalBudget) 
                                    "₹%,.0f over budget".format(uiState.totalSpend - uiState.totalBudget)
                                else 
                                    "₹%,.0f balance remaining".format(balance),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.totalSpend > uiState.totalBudget) Ink.danger else Ink.mint
                            )
                        } else {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onOpenBudgets, modifier = Modifier.fillMaxWidth()) {
                                Text("Set a monthly budget")
                            }
                        }
                    }
                }

                InkCard(Modifier.fillMaxWidth().clickable(onClick = onOpenTrends)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = Ink.mint)
                        Spacer(Modifier.width(12.dp))
                        Text("Trends", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ink.textDim)
                    }
                }

                // Sub Hero Cards
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InkCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.ElectricBolt, null, tint = Ink.ember, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("SAFE TO SPEND", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            Text("₹%,.0f".format(uiState.safeToSpendToday), style = MaterialTheme.typography.titleLarge)
                            Text("TODAY", style = MaterialTheme.typography.labelSmall, color = Ink.textDim, fontSize = 9.sp)
                        }
                    }
                    InkCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.LocalFireDepartment, null, tint = Ink.danger, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("BUDGET STREAK", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            Text("${uiState.withinBudgetStreak} DAYS", style = MaterialTheme.typography.titleLarge)
                            Text("ON TRACK 🔥", style = MaterialTheme.typography.labelSmall, color = Ink.danger, fontSize = 9.sp)
                        }
                    }
                }

                // Third Row: No-Spend Streak
                if (uiState.noSpendStreak > 0) {
                    InkCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null, tint = Ink.violet, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("NO-SPEND STREAK", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                                Text("${uiState.noSpendStreak} CONSECUTIVE DAYS", style = MaterialTheme.typography.titleSmall)
                            }
                            Text("🧘", fontSize = 20.sp)
                        }
                    }
                }
            }
        }

        item {
            Eyebrow("Spending Calendar")
            InkCard(Modifier.fillMaxWidth()) {
                SpendingHeatmapGrid(selectedMonth, uiState.dailyHeatmap, uiState.totalBudget)
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Categories", Modifier.weight(1f))
                IconButton(onClick = onAddCategory) {
                    Icon(Icons.Default.Add, null, tint = Ink.mint, modifier = Modifier.size(20.dp))
                }
            }
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (uiState.categoryRows.isEmpty()) {
                        Text(
                            "No categories yet. Tap + to create one.",
                            Modifier.padding(20.dp),
                            color = Ink.textDim
                        )
                    }
                    uiState.categoryRows.forEach { row ->
                        val budget = row.budget ?: 0.0
                        Column(
                            Modifier
                                // Every row is tappable now, including zero-spend categories.
                                .clickable(enabled = row.category != null) {
                                    editingBudgetFor = row.category
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryIconBox(row.name, row.emoji, parseColor(row.colorHex))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        when {
                                            budget > 0 -> "₹%,.0f of ₹%,.0f limit".format(row.spend, budget)
                                            row.category == null -> "Categorize these in the Inbox"
                                            else -> "No limit set — tap to add"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (budget > 0 && row.spend > budget) Ink.danger else Ink.textDim
                                    )
                                }
                                Text("₹%,.0f".format(row.spend), style = MaterialTheme.typography.titleMedium)
                                row.category?.let { cat ->
                                    IconButton(onClick = { onEditCategory(cat) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.MoreVert, "Edit ${cat.name}", Modifier.size(18.dp), tint = Ink.textDim)
                                    }
                                }
                            }
                            if (budget > 0) {
                                Spacer(Modifier.height(8.dp))
                                val fraction = (row.spend / budget).toFloat().coerceIn(0f, 1f)
                                SlimProgress(
                                    fraction = fraction,
                                    accent = if (row.spend > budget) Ink.danger else parseColor(row.colorHex)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        item {
            val incomeRows by vm.incomeByCategory.collectAsState()
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.TrendingDown, null, tint = Ink.mint)
                        Spacer(Modifier.width(12.dp))
                        Text("Monthly Income", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("₹%,.0f".format(uiState.totalIncome), style = MaterialTheme.typography.titleMedium, color = Ink.mint)
                    }
                    // Per-source split: Salary vs Freelance vs whatever the user defined.
                    incomeRows.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(parseColor(r.colorHex)))
                            Spacer(Modifier.width(10.dp))
                            Text(r.name ?: "Unassigned", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Ink.textDim)
                            Text("₹%,.0f".format(r.total), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item {
            val classify by vm.classifyStatus.collectAsState()
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Ink.mint)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Auto-categorize", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                classify ?: "Guess categories for uncategorized spends by merchant name.",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (classify != null) Ink.mint else Ink.textDim
                            )
                        }
                        TextButton(onClick = { vm.runMerchantClassification() }) { Text("Classify", color = Ink.mint) }
                    }
                    if (classify != null) {
                        TextButton(
                            onClick = { vm.undoMerchantClassification() },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Undo auto-tags", color = Ink.textDim, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }

        item {
            val sweep by vm.transferSweepStatus.collectAsState()
            InkCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapHoriz, null, tint = Ink.violet)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Self-transfers", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            sweep ?: "Exclude money moved between your own accounts.",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sweep != null) Ink.mint else Ink.textDim
                        )
                    }
                    TextButton(onClick = { vm.runTransferSweep() }) { Text("Find", color = Ink.violet) }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }

    editingBudgetFor?.let { cat ->
        key(cat.id) {
            BudgetEditDialog(
                category = cat,
                onDismiss = { editingBudgetFor = null },
                onSave = { vm.setBudget(cat.id, it) }
            )
        }
    }
}

@Composable
private fun CategoryIconBox(name: String, emoji: String?, color: Color, type: TxnType = TxnType.DEBIT) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color.copy(0.15f)),
        contentAlignment = Alignment.Center
    ) {
        val icon = if (type == TxnType.CREDIT) Icons.Default.Payments else getCategoryIcon(name, emoji)
        if (icon is ImageVector) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (type == TxnType.CREDIT) Ink.mint else color)
        } else {
            Text(icon.toString(), fontSize = 18.sp)
        }
    }
}

@Composable
private fun SpendingHeatmapGrid(month: YearMonth, heatmap: Map<Long, Double>, monthlyBudget: Double) {
    val dailyBudget = remember(monthlyBudget, month) { 
        if (monthlyBudget > 0) monthlyBudget / month.lengthOfMonth() else 500.0 
    }
    
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.textDim, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        
        val firstDay = remember(month) { month.atDay(1).dayOfWeek.value } // 1-7
        val daysInMonth = remember(month) { month.lengthOfMonth() }
        var currentDay = 1
        
        for (w in 0..5) {
            if (currentDay > daysInMonth) break
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (d in 1..7) {
                    if ((w == 0 && d < firstDay) || currentDay > daysInMonth) {
                        Box(Modifier.size(32.dp))
                    } else {
                        val day = currentDay
                        val date = remember(month, day) { month.atDay(day) }
                        val spend = heatmap[date.toEpochDay()] ?: 0.0
                        val color = when {
                            spend == 0.0 -> Ink.hairline
                            spend <= dailyBudget * 0.5 -> Color(0xFFFDE68A)
                            spend <= dailyBudget -> Color(0xFFFBBF24)
                            else -> Color(0xFFEF4444)
                        }
                        Box(
                            Modifier.size(32.dp).padding(2.dp).clip(MaterialTheme.shapes.extraSmall).background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(day.toString(), fontSize = 10.sp, color = if (spend > 0) Ink.bg else Ink.textDim)
                        }
                        currentDay++
                    }
                }
            }
        }
    }
}

@Composable
private fun TxnList(
    txns: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    tagsByTxn: Map<Long, List<TxnTagLink>> = emptyMap(),
    emptyText: String = "No transactions for this period.",
    onTap: (TransactionEntity) -> Unit
) {
    if (txns.isEmpty()) {
        EmptyNote(emptyText)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(txns, key = { it.id }) { t ->
            val cat = categories.firstOrNull { it.id == t.categoryId }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onTap(t) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIconBox(cat?.name ?: "Other", cat?.iconEmoji, parseColor(cat?.colorHex), t.type)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.merchant, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        (t.bank ?: "Manual") +
                            (if (t.isExcluded) " • Excluded" else "") +
                            // Flags a keyword guess so the user can tell it apart from a
                            // confirmed category and fix it if the classifier got it wrong.
                            (if (t.categorySource == CategorySource.KEYWORD_AUTO) " • Auto-tagged" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.textDim
                    )
                    val tags = tagsByTxn[t.id].orEmpty()
                    if (tags.size > 0) {
                        Row(
                            Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tags.take(3).forEach { tag -> TagPill(tag.name, parseColor(tag.colorHex)) }
                            if (tags.size > 3) {
                                Text("+${tags.size - 3}", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "₹%,.0f".format(t.amount),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (t.type == TxnType.CREDIT) Ink.mint else Ink.text
                        )
                        // Money OUT points up, money IN points down (user preference).
                        // Flip these two lines to go back to standard credit/debit convention.
                        Icon(
                            if (t.type == TxnType.DEBIT) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            null, Modifier.size(14.dp),
                            tint = if (t.type == TxnType.CREDIT) Ink.mint else Ink.danger
                        )
                    }
                    Text(
                        SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(t.timestamp)),
                        style = MaterialTheme.typography.labelSmall, color = Ink.textDim, fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionDetailsSheet(
    vm: ExpenseViewModel,
    txn: TransactionEntity,
    onAddCategory: () -> Unit,
    onDone: () -> Unit
) {
    val categories by vm.categories.collectAsState()
    val allTags by vm.tags.collectAsState()
    val tagsByTxn by vm.tagsByTxn.collectAsState()
    var selectedCatId by remember { mutableStateOf(txn.categoryId) }
    val currentCat = categories.find { it.id == selectedCatId }

    val originalTagIds = remember(txn.id, tagsByTxn) {
        tagsByTxn[txn.id].orEmpty().map { it.tagId }.toSet()
    }
    var selectedTagIds by remember(txn.id) { mutableStateOf(originalTagIds) }
    LaunchedEffect(originalTagIds) { selectedTagIds = originalTagIds }
    var showNewTag by remember { mutableStateOf(false) }

    var isExcluded by remember { mutableStateOf(txn.isExcluded) }
    var note by remember { mutableStateOf(txn.note ?: "") }
    var isEditingNote by remember { mutableStateOf(false) }
    var confirmDeleteTxn by remember { mutableStateOf(false) }
    var showMerchantHistory by remember { mutableStateOf(false) }
    // Set only when a category change needs the "apply to N other transactions too?" prompt.
    var pendingCategoryChange by remember { mutableStateOf<Long?>(null) }
    var pendingOtherCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val visits by remember(txn.matchKey) { vm.visitCount(txn.matchKey) }.collectAsState(initial = 1)

    // Everything except the category change applies immediately and unconditionally; only
    // the category needs the "found N other transactions, change those too?" detour.
    fun finishNonCategoryChanges() {
        if (isExcluded != txn.isExcluded) vm.toggleExclusion(txn.id, isExcluded)
        if (note != (txn.note ?: "")) vm.updateNote(txn.id, note)
        if (selectedTagIds != originalTagIds) vm.setTagsForTxn(txn.id, selectedTagIds.toList())
        onDone()
    }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Transaction Details", style = MaterialTheme.typography.titleSmall, color = Ink.textDim, modifier = Modifier.weight(1f))
            AssistChip(
                onClick = { showMerchantHistory = true },
                label = { Text(if (visits == 1) "1 visit" else "$visits visits") },
                leadingIcon = { Icon(Icons.Default.BarChart, null, Modifier.size(16.dp)) }
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { confirmDeleteTxn = true }) {
                Icon(Icons.Default.DeleteOutline, "Delete transaction", tint = Ink.danger)
            }
            IconButton(onClick = onDone) { Icon(Icons.Default.Close, null) }
        }

        Spacer(Modifier.height(16.dp))
        
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CategoryIconBox(currentCat?.name ?: "Other", currentCat?.iconEmoji, parseColor(currentCat?.colorHex), txn.type)
            Spacer(Modifier.height(12.dp))
            Text(txn.merchant, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (txn.type == TxnType.DEBIT) "Outflow" else "Inflow", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
            Spacer(Modifier.height(8.dp))
            Text(
                "₹%,.2f".format(txn.amount),
                style = MaterialTheme.typography.displayLarge,
                color = if (txn.type == TxnType.CREDIT) Ink.mint else Ink.text
            )
        }

        Spacer(Modifier.height(24.dp))
        
        InkCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("Date & Time", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(txn.timestamp)))
                DetailRow("Bank", txn.bank ?: "Manual")
            }
        }

        Spacer(Modifier.height(16.dp))

        // The category picker itself — this is what went missing in the redesign.
        Eyebrow("Category")
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories, key = { it.id }) { c ->
                FilterChip(
                    selected = selectedCatId == c.id,
                    onClick = { selectedCatId = if (selectedCatId == c.id) null else c.id },
                    label = { Text(c.name) },
                    leadingIcon = if (selectedCatId == c.id) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = parseColor(c.colorHex).copy(alpha = 0.22f),
                        selectedLabelColor = parseColor(c.colorHex),
                        selectedLeadingIconColor = parseColor(c.colorHex)
                    )
                )
            }
            item {
                AssistChip(
                    onClick = onAddCategory,
                    label = { Text("New") },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Spacer(Modifier.height(16.dp))

        // Tags: free-form labels, independent of category. A txn can carry several.
        Eyebrow("Tags")
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allTags, key = { it.id }) { g ->
                val on = g.id in selectedTagIds
                FilterChip(
                    selected = on,
                    onClick = {
                        selectedTagIds = if (on) selectedTagIds - g.id else selectedTagIds + g.id
                    },
                    label = { Text(g.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = parseColor(g.colorHex).copy(alpha = 0.22f),
                        selectedLabelColor = parseColor(g.colorHex)
                    )
                )
            }
            item {
                AssistChip(
                    onClick = { showNewTag = true },
                    label = { Text("New tag") },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        InkCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notes", style = MaterialTheme.typography.bodyMedium, color = Ink.textDim, modifier = Modifier.weight(1f))
                    TextButton(onClick = { isEditingNote = !isEditingNote }) { 
                        Text(if (isEditingNote) "Done" else "Edit", color = Ink.mint) 
                    }
                }
                if (isEditingNote) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Add some context...") }
                    )
                } else {
                    Text(
                        if (note.isBlank()) "No notes added for this transaction." else note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (note.isBlank()) Ink.textDim else Ink.text
                    )
                }
            }
        }

        if (!txn.rawSms.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("SOURCE MESSAGE", style = MaterialTheme.typography.labelMedium, color = Ink.textDim)
            InkCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    txn.rawSms,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.textDim
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = isExcluded, onCheckedChange = { isExcluded = it })
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Exclude from totals", style = MaterialTheme.typography.bodyMedium)
                Text("Keep record, but skip in charts", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
            }
        }

        Spacer(Modifier.height(24.dp))
        val dirty = selectedCatId != txn.categoryId ||
            isExcluded != txn.isExcluded ||
            note != (txn.note ?: "") ||
            selectedTagIds != originalTagIds

        Button(
            onClick = {
                val newCatId = selectedCatId
                if (newCatId != null && newCatId != txn.categoryId) {
                    scope.launch {
                        val otherCount = vm.countOtherTxnsForMerchant(txn.matchKey, txn.id)
                        if (otherCount > 0) {
                            // Ask before touching the merchant's OTHER transactions. This txn's
                            // own category is applied only once that choice is made (below).
                            pendingOtherCount = otherCount
                            pendingCategoryChange = newCatId
                        } else {
                            vm.categorizeAndLearn(txn, newCatId, applyToExisting = false)
                            finishNonCategoryChanges()
                        }
                    }
                } else {
                    finishNonCategoryChanges()
                }
            },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint)
        ) { Text("Save Changes", color = Ink.bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(32.dp))
    }

    pendingCategoryChange?.let { catId ->
        AlertDialog(
            onDismissRequest = { /* must choose Cancel or Change -- this txn's category is not
                                    yet applied, so an accidental outside-tap shouldn't lose it */ },
            title = { Text("Update $pendingOtherCount other transaction${if (pendingOtherCount == 1) "" else "s"}?") },
            text = {
                Text(
                    "Found $pendingOtherCount other transaction${if (pendingOtherCount == 1) "" else "s"} " +
                        "at ${txn.merchant}. Change ${if (pendingOtherCount == 1) "its" else "their"} category too?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.categorizeAndLearn(txn, catId, applyToExisting = true)
                    pendingCategoryChange = null
                    finishNonCategoryChanges()
                }) { Text("Change", color = Ink.mint, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    // "Cancel" only means "don't touch the OTHERS" -- this transaction's own
                    // category change still goes through, matching the reference flow exactly.
                    vm.categorizeAndLearn(txn, catId, applyToExisting = false)
                    pendingCategoryChange = null
                    finishNonCategoryChanges()
                }) { Text("Cancel") }
            }
        )
    }

    if (showMerchantHistory) {
        ModalBottomSheet(onDismissRequest = { showMerchantHistory = false }) {
            MerchantHistorySheet(vm, txn.matchKey, txn.merchant) { showMerchantHistory = false }
        }
    }

    if (showNewTag) {
        NewTagDialog(
            onDismiss = { showNewTag = false },
            onCreate = { name, color -> vm.addTag(name, color); showNewTag = false }
        )
    }

    if (confirmDeleteTxn) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTxn = false },
            title = { Text("Delete this transaction?") },
            text = {
                Text(
                    "\"${txn.merchant}\" for ₹%,.2f will be permanently removed. This cannot be undone."
                        .format(txn.amount)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTransaction(txn.id)
                    confirmDeleteTxn = false
                    onDone()
                }) { Text("Delete", color = Ink.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTxn = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Tapping the "N visits" badge opens this: a month-by-month bar chart of how often this
 * merchant/UPI id has been transacted with, plus the raw list underneath.
 */
/** "2026-07" -> "Jul '26" for the merchant-history bar chart. Falls back to the raw string if
 *  the format is ever unexpected, rather than crashing the sheet over a display label. */
private fun monthLabel(yearMonth: String): String =
    runCatching {
        YearMonth.parse(yearMonth).format(DateTimeFormatter.ofPattern("MMM ''yy"))
    }.getOrDefault(yearMonth)

@Composable
private fun MerchantHistorySheet(vm: ExpenseViewModel, matchKey: String, merchantName: String, onDone: () -> Unit) {
    val monthly by remember(matchKey) { vm.visitsByMonth(matchKey) }.collectAsState(initial = emptyList())
    val txns by remember(matchKey) { vm.txnsForMerchant(matchKey) }.collectAsState(initial = emptyList())
    var selectedMonth by remember(matchKey) { mutableStateOf<String?>(null) }
    val ymFormat = remember { SimpleDateFormat("yyyy-MM", Locale.getDefault()) }
    val shownTxns = remember(txns, selectedMonth) {
        if (selectedMonth == null) txns else txns.filter { ymFormat.format(Date(it.timestamp)) == selectedMonth }
    }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(merchantName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${txns.size} visit${if (txns.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim
                )
            }
            IconButton(onClick = onDone) { Icon(Icons.Default.Close, null) }
        }

        Spacer(Modifier.height(16.dp))

        if (monthly.isNotEmpty()) {
            Eyebrow("Spend by month")
            val maxSpend = monthly.maxOf { it.spend }.takeIf { it > 0 } ?: 1.0
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                monthly.forEach { m ->
                    val isSel = m.yearMonth == selectedMonth
                    Column(
                        Modifier.width(64.dp).clickable { selectedMonth = if (isSel) null else m.yearMonth }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.width(28.dp).height((80.0 * (m.spend / maxSpend)).coerceAtLeast(4.0).dp)
                            .background(if (isSel) Ink.mint else Ink.mint.copy(alpha = 0.35f), RoundedCornerShape(6.dp)))
                        Spacer(Modifier.height(6.dp))
                        Text(monthLabel(m.yearMonth), style = MaterialTheme.typography.labelSmall,
                            color = if (isSel) Ink.mint else Ink.textDim, maxLines = 1)
                    }
                }
            }
            selectedMonth?.let { sel ->
                val m = monthly.first { it.yearMonth == sel }
                Spacer(Modifier.height(8.dp))
                Text("${monthLabel(sel)} · ₹%,.0f · ${m.count} visit${if (m.count == 1) "" else "s"}".format(m.spend),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.mint)
            }
            Spacer(Modifier.height(20.dp))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(if (selectedMonth == null) "All transactions" else "${monthLabel(selectedMonth!!)} transactions", Modifier.weight(1f))
            if (selectedMonth != null) TextButton(onClick = { selectedMonth = null }) { Text("Show all", color = Ink.mint) }
        }

        if (shownTxns.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
        } else {
            Column {
                shownTxns.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(t.timestamp)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (t.isExcluded) {
                                Text("Excluded", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            }
                        }
                        Text(
                            "₹%,.0f".format(t.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t.type == TxnType.CREDIT) Ink.mint else Ink.text
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NewTagDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#78909C") }
    val palette = listOf("#FF7043", "#66BB6A", "#42A5F5", "#AB47BC", "#FFA726", "#EC407A", "#26A69A", "#78909C")

    Dialog(onDismissRequest = onDismiss) {
        InkCard {
            Column(Modifier.padding(24.dp)) {
                Text("New Tag", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tag name") },
                    placeholder = { Text("e.g. goa-trip, reimbursable") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(palette) { c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c.toColorInt()))
                                .clickable { color = c }
                                .padding(4.dp)
                        ) {
                            if (color == c) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(0.4f)))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onCreate(name, color) },
                        enabled = name.isNotBlank()
                    ) { Text("Create", color = Ink.mint) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.textDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Credit cards, deliberately isolated from every other tab. A card's transactions never appear
 * in Overview/Activity/Year totals -- they carry isExcluded=true, exclusionSource=CREDIT_CARD.
 * This tab is the only place they're visible, by design: individual charges are "a loan" until
 * the bill is paid, and the bill payment itself is also excluded rather than counted, since the
 * user's mental model treats the whole credit-card lifecycle as separate from bank-account cash
 * flow, not as a second copy of it.
 */
@Composable
private fun CreditCardsTab(vm: ExpenseViewModel) {
    val summaries by vm.creditCardSummaries.collectAsState()
    val status by vm.creditCardStatus.collectAsState()
    var showAddCard by remember { mutableStateOf(false) }
    var openCardId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Your cards", Modifier.weight(1f))
                TextButton(onClick = { vm.runCreditCardRematch() }) { Text("Rematch", color = Ink.textDim) }
                IconButton(onClick = { showAddCard = true }) {
                    Icon(Icons.Default.Add, "Add card", tint = Ink.mint)
                }
            }
        }

        status?.let { s ->
            item {
                Text(s, style = MaterialTheme.typography.labelSmall, color = Ink.mint)
            }
        }

        if (summaries.isEmpty()) {
            item {
                InkCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "No credit cards tracked yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A card is added automatically the first time its statement SMS " +
                                "arrives (\"Credit Card XX1234 Statement...\"). Import your SMS " +
                                "from the Inbox tab, or add one manually with the + above.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.textDim
                        )
                    }
                }
            }
        }

        items(summaries, key = { it.card.id }) { summary ->
            CreditCardRow(summary, onClick = { openCardId = summary.card.id })
        }

        item { Spacer(Modifier.height(20.dp)) }
    }

    if (showAddCard) {
        ModalBottomSheet(onDismissRequest = { showAddCard = false }) {
            AddCreditCardSheet(vm) { showAddCard = false }
        }
    }

    openCardId?.let { id ->
        key(id) {
            val summary = summaries.firstOrNull { it.card.id == id }
            if (summary != null) {
                ModalBottomSheet(onDismissRequest = { openCardId = null }) {
                    CreditCardDetailSheet(vm, summary) { openCardId = null }
                }
            }
        }
    }
}

@Composable
private fun CreditCardRow(summary: ExpenseViewModel.CreditCardSummary, onClick: () -> Unit) {
    val card = summary.card
    InkCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(parseColor(card.colorHex).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Text(card.emoji, fontSize = 18.sp) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "•••• ${card.lastFourDigits} · this cycle",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.textDim
                    )
                }
                Text("₹%,.0f".format(summary.cycleSpend), style = MaterialTheme.typography.titleMedium)
            }
            summary.lastStatementTotal?.let { total ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last bill: ₹%,.0f".format(total),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim
                )
            }
        }
    }
}

@Composable
private fun AddCreditCardSheet(vm: ExpenseViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#7E57C2") }
    val palette = listOf("#7E57C2", "#FF7043", "#42A5F5", "#26A69A", "#EC407A", "#FFA726", "#66BB6A", "#78909C")

    Column(
        Modifier
            .padding(20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Add Credit Card", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Only needed if a statement SMS for this card hasn't arrived yet -- otherwise it's " +
                "added automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.textDim
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Card name (e.g. HDFC Card)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = last4,
            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) last4 = it },
            label = { Text("Last 4 digits") },
            placeholder = { Text("1234") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Eyebrow("Color", Modifier.padding(top = 20.dp, bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(palette) { c ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(c.toColorInt()))
                        .clickable { color = c }
                        .padding(4.dp)
                ) {
                    if (color == c) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(0.4f)))
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { vm.addCreditCard(name, last4, color, "💳"); onDone() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint),
            enabled = name.isNotBlank() && last4.length == 4
        ) { Text("Add Card", color = Ink.bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CreditCardDetailSheet(
    vm: ExpenseViewModel,
    summary: ExpenseViewModel.CreditCardSummary,
    onDone: () -> Unit
) {
    val card = summary.card
    val txns by vm.txnsForCard(card.id).collectAsState()
    val statements by vm.statementsForCard(card.id).collectAsState()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember(card.id) { mutableStateOf(card.name) }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text("•••• ${card.lastFourDigits}", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
            }
            IconButton(onClick = {
                if (editing) vm.updateCreditCard(card.id, name, card.colorHex, card.emoji)
                editing = !editing
            }) {
                Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, null, tint = Ink.mint)
            }
            IconButton(onClick = onDone) { Icon(Icons.Default.Close, null) }
        }

        Spacer(Modifier.height(16.dp))
        InkCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("THIS CYCLE", style = MaterialTheme.typography.labelMedium, color = Ink.textDim)
                Text("₹%,.0f".format(summary.cycleSpend), style = MaterialTheme.typography.displayLarge)
                Text(
                    "${summary.cycleTxnCount} transaction(s) since " +
                        SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(summary.cycleStart)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim
                )
            }
        }

        if (statements.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Eyebrow("Statement history")
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    statements.forEach { st ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(st.statementDate)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                st.totalDue?.let { "₹%,.0f".format(it) } ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.textDim
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Eyebrow("Transactions")
        if (txns.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.labelSmall, color = Ink.textDim, modifier = Modifier.padding(vertical = 12.dp))
        } else {
            Column {
                txns.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(t.merchant, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(t.timestamp)),
                                style = MaterialTheme.typography.labelSmall, color = Ink.textDim
                            )
                        }
                        Text(
                            "₹%,.0f".format(t.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t.type == TxnType.CREDIT) Ink.mint else Ink.text
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp), tint = Ink.danger)
            Spacer(Modifier.width(8.dp))
            Text("Stop tracking this card", color = Ink.danger)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Stop tracking ${card.name}?") },
            text = { Text("Its transactions move back into your normal Overall spending instead of staying separate.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCreditCard(card.id)
                    confirmDelete = false
                    onDone()
                }) { Text("Remove", color = Ink.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InboxTab(vm: ExpenseViewModel, onTap: (TransactionEntity) -> Unit) {
    val pending by vm.uncategorized.collectAsState()
    val imported by vm.importResult.collectAsState()
    val categories by vm.categories.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { vm.importFromInbox() }) {
                Text("Import last 90 days")
            }
            OutlinedButton(onClick = { vm.importAllFromInbox() }) {
                Text("Import ALL SMS")
            }
        }
        imported?.let {
            Text(
                "Imported $it transactions",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.mint,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        if (pending.isEmpty()) {
            EmptyNote("Inbox zero. Every transaction has a category.")
        } else {
            Eyebrow(
                "${pending.size} to categorize",
                Modifier.padding(horizontal = 20.dp)
            )
            TxnList(
                txns = pending,
                categories = categories,
                tagsByTxn = vm.tagsByTxn.collectAsState().value,
                emptyText = "Inbox zero.",
                onTap = onTap
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTransactionSheet(vm: ExpenseViewModel, onDone: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var selectedCatId by remember { mutableStateOf<Long?>(null) }
    var bank by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxnType.DEBIT) }
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val expenseCats by vm.expenseCategories.collectAsState()
    val incomeCats by vm.incomeCategories.collectAsState()
    val isExpense = type == TxnType.DEBIT
    val categories = if (isExpense) expenseCats else incomeCats
    // Switching side clears a now-irrelevant selection.
    LaunchedEffect(isExpense) { selectedCatId = null }

    Column(
        Modifier
            .padding(20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            if (isExpense) "New Expense" else "New Income",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        // Expense / Income toggle — restored so non-SMS income can be entered by hand.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isExpense,
                onClick = { type = TxnType.DEBIT },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text("Expense") }
            )
            SegmentedButton(
                selected = !isExpense,
                onClick = { type = TxnType.CREDIT },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text("Income") }
            )
        }

        Spacer(Modifier.height(20.dp))
        
        OutlinedTextField(
            value = amount,
            onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) amount = it },
            label = { Text("Amount (₹)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = merchant,
            onValueChange = { merchant = it },
            label = { Text(if (isExpense) "Merchant / Description" else "Source / Description") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = bank,
            onValueChange = { bank = it },
            label = { Text("Bank (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // Backdating: cash spends are rarely entered the moment they happen.
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CalendarToday, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestamp)))
        }

        Eyebrow(if (isExpense) "Category" else "Income source", Modifier.padding(top = 16.dp, bottom = 8.dp))
        if (categories.isEmpty()) {
            Text(
                if (isExpense) "No expense categories yet."
                else "No income sources yet. Add one from the + on the Categories card (choose Income).",
                style = MaterialTheme.typography.labelSmall, color = Ink.textDim
            )
        }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories, key = { it.id }) { c ->
                FilterChip(
                    selected = selectedCatId == c.id,
                    onClick = { selectedCatId = if (selectedCatId == c.id) null else c.id },
                    label = { Text(c.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = parseColor(c.colorHex).copy(alpha = 0.22f),
                        selectedLabelColor = parseColor(c.colorHex)
                    )
                )
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                val amt = amount.toDoubleOrNull() ?: 0.0
                if (amt > 0 && merchant.isNotBlank()) {
                    vm.addManualTxn(
                        amount = amt,
                        merchant = merchant,
                        categoryId = selectedCatId,
                        bank = bank.takeIn { it.isNotBlank() },
                        type = type,
                        timestamp = timestamp
                    )
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint),
            enabled = (amount.toDoubleOrNull() ?: 0.0) > 0.0 && merchant.isNotBlank()
        ) {
            Text(
                if (isExpense) "Add Expense" else "Add Income",
                color = Ink.bg,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = timestamp)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker hands back UTC midnight; keep the current wall-clock time so
                    // the txn doesn't land on the wrong local day.
                    state.selectedDateMillis?.let { picked ->
                        timestamp = Instant.ofEpochMilli(picked)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .atTime(LocalTime.now())
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun AddCategorySheet(vm: ExpenseViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#4CAF50") }
    var kind by remember { mutableStateOf(com.lifetrack.app.data.db.entity.CategoryKind.EXPENSE) }
    val isExpense = kind == com.lifetrack.app.data.db.entity.CategoryKind.EXPENSE

    val palette = listOf("#FF7043", "#66BB6A", "#42A5F5", "#AB47BC", "#FFA726", "#EC407A", "#26A69A", "#78909C")

    Column(
        Modifier
            .padding(20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text("New Category", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isExpense,
                onClick = { kind = com.lifetrack.app.data.db.entity.CategoryKind.EXPENSE },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("Expense") }
            )
            SegmentedButton(
                selected = !isExpense,
                onClick = { kind = com.lifetrack.app.data.db.entity.CategoryKind.INCOME },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("Income") }
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(if (isExpense) "Category Name" else "Income source (e.g. Salary)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = budget,
                onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) budget = it },
                label = { Text("Budget (₹)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
            OutlinedTextField(
                value = emoji,
                onValueChange = { if (it.length <= 2) emoji = it },
                label = { Text("Icon (Emoji)") },
                modifier = Modifier.weight(0.6f),
                singleLine = true
            )
        }
        
        Eyebrow("Accent Color", Modifier.padding(top = 20.dp, bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(palette) { c ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(c.toColorInt()))
                        .clickable { color = c }
                        .padding(4.dp)
                ) {
                    if (color == c) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(0.4f)))
                    }
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (name.isNotBlank()) {
                    vm.addCategory(
                        name = name,
                        colorHex = color,
                        budget = if (isExpense) budget.toDoubleOrNull() else null,
                        emoji = emoji.takeIf { it.isNotBlank() },
                        kind = kind
                    )
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint),
            enabled = name.isNotBlank()
        ) { Text("Create Category", color = Ink.bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun BudgetEditDialog(category: CategoryEntity, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var budget by remember { mutableStateOf(category.monthlyBudget?.toString() ?: "") }
    Dialog(onDismissRequest = onDismiss) {
        InkCard {
            Column(Modifier.padding(24.dp)) {
                Text("Set Monthly Budget", style = MaterialTheme.typography.titleLarge)
                Text(category.name, style = MaterialTheme.typography.bodyMedium, color = Ink.mint)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = budget,
                    onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) budget = it },
                    label = { Text("Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave blank to remove the limit.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (category.monthlyBudget != null) {
                        TextButton(onClick = { onSave(null); onDismiss() }) {
                            Text("Remove", color = Ink.danger)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onSave(budget.toDoubleOrNull()); onDismiss() },
                        enabled = budget.isBlank() || budget.toDoubleOrNull() != null
                    ) { Text("Save", color = Ink.mint) }
                }
            }
        }
    }
}

@Composable
private fun BudgetSheet(vm: ExpenseViewModel, onDone: () -> Unit) {
    val categories by vm.categories.collectAsState()
    val overall by vm.overallBudget.collectAsState()
    val catSum by vm.categoryBudgetSum.collectAsState()

    var overallText by remember(overall) {
        mutableStateOf(overall?.let { "%.0f".format(it) } ?: "")
    }
    val limits = remember { mutableStateMapOf<Long, String>() }
    LaunchedEffect(categories) {
        categories.forEach { c ->
            if (!limits.containsKey(c.id)) {
                limits[c.id] = c.monthlyBudget?.let { "%.0f".format(it) } ?: ""
            }
        }
    }

    fun numeric(v: String) = v.all { it.isDigit() || it == '.' }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Budgets",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDone) { Icon(Icons.Default.Close, "Close") }
        }

        Spacer(Modifier.height(16.dp))

        Eyebrow("Overall monthly cap")
        OutlinedTextField(
            value = overallText,
            onValueChange = { if (numeric(it)) overallText = it },
            label = { Text("Total budget (₹)") },
            placeholder = { Text("e.g. 40000") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (overallText.isBlank())
                "Blank = use the sum of your category limits (₹%,.0f).".format(catSum)
            else
                "This drives the dashboard total, Safe to Spend, and the calendar shading.",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.textDim
        )

        Spacer(Modifier.height(24.dp))
        Eyebrow("Per-category limits")

        if (categories.isEmpty()) {
            Text(
                "No categories yet — create one first and it'll show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.textDim,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        categories.forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIconBox(c.name, c.iconEmoji, parseColor(c.colorHex))
                Spacer(Modifier.width(12.dp))
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = limits[c.id] ?: "",
                    onValueChange = { if (numeric(it)) limits[c.id] = it },
                    modifier = Modifier.width(120.dp),
                    placeholder = { Text("—", color = Ink.textDim) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        val enteredSum = categories.sumOf { limits[it.id]?.toDoubleOrNull() ?: 0.0 }
        val cap = overallText.toDoubleOrNull()
        if (cap != null && enteredSum > cap) {
            Text(
                "Category limits add up to ₹%,.0f — that's ₹%,.0f over your overall cap.".format(enteredSum, enteredSum - cap),
                style = MaterialTheme.typography.labelSmall,
                color = Ink.danger
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                vm.setOverallBudget(overallText.toDoubleOrNull())
                categories.forEach { c ->
                    val v = limits[c.id]?.toDoubleOrNull()
                    if (v != c.monthlyBudget) vm.setBudget(c.id, v)
                }
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint)
        ) { Text("Save Budgets", color = Ink.bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun EditCategorySheet(vm: ExpenseViewModel, category: CategoryEntity, onDone: () -> Unit) {
    var name by remember { mutableStateOf(category.name) }
    var emoji by remember { mutableStateOf(category.iconEmoji ?: "") }
    var color by remember { mutableStateOf(category.colorHex) }
    var confirmDelete by remember { mutableStateOf(false) }

    val palette = listOf("#FF7043", "#66BB6A", "#42A5F5", "#AB47BC", "#FFA726", "#EC407A", "#26A69A", "#78909C")

    Column(
        Modifier
            .padding(20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Edit Category", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = emoji,
                onValueChange = { if (it.length <= 2) emoji = it },
                label = { Text("Icon") },
                modifier = Modifier.weight(0.5f),
                singleLine = true
            )
        }

        Eyebrow("Accent Color", Modifier.padding(top = 20.dp, bottom = 12.dp))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(palette) { c ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(c.toColorInt()))
                        .clickable { color = c }
                        .padding(4.dp)
                ) {
                    if (color == c) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(0.4f)))
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { vm.updateCategory(category.id, name, color, emoji); onDone() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint),
            enabled = name.isNotBlank()
        ) { Text("Save Changes", color = Ink.bg, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp), tint = Ink.danger)
            Spacer(Modifier.width(8.dp))
            Text("Delete category", color = Ink.danger)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${category.name}?") },
            text = {
                Text(
                    "Transactions stay, but they'll move back to the Inbox to be re-categorized. " +
                        "Any learned merchant rules for this category are removed too."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(category.id)
                    confirmDelete = false
                    onDone()
                }) { Text("Delete", color = Ink.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TagPill(name: String, color: Color) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 9.sp,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun ActivityTab(vm: ExpenseViewModel, onTap: (TransactionEntity) -> Unit) {
    val filters by vm.searchFilters.collectAsState()
    val categories by vm.categories.collectAsState()
    val allTags by vm.tags.collectAsState()
    val tagsByTxn by vm.tagsByTxn.collectAsState()
    val monthTxns by vm.filteredTxns.collectAsState()
    val results by vm.searchResults.collectAsState()
    val totals by vm.searchTotals.collectAsState()

    var showFilters by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filters.text,
            onValueChange = { q -> vm.updateSearch { it.copy(text = q) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            placeholder = { Text("Search merchant, note, tag, amount…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                Row {
                    if (filters.isActive) {
                        IconButton(onClick = { vm.clearSearch() }) { Icon(Icons.Default.Close, "Clear") }
                    }
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(
                            Icons.Default.FilterList,
                            "Filters",
                            tint = if (showFilters || filters.isActive) Ink.mint else Ink.textDim
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium
        )

        if (showFilters) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Eyebrow("Type")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filters.type == TxnType.DEBIT,
                        onClick = {
                            vm.updateSearch { it.copy(type = if (it.type == TxnType.DEBIT) null else TxnType.DEBIT) }
                        },
                        label = { Text("Out") }
                    )
                    FilterChip(
                        selected = filters.type == TxnType.CREDIT,
                        onClick = {
                            vm.updateSearch { it.copy(type = if (it.type == TxnType.CREDIT) null else TxnType.CREDIT) }
                        },
                        label = { Text("In") }
                    )
                    FilterChip(
                        selected = filters.includeExcluded,
                        onClick = { vm.updateSearch { it.copy(includeExcluded = !it.includeExcluded) } },
                        label = { Text("Incl. excluded") }
                    )
                }

                if (categories.isNotEmpty()) {
                    Eyebrow("Category")
                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories, key = { it.id }) { c ->
                            FilterChip(
                                selected = filters.categoryId == c.id,
                                onClick = {
                                    vm.updateSearch { f -> f.copy(categoryId = if (f.categoryId == c.id) null else c.id) }
                                },
                                label = { Text(c.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = parseColor(c.colorHex).copy(alpha = 0.22f),
                                    selectedLabelColor = parseColor(c.colorHex)
                                )
                            )
                        }
                    }
                }

                if (allTags.size > 0) {
                    Eyebrow("Tag")
                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(allTags, key = { it.id }) { g ->
                            FilterChip(
                                selected = filters.tagId == g.id,
                                onClick = {
                                    vm.updateSearch { f -> f.copy(tagId = if (f.tagId == g.id) null else g.id) }
                                },
                                label = { Text(g.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = parseColor(g.colorHex).copy(alpha = 0.22f),
                                    selectedLabelColor = parseColor(g.colorHex)
                                )
                            )
                        }
                    }
                }
            }
        }

        if (filters.isActive) {
            val (count, out, inn) = totals
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$count result${if (count == 1) "" else "s"} · all history",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.textDim,
                    modifier = Modifier.weight(1f)
                )
                if (out > 0) Text("↑₹%,.0f".format(out), style = MaterialTheme.typography.labelMedium, color = Ink.danger)
                if (inn > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text("↓₹%,.0f".format(inn), style = MaterialTheme.typography.labelMedium, color = Ink.mint)
                }
            }
            if (count == 400) {
                Text(
                    "Showing the 400 most recent matches — narrow the search to see older ones.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        TxnList(
            txns = if (filters.isActive) results else monthTxns,
            categories = categories,
            tagsByTxn = tagsByTxn,
            emptyText = if (filters.isActive) "Nothing matches that." else "No transactions for this period.",
            onTap = onTap
        )
    }
}

@Composable
private fun YearTab(vm: ExpenseViewModel, onOpenArchive: () -> Unit) {
    val state by vm.yearView.collectAsState()
    val years by vm.availableYears.collectAsState()
    val selected by vm.selectedYear.collectAsState()
    val catSpend by vm.yearCategorySpend.collectAsState()
    val categories by vm.categories.collectAsState()

    val monthLabels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(years) { y ->
                    FilterChip(
                        selected = y == selected,
                        onClick = { vm.selectedYear.value = y },
                        label = { Text(y.toString(), fontWeight = if (y == selected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Ink.mint.copy(alpha = 0.2f),
                            selectedLabelColor = Ink.mint
                        )
                    )
                }
            }
        }

        item {
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("SPENT IN ${state.year}", style = MaterialTheme.typography.labelMedium, color = Ink.textDim)
                    Text("₹%,.0f".format(state.totalSpend), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("RECEIVED", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            Text("₹%,.0f".format(state.totalIncome), style = MaterialTheme.typography.titleMedium, color = Ink.mint)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("AVG / MONTH", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            Text("₹%,.0f".format(state.avgMonthlySpend), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("NET", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                            val net = state.totalIncome - state.totalSpend
                            Text(
                                "₹%,.0f".format(net),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (net < 0) Ink.danger else Ink.mint
                            )
                        }
                    }
                }
            }
        }

        item {
            Eyebrow("Month by month")
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val peak = state.months.maxOfOrNull { it.spend }?.takeIf { it > 0 } ?: 1.0
                    Row(
                        Modifier.fillMaxWidth().height(140.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        state.months.forEachIndexed { idx, bar ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.weight(1f)
                            ) {
                                val fraction = (bar.spend / peak).toFloat().coerceIn(0f, 1f)
                                Box(
                                    Modifier
                                        .width(14.dp)
                                        .height((4 + 106 * fraction).dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(
                                            when {
                                                bar.spend <= 0.0 -> Ink.hairline
                                                bar.archived -> Ink.violet.copy(alpha = 0.55f)
                                                else -> Ink.mint
                                            }
                                        )
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    monthLabels[idx],
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = if (idx + 1 == state.busiestMonth) Ink.text else Ink.textDim
                                )
                            }
                        }
                    }
                    if (state.months.any { it.archived }) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.violet.copy(alpha = 0.55f)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Archived — totals kept, individual transactions moved to a file",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.textDim
                            )
                        }
                    }
                }
            }
        }

        item {
            Eyebrow("Where it went in ${state.year}")
            InkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (catSpend.isEmpty()) {
                        Text("Nothing recorded for this year.", Modifier.padding(20.dp), color = Ink.textDim)
                    }
                    val top = catSpend.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1.0
                    catSpend.forEach { c ->
                        val cat = categories.firstOrNull { it.id == c.categoryId }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryIconBox(c.name ?: "Uncategorized", cat?.iconEmoji, parseColor(c.colorHex))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name ?: "Uncategorized", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "₹%,.0f / month avg".format(c.total / 12),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Ink.textDim
                                    )
                                }
                                Text("₹%,.0f".format(c.total), style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            SlimProgress((c.total / top).toFloat(), parseColor(c.colorHex))
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Archive, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Archive & free up space")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ArchiveSheet(vm: ExpenseViewModel, onDone: () -> Unit) {
    val preview by vm.archivePreview.collectAsState()
    val status by vm.archiveStatus.collectAsState()

    var monthsBack by remember { mutableIntStateOf(12) }
    var deleteAfter by remember { mutableStateOf(true) }
    val cutoff = remember(monthsBack) { LocalDate.now().minusMonths(monthsBack.toLong()).withDayOfMonth(1) }

    LaunchedEffect(cutoff) { vm.previewArchive(cutoff) }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Archive & free space",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.clearArchiveStatus(); onDone() }) { Icon(Icons.Default.Close, "Close") }
        }

        Spacer(Modifier.height(16.dp))
        Eyebrow("Archive transactions older than")
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(6, 12, 24, 36)) { m ->
                FilterChip(
                    selected = monthsBack == m,
                    onClick = { monthsBack = m },
                    label = { Text(if (m % 12 == 0) "${m / 12}y" else "${m}m") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Ink.mint.copy(alpha = 0.2f),
                        selectedLabelColor = Ink.mint
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Cutoff: anything before ${cutoff}",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.textDim
        )

        Spacer(Modifier.height(20.dp))
        InkCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val count = preview?.first ?: 0
                val bytes = preview?.second ?: 0L
                DetailRow("Transactions to archive", "$count")
                DetailRow("Database size right now", "%.1f MB".format(bytes / 1_048_576.0))
                Text(
                    "For reference: roughly 1 KB per transaction. Clearing 5,000 of them frees " +
                        "about 5 MB, so do this for a faster, tidier list rather than for storage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = deleteAfter, onCheckedChange = { deleteAfter = it })
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Delete after saving", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (deleteAfter) "Monthly totals stay, so the Year chart keeps its bars"
                    else "Export only — nothing is removed",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.textDim
                )
            }
        }

        status?.let {
            Spacer(Modifier.height(16.dp))
            InkCard(Modifier.fillMaxWidth()) {
                Text(
                    it,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Failed")) Ink.danger else Ink.mint
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.archiveAndPurge(cutoff, deleteAfter) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint),
            enabled = (preview?.first ?: 0) > 0
        ) {
            Text(
                if (deleteAfter) "Save to file & clear" else "Save to file",
                color = Ink.bg,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Saved to Downloads/LifeTrack as JSON. Nothing is deleted unless the file is written successfully.",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.textDim
        )
        Spacer(Modifier.height(32.dp))
    }
}

private fun String.takeIn(predicate: (String) -> Boolean): String? {
    return if (predicate(this)) this else null
}
