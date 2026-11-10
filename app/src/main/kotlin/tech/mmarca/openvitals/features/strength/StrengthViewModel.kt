package tech.mmarca.openvitals.features.strength

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.core.performance.LoadCoordinator
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.core.period.PeriodRangePreferenceKey
import tech.mmarca.openvitals.core.period.PeriodSelection
import tech.mmarca.openvitals.core.period.PeriodSelectionDriver
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.core.period.WeekPeriodMode
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.core.presentation.toScreenError
import tech.mmarca.openvitals.data.repository.contract.PeriodPreferences
import tech.mmarca.openvitals.data.repository.contract.StrengthImportSummary
import tech.mmarca.openvitals.data.repository.contract.StrengthRepository
import tech.mmarca.openvitals.navigation.selectedDayOrNull
import tech.mmarca.openvitals.domain.model.StrengthSession
import tech.mmarca.openvitals.domain.model.HevyCsvParser

/** The outcome of the last import, shown on the import card until the next one. */
sealed interface StrengthImportResult {
    data class Done(val summary: StrengthImportSummary) : StrengthImportResult
    data object NotHevy : StrengthImportResult
    data object Failed : StrengthImportResult
}

@Immutable
data class StrengthUiState(
    val isLoading: Boolean = true,
    val selectedRange: TimeRange = TimeRange.MONTH,
    val selectedDate: LocalDate = LocalDate.now(),
    val weekPeriodMode: WeekPeriodMode = WeekPeriodMode.MONDAY_TO_SUNDAY,
    val sessions: List<StrengthSession> = emptyList(),
    val importedWorkoutCount: Int = 0,
    val display: StrengthDisplayState = StrengthDisplayState(),
    val isImporting: Boolean = false,
    val importResult: StrengthImportResult? = null,
    val error: ScreenError? = null,
)

@HiltViewModel
class StrengthViewModel @Inject constructor(
    private val repository: StrengthRepository,
    private val periodPreferences: PeriodPreferences,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialRange = periodPreferences.timeRangeFor(PeriodRangePreferenceKey.STRENGTH)
    private val initialWeekPeriodMode = periodPreferences.weekPeriodMode
    private val periodDriver = PeriodSelectionDriver(
        initialRange = initialRange,
        initialDate = savedStateHandle.selectedDayOrNull() ?: LocalDate.now(),
        initialWeekPeriodMode = initialWeekPeriodMode,
        onRangeSelected = { range ->
            periodPreferences.setTimeRangeFor(PeriodRangePreferenceKey.STRENGTH, range)
        },
    )
    private val _uiState = MutableStateFlow(
        StrengthUiState(
            selectedRange = initialRange,
            selectedDate = periodDriver.selection.selectedDate,
            weekPeriodMode = initialWeekPeriodMode,
        ),
    )
    val uiState: StateFlow<StrengthUiState> = _uiState.asStateFlow()
    private val loadCoordinator = LoadCoordinator()

    init {
        observeWeekPeriodMode()
        load()
    }

    private fun observeWeekPeriodMode() {
        viewModelScope.launch {
            periodPreferences.weekPeriodModeFlow.drop(1).collect { mode ->
                periodDriver.weekPeriodMode = mode
                _uiState.value = _uiState.value.copy(weekPeriodMode = mode)
                if (_uiState.value.selectedRange == TimeRange.WEEK) {
                    load()
                }
            }
        }
    }

    fun selectRange(range: TimeRange) {
        applyPeriodSelection(periodDriver.selectRange(range))
        load()
    }

    fun previousPeriod() {
        applyPeriodSelection(periodDriver.previousPeriod())
        load()
    }

    fun nextPeriod() {
        periodDriver.nextPeriod()?.let { next ->
            applyPeriodSelection(next)
            load()
        }
    }

    fun selectDate(date: LocalDate) {
        applyPeriodSelection(periodDriver.selectDate(date))
        load()
    }

    fun selectDay(date: LocalDate) {
        applyPeriodSelection(periodDriver.selectDay(date))
        load()
    }

    fun resumeCurrentPeriod(refreshCurrent: Boolean = false) {
        val selection = periodDriver.resumeCurrentPeriod()
        if (selection == null) {
            if (refreshCurrent) load()
            return
        }
        applyPeriodSelection(selection)
        load()
    }

    fun selectExercise(exercise: String) {
        val state = _uiState.value
        if (exercise !in state.display.progressExercises) return
        _uiState.value = state.copy(
            display = StrengthPresentationMapper.withExercise(state.display, state.sessions, exercise),
        )
    }

    fun importHevyCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, importResult = null)
            val result = try {
                StrengthImportResult.Done(repository.importHevyCsv(uri))
            } catch (e: HevyCsvParser.FormatException) {
                StrengthImportResult.NotHevy
            } catch (e: Exception) {
                StrengthImportResult.Failed
            }
            _uiState.value = _uiState.value.copy(isImporting = false, importResult = result)
            if (result is StrengthImportResult.Done) load()
        }
    }

    fun load() {
        loadCoordinator.launch(viewModelScope) load@{
            val query = PeriodLoadQuery(
                range = periodDriver.selection.selectedRange,
                anchorDate = periodDriver.selection.selectedDate,
                weekPeriodMode = _uiState.value.weekPeriodMode,
            )
            val date = query.selectedDate
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.loadStrengthPeriod(query) }
                .onSuccess { data ->
                    if (!isCurrent) return@load
                    val display = withContext(dispatchers.default) {
                        StrengthPresentationMapper.build(
                            query = query,
                            sessions = data.sessions,
                            selectedExercise = _uiState.value.display.selectedExercise,
                        )
                    }
                    if (!isCurrent) return@load
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedDate = date,
                        sessions = data.sessions,
                        importedWorkoutCount = data.importedWorkoutCount,
                        display = display,
                    )
                }
                .onFailure { error ->
                    if (!isCurrent) return@load
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedDate = date,
                        error = error.toScreenError(),
                    )
                }
        }
    }

    private fun applyPeriodSelection(selection: PeriodSelection) {
        _uiState.value = _uiState.value.copy(
            selectedRange = selection.selectedRange,
            selectedDate = selection.selectedDate,
        )
    }
}
