package tech.mmarca.openvitals.features.strength

import androidx.annotation.StringRes
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.domain.model.StrengthSession

/** The muscle groups of the body map. */
enum class MuscleGroup(@param:StringRes val labelRes: Int) {
    CHEST(R.string.strength_muscle_chest),
    SHOULDERS(R.string.strength_muscle_shoulders),
    BICEPS(R.string.strength_muscle_biceps),
    TRICEPS(R.string.strength_muscle_triceps),
    FOREARMS(R.string.strength_muscle_forearms),
    ABS(R.string.strength_muscle_abs),
    OBLIQUES(R.string.strength_muscle_obliques),
    TRAPS(R.string.strength_muscle_traps),
    LATS(R.string.strength_muscle_lats),
    LOWER_BACK(R.string.strength_muscle_lower_back),
    GLUTES(R.string.strength_muscle_glutes),
    QUADS(R.string.strength_muscle_quads),
    HAMSTRINGS(R.string.strength_muscle_hamstrings),
    ADDUCTORS(R.string.strength_muscle_adductors),
    CALVES(R.string.strength_muscle_calves),
}

/**
 * Assigns an exercise to muscle groups by its name, as the training app spells it. The
 * patterns know English, Italian, German, Spanish and French names; an exercise none of
 * them matches counts for no muscle, and the card says so.
 */
object StrengthMuscles {

    private class Rule(val pattern: Regex, val primary: List<MuscleGroup>, val secondary: List<MuscleGroup>)

    private fun rule(pattern: String, primary: List<MuscleGroup>, secondary: List<MuscleGroup> = emptyList()) =
        Rule(Regex(pattern, RegexOption.IGNORE_CASE), primary, secondary)

    private fun g(vararg groups: MuscleGroup) = groups.toList()

    /** First match wins, so the specific names come before the generic ones ("leg curl" before "curl"). */
    private val rules = listOf(
        rule("leg ?curl|femoral|hamstring|rumeno|romanian|rumän|rumano|roumain|good ?morning|beinbeuger|curl femoral|ischio", g(MuscleGroup.HAMSTRINGS)),
        rule("leg ?ext|beinstreck|extensi[oó]n de (pierna|cu[aá]driceps)|leg extension", g(MuscleGroup.QUADS)),
        rule("leg ?press|squat|hack|affond|lunge|step ?up|bulgar|kniebeuge|beinpresse|sentadilla|zancada|prensa|fente|presse à cuisses", g(MuscleGroup.QUADS, MuscleGroup.GLUTES), g(MuscleGroup.HAMSTRINGS, MuscleGroup.ADDUCTORS)),
        rule("stacco|deadlift|kreuzheben|peso muerto|soulevé de terre", g(MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.LOWER_BACK), g(MuscleGroup.TRAPS, MuscleGroup.FOREARMS)),
        rule("abduz|abduct|abdukt|abducc|hip thrust|ponte glutei|glute|gesäß|gl[uú]teo|fessier|kickback", g(MuscleGroup.GLUTES)),
        rule("adduz|adduct|addukt|aducc|adducteur", g(MuscleGroup.ADDUCTORS)),
        rule("calf|polpacc|wade|gemelo|pantorrilla|mollet", g(MuscleGroup.CALVES)),
        rule("iperesten|hyperext|lombar|back ext|rückenstreck|lumbar|lombaire", g(MuscleGroup.LOWER_BACK), g(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS)),
        rule("russian twist|obliqu|side bend|woodchop|seitbeug", g(MuscleGroup.OBLIQUES), g(MuscleGroup.ABS)),
        rule("crunch|sit ?up|knee raise|leg raise|plank|addomin|ab wheel|bauch|abdominal|abdominau|gainage|beinheben", g(MuscleGroup.ABS), g(MuscleGroup.OBLIQUES)),
        rule("tricip|tr[ií]cep|trizep|push ?down|french|skull|extensi[oó]n de tr", g(MuscleGroup.TRICEPS)),
        rule("curl|bicip|b[ií]cep|bizep|martello|hammer", g(MuscleGroup.BICEPS), g(MuscleGroup.FOREARMS)),
        rule("croci inverse|deltoide post|rear delt|reverse fly|face ?pull|hinter(e|er)? schulter|deltoide posterior|oiseau", g(MuscleGroup.SHOULDERS), g(MuscleGroup.TRAPS)),
        rule("trazion|pull ?up|chin ?up|lat ?pull|pulldown|lat machine|klimmz|latzug|dominada|jal[oó]n|traction|tirage vertical", g(MuscleGroup.LATS), g(MuscleGroup.BICEPS)),
        rule("rematore|\\brow\\b|rows?\\b|pulley|rudern|remo\\b|tirage horizontal|rowing", g(MuscleGroup.LATS, MuscleGroup.TRAPS), g(MuscleGroup.BICEPS, MuscleGroup.SHOULDERS)),
        rule("scrollat|shrug|schulterheben|encogimiento|haussement", g(MuscleGroup.TRAPS)),
        rule("military|shoulder press|lento|alzate|lateral raise|arnold|overhead|spalle|schulterdr|seitheben|press de hombro|elevaci[oó]n lateral|développé (militaire|épaules)|élévation latérale", g(MuscleGroup.SHOULDERS), g(MuscleGroup.TRICEPS)),
        rule("panca|bench|chest press|piegament|push ?up|flessioni|\\bdip|bankdr|liegest|press de banca|flexion|fondos|développé couché|pompes", g(MuscleGroup.CHEST), g(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)),
        rule("butterfly|pec deck|croci|\\bfly|crossover|fliegende|aperturas|cruce de poleas|écarté", g(MuscleGroup.CHEST), g(MuscleGroup.SHOULDERS)),
    )

