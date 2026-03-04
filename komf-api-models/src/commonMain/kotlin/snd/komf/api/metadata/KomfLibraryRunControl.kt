package snd.komf.api.metadata

import kotlinx.serialization.Serializable

@Serializable
enum class KomfLibraryRunResumeMode {
    CONTINUE,
    NEW,
}

@Serializable
data class KomfLibraryRunCheckpoint(
    val pageNumber: Int,
    val startIndexInPage: Int,
    val dryRun: Boolean,
    val updatedAtEpochMs: Long,
)

@Serializable
data class KomfLibraryRunControlStatus(
    val active: Boolean,
    val paused: Boolean,
    val stopRequested: Boolean,
    val hasCheckpoint: Boolean,
    val checkpoint: KomfLibraryRunCheckpoint? = null,
)

