package tech.mmarca.openvitals.domain.insights

import java.time.LocalDate
import tech.mmarca.openvitals.domain.preferences.BiologicalSex
import tech.mmarca.openvitals.domain.preferences.BodyProfile

/** A body profile field the workout calorie estimate cannot do without. */
enum class WorkoutEnergyInput { WEIGHT }

/** How a workout's calories were estimated. */
enum class WorkoutEnergyMethod {
    /** Keytel et al. 2005, from the session's mean heart rate. */
    HEART_RATE,

    /** The activity's MET value in the 2024 Adult Compendium. */
    COMPENDIUM_MET,
}

sealed interface WorkoutEnergyResult {
    data class Estimate(
        val activeKcal: Double,
        val totalKcal: Double,
        val method: WorkoutEnergyMethod,
    ) : WorkoutEnergyResult

    /** The profile lacks a field the estimate needs: say which, never guess it. */
    data class MissingInputs(val inputs: Set<WorkoutEnergyInput>) : WorkoutEnergyResult

    /** No sourced energy cost for this activity, or no usable duration. */
    data object Unavailable : WorkoutEnergyResult
}

/**
 * The calories a workout burned, estimated, for a session that recorded none.
 *
 * Gross energy comes from the heart rate when the session has one in the range
 * Keytel's equation was derived on and the profile has sex and age; otherwise
 * from the activity's Compendium MET. The resting share for the same time is
 * the wearer's own basal metabolic rate (Mifflin-St Jeor) when the profile has
 * every field it needs, else 1 MET; active = gross − resting.
 *
 * Without a weight there is no estimate: [WorkoutEnergyResult.MissingInputs].
 */
fun estimateWorkoutEnergy(
    exerciseType: Int,
    durationMinutes: Double,
    meanHeartRateBpm: Double?,
    profile: BodyProfile,
    today: LocalDate,
): WorkoutEnergyResult {
    if (!(durationMinutes > 0.0) || !durationMinutes.isFinite()) return WorkoutEnergyResult.Unavailable
    val weightKg = profile.weightKg ?: return WorkoutEnergyResult.MissingInputs(setOf(WorkoutEnergyInput.WEIGHT))
    val restingKcal = restingKcal(profile, weightKg, durationMinutes, today)

    val heartRateGross = meanHeartRateBpm?.let { heartRate ->
        keytelGrossKcal(profile, weightKg, heartRate, durationMinutes, today)
    }
    val (grossKcal, method) = when {
        heartRateGross != null -> heartRateGross to WorkoutEnergyMethod.HEART_RATE
        else -> {
            val met = CompendiumMet.forExerciseType(exerciseType)?.met ?: return WorkoutEnergyResult.Unavailable
            CompendiumMet.grossKcal(met, weightKg, durationMinutes) to WorkoutEnergyMethod.COMPENDIUM_MET
        }
    }
    val activeKcal = (grossKcal - restingKcal).coerceAtLeast(0.0)
    if (activeKcal <= 0.0) return WorkoutEnergyResult.Unavailable
    return WorkoutEnergyResult.Estimate(
        activeKcal = activeKcal,
        totalKcal = activeKcal + restingKcal,
        method = method,
    )
}

private fun restingKcal(profile: BodyProfile, weightKg: Double, durationMinutes: Double, today: LocalDate): Double =
    profile.basalMetabolicRateKcal(today)?.let { it * durationMinutes / MinutesPerDay }
        ?: CompendiumMet.grossKcal(met = 1.0, weightKg = weightKg, durationMinutes = durationMinutes)

private fun keytelGrossKcal(
    profile: BodyProfile,
    weightKg: Double,
    meanHeartRateBpm: Double,
    durationMinutes: Double,
    today: LocalDate,
): Double? {
    val sex = profile.sex ?: return null
    val age = profile.ageYears(today) ?: return null
    val maxHeartRate = profile.maxHeartRateBpm?.toDouble() ?: ageMaxHeartRate(age, sex).toDouble()
    if (meanHeartRateBpm < KeytelHeartRate.MinFractionOfMax * maxHeartRate) return null
    if (meanHeartRateBpm > KeytelHeartRate.MaxFractionOfMax * maxHeartRate) return null
    val kjPerMinute = KeytelHeartRate.kilojoulesPerMinute(sex, meanHeartRateBpm, weightKg, age)
        .takeIf { it > 0.0 } ?: return null
    return kjPerMinute / KilojoulesPerKilocalorie * durationMinutes
}

/**
 * Keytel et al., "Prediction of energy expenditure from heart rate monitoring
 * during submaximal exercise", J Sports Sci 23(3):289–297 (2005),
 * doi:10.1080/02640410470001730089. Coefficients read in Keytel's PhD thesis
 * (University of Cape Town, 2004), chapter 4, the model without VO2max:
 *
 * EE (kJ/min) = gender × (−55.0969 + 0.6309 HR + 0.1988 weight + 0.2017 age)
 *             + (1 − gender) × (−20.4022 + 0.4472 HR − 0.1263 weight + 0.074 age),
 * gender = 1 for men, 0 for women. Gross energy expenditure, rest included.
 *
 * Derived on 115 adults aged 19 to 45 at steady-state treadmill or cycle work
 * of 57 to 90 % of their maximum heart rate. Outside that heart rate range the
 * estimate is not used: this project's own rule, not the paper's.
 */
object KeytelHeartRate {
    const val MinFractionOfMax = 0.57
    const val MaxFractionOfMax = 0.90

    fun kilojoulesPerMinute(sex: BiologicalSex, heartRateBpm: Double, weightKg: Double, ageYears: Int): Double =
        when (sex) {
            BiologicalSex.MALE -> -55.0969 + 0.6309 * heartRateBpm + 0.1988 * weightKg + 0.2017 * ageYears
            BiologicalSex.FEMALE -> -20.4022 + 0.4472 * heartRateBpm - 0.1263 * weightKg + 0.074 * ageYears
        }
}

private const val MinutesPerDay = 24.0 * 60.0
private const val KilojoulesPerKilocalorie = 4.184
