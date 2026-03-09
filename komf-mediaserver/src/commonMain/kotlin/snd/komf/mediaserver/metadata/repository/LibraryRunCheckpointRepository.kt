package snd.komf.mediaserver.metadata.repository

import snd.komf.mediaserver.metadata.LibraryRunCheckpoint
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.mediaserver.repository.LibraryRunCheckpointQueries

class LibraryRunCheckpointRepository(
    private val queries: LibraryRunCheckpointQueries
) {
    fun get(libraryId: MediaServerLibraryId): LibraryRunCheckpoint? {
        return queries.getForLibrary(libraryId.value).executeAsOneOrNull()?.toCheckpoint()
    }

    fun save(libraryId: MediaServerLibraryId, checkpoint: LibraryRunCheckpoint) {
        queries.upsert(
            libraryId = libraryId.value,
            pageNumber = checkpoint.pageNumber.toLong(),
            startIndexInPage = checkpoint.startIndexInPage.toLong(),
            dryRun = if (checkpoint.dryRun) 1L else 0L,
            lastSeriesId = checkpoint.lastCompletedSeriesId?.value,
            updatedAtEpochMs = checkpoint.updatedAtEpochMs
        )
    }

    fun delete(libraryId: MediaServerLibraryId) {
        queries.deleteForLibrary(libraryId.value)
    }

    private fun snd.komf.mediaserver.repository.LibraryRunCheckpoint.toCheckpoint() = LibraryRunCheckpoint(
        pageNumber = pageNumber.toInt(),
        startIndexInPage = startIndexInPage.toInt(),
        dryRun = dryRun != 0L,
        lastCompletedSeriesId = lastSeriesId?.let { MediaServerSeriesId(it) },
        updatedAtEpochMs = updatedAtEpochMs
    )
}

