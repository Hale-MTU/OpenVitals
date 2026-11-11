package tech.mmarca.openvitals.features.manualentry.activity

import java.time.LocalDate
import tech.mmarca.openvitals.domain.preferences.BiologicalSex
import tech.mmarca.openvitals.domain.preferences.BodyProfile

/** A complete body profile for tests that import or record a session and let it be estimated. */
internal val TestCalorieContext = CalorieEstimateContext(
    profile = BodyProfile(birthYear = 1990, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE),
    today = LocalDate.of(2026, 6, 1),
)
