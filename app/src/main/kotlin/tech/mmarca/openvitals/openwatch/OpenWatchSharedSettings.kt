package tech.mmarca.openvitals.openwatch

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import tech.mmarca.openlink.OpenLinkSetting
import tech.mmarca.openlink.OpenLinkSettingKey
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openvitals.data.repository.contract.HydrationGoalPreferences
import tech.mmarca.openvitals.data.repository.contract.UnitPreferences
import tech.mmarca.openvitals.domain.preferences.UnitSystemPreference
import tech.mmarca.openvitals.features.hydration.reminders.HydrationReminderSettings

/**
 * What OpenVitals shares with OpenWatch as OpenLink settings (version 3, "Settings" in
 * openlink/docs/spec.md), each with the time it last changed:
 *
 * - the daily water goal, both ways, the later change winning, as the body profile is.
 *   OpenWatch shows it on a Garmin watch's hydration widget and a Wear OS watch, and a goal set
 *   there comes back here. Only a goal the user chose crosses, never the default.
 * - the units, from here only: OpenWatch's screens follow them while OpenVitals shares them, and
 *   OpenWatch's own choice waits until it no longer does. What OpenWatch holds is never taken.
 */
@Singleton
class OpenWatchSharedSettings internal constructor(
    private val prefs: SharedPreferences,
    private val goal: HydrationGoalPreferences,
    private val units: UnitPreferences,
    private val reminders: HydrationReminderSettings,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        goal: HydrationGoalPreferences,
        units: UnitPreferences,
        reminders: HydrationReminderSettings,
    ) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), goal, units, reminders, System::currentTimeMillis)

    private val water = stamp(WATER)

    // OpenVitals decides the units: the first choice it ever offers is stamped now, so it wins
    // over one another app left in OpenWatch, also after a reinstall here.
    private val unitSystem = stamp(UNITS, firstSightNow = true)

    /**
     * What to send OpenWatch: each setting as it stands when collected, and each change, stamped
     * now. OpenWatch keeps one only if it is newer, so offering it on every start is harmless and
     * a send queued before the link was allowed does not outlive the process.
     */
    val offers: Flow<OpenLinkSettings> = merge(
        goal.hydrationGoalChoiceLitersFlow.mapNotNull { offer(water, waterValues(it)) },
        units.unitSystemPreferenceFlow.mapNotNull { offer(unitSystem, listOf(unitsValue(it))) },
    )

    /** All of OpenWatch's settings, as it publishes them: a newer water goal replaces this app's. */
    fun onHostSettings(settings: OpenLinkSettings) {
        val remote = settings.entries[WATER.name] ?: return
        val adopt = synchronized(water) { water.onRemote(remote).also { save(water) } } ?: return
        val milliliters = adopt.singleOrNull()
        if (milliliters == null) goal.resetHydrationDailyGoal() else goal.hydrationDailyGoalLiters = milliliters / ML_PER_LITER
        // The reminder text quotes the goal, so the alarm is re-planned, as after a change here.
        reminders.applyStoredConfig()
    }

    private fun offer(stamp: SettingStamp, values: List<Double>): OpenLinkSettings? {
        val setting = synchronized(stamp) { (stamp.onLocal(values, clock()) ?: stamp.current(values)).also { save(stamp) } }
        return setting?.let { OpenLinkSettings().with(stamp.key, it.values, it.updatedAtMillis) }
    }

    private fun stamp(key: OpenLinkSettingKey, firstSightNow: Boolean = false) = SettingStamp(
        key,
        firstSightNow,
        prefs.getLong(key.name + AT_SUFFIX, 0L),
        prefs.getString(key.name + VALUES_SUFFIX, null)?.let(::decodeValues),
    )

    private fun save(stamp: SettingStamp) {
        prefs.edit {
            putLong(stamp.key.name + AT_SUFFIX, stamp.updatedAtMillis)
            val values = stamp.values
            if (values == null) remove(stamp.key.name + VALUES_SUFFIX) else putString(stamp.key.name + VALUES_SUFFIX, values.joinToString(","))
        }
    }

    private companion object {
        val WATER = OpenLinkSettingKeys.GOAL_WATER_ML
        val UNITS = OpenLinkSettingKeys.UNITS_SYSTEM
        const val ML_PER_LITER = 1_000.0

        /** OpenWatchLink's file: the link's own state, kept together. */
        const val PREFS = "openwatch_link"
        const val AT_SUFFIX = ".updated_at"
        const val VALUES_SUFFIX = ".values"

        /** The goal in whole milliliters, as the key takes it; empty for none chosen. */
        fun waterValues(liters: Double?): List<Double> =
            listOfNotNull(liters?.let { (it * ML_PER_LITER).roundToInt().toDouble() })

        fun unitsValue(preference: UnitSystemPreference): Double = when (preference) {
            UnitSystemPreference.SYSTEM -> OpenLinkSettingKeys.UNITS_BY_REGION
            UnitSystemPreference.METRIC -> OpenLinkSettingKeys.UNITS_METRIC
            UnitSystemPreference.IMPERIAL -> OpenLinkSettingKeys.UNITS_IMPERIAL
        }

        fun decodeValues(raw: String): List<Double>? =
            raw.split(',').filter { it.isNotEmpty() }.map { it.toDoubleOrNull() ?: return null }
    }
}

/**
 * One shared setting's side of the link, the later change winning: the time of this side's
 * last change and the values it belongs to, as [ProfileStamp] keeps the profile's. Empty values:
 * nothing chosen, or cleared. Pure, so the rules are tested on the JVM.
 */
internal class SettingStamp(
    val key: OpenLinkSettingKey,
    /** This side decides the setting: its first value is stamped now, not as older than the link. */
    private val firstSightNow: Boolean = false,
    var updatedAtMillis: Long = 0L,
    /** The values [updatedAtMillis] belongs to; null before the first local value was seen. */
    var values: List<Double>? = null,
) {
    /**
     * A local value: a change if it is not the one stamped. Returns what to send, or null. The
     * first value ever seen predates the link: stamped 1 (0, never sent, if nothing is chosen),
     * so a choice made since on either side beats it, unless this side decides. A change is
     * stamped now, and later than the time it replaces, so it wins even over a time from a
     * clock that ran ahead.
     */
    fun onLocal(values: List<Double>, now: Long): OpenLinkSetting? {
        if (values == this.values) return null
        updatedAtMillis = when {
            this.values != null -> maxOf(now, updatedAtMillis + 1)
            values.isEmpty() -> 0L
            firstSightNow -> now
            else -> 1L
        }
        this.values = values
        return current(values)
    }

    /** [values] as the other side should hold them, or null for nothing this side has stamped. */
    fun current(values: List<Double>): OpenLinkSetting? =
        OpenLinkSetting(values, updatedAtMillis).takeIf { updatedAtMillis > 0 && values == this.values }

    /** The other side's setting: the values to adopt if it is newer, else null. */
    fun onRemote(setting: OpenLinkSetting): List<Double>? {
        if (setting.updatedAtMillis <= updatedAtMillis) return null
        updatedAtMillis = setting.updatedAtMillis
        values = setting.values
        return setting.values
    }
}
