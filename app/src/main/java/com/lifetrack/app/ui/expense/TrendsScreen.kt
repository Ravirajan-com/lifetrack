@file:OptIn(ExperimentalMaterial3Api::class)
package com.lifetrack.app.ui.expense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.db.dao.PeriodTotals
import com.lifetrack.app.data.db.entity.TxnType
import com.lifetrack.app.ui.common.InkCard
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * The Trends page: pick a granularity (day/week/month), see spend (and a connecting income/spend
 * line) across a rolling window of recent periods, tap any period to see its totals, then Review
 * to open that period's actual transactions, newest first.
 */
@Composable
fun TrendsTab(vm: ExpenseViewModel, onBack: () -> Unit) {
    var granularity by remember { mutableStateOf(ExpenseViewModel.TrendGranularity.DAY) }
    var granularityMenuOpen by remember { mutableStateOf(false) }
    var selectedIndex by remember(granularity) { mutableStateOf<Int?>(null) }
    var showReview by remember { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    val (windowStart, windowEnd) = remember(granularity, today) {
        when (granularity) {
            ExpenseViewModel.TrendGranularity.DAY -> today.minusDays(13) to today.plusDays(1)
            ExpenseViewModel.TrendGranularity.WEEK ->
                today.with(DayOfWeek.MONDAY).minusWeeks(11) to today.plusDays(1)
            ExpenseViewModel.TrendGranularity.MONTH ->
                today.withDayOfMonth(1).minusMonths(11) to today.plusDays(1)
        }.let { (s, e) ->
            s.atStartOfDay(zone).toInstant().toEpochMilli() to e.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    val periods by remember(granularity, windowStart, windowEnd) {
        vm.trends(granularity, windowStart, windowEnd)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(periods) {
        if (selectedIndex == null && periods.isNotEmpty()) selectedIndex = periods.lastIndex
    }
    val selected = selectedIndex?.let { periods.getOrNull(it) }
    val budget by vm.categoryBudgetSum.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Trends", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        Box {
            AssistChip(
                onClick = { granularityMenuOpen = true },
                label = { Text("Trends by ${granularity.name.lowercase()}") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
            )
            DropdownMenu(expanded = granularityMenuOpen, onDismissRequest = { granularityMenuOpen = false }) {
                ExpenseViewModel.TrendGranularity.entries.forEach { g ->
                    DropdownMenuItem(
                        text = { Text("Trends by ${g.name.lowercase()}") },
                        onClick = {
                            granularity = g
                            selectedIndex = null
                            granularityMenuOpen = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (periods.isEmpty()) {
            Text(
                "No transactions yet in this window.",
                color = com.lifetrack.app.ui.theme.Ink.textDim,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            TrendsComboChart(
                periods = periods,
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
                granularity = granularity
            )
        }

        selected?.let { p ->
            Spacer(Modifier.height(20.dp))
            val label = periodLabel(p.period, granularity)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SPENDS", style = MaterialTheme.typography.labelSmall, color = com.lifetrack.app.ui.theme.Ink.textDim)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "₹%,.0f".format(p.spend),
                            style = MaterialTheme.typography.titleLarge,
                            color = com.lifetrack.app.ui.theme.Ink.text
                        )
                    }
                }
                InkCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("INCOME", style = MaterialTheme.typography.labelSmall, color = com.lifetrack.app.ui.theme.Ink.textDim)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "₹%,.0f".format(p.income),
                            style = MaterialTheme.typography.titleLarge,
                            color = com.lifetrack.app.ui.theme.Ink.mint
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            InkCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("BUDGET", style = MaterialTheme.typography.labelSmall, color = com.lifetrack.app.ui.theme.Ink.textDim)
                    Text("₹%,.0f".format(budget), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { showReview = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = com.lifetrack.app.ui.theme.Ink.mint)
            ) { Text("Review $label", color = com.lifetrack.app.ui.theme.Ink.bg, fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showReview && selected != null) {
        val (rangeStart, rangeEnd) = periodRange(selected.period, granularity, zone)
        ModalBottomSheet(onDismissRequest = { showReview = false }) {
            ReviewPeriodSheet(vm, rangeStart, rangeEnd, periodLabel(selected.period, granularity)) {
                showReview = false
            }
        }
    }
}

@Composable
private fun TrendsComboChart(
    periods: List<PeriodTotals>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    granularity: ExpenseViewModel.TrendGranularity
) {
    val max = periods.maxOf { it.spend }.takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .pointerInput(periods) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / periods.size
                        val idx = (offset.x / slot).toInt().coerceIn(0, periods.size - 1)
                        onSelect(idx)
                    }
                }
        ) {
            val slot = size.width / periods.size
            val barWidth = slot * 0.5f
            val points = mutableListOf<Offset>()
            periods.forEachIndexed { i, p ->
                val h = (p.spend / max * size.height * 0.85f).toFloat()
                val left = i * slot + (slot - barWidth) / 2
                val isSel = i == selectedIndex
                drawRoundRect(
                    color = if (isSel) com.lifetrack.app.ui.theme.Ink.violet else com.lifetrack.app.ui.theme.Ink.violet.copy(alpha = 0.35f),
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 4, barWidth / 4)
                )
                points.add(Offset(i * slot + slot / 2, size.height - h))
            }
            for (i in 0 until points.size - 1) {
                drawLine(color = com.lifetrack.app.ui.theme.Ink.mint, start = points[i], end = points[i + 1], strokeWidth = 4f, cap = StrokeCap.Round)
            }
            points.forEachIndexed { i, pt ->
                drawCircle(
                    color = if (i == selectedIndex) com.lifetrack.app.ui.theme.Ink.mint else com.lifetrack.app.ui.theme.Ink.mint.copy(alpha = 0.6f),
                    radius = if (i == selectedIndex) 9f else 5f,
                    center = pt
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            periods.forEachIndexed { i, p ->
                Text(
                    periodAxisLabel(p.period, granularity),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selectedIndex) com.lifetrack.app.ui.theme.Ink.mint else com.lifetrack.app.ui.theme.Ink.textDim
                )
            }
        }
    }
}

@Composable
private fun ReviewPeriodSheet(vm: ExpenseViewModel, from: Long, to: Long, label: String, onDone: () -> Unit) {
    val txns by remember(from, to) { vm.txnsInRange(from, to) }.collectAsState(initial = emptyList())
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onDone) { Icon(Icons.Default.Close, null) }
        }
        Text(
            "${txns.size} transaction${if (txns.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            color = com.lifetrack.app.ui.theme.Ink.textDim
        )
        Spacer(Modifier.height(12.dp))

        if (txns.isEmpty()) {
            Text("Nothing in this period.", color = com.lifetrack.app.ui.theme.Ink.textDim, style = MaterialTheme.typography.bodyMedium)
        } else {
            txns.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            t.merchant,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(t.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = com.lifetrack.app.ui.theme.Ink.textDim
                        )
                    }
                    Text(
                        "₹%,.0f".format(t.amount),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (t.type == TxnType.CREDIT) com.lifetrack.app.ui.theme.Ink.mint else com.lifetrack.app.ui.theme.Ink.text
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun periodLabel(period: String, g: ExpenseViewModel.TrendGranularity): String = when (g) {
    ExpenseViewModel.TrendGranularity.MONTH ->
        YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    ExpenseViewModel.TrendGranularity.WEEK -> {
        val start = LocalDate.parse(period)
        "${start.format(DateTimeFormatter.ofPattern("d MMM"))} – ${start.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM"))}"
    }
    ExpenseViewModel.TrendGranularity.DAY ->
        LocalDate.parse(period).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
}

private fun periodAxisLabel(period: String, g: ExpenseViewModel.TrendGranularity): String = when (g) {
    ExpenseViewModel.TrendGranularity.MONTH -> YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMM"))
    ExpenseViewModel.TrendGranularity.WEEK -> LocalDate.parse(period).format(DateTimeFormatter.ofPattern("d MMM"))
    ExpenseViewModel.TrendGranularity.DAY -> LocalDate.parse(period).format(DateTimeFormatter.ofPattern("d"))
}

private fun periodRange(period: String, g: ExpenseViewModel.TrendGranularity, zone: ZoneId): Pair<Long, Long> =
    when (g) {
        ExpenseViewModel.TrendGranularity.MONTH -> {
            val ym = YearMonth.parse(period)
            ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
                ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        ExpenseViewModel.TrendGranularity.WEEK -> {
            val start = LocalDate.parse(period)
            start.atStartOfDay(zone).toInstant().toEpochMilli() to
                start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        ExpenseViewModel.TrendGranularity.DAY -> {
            val d = LocalDate.parse(period)
            d.atStartOfDay(zone).toInstant().toEpochMilli() to
                d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
