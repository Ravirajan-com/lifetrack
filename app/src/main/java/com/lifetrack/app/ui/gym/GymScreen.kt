package com.lifetrack.app.ui.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifetrack.app.data.db.dao.CategoryFrequency
import com.lifetrack.app.data.db.dao.PersonalBest
import com.lifetrack.app.data.db.dao.SessionWithCategory
import com.lifetrack.app.data.db.entity.ExerciseEntity
import com.lifetrack.app.data.db.entity.WorkoutCategoryEntity
import com.lifetrack.app.ui.common.*
import com.lifetrack.app.ui.theme.Ink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GymScreen(vm: GymViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val activeSession by vm.activeSession.collectAsState()

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = Ink.ember,
        ) {
            listOf("Dashboard", "Train", "Manage").forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(if ((i == 1) && (activeSession != null)) "$t (Live)" else t) },
                    selectedContentColor = Ink.ember,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        when (tab) {
            0 -> GymDashboard(vm)
            1 -> TrainingTab(vm)
            2 -> ManageTab(vm)
        }
    }
}

@Composable
private fun GymDashboard(vm: GymViewModel) {
    val workoutCount by vm.workoutCount.collectAsState()
    val totalVolume by vm.totalVolume.collectAsState()
    val pbs by vm.personalBests.collectAsState()
    val frequency by vm.workoutFrequency.collectAsState()
    val history by vm.history.collectAsState()
    val selectedMonth by vm.selectedMonth.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.selectedMonth.value = selectedMonth.minusMonths(1) }) {
                        Icon(Icons.Default.ChevronLeft, null)
                    }
                    IconButton(onClick = { vm.selectedMonth.value = selectedMonth.plusMonths(1) }) {
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroStat(
                        eyebrow = "Workouts",
                        value = workoutCount.toString(),
                        sub = "this month",
                        accent = Ink.ember,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStat(
                        eyebrow = "Volume",
                        value = "%.1f".format(totalVolume / 1000) + "k",
                        sub = "tons moved",
                        accent = Ink.ember,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Eyebrow("Global Heatmap")
            InkCard(Modifier.fillMaxWidth()) {
                ConsistencyHeatmap(history)
            }
        }

        if (frequency.isNotEmpty()) {
            item {
                Eyebrow("Workout Frequency")
                InkCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        DonutChart(
                            slices = frequency.map {
                                PieSlice(it.categoryName, it.count.toDouble(), parseColor(it.colorHex))
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        frequency.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(parseColor(f.colorHex)))
                                Spacer(Modifier.width(8.dp))
                                Text(f.categoryName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${f.count} sessions", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        if (pbs.isNotEmpty()) {
            item {
                Eyebrow("Personal Bests")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pbs) { pb ->
                        PBItem(pb)
                    }
                }
            }
        }
        
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ConsistencyHeatmap(sessions: List<SessionWithCategory>) {
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now()
    val sessionMap = sessions.groupBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }

    Column(Modifier.padding(16.dp)) {
        // Simple 4-week grid
        for (w in 3 downTo 0) {
            val weekStart = today.minusWeeks(w.toLong()).with(java.time.DayOfWeek.MONDAY)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(weekStart.format(DateTimeFormatter.ofPattern("dd MMM")), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(45.dp), color = Ink.textDim)
                for (d in 0..6) {
                    val date = weekStart.plusDays(d.toLong())
                    val daySessions = sessionMap[date]
                    val color = if (daySessions == null) Ink.hairline else parseColor(daySessions.first().colorHex)
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun PBItem(pb: PersonalBest) {
    InkCard(Modifier.width(150.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(pb.exerciseName, style = MaterialTheme.typography.labelSmall, color = Ink.textDim, maxLines = 1)
            Text("%.1f kg".format(pb.weight), style = MaterialTheme.typography.titleMedium, color = Ink.ember)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
private fun TrainingTab(vm: GymViewModel) {
    val categories by vm.categories.collectAsState()
    val activeSession by vm.activeSession.collectAsState()
    val trainingDate by vm.trainingDate.collectAsState()
    val variationPool by vm.variationPool.collectAsState()
    val activeSets by vm.activeSets.collectAsState()
    val lastSets by vm.lastSets.collectAsState()

    var showCategoryPicker by remember { mutableStateOf(value = false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            DateStrip(trainingDate) { vm.trainingDate.value = it }
        }

        if (activeSession == null) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { showCategoryPicker = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.ember)
                    ) {
                        Icon(Icons.Default.FitnessCenter, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Workout", color = Ink.bg)
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val cat = categories.find { it.id == activeSession!!.categoryId }
                        Text(cat?.name ?: "Workout", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Session in progress", style = MaterialTheme.typography.labelSmall, color = Ink.ember)
                    }
                    Button(
                        onClick = { vm.finishSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.danger)
                    ) { Text("Finish", color = Color.White) }
                }
            }

            items(variationPool) { ex ->
                ExerciseCard(
                    ex = ex,
                    sets = activeSets.filter { it.exerciseId == ex.id },
                    lastSets = lastSets[ex.id] ?: emptyList(),
                    onLog = { r, w -> vm.logSet(ex.id, r, w) },
                    onClear = { vm.clearSets(exerciseId = ex.id) }
                )
            }
        }
    }

    if (showCategoryPicker) {
        ModalBottomSheet(onDismissRequest = { showCategoryPicker = false }) {
            Column(Modifier.padding(20.dp)) {
                Text("What are we hitting today?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                categories.forEach { cat ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selectTrainingState(trainingDate, cat)
                                showCategoryPicker = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(cat.colorHex)))
                        Spacer(Modifier.width(12.dp))
                        Text(cat.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun DateStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val dates = remember { (-3..3).map { LocalDate.now().plusDays(it.toLong()) } }
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items(dates) { d ->
            val isSelected = d == selected
            Column(
                Modifier
                    .width(45.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) Ink.ember.copy(0.1f) else Color.Transparent)
                    .clickable { onSelect(d) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.format(DateTimeFormatter.ofPattern("E")), style = MaterialTheme.typography.labelSmall, color = if (isSelected) Ink.ember else Ink.textDim)
                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (isSelected) Ink.ember else Ink.text)
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    ex: ExerciseEntity,
    sets: List<com.lifetrack.app.data.db.entity.SetLogEntity>,
    lastSets: List<com.lifetrack.app.data.db.entity.SetLogEntity>,
    onLog: (Int, Double) -> Unit,
    onClear: () -> Unit
) {
    var reps by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }

    InkCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(ex.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            if (lastSets.isNotEmpty()) {
                Text(
                    "Last: " + lastSets.joinToString("  ") { "${it.reps}x${it.weightKg}k" },
                    style = MaterialTheme.typography.labelSmall, color = Ink.textDim, modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (sets.isNotEmpty()) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    sets.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("Set ${s.setNumber}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
                            Text("${s.reps} reps @ ${s.weightKg} kg", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.ember)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onClear, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Default.Close, null, tint = Ink.textDim)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Ink.hairline, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    reps, { if (it.all { c -> c.isDigit() }) reps = it }, 
                    label = { Text("Reps") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    weight, { if (it.all { c -> c.isDigit() || c == '.' }) weight = it }, 
                    label = { Text("Kg") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                IconButton(
                    onClick = {
                        val r = reps.toIntOrNull(); val w = weight.toDoubleOrNull()
                        if (r != null && w != null) { onLog(r, w); reps = ""; weight = "" }
                    },
                    modifier = Modifier.clip(CircleShape).background(Ink.ember)
                ) { Icon(Icons.Default.Add, null, tint = Ink.bg) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageTab(vm: GymViewModel) {
    val categories by vm.categories.collectAsState()
    var showAddCat by remember { mutableStateOf(false) }
    var managingCat by remember { mutableStateOf<WorkoutCategoryEntity?>(null) }
    var editingCat by remember { mutableStateOf<WorkoutCategoryEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Workout Categories", Modifier.weight(1f))
                IconButton(onClick = { showAddCat = true }) { Icon(Icons.Default.Add, null, tint = Ink.ember) }
            }
        }

        items(categories) { cat ->
            InkCard(Modifier.fillMaxWidth().clickable { managingCat = cat }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(cat.colorHex)))
                    Spacer(Modifier.width(16.dp))
                    Text(cat.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editingCat = cat }) {
                        Icon(Icons.Default.Edit, null, tint = Ink.textDim, modifier = Modifier.size(20.dp))
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Ink.textDim)
                }
            }
        }
    }

    if (showAddCat) {
        var name by remember { mutableStateOf("") }
        val colors = listOf("#FF7043", "#66BB6A", "#42A5F5", "#AB47BC", "#FFA726", "#EC407A", "#26A69A", "#78909C")
        var selectedColor by remember { mutableStateOf(colors.first()) }

        ModalBottomSheet(onDismissRequest = { showAddCat = false }) {
            Column(Modifier.padding(20.dp)) {
                Text("New Category", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Chest)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(colors) { c ->
                        Box(Modifier.size(36.dp).clip(CircleShape).background(parseColor(c)).clickable { selectedColor = c }) {
                            if (c == selectedColor) Icon(Icons.Default.Check, null, Modifier.align(Alignment.Center), tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { vm.addCategory(name, selectedColor); showAddCat = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Ink.ember)) {
                    Text("Create", color = Ink.bg)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    editingCat?.let { cat ->
        var name by remember { mutableStateOf(cat.name) }
        val colors = listOf("#FF7043", "#66BB6A", "#42A5F5", "#AB47BC", "#FFA726", "#EC407A", "#26A69A", "#78909C")
        var selectedColor by remember { mutableStateOf(cat.colorHex) }
        var confirmDelete by remember { mutableStateOf(false) }

        ModalBottomSheet(onDismissRequest = { editingCat = null }) {
            Column(Modifier.padding(20.dp)) {
                Text("Edit Category", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(colors) { c ->
                        Box(Modifier.size(36.dp).clip(CircleShape).background(parseColor(c)).clickable { selectedColor = c }) {
                            if (c == selectedColor) Icon(Icons.Default.Check, null, Modifier.align(Alignment.Center), tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { vm.updateCategory(cat.id, name, selectedColor); editingCat = null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.ember)
                ) { Text("Save Changes", color = Ink.bg) }
                
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete Category", color = Ink.danger)
                }
                Spacer(Modifier.height(32.dp))
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${cat.name}?") },
                text = { Text("This will remove the category and all its exercises. Logged sessions will stay but become unassigned.") },
                confirmButton = {
                    TextButton(onClick = { vm.deleteCategory(cat.id); confirmDelete = false; editingCat = null }) {
                        Text("Delete", color = Ink.danger)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
            )
        }
    }

    if (managingCat != null) {
        var newEx by remember { mutableStateOf("") }
        var editingEx by remember { mutableStateOf<ExerciseEntity?>(null) }
        
        ModalBottomSheet(onDismissRequest = { managingCat = null }) {
            val variationsFlow = vm.variationPool.collectAsState()
            LaunchedEffect(managingCat) { vm.trainingCategory.value = managingCat }
            
            Column(Modifier.padding(20.dp).fillMaxHeight(0.8f)) {
                Text("Manage ${managingCat!!.name}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    newEx, { newEx = it }, 
                    label = { Text("Add variation (e.g. Incline Bench)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { if (newEx.isNotBlank()) { vm.addExerciseVariation(managingCat!!.id, newEx); newEx = "" } }) {
                            Icon(Icons.Default.Add, null)
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                Eyebrow("Variations")
                LazyColumn(Modifier.weight(1f)) {
                    items(variationsFlow.value) { ex ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ex.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { editingEx = ex }) {
                                Icon(Icons.Default.Edit, null, tint = Ink.textDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }

        editingEx?.let { ex ->
            var name by remember { mutableStateOf(ex.name) }
            var confirmDeleteEx by remember { mutableStateOf(false) }

            Dialog(onDismissRequest = { editingEx = null }) {
                InkCard {
                    Column(Modifier.padding(24.dp)) {
                        Text("Edit Variation", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { vm.updateExerciseVariation(ex.id, ex.categoryId, name); editingEx = null },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink.ember)
                        ) { Text("Save", color = Ink.bg) }
                        TextButton(onClick = { confirmDeleteEx = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete Variation", color = Ink.danger)
                        }
                        TextButton(onClick = { editingEx = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
            }

            if (confirmDeleteEx) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteEx = false },
                    title = { Text("Delete ${ex.name}?") },
                    text = { Text("This will remove this exercise variation. Past sets logged with this variation will be lost.") },
                    confirmButton = {
                        TextButton(onClick = { vm.deleteExerciseVariation(ex.id); confirmDeleteEx = false; editingEx = null }) {
                            Text("Delete", color = Ink.danger)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmDeleteEx = false }) { Text("Cancel") } }
                )
            }
        }
    }
}
