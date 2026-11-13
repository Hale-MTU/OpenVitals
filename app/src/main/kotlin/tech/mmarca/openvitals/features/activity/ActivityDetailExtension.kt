package tech.mmarca.openvitals.features.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.mmarca.openvitals.domain.model.ExerciseData

/**
 * A card an optional integration adds to an activity's detail screen, under the export card.
 * The default build binds none (`di/ExtensionsModule`). The opt-in VertMatch build
 * (`app/src/vertmatch`) binds the card that sends the activity to VertMatch.
 */
internal interface ActivityDetailExtension {
    @Composable
    fun Card(workout: ExerciseData, series: ActivityWorkoutSeries, modifier: Modifier)
}
