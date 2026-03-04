package snd.komf.api.metadata

import kotlinx.serialization.Serializable
import snd.komf.api.KomfServerSeriesId
import snd.komf.api.job.KomfMetadataJobId

@Serializable
data class KomfSkippedSeriesEntry(
    val oldSeriesId: KomfServerSeriesId,
    val hintedName: String? = null,
    val hintedSortName: String? = null,
    val reason: String,
    val observedAtEpochMs: Long,
)

@Serializable
data class KomfRetrySkippedSeriesResponse(
    val totalSkipped: Int,
    val resolved: Int,
    val retried: Int,
    val unresolved: Int,
    val unresolvedSeriesIds: Collection<KomfServerSeriesId>,
    val retryJobIds: Collection<KomfMetadataJobId>,
)

@Serializable
data class KomfClearSkippedSeriesResponse(
    val cleared: Int,
)
