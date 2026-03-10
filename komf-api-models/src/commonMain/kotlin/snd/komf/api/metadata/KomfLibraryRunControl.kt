package snd.komf.api.metadata

import kotlinx.serialization.Serializable

@Serializable
enum class KomfLibraryRunResumeMode {
    CONTINUE,
    NEW,
}

@Serializable
enum class KomfLibraryApplyMode {
    CORE,
    CHAPTERS,
    FULL,
}

@Serializable
data class KomfLibraryRunCheckpoint(
    val pageNumber: Int,
    val startIndexInPage: Int,
    val dryRun: Boolean,
    val applyMode: KomfLibraryApplyMode,
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
