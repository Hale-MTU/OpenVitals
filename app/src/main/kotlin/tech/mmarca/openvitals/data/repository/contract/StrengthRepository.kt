package tech.mmarca.openvitals.data.repository.contract

import android.net.Uri
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.domain.query.StrengthPeriodData

/** What a training app's export added: the workouts and sets it holds, and how many were new here. */
data class StrengthImportSummary(
    val workouts: Int,
    val sets: Int,
    val added: Int,
)

interface StrengthRepository {
    /** The period's strength sessions: Health Connect's, joined with the imported sets. */
    suspend fun loadStrengthPeriod(query: PeriodLoadQuery): StrengthPeriodData

    /**
     * Reads a Hevy workout export and keeps its sets. Throws
     * [tech.mmarca.openvitals.domain.model.HevyCsvParser.FormatException] when the file
     * is not one, and whatever reading the file throws.
     */
    suspend fun importHevyCsv(uri: Uri): StrengthImportSummary
}
