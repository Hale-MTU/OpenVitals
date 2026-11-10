package tech.mmarca.openvitals.features.strength

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openvitals.domain.model.HevyCsvParser
import tech.mmarca.openvitals.domain.model.StrengthSessions

/** Two workouts as Hevy exports them, the same as the domain module's parser test uses. */
object StrengthTestData {
    val CSV = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Panca Piana (Multipower)",,"",0,"normal",35,12,,,
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Panca Piana (Multipower)",,"",1,"normal",40,10,,,
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Trazione Supina (Assistita)",,"",0,"normal",35,10,,,
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Tapis Roulant",,"",0,"normal",,,1.5,911,
        "Gambe","Oct 28, 2026, 7:57 PM","Oct 28, 2026, 8:52 PM","","Leg Extension (Macchina)",,"",0,"warmup",63,12,,,
    """.trimIndent()
}

class StrengthMusclesTest {

    @Test
    fun `matches exercise names in several languages`() {
        fun primary(name: String) = StrengthMuscles.musclesOf(name)?.primary
        assertEquals(listOf(MuscleGroup.HAMSTRINGS), primary("Leg Curl Seduto (Macchina)"))
        assertEquals(listOf(MuscleGroup.BICEPS), primary("Bicipiti Martello (Cavo)"))
        assertEquals(listOf(MuscleGroup.TRICEPS), primary("Dip Tricipiti (Assistito)"))
        assertEquals(listOf(MuscleGroup.SHOULDERS), primary("Croci Inverse Deltoide Posteriore (Macchina)"))
        assertEquals(listOf(MuscleGroup.CHEST), primary("Butterfly (Pec Deck)"))
        assertEquals(listOf(MuscleGroup.LATS), primary("Lat Pulldown Braccia Dritte (Cavo)"))
        assertEquals(listOf(MuscleGroup.OBLIQUES), primary("Russian Twist (Con Peso Aggiunto)"))
        assertEquals(listOf(MuscleGroup.ADDUCTORS), primary("Adduzione Anche (Macchina)"))
        assertEquals(listOf(MuscleGroup.GLUTES), primary("Abduzione Anche (Macchina)"))
        assertEquals(listOf(MuscleGroup.CHEST), primary("Bench Press (Barbell)"))
        assertEquals(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), primary("Kniebeuge (Langhantel)"))
        assertEquals(listOf(MuscleGroup.CHEST), primary("Press de banca"))
        assertEquals(listOf(MuscleGroup.LATS), primary("Tirage vertical"))
        assertEquals(listOf(MuscleGroup.CALVES), primary("Wadenheben"))
        assertNull(StrengthMuscles.musclesOf("Tapis Roulant"))
        assertNull(StrengthMuscles.musclesOf("Vogatore"))
    }

    @Test
    fun `counts a primary muscle a set and a secondary one half, cardio none`() {
        val sessions = StrengthSessions.build(emptyList(), HevyCsvParser.parse(StrengthTestData.CSV, Locale.ITALIAN).workouts, ZoneId.of("Europe/Zurich"))
        val sets = StrengthMuscles.setsPerMuscle(sessions.filter { it.date == LocalDate.of(2026, 11, 2) })

        // Two bench sets: chest 2, triceps and shoulders half each; the assisted pull-up counts for the lats.
        assertEquals(2.0, sets.getValue(MuscleGroup.CHEST), 0.01)
        assertEquals(1.0, sets.getValue(MuscleGroup.TRICEPS), 0.01)
        assertEquals(1.0, sets.getValue(MuscleGroup.LATS), 0.01)
        assertNull(sets[MuscleGroup.QUADS])
    }
}
