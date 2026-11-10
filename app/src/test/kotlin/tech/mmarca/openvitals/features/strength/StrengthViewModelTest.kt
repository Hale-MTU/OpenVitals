package tech.mmarca.openvitals.features.strength

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.core.period.PeriodRangePreferenceKey
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.data.repository.contract.FakePreferences
import tech.mmarca.openvitals.data.repository.contract.StrengthRepository
import tech.mmarca.openvitals.domain.model.HevyCsvParser
import tech.mmarca.openvitals.domain.model.StrengthSession
import tech.mmarca.openvitals.domain.model.StrengthSessions
import tech.mmarca.openvitals.domain.query.StrengthPeriodData
import tech.mmarca.openvitals.util.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class StrengthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.now()

    /** The CSV's workouts, both moved to today so every period holds them, whatever the date. */
    private fun sessions(): List<StrengthSession> {
        val imported = HevyCsvParser.parse(StrengthTestData.CSV, Locale.ITALIAN).workouts
        val shifted = imported.map { workout ->
            val date = today
            workout.copy(
                start = LocalDateTime.of(date, workout.start.toLocalTime()),
                end = workout.end?.let { LocalDateTime.of(date, it.toLocalTime()) },
            )
        }
        return StrengthSessions.build(emptyList(), shifted)
    }

    private fun repo(data: StrengthPeriodData = StrengthPeriodData()) = mockk<StrengthRepository>().also { repo ->
        coEvery { repo.loadStrengthPeriod(any()) } returns data
    }

    private fun viewModel(repository: StrengthRepository, preferences: FakePreferences = FakePreferences()) =
        StrengthViewModel(
            repository = repository,
            periodPreferences = preferences,
            dispatchers = mainDispatcherRule.dispatcherProvider,
            savedStateHandle = SavedStateHandle(),
        )

    @Test fun `the month is the first range and an empty period has no data`() = runTest {
        val vm = viewModel(repo())

        assertEquals(TimeRange.MONTH, vm.uiState.value.selectedRange)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.display.hasData)
        assertNull(vm.uiState.value.error)
    }

    @Test fun `a load fills the stats, the calendar, the muscles and the progress`() = runTest {
        val vm = viewModel(repo(StrengthPeriodData(sessions = sessions(), importedWorkoutCount = 2)))

        val display = vm.uiState.value.display
        assertTrue(display.hasData)
        assertEquals(2, display.stats.sessions)
        assertEquals(1, display.stats.trainingDays)
        assertEquals(4, display.stats.sets)
        assertEquals(0, display.stats.restDays)
        assertEquals(1, display.chartValues.size)
        assertEquals(2, vm.uiState.value.importedWorkoutCount)
        assertEquals("Leg Extension (Macchina)", display.selectedExercise)
        assertEquals(1, display.progress.size)
        assertTrue(display.setsPerMuscle.getValue(MuscleGroup.CHEST) > 0)
    }

    @Test fun `choosing an exercise redraws its progress without a reload`() = runTest {
        val repository = repo(StrengthPeriodData(sessions = sessions()))
        val vm = viewModel(repository)

        vm.selectExercise("Panca Piana (Multipower)")

        assertEquals("Panca Piana (Multipower)", vm.uiState.value.display.selectedExercise)
        assertEquals(40 * (1 + 10 / 30.0), vm.uiState.value.display.progress.single().oneRepMaxKg, 0.01)
        coVerify(exactly = 1) { repository.loadStrengthPeriod(any()) }
    }

    @Test fun `the range change is remembered and reloads`() = runTest {
        val preferences = FakePreferences()
        val repository = repo()
        val vm = viewModel(repository, preferences)

        vm.selectRange(TimeRange.WEEK)
        advanceUntilIdle()

        assertEquals(TimeRange.WEEK, vm.uiState.value.selectedRange)
        assertEquals(TimeRange.WEEK, preferences.timeRangeFor(PeriodRangePreferenceKey.STRENGTH))
        coVerify(exactly = 2) { repository.loadStrengthPeriod(any()) }
    }

    @Test fun `a failure is a screen error, a missing permission its own kind`() = runTest {
        val repository = mockk<StrengthRepository>()
        coEvery { repository.loadStrengthPeriod(any()) } throws SecurityException("no read")

        val vm = viewModel(repository)

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.error is ScreenError.PermissionDenied)
    }

    @Test fun `a stale load cannot overwrite the newer one it lost to`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = mockk<StrengthRepository>()
        coEvery { repository.loadStrengthPeriod(any()) } coAnswers {
            val query = firstArg<PeriodLoadQuery>()
            if (query.range == TimeRange.MONTH) gate.await()
            StrengthPeriodData(importedWorkoutCount = if (query.range == TimeRange.MONTH) 99 else 1)
        }

        // The month load parks on the wire; the week selection supersedes it.
        val vm = viewModel(repository)
        vm.selectRange(TimeRange.WEEK)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(TimeRange.WEEK, vm.uiState.value.selectedRange)
        assertEquals(1, vm.uiState.value.importedWorkoutCount)
    }
}
