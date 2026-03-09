package snd.komf.mediaserver.metadata.repository

import snd.komf.mediaserver.metadata.LibrarySeriesRunStatus
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.mediaserver.repository.LibrarySeriesRunResultQueries

data class LibrarySeriesRunResultEntry(
    val seriesId: MediaServerSeriesId,
    val desiredHash: String,
    val status: LibrarySeriesRunStatus,
    val updatedAtEpochMs: Long,
)

class LibrarySeriesRunResultRepository(
    private val queries: LibrarySeriesRunResultQueries
) {
    fun get(libraryId: MediaServerLibraryId, seriesId: MediaServerSeriesId): LibrarySeriesRunResultEntry? {
        return queries.getForSeries(libraryId.value, seriesId.value).executeAsOneOrNull()?.toEntry()
    }

    fun save(
        libraryId: MediaServerLibraryId,
        seriesId: MediaServerSeriesId,
        desiredHash: String,
        status: LibrarySeriesRunStatus,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        queries.upsert(
            libraryId = libraryId.value,
            seriesId = seriesId.value,
            desiredHash = desiredHash,
            status = status.name,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }

    fun deleteForLibrary(libraryId: MediaServerLibraryId) {
        queries.deleteForLibrary(libraryId.value)
    }

    private fun snd.komf.mediaserver.repository.LibrarySeriesRunResult.toEntry() = LibrarySeriesRunResultEntry(
        seriesId = MediaServerSeriesId(seriesId),
        desiredHash = desiredHash,
        status = LibrarySeriesRunStatus.valueOf(status),
        updatedAtEpochMs = updatedAtEpochMs
    )
}

