package tech.mmarca.openvitals.openwatch

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkSetting
import tech.mmarca.openlink.OpenLinkSettingKey
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openvitals.data.repository.HydrationGoalStore
import tech.mmarca.openvitals.data.repository.contract.FakePreferences
import tech.mmarca.openvitals.domain.preferences.UnitSystem
import tech.mmarca.openvitals.domain.preferences.UnitSystemPreference
import tech.mmarca.openvitals.features.hydration.reminders.FakeHydrationReminderSettings
import tech.mmarca.openvitals.util.FakeSharedPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class OpenWatchSharedSettingsTest {

    private val linkPrefs = FakeSharedPreferences()
    private val goal = HydrationGoalStore(FakeSharedPreferences())
    private val units = FakePreferences(unitSystem = UnitSystem.METRIC)
    private val reminders = FakeHydrationReminderSettings()
    private var now = 1_000_000L

    private fun goals() = OpenWatchSharedSettings(linkPrefs, goal, units, reminders) { now }

    /** The settings of [key] that [goals] sends, as they go. */
    private fun TestScope.sent(goals: OpenWatchSharedSettings, key: OpenLinkSettingKey = WATER): List<OpenLinkSetting> {
        val sent = mutableListOf<OpenLinkSetting>()
        backgroundScope.launch { goals.offers.collect { offer -> offer.entries[key.name]?.let { sent += it } } }
        runCurrent()
        return sent
    }

    private fun fromOpenWatch(milliliters: Double?, at: Long) =
        OpenLinkSettings().with(WATER, listOfNotNull(milliliters), at)

    @Test
    fun `a goal the user chooses goes to OpenWatch in milliliters, the default never does`() = runTest {
        val sent = sent(goals())
        assertTrue(sent.isEmpty())

        goal.hydrationDailyGoalLiters = 2.5
        runCurrent()

        assertEquals(listOf(OpenLinkSetting(2_500.0, now)), sent)
    }

    @Test
    fun `a goal chosen before the link predates it, so a goal set in OpenWatch wins`() = runTest {
        goal.hydrationDailyGoalLiters = 3.0
        val goals = goals()
        val sent = sent(goals)
        assertEquals(listOf(OpenLinkSetting(3_000.0, 1)), sent)

        goals.onHostSettings(fromOpenWatch(2_200.0, at = 5))
        runCurrent()

        assertEquals(2.2, goal.hydrationDailyGoalLiters, 1e-6)
        assertEquals(1, reminders.appliedFromStore)
        // Its echo keeps OpenWatch's time: nothing new to win with.
        assertEquals(OpenLinkSetting(2_200.0, 5), sent.last())
    }

    @Test
    fun `an older goal from OpenWatch changes nothing`() = runTest {
        val goals = goals()
        sent(goals)
        goal.hydrationDailyGoalLiters = 2.5
        runCurrent()

        goals.onHostSettings(fromOpenWatch(1_800.0, at = now - 1))

        assertEquals(2.5, goal.hydrationDailyGoalLiters, 1e-6)
        assertEquals(0, reminders.appliedFromStore)
    }

    @Test
    fun `a goal cleared in OpenWatch brings back the default`() = runTest {
        goal.hydrationDailyGoalLiters = 3.0
        val goals = goals()
        sent(goals)

        goals.onHostSettings(fromOpenWatch(null, at = now))

        assertNull(goal.hydrationGoalChoiceLitersFlow.value)
        assertEquals(HydrationGoalStore.DEFAULT_LITERS, goal.hydrationDailyGoalLiters, 1e-9)
    }

    @Test
    fun `a goal below the app's floor is raised to it and sent back as the later change`() = runTest {
        val goals = goals()
        val sent = sent(goals)

        goals.onHostSettings(fromOpenWatch(150.0, at = now + 10))
        runCurrent()

        assertEquals(0.25, goal.hydrationDailyGoalLiters, 1e-6)
        assertEquals(OpenLinkSetting(250.0, now + 11), sent.last())
    }

    @Test
    fun `the goal's time outlives a restart`() = runTest {
        val sentBefore = sent(goals())
        goal.hydrationDailyGoalLiters = 2.5
        runCurrent()
        val chosenAt = sentBefore.last().updatedAtMillis
        now += 60_000

        val restarted = goals()
        val sent = sent(restarted)
        restarted.onHostSettings(fromOpenWatch(1_800.0, at = chosenAt))

        assertEquals(listOf(OpenLinkSetting(2_500.0, chosenAt)), sent)
        assertEquals(2.5, goal.hydrationDailyGoalLiters, 1e-6)
    }

    @Test
    fun `the units go to OpenWatch as they are chosen, and the first choice wins as of now`() = runTest {
        val sent = sent(goals(), UNITS)

        units.unitSystemPreferenceFlow.value = UnitSystemPreference.IMPERIAL
        runCurrent()

        assertEquals(
            listOf(
                OpenLinkSetting(OpenLinkSettingKeys.UNITS_METRIC, now),
                OpenLinkSetting(OpenLinkSettingKeys.UNITS_IMPERIAL, now + 1),
            ),
            sent,
        )
    }

    @Test
    fun `units OpenWatch holds are never taken`() = runTest {
        val goals = goals()
        sent(goals, UNITS)

        goals.onHostSettings(OpenLinkSettings().with(UNITS, listOf(OpenLinkSettingKeys.UNITS_IMPERIAL), now + 10))

        assertEquals(UnitSystemPreference.METRIC, units.unitSystemPreferenceFlow.value)
    }

    private companion object {
        val WATER = OpenLinkSettingKeys.GOAL_WATER_ML
        val UNITS = OpenLinkSettingKeys.UNITS_SYSTEM
    }
}