    class Muscles(val primary: List<MuscleGroup>, val secondary: List<MuscleGroup>)

    fun musclesOf(exerciseName: String): Muscles? =
        rules.firstOrNull { it.pattern.containsMatchIn(exerciseName) }?.let { Muscles(it.primary, it.secondary) }

    /**
     * Sets per muscle group over [sessions]: a primary muscle counts each set, a secondary one
     * half a set. Cardio rows and sets without reps or load are left out.
     */
    fun setsPerMuscle(sessions: List<StrengthSession>): Map<MuscleGroup, Double> {
        val totals = mutableMapOf<MuscleGroup, Double>()
        sessions.forEach { session ->
            session.exercises.forEach { exercise ->
                val muscles = musclesOf(exercise.name) ?: return@forEach
                val sets = exercise.workingSetCount
                if (sets == 0) return@forEach
                muscles.primary.forEach { totals[it] = (totals[it] ?: 0.0) + sets }
                muscles.secondary.forEach { totals[it] = (totals[it] ?: 0.0) + sets / 2.0 }
            }
        }
        return totals
    }

    /** Which group colours each outline of the figure; head and knees stay body-coloured. */
    internal val outlineGroups: Map<String, MuscleGroup> = mapOf(
        "CHEST" to MuscleGroup.CHEST,
        "OBLIQUES" to MuscleGroup.OBLIQUES,
        "ABS" to MuscleGroup.ABS,
        "BICEPS" to MuscleGroup.BICEPS,
        "TRICEPS" to MuscleGroup.TRICEPS,
        "NECK" to MuscleGroup.TRAPS,
        "TRAPEZIUS" to MuscleGroup.TRAPS,
        "FRONT_DELTOIDS" to MuscleGroup.SHOULDERS,
        "BACK_DELTOIDS" to MuscleGroup.SHOULDERS,
        "UPPER_BACK" to MuscleGroup.LATS,
        "LOWER_BACK" to MuscleGroup.LOWER_BACK,
        "FOREARM" to MuscleGroup.FOREARMS,
        "GLUTEAL" to MuscleGroup.GLUTES,
        "ABDUCTORS" to MuscleGroup.ADDUCTORS,
        "ADDUCTOR" to MuscleGroup.ADDUCTORS,
        "QUADRICEPS" to MuscleGroup.QUADS,
        "HAMSTRING" to MuscleGroup.HAMSTRINGS,
        "CALVES" to MuscleGroup.CALVES,
        "LEFT_SOLEUS" to MuscleGroup.CALVES,
        "RIGHT_SOLEUS" to MuscleGroup.CALVES,
    )
}
