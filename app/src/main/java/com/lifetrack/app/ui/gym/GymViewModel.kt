package com.lifetrack.app.ui.gym

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.db.AppDatabase
import com.lifetrack.app.data.db.dao.ExerciseProgressPoint
import com.lifetrack.app.data.db.entity.ExerciseEntity
import com.lifetrack.app.data.db.entity.SessionEntity
import com.lifetrack.app.data.db.entity.SetLogEntity
import com.lifetrack.app.data.db.entity.WorkoutCategoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class GymViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).gymDao()
    private val zone = ZoneId.systemDefault()

    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Dashboard Stats */
    val selectedMonth = MutableStateFlow(YearMonth.now())
    
    private val monthRange = selectedMonth.map { month ->
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        start to end
    }

    val workoutCount = monthRange.flatMapLatest { (start, end) ->
        dao.getMonthlyWorkoutCount(start, end)
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val totalVolume = monthRange.flatMapLatest { (start, end) ->
        dao.getMonthlyVolume(start, end).map { it ?: 0.0 }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val personalBests = dao.getPersonalBests().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val workoutFrequency = dao.getWorkoutFrequency().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val history = dao.getSessionHistoryWithCategories().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Training State */
    val trainingDate = MutableStateFlow(LocalDate.now())
    val trainingCategory = MutableStateFlow<WorkoutCategoryEntity?>(null)
    
    // Pool of variations for the selected category
    val variationPool = trainingCategory.flatMapLatest { cat ->
        if (cat == null) flowOf(emptyList()) else dao.exercisesOf(cat.id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Active session for the selected date and category
    private val _activeSession = MutableStateFlow<SessionEntity?>(null)
    val activeSession: StateFlow<SessionEntity?> = _activeSession

    val activeSets = _activeSession.flatMapLatest { session ->
        if (session == null) flowOf(emptyList()) else dao.setsOf(session.id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _lastSets = MutableStateFlow<Map<Long, List<SetLogEntity>>>(emptyMap())
    val lastSets: StateFlow<Map<Long, List<SetLogEntity>>> = _lastSets

    /** UI actions */

    fun addCategory(name: String, color: String) = viewModelScope.launch {
        dao.insertCategory(WorkoutCategoryEntity(name = name, colorHex = color))
    }

    fun addExerciseVariation(catId: Long, name: String) = viewModelScope.launch {
        dao.insertExercise(ExerciseEntity(categoryId = catId, name = name))
    }

    fun selectTrainingState(date: LocalDate, category: WorkoutCategoryEntity) = viewModelScope.launch {
        trainingDate.value = date
        trainingCategory.value = category
        
        val dateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        var session = dao.getSession(dateMillis, category.id)
        if (session == null) {
            val id = dao.insertSession(SessionEntity(categoryId = category.id, date = dateMillis))
            session = SessionEntity(id = id, categoryId = category.id, date = dateMillis)
        }
        _activeSession.value = session
        
        // Preload last sets for the entire variation pool
        val pool = dao.exercisesOf(category.id).first()
        val map = mutableMapOf<Long, List<SetLogEntity>>()
        pool.forEach { ex -> map[ex.id] = dao.lastSetsFor(ex.id, session.id) }
        _lastSets.value = map
    }

    fun logSet(exerciseId: Long, reps: Int, weight: Double) = viewModelScope.launch {
        val session = _activeSession.value ?: return@launch
        val currentSets = activeSets.value.filter { it.exerciseId == exerciseId }
        dao.insertSet(
            SetLogEntity(
                sessionId = session.id, 
                exerciseId = exerciseId, 
                setNumber = currentSets.size + 1, 
                reps = reps, 
                weightKg = weight,
            )
        )
    }

    fun clearSets(exerciseId: Long) = viewModelScope.launch {
        val session = _activeSession.value ?: return@launch
        dao.clearSetsForExercise(session.id, exerciseId)
    }

    fun finishSession() {
        _activeSession.value = null
        trainingCategory.value = null
    }

    fun progressFor(exerciseId: Long): Flow<List<ExerciseProgressPoint>> = dao.progressFor(exerciseId)
}
