package snd.komf.api.metadata

import kotlinx.serialization.Serializable
import snd.komf.api.KomfServerLibraryId
import snd.komf.api.KomfServerSeriesId

@Serializable
data class KomfLibraryRunSummary(
    val libraryId: KomfServerLibraryId,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val dryRun: Boolean,
    val totalSeries: Int,
    val processedSeries: Int,
    val updatedSeries: Int,
    val skippedSeries: Int,
    val unmatchedSeries: Int,
    val providerErrors: Int,
    val processingErrors: Int,
    val unexpectedErrors: Int,
    val skippedSeriesIds: Collection<KomfServerSeriesId>,
)
