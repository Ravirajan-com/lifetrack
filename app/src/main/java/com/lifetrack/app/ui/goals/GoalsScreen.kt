package com.lifetrack.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifetrack.app.ui.common.*
import com.lifetrack.app.ui.theme.Ink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(vm: GoalsViewModel = viewModel()) {
    val goals by vm.goals.collectAsState()
    val completions by vm.completions.collectAsState()
    val streaks by vm.streaks.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val doneIds = completions.map { it.goalId }.toSet()

    var showAddSheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(goals) { goals.forEach { vm.refreshStreak(it.id) } }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }, containerColor = Ink.violet, contentColor = Ink.bg) {
                Icon(Icons.Default.Add, "Add Goal")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Horizontal Date Strip
            HorizontalDateStrip(
                selectedDate = selectedDate,
                onDateSelected = { vm.selectedDate.value = it },
                onOpenPicker = { showDatePicker = true }
            )

            Column(Modifier.padding(horizontal = 20.dp)) {
                val isToday = selectedDate == LocalDate.now()
                HeroStat(
                    eyebrow = if (isToday) "Today's Focus" else selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    value = "${doneIds.size}/${goals.size}",
                    sub = if (goals.isNotEmpty() && doneIds.size == goals.size) "all done — good day"
                    else "goals completed",
                    accent = Ink.violet
                )
                if (goals.isNotEmpty()) {
                    SlimProgress(
                        fraction = doneIds.size.toFloat() / goals.size,
                        accent = Ink.violet
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (goals.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No goals for this day.", color = Ink.textDim, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(goals, key = { it.id }) { g ->
                            val done = g.id in doneIds
                            InkCard(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = done,
                                        onCheckedChange = { vm.toggle(g, it) },
                                        colors = CheckboxDefaults.colors(checkedColor = Ink.violet)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            g.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            textDecoration = if (done) TextDecoration.LineThrough else null,
                                            color = if (done) Ink.textDim else Ink.text
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (g.isRecurring) "Daily" else "Once",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Ink.textDim
                                            )
                                            g.reminderHour?.let {
                                                Spacer(Modifier.width(8.dp))
                                                Icon(Icons.Default.Schedule, null, Modifier.size(10.dp), tint = Ink.textDim)
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    "%02d:%02d".format(it, g.reminderMinute ?: 0),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Ink.textDim
                                                )
                                            }
                                            streaks[g.id]?.takeIf { it > 1 }?.let {
                                                Spacer(Modifier.width(12.dp))
                                                Text("🔥 $it", style = MaterialTheme.typography.labelSmall, color = Ink.violet)
                                            }
                                        }
                                    }
                                    IconButton(onClick = { vm.archive(g) }) {
                                        Icon(Icons.Default.DeleteOutline, null, tint = Ink.textDim, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            AddGoalSheet(vm, selectedDate) { showAddSheet = false }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        vm.selectedDate.value = Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("Select") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun HorizontalDateStrip(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onOpenPicker: () -> Unit
) {
    val today = LocalDate.now()
    val dates = remember { (-3..10).map { today.plusDays(it.toLong()) } }

    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenPicker) {
            Icon(Icons.Default.CalendarMonth, null, tint = Ink.violet)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 20.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(dates) { date ->
                val isSelected = date == selectedDate
                val isToday = date == today
                
                Column(
                    modifier = Modifier
                        .width(45.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (isSelected) Ink.violet else Color.Transparent)
                        .clickable { onDateSelected(date) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        date.format(DateTimeFormatter.ofPattern("E")).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Ink.bg else if (isToday) Ink.violet else Ink.textDim,
                        fontSize = 10.sp
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Ink.bg else Ink.text
                    )
                    if (isToday && !isSelected) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(Ink.violet))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalSheet(vm: GoalsViewModel, defaultDate: LocalDate, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf(true) }
    var selectedTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

    Column(Modifier.padding(20.dp).imePadding()) {
        Text("New Goal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("What's the goal?") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repeats Daily", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = recurring,
                onCheckedChange = { recurring = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Ink.violet)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(Ink.surfaceHi)
                .clickable { showTimePicker = true }
                .padding(12.dp)
        ) {
            Icon(Icons.Default.Notifications, null, tint = Ink.violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Reminder", style = MaterialTheme.typography.labelSmall, color = Ink.textDim)
                Text(
                    selectedTime?.let { "%02d:%02d".format(it.first, it.second) } ?: "No reminder set",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (title.isNotBlank()) {
                    vm.addGoal(title, recurring, defaultDate, selectedTime?.first, selectedTime?.second)
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.violet),
            enabled = title.isNotBlank()
        ) {
            Text("Create Goal", color = Ink.bg, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showTimePicker) {
        val state = rememberTimePickerState()
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            onConfirm = {
                selectedTime = state.hour to state.minute
                showTimePicker = false
            }
        ) {
            TimePicker(state = state)
        }
    }
}

@Composable
fun TimePickerDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        InkCard {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                content()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) { Text("Cancel") }
                    TextButton(onClick = onConfirm) { Text("OK") }
                }
            }
        }
    }
}
