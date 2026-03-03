package snd.komf.mediaserver

import snd.komf.mediaserver.model.MediaServerBook
import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerBookMetadataUpdate
import snd.komf.mediaserver.model.MediaServerBookThumbnail
import snd.komf.mediaserver.model.MediaServerLibrary
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeries
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.mediaserver.model.MediaServerSeriesMetadataUpdate
import snd.komf.mediaserver.model.MediaServerSeriesThumbnail
import snd.komf.mediaserver.model.MediaServerThumbnailId
import snd.komf.mediaserver.model.Page
import snd.komf.model.Image

class DisabledMediaServerClient(
    private val mediaServerName: String,
    private val reason: String
) : MediaServerClient {
    private fun disabled(): Nothing = error("$mediaServerName media server is disabled: $reason")

    override suspend fun getSeries(seriesId: MediaServerSeriesId): MediaServerSeries = disabled()
    override suspend fun getSeries(libraryId: MediaServerLibraryId, pageNumber: Int): Page<MediaServerSeries> = disabled()
    override suspend fun getSeriesThumbnail(seriesId: MediaServerSeriesId): Image? = disabled()
    override suspend fun getSeriesThumbnails(seriesId: MediaServerSeriesId): Collection<MediaServerSeriesThumbnail> = disabled()
    override suspend fun getBook(bookId: MediaServerBookId): MediaServerBook = disabled()
    override suspend fun getBooks(seriesId: MediaServerSeriesId): Collection<MediaServerBook> = disabled()
    override suspend fun getBookThumbnails(bookId: MediaServerBookId): Collection<MediaServerBookThumbnail> = disabled()
    override suspend fun getBookThumbnail(bookId: MediaServerBookId): Image? = disabled()
    override suspend fun getLibrary(libraryId: MediaServerLibraryId): MediaServerLibrary = disabled()
    override suspend fun getLibraries(): List<MediaServerLibrary> = disabled()
    override suspend fun updateSeriesMetadata(seriesId: MediaServerSeriesId, metadata: MediaServerSeriesMetadataUpdate) = disabled()
    override suspend fun deleteSeriesThumbnail(seriesId: MediaServerSeriesId, thumbnailId: MediaServerThumbnailId) = disabled()
    override suspend fun updateBookMetadata(bookId: MediaServerBookId, metadata: MediaServerBookMetadataUpdate) = disabled()
    override suspend fun deleteBookThumbnail(bookId: MediaServerBookId, thumbnailId: MediaServerThumbnailId) = disabled()
    override suspend fun resetBookMetadata(bookId: MediaServerBookId, bookName: String, bookNumber: Int?) = disabled()
    override suspend fun resetSeriesMetadata(seriesId: MediaServerSeriesId, seriesName: String) = disabled()
    override suspend fun uploadSeriesThumbnail(
        seriesId: MediaServerSeriesId,
        thumbnail: Image,
        selected: Boolean,
        lock: Boolean
    ): MediaServerSeriesThumbnail? = disabled()

    override suspend fun uploadBookThumbnail(
        bookId: MediaServerBookId,
        thumbnail: Image,
        selected: Boolean,
        lock: Boolean
    ): MediaServerBookThumbnail? = disabled()

    override suspend fun refreshMetadata(
        libraryId: MediaServerLibraryId,
        seriesId: MediaServerSeriesId,
        deferScan: Boolean
    ) = disabled()
}
