package snd.komf.mediaserver.kavita

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import snd.komf.mediaserver.MediaServerClient
import snd.komf.mediaserver.SeriesPassSnapshotAware
import snd.komf.mediaserver.VolumeCoverTargetResolver
import snd.komf.mediaserver.kavita.model.KavitaAgeRating
import snd.komf.mediaserver.kavita.model.KavitaAgeRating.UNKNOWN
import snd.komf.mediaserver.kavita.model.KavitaAuthor
import snd.komf.mediaserver.kavita.model.KavitaChapter
import snd.komf.mediaserver.kavita.model.KavitaGenre
import snd.komf.mediaserver.kavita.model.KavitaLibrary
import snd.komf.mediaserver.kavita.model.KavitaPublicationStatus
import snd.komf.mediaserver.kavita.model.KavitaSeries
import snd.komf.mediaserver.kavita.model.KavitaSeriesId
import snd.komf.mediaserver.kavita.model.KavitaSeriesMetadata
import snd.komf.mediaserver.kavita.model.KavitaTag
import snd.komf.mediaserver.kavita.model.KavitaChapterId
import snd.komf.mediaserver.kavita.model.KavitaVolume
import snd.komf.mediaserver.kavita.model.KavitaVolumeId
import snd.komf.mediaserver.kavita.model.request.KavitaChapterMetadataUpdateRequest
import snd.komf.mediaserver.kavita.model.request.KavitaSeriesMetadataUpdateRequest
import snd.komf.mediaserver.kavita.model.request.KavitaSeriesUpdateRequest
import snd.komf.mediaserver.kavita.model.effectiveVolumeNumber
import snd.komf.mediaserver.kavita.model.resolveVolumeNumber
import snd.komf.mediaserver.kavita.model.toKavitaChapterId
import snd.komf.mediaserver.kavita.model.toKavitaLibraryId
import snd.komf.mediaserver.kavita.model.toKavitaSeriesId
import snd.komf.mediaserver.model.MediaServerAlternativeTitle
import snd.komf.mediaserver.model.MediaServerAuthor
import snd.komf.mediaserver.model.MediaServerBook
import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerBookMetadata
import snd.komf.mediaserver.model.MediaServerBookMetadataUpdate
import snd.komf.mediaserver.model.MediaServerBookThumbnail
import snd.komf.mediaserver.model.MediaServerLibrary
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeries
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.mediaserver.model.MediaServerSeriesMetadata
import snd.komf.mediaserver.model.MediaServerSeriesMetadataUpdate
import snd.komf.mediaserver.model.MediaServerSeriesThumbnail
import snd.komf.mediaserver.model.MediaServerThumbnailId
import snd.komf.mediaserver.model.Page
import snd.komf.model.AuthorRole
import snd.komf.model.Image
import snd.komf.model.SeriesStatus
import snd.komf.model.WebLink
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

class KavitaMediaServerClientAdapter(
    private val kavitaClient: KavitaClient,
    private val deferredLibraryScanDelayMs: Long = 120_000L,
    private val scanState: KavitaScanState = KavitaScanState(),
    private val scanSafetyEnabled: Boolean = true,
    private val activeScanWaitTimeoutMs: Long = 1_800_000L,
    private val activeScanPollIntervalMs: Long = 2_000L,
) : MediaServerClient, SeriesPassSnapshotAware, VolumeCoverTargetResolver {
    @Volatile
    private var activeRunMetrics: KavitaRunMetrics? = null

    private val snapshotMutex = Mutex()
    private var activeSnapshotSeriesId: KavitaSeriesId? = null
    private val seriesCache = mutableMapOf<KavitaSeriesId, KavitaSeries>()
    private val seriesMetadataCache = mutableMapOf<KavitaSeriesId, KavitaSeriesMetadata>()
    private val seriesVolumesCache = mutableMapOf<KavitaSeriesId, Collection<KavitaVolume>>()
    private val chapterCache = mutableMapOf<KavitaChapterId, KavitaChapter>()
    private val volumeCache = mutableMapOf<KavitaVolumeId, KavitaVolume>()

    override suspend fun beginSeriesPass(seriesId: MediaServerSeriesId) {
        snapshotMutex.withLock {
            clearSnapshot()
            activeSnapshotSeriesId = seriesId.toKavitaSeriesId()
        }
    }

    override suspend fun endSeriesPass(seriesId: MediaServerSeriesId) {
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId.toKavitaSeriesId()) {
                clearSnapshot()
                activeSnapshotSeriesId = null
            }
        }
    }

    private fun clearSnapshot() {
        seriesCache.clear()
        seriesMetadataCache.clear()
        seriesVolumesCache.clear()
        chapterCache.clear()
        volumeCache.clear()
    }

    override suspend fun getSeries(seriesId: MediaServerSeriesId): MediaServerSeries {
        val kavitaSeriesId = seriesId.toKavitaSeriesId()
        val series = getOrFetchSeries(kavitaSeriesId)
        val metadata = getOrFetchSeriesMetadata(kavitaSeriesId)
        return series.toMediaServerSeries(metadata, metadata.totalCount)
    }

    override suspend fun getSeries(libraryId: MediaServerLibraryId, pageNumber: Int): Page<MediaServerSeries> {
        val kavitaLibraryId = libraryId.toKavitaLibraryId()
        val kavitaPage = kavitaClient.getSeries(kavitaLibraryId, pageNumber)
        return Page(
            content = kavitaPage.content.map {
                val metadata = getOrFetchSeriesMetadata(it.id, prefetchedSeries = it)
                it.toMediaServerSeries(metadata, metadata.totalCount)
            },
            pageNumber = kavitaPage.currentPage,
            totalElements = kavitaPage.totalItems,
            totalPages = kavitaPage.totalPages
        )
    }

    override suspend fun getSeriesThumbnail(seriesId: MediaServerSeriesId): Image? {
        return runCatching { kavitaClient.getSeriesCover(seriesId.toKavitaSeriesId()) }.getOrNull()
    }

    override suspend fun getSeriesThumbnails(seriesId: MediaServerSeriesId): Collection<MediaServerSeriesThumbnail> {
        return emptyList()
    }

    override suspend fun getBook(bookId: MediaServerBookId): MediaServerBook {
        val chapterId = bookId.toKavitaChapterId()
        val chapter = getOrFetchChapter(chapterId)
        val volume = getOrFetchVolume(chapter.volumeId)

        return chapter.toMediaServerBook(volume)
    }

    override suspend fun getBooks(seriesId: MediaServerSeriesId): Collection<MediaServerBook> {
        return getOrFetchVolumes(seriesId.toKavitaSeriesId())
            .flatMap { volume ->
                val resolution = volume.resolveVolumeNumber()
                logger.info {
                    "volume id=${volume.id.value} name='${volume.name}' number=${volume.minNumber} " +
                        "effective=${resolution.effectiveNumber?.toString() ?: "null"} source=${resolution.source.logValue}"
                }
                volume.chapters.map { it.toMediaServerBook(volume) }
            }
    }

    override suspend fun getBookThumbnails(bookId: MediaServerBookId): Collection<MediaServerBookThumbnail> {
        return emptyList()
    }

    override suspend fun getBookThumbnail(bookId: MediaServerBookId): Image? {
        return runCatching { kavitaClient.getChapterCover(bookId.toKavitaChapterId()) }.getOrNull()
    }

    override suspend fun getLibrary(libraryId: MediaServerLibraryId): MediaServerLibrary {
        return kavitaClient.getLibraries().first { it.id == libraryId.toKavitaLibraryId() }
            .toMediaServerLibrary()
    }

    override suspend fun getLibraries(): List<MediaServerLibrary> {
        return kavitaClient.getLibraries().map { it.toMediaServerLibrary() }
    }

    override suspend fun updateSeriesMetadata(
        seriesId: MediaServerSeriesId,
        metadata: MediaServerSeriesMetadataUpdate
    ) {
        val localizedName = metadata.alternativeTitles?.find { it.language != null }
        if (metadata.titleSort != null || localizedName != null) {
            val series = getOrFetchSeries(seriesId.toKavitaSeriesId())
            kavitaClient.updateSeries(
                series.toKavitaTitleUpdate(
                    metadata.titleSort?.name,
                    localizedName?.name
                )
            )
        }

        val oldMetadata = getOrFetchSeriesMetadata(seriesId.toKavitaSeriesId())
        kavitaClient.updateSeriesMetadata(metadata.toKavitaSeriesMetadataUpdate(oldMetadata))
        snapshotMutex.withLock {
            seriesMetadataCache.remove(seriesId.toKavitaSeriesId())
            seriesCache.remove(seriesId.toKavitaSeriesId())
        }
    }

    override suspend fun deleteSeriesThumbnail(seriesId: MediaServerSeriesId, thumbnailId: MediaServerThumbnailId) {
        val series = kavitaClient.getSeries(seriesId.toKavitaSeriesId())
        kavitaClient.updateSeries(series.toKavitaCoverResetRequest())
    }

    override suspend fun updateBookMetadata(bookId: MediaServerBookId, metadata: MediaServerBookMetadataUpdate) {
        val currentChapter = getOrFetchChapter(bookId.toKavitaChapterId())
        val request = metadata.toKavitaChapterMetadataUpdate(currentChapter)
        kavitaClient.updateChapterMetadata(request)
        snapshotMutex.withLock { chapterCache.remove(bookId.toKavitaChapterId()) }
    }

    override suspend fun deleteBookThumbnail(bookId: MediaServerBookId, thumbnailId: MediaServerThumbnailId) {}

    override suspend fun resetBookMetadata(bookId: MediaServerBookId, bookName: String, bookNumber: Int?) {
        kavitaClient.resetChapterLock(bookId.toKavitaChapterId())
    }

    override suspend fun resetSeriesMetadata(seriesId: MediaServerSeriesId, seriesName: String) {
        val series = kavitaClient.getSeries(seriesId.toKavitaSeriesId())
        kavitaClient.updateSeries(series.toKavitaCoverResetRequest())
        kavitaClient.updateSeriesMetadata(kavitaSeriesResetRequest(seriesId.toKavitaSeriesId()))
    }

    override suspend fun uploadSeriesThumbnail(
        seriesId: MediaServerSeriesId,
        thumbnail: Image,
        selected: Boolean,
        lock: Boolean
    ): MediaServerSeriesThumbnail? {
        kavitaClient.uploadSeriesCover(seriesId.toKavitaSeriesId(), thumbnail, lock)
        return null
    }

    override suspend fun uploadBookThumbnail(
        bookId: MediaServerBookId,
        thumbnail: Image,
        selected: Boolean,
        lock: Boolean
    ): MediaServerBookThumbnail? {
        val chapter = getOrFetchChapter(bookId.toKavitaChapterId())
        logger.info { "uploading volume cover volumeId=${chapter.volumeId.value} bookId=${bookId.value}" }
        kavitaClient.uploadVolumeCover(chapter.volumeId, thumbnail, lock)
        return null
    }

    override suspend fun resolveVolumeTargetId(bookId: MediaServerBookId): String {
        val chapter = getOrFetchChapter(bookId.toKavitaChapterId())
        return chapter.volumeId.value.toString()
    }

    override suspend fun refreshMetadata(libraryId: MediaServerLibraryId, seriesId: MediaServerSeriesId, deferScan: Boolean) {
        if (!deferScan) {
            val kavitaLibraryId = libraryId.toKavitaLibraryId()
            kavitaClient.scanSeries(kavitaLibraryId, seriesId.toKavitaSeriesId())
        }
    }

    suspend fun executeDeferredScans(scans: Collection<Pair<MediaServerLibraryId, MediaServerSeriesId>>) {
        val groupedByLibrary = scans.groupBy(
            keySelector = { (libraryId, _) -> libraryId },
            valueTransform = { (_, _) -> Unit }
        )

        groupedByLibrary.forEach { (libraryId, _) ->
            val kavitaLibraryId = libraryId.toKavitaLibraryId()
            // Library-wide/deferred run: trigger a single library scan at end.
            delay(deferredLibraryScanDelayMs.coerceAtLeast(0))
            kavitaClient.scanLibrary(kavitaLibraryId)
        }
    }

    suspend fun executeLibraryEndScan(libraryId: MediaServerLibraryId) {
        val kavitaLibraryId = libraryId.toKavitaLibraryId()
        delay(deferredLibraryScanDelayMs.coerceAtLeast(0))
        kavitaClient.scanLibrary(kavitaLibraryId)
    }

    suspend fun waitForSafeScanWindow(
        operation: String,
        libraryId: MediaServerLibraryId
    ): Boolean {
        if (!scanSafetyEnabled) return true
        if (!scanState.isBusy()) return true

        val timeoutMs = activeScanWaitTimeoutMs.coerceAtLeast(0L)
        val pollMs = activeScanPollIntervalMs.coerceAtLeast(250L)
        val active = scanState.activeActivities().joinToString(", ").ifBlank { "unknown" }
        logger.warn {
            "Kavita activity is active ($active). Waiting before $operation for library ${libraryId.value} " +
                "(timeout=${timeoutMs}ms, poll=${pollMs}ms)"
        }
        val success = scanState.awaitIdle(timeoutMs, pollMs) { elapsed, timeout ->
            val waitingOn = scanState.activeActivities().joinToString(", ").ifBlank { "unknown" }
            logger.warn {
                "Still waiting for Kavita activity to finish ($waitingOn) before $operation for library ${libraryId.value} " +
                    "(elapsed=${elapsed}ms/${timeout}ms)"
            }
        }
        if (!success) {
            val waitingOn = scanState.activeActivities().joinToString(", ").ifBlank { "unknown" }
            logger.error {
                "Timed out waiting for Kavita activity to finish ($waitingOn) before $operation for library ${libraryId.value}"
            }
        }
        return success
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    fun setActiveRunMetrics(metrics: KavitaRunMetrics?) {
        activeRunMetrics = metrics
        kavitaClient.setActiveRunMetrics(metrics)
    }

    fun getActiveRunMetrics(): KavitaRunMetrics? = activeRunMetrics

    private suspend fun getOrFetchSeries(seriesId: KavitaSeriesId): KavitaSeries {
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId) {
                seriesCache[seriesId]?.let {
                    activeRunMetrics?.recordCacheHit(KavitaEndpoint.SERIES)
                    return it
                }
            }
        }

        activeRunMetrics?.recordCacheMiss(KavitaEndpoint.SERIES)
        val fetched = kavitaClient.getSeries(seriesId)
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId) {
                seriesCache[seriesId] = fetched
            }
        }
        return fetched
    }

    private suspend fun getOrFetchSeriesMetadata(
        seriesId: KavitaSeriesId,
        prefetchedSeries: KavitaSeries? = null
    ): KavitaSeriesMetadata {
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId) {
                seriesMetadataCache[seriesId]?.let {
                    activeRunMetrics?.recordCacheHit(KavitaEndpoint.SERIES_METADATA)
                    return it
                }
            }
        }

        activeRunMetrics?.recordCacheMiss(KavitaEndpoint.SERIES_METADATA)
        val fetched = kavitaClient.getSeriesMetadata(seriesId)
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId) {
                seriesMetadataCache[seriesId] = fetched
                prefetchedSeries?.let { seriesCache[seriesId] = it }
            }
        }
        return fetched
    }

    private suspend fun getOrFetchVolumes(seriesId: KavitaSeriesId): Collection<KavitaVolume> {
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId) {
                seriesVolumesCache[seriesId]?.let {
                    activeRunMetrics?.recordCacheHit(KavitaEndpoint.VOLUMES)
                    return it
                }
            }
        }

        activeRunMetrics?.recordCacheMiss(KavitaEndpoint.VOLUMES)
        val fetched = kavitaClient.getVolumes(seriesId)
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId == seriesId) {
                seriesVolumesCache[seriesId] = fetched
                fetched.forEach { volume ->
                    volumeCache[volume.id] = volume
                    volume.chapters.forEach { chapter -> chapterCache[chapter.id] = chapter }
                }
            }
        }
        return fetched
    }

    private suspend fun getOrFetchChapter(chapterId: KavitaChapterId): KavitaChapter {
        snapshotMutex.withLock {
            chapterCache[chapterId]?.let {
                activeRunMetrics?.recordCacheHit(KavitaEndpoint.CHAPTER)
                return it
            }
        }

        activeRunMetrics?.recordCacheMiss(KavitaEndpoint.CHAPTER)
        val fetched = kavitaClient.getChapter(chapterId)
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId != null) {
                chapterCache[chapterId] = fetched
            }
        }
        return fetched
    }

    private suspend fun getOrFetchVolume(volumeId: KavitaVolumeId): KavitaVolume {
        snapshotMutex.withLock {
            volumeCache[volumeId]?.let {
                activeRunMetrics?.recordCacheHit(KavitaEndpoint.VOLUME)
                return it
            }
        }

        activeRunMetrics?.recordCacheMiss(KavitaEndpoint.VOLUME)
        val fetched = kavitaClient.getVolume(volumeId)
        snapshotMutex.withLock {
            if (activeSnapshotSeriesId != null) {
                volumeCache[volumeId] = fetched
            }
        }
        return fetched
    }
}


private fun KavitaSeries.toMediaServerSeries(metadata: KavitaSeriesMetadata, bookCount: Int): MediaServerSeries {
    return MediaServerSeries(
        id = MediaServerSeriesId(id.value.toString()),
        libraryId = MediaServerLibraryId(libraryId.toString()),
        name = originalName,
        booksCount = bookCount,
        metadata = metadata.toMediaServerSeriesMetadata(this),
        url = folderPath,
        deleted = false,
    )
}

internal fun resolveKavitaBookNumber(volume: KavitaVolume): Int {
    return volume.effectiveVolumeNumber() ?: 0
}

internal fun KavitaChapter.toMediaServerBook(volume: KavitaVolume): MediaServerBook {
    val filePath = Path.of(files.first().filePath)
    val fileName = filePath.fileName.nameWithoutExtension

    return MediaServerBook(
        id = MediaServerBookId(id.value.toString()),
        seriesId = MediaServerSeriesId(volume.seriesId.toString()),
        libraryId = null,
        seriesTitle = title,
        name = fileName,
        url = filePath.toString(),
        number = resolveKavitaBookNumber(volume),
        oneshot = false,
        metadata = toMediaServerBookMetadata(),
        deleted = false,
    )
}

private fun KavitaChapter.toMediaServerBookMetadata(): MediaServerBookMetadata {
    val authors = writers.map { MediaServerAuthor(it.name, AuthorRole.WRITER.name) } +
            coverArtists.map { MediaServerAuthor(it.name, AuthorRole.COVER.name) } +
            pencillers.map { MediaServerAuthor(it.name, AuthorRole.PENCILLER.name) } +
            letterers.map { MediaServerAuthor(it.name, AuthorRole.LETTERER.name) } +
            inkers.map { MediaServerAuthor(it.name, AuthorRole.INKER.name) } +
            colorists.map { MediaServerAuthor(it.name, AuthorRole.COLORIST.name) } +
            editors.map { MediaServerAuthor(it.name, AuthorRole.EDITOR.name) } +
            translators.map { MediaServerAuthor(it.name, AuthorRole.TRANSLATOR.name) }
    val authorsLock = sequenceOf(
        writerLocked,
        coverArtistLocked,
        pencillerLocked,
        lettererLocked,
        inkerLocked,
        coloristLocked,
        editorLocked,
        translatorLocked
    ).any { it } //TODO per role locks?

    return MediaServerBookMetadata(
        title = title,
        summary = summary?.ifBlank { null },
        number = number ?: "0",
        numberSort = number,
        releaseDate = releaseDate.date,
        authors = authors,
        tags = tags.map { it.title },
        isbn = isbn.ifBlank { null },
        links = emptyList(),

        titleLock = false,
        summaryLock = summaryLocked,
        numberLock = false,
        numberSortLock = false,
        releaseDateLock = false,
        authorsLock = authorsLock,
        tagsLock = tagsLocked,
        isbnLock = false,
        linksLock = false,
    )
}

private fun KavitaLibrary.toMediaServerLibrary() = MediaServerLibrary(
    id = MediaServerLibraryId(id.value.toString()),
    name = name,
    roots = folders
)

private fun KavitaSeriesMetadata.toMediaServerSeriesMetadata(series: KavitaSeries): MediaServerSeriesMetadata {
    val status = when (publicationStatus) {
        KavitaPublicationStatus.ONGOING -> SeriesStatus.ONGOING
        KavitaPublicationStatus.HIATUS -> SeriesStatus.HIATUS
        KavitaPublicationStatus.COMPLETED -> SeriesStatus.COMPLETED
        KavitaPublicationStatus.CANCELLED -> SeriesStatus.ABANDONED
        KavitaPublicationStatus.ENDED -> SeriesStatus.ENDED
    }
    val authors = writers.map { MediaServerAuthor(it.name, AuthorRole.WRITER.name) } +
            coverArtists.map { MediaServerAuthor(it.name, AuthorRole.COVER.name) } +
            pencillers.map { MediaServerAuthor(it.name, AuthorRole.PENCILLER.name) } +
            letterers.map { MediaServerAuthor(it.name, AuthorRole.LETTERER.name) } +
            inkers.map { MediaServerAuthor(it.name, AuthorRole.INKER.name) } +
            colorists.map { MediaServerAuthor(it.name, AuthorRole.COLORIST.name) } +
            editors.map { MediaServerAuthor(it.name, AuthorRole.EDITOR.name) } +
            translators.map { MediaServerAuthor(it.name, AuthorRole.TRANSLATOR.name) }

    val authorsLock = sequenceOf(
        writerLocked,
        coverArtistLocked,
        pencillerLocked,
        lettererLocked,
        inkerLocked,
        coloristLocked,
        editorLocked,
        translatorLocked
    ).any { it } //TODO per role locks?

    return MediaServerSeriesMetadata(
        status = status,
        title = series.name,
        titleSort = series.sortName,
        alternativeTitles = series.localizedName?.let { listOf(MediaServerAlternativeTitle("Localized", it)) }
            ?: emptyList(),
        summary = summary ?: "",
        readingDirection = null,
        publisher = null,
        alternativePublishers = publishers.map { it.name }.toSet(),
        ageRating = ageRating.ageRating,
        language = language,
        genres = genres.map { it.title },
        tags = tags.map { it.title },
        totalBookCount = if (totalCount == 0) null else totalCount,
        authors = authors,
        releaseYear = releaseYear,
        links = webLinks?.split(",")?.map { WebLink(it, it) } ?: emptyList(),

        statusLock = publicationStatusLocked,
        titleLock = false,
        titleSortLock = series.sortNameLocked,
        summaryLock = summaryLocked,
        readingDirectionLock = false,
        publisherLock = publisherLocked,
        ageRatingLock = ageRatingLocked,
        languageLock = languageLocked,
        genresLock = genresLocked,
        tagsLock = tagsLocked,
        totalBookCountLock = false,
        authorsLock = authorsLock,
        releaseYearLock = releaseYearLocked,
        alternativeTitlesLock = series.localizedNameLocked,
        linksLock = false,
    )
}

private fun MediaServerSeriesMetadataUpdate.toKavitaSeriesMetadataUpdate(currentMetadata: KavitaSeriesMetadata): KavitaSeriesMetadataUpdateRequest {
    val status = when (status) {
        SeriesStatus.ENDED -> KavitaPublicationStatus.ENDED
        SeriesStatus.ONGOING -> KavitaPublicationStatus.ONGOING
        SeriesStatus.ABANDONED -> KavitaPublicationStatus.CANCELLED
        SeriesStatus.HIATUS -> KavitaPublicationStatus.HIATUS
        SeriesStatus.COMPLETED -> KavitaPublicationStatus.COMPLETED
        null -> null
    }
    val publishers =
        if (publisher == null && alternativePublishers == null) currentMetadata.publishers
        else ((alternativePublishers ?: emptyList()) + listOfNotNull(publisher))
            .map { KavitaAuthor(id = 0, name = it) }.toSet()

    val authors = authors?.groupBy { it.role.lowercase() }
    val ageRating = ageRating
        ?.let { metadataRating ->
            KavitaAgeRating.entries
                .filter { it.ageRating != null }
                .sortedBy { it.ageRating }
                .firstOrNull { it.ageRating == it.ageRating!!.coerceAtLeast(metadataRating) }
                ?: KavitaAgeRating.ADULTS_ONLY
        }

    val metadata = KavitaSeriesMetadata(
        publicationStatus = status ?: currentMetadata.publicationStatus,
        summary = summary ?: currentMetadata.summary,
        publishers = publishers,
        genres = genres?.let { deduplicate(it) }?.map { KavitaGenre(id = 0, title = it) }?.toSet()
            ?: currentMetadata.genres,
        tags = tags?.let { deduplicate(it) }?.map { KavitaTag(id = 0, title = it) }?.toSet() ?: currentMetadata.tags,
        writers = authors
            ?.get(AuthorRole.WRITER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentMetadata.writers } ?: currentMetadata.writers,
        coverArtists = authors
            ?.get(AuthorRole.COVER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentMetadata.coverArtists } ?: currentMetadata.coverArtists,
        pencillers = authors
            ?.get(AuthorRole.PENCILLER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentMetadata.pencillers } ?: currentMetadata.pencillers,
        inkers = authors
            ?.get(AuthorRole.INKER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentMetadata.inkers } ?: currentMetadata.inkers,
        colorists = authors
            ?.get(AuthorRole.COLORIST.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentMetadata.colorists } ?: currentMetadata.colorists,
        letterers = authors
            ?.get(AuthorRole.LETTERER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentMetadata.letterers } ?: currentMetadata.letterers,
        editors = authors
            ?.get(AuthorRole.EDITOR.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet() ?: currentMetadata.editors,
        translators = authors
            ?.get(AuthorRole.TRANSLATOR.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet() ?: currentMetadata.translators,
        ageRating = ageRating ?: currentMetadata.ageRating,
        language = language ?: currentMetadata.language,
        releaseYear = releaseYear ?: currentMetadata.releaseYear,
        webLinks = links?.joinToString(separator = ",") { it.url } ?: currentMetadata.webLinks,
        id = currentMetadata.id,
        seriesId = currentMetadata.seriesId,
        characters = currentMetadata.characters,
        imprints = currentMetadata.imprints,
        teams = currentMetadata.teams,
        locations = currentMetadata.locations,
        maxCount = currentMetadata.maxCount,
        totalCount = currentMetadata.totalCount,
        languageLocked = languageLock ?: currentMetadata.languageLocked,
        summaryLocked = summaryLock ?: currentMetadata.summaryLocked,
        ageRatingLocked = ageRatingLock ?: currentMetadata.ageRatingLocked,
        publicationStatusLocked = statusLock ?: currentMetadata.publicationStatusLocked,
        genresLocked = genresLock ?: currentMetadata.genresLocked,
        tagsLocked = tagsLock ?: currentMetadata.tagsLocked,
        writerLocked = authorsLock ?: currentMetadata.writerLocked,
        characterLocked = currentMetadata.characterLocked,
        coloristLocked = authorsLock ?: currentMetadata.coloristLocked,
        editorLocked = authorsLock ?: currentMetadata.editorLocked,
        inkerLocked = authorsLock ?: currentMetadata.inkerLocked,
        imprintLocked = currentMetadata.imprintLocked,
        lettererLocked = authorsLock ?: currentMetadata.lettererLocked,
        pencillerLocked = authorsLock ?: currentMetadata.pencillerLocked,
        publisherLocked = publisherLock ?: currentMetadata.publisherLocked,
        translatorLocked = authorsLock ?: currentMetadata.translatorLocked,
        teamLocked = currentMetadata.teamLocked,
        locationLocked = currentMetadata.locationLocked,
        coverArtistLocked = authorsLock ?: currentMetadata.coverArtistLocked,
        releaseYearLocked = releaseYearLock ?: currentMetadata.releaseYearLocked
    )
    return KavitaSeriesMetadataUpdateRequest(metadata)
}

private val normalizeRegex = "[^\\p{L}0-9+!]".toRegex()
private fun deduplicate(values: Collection<String>) = values
    .map { normalizeRegex.replace(it, "").trim().lowercase() to it }
    .distinctBy { (normalized, _) -> normalized }
    .map { (_, value) -> value }

private fun kavitaSeriesResetRequest(seriesId: KavitaSeriesId): KavitaSeriesMetadataUpdateRequest {
    val metadata = KavitaSeriesMetadata(
        id = 0,
        seriesId = seriesId,
        summary = "",
        genres = emptySet(),
        tags = emptySet(),
        writers = emptySet(),
        coverArtists = emptySet(),
        publishers = emptySet(),
        characters = emptySet(),
        pencillers = emptySet(),
        inkers = emptySet(),
        imprints = emptySet(),
        colorists = emptySet(),
        letterers = emptySet(),
        editors = emptySet(),
        translators = emptySet(),
        teams = emptySet(),
        locations = emptySet(),
        ageRating = UNKNOWN,
        releaseYear = 0,
        language = "",
        maxCount = 0,
        totalCount = 0,
        publicationStatus = KavitaPublicationStatus.ONGOING,
        webLinks = "",

        languageLocked = false,
        summaryLocked = false,
        ageRatingLocked = false,
        publicationStatusLocked = false,
        genresLocked = false,
        tagsLocked = false,
        writerLocked = false,
        characterLocked = false,
        coloristLocked = false,
        editorLocked = false,
        inkerLocked = false,
        imprintLocked = false,
        lettererLocked = false,
        pencillerLocked = false,
        publisherLocked = false,
        translatorLocked = false,
        teamLocked = false,
        locationLocked = false,
        coverArtistLocked = false,
        releaseYearLocked = false,
    )
    return KavitaSeriesMetadataUpdateRequest(metadata)
}

private fun KavitaSeries.toKavitaTitleUpdate(newSortName: String?, newLocalizedName: String?) =
    KavitaSeriesUpdateRequest(
        id = id,
        sortName = newSortName?.trim() ?: sortName,
        localizedName = newLocalizedName?.trim() ?: localizedName,
        sortNameLocked = sortNameLocked,
        localizedNameLocked = localizedNameLocked,

        coverImageLocked = coverImageLocked
    )

private fun KavitaSeries.toKavitaCoverResetRequest() = KavitaSeriesUpdateRequest(
    id = id,
    localizedName = localizedName,
    sortName = sortName,
    sortNameLocked = false,
    localizedNameLocked = false,

    coverImageLocked = false
)

private fun MediaServerBookMetadataUpdate.toKavitaChapterMetadataUpdate(currentChapter: KavitaChapter): KavitaChapterMetadataUpdateRequest {
    val authors = authors?.groupBy { it.role.lowercase() }
    return KavitaChapterMetadataUpdateRequest(
        id = currentChapter.id,
        summary = summary ?: currentChapter.summary,
        genres = currentChapter.genres,
        tags = tags?.let { deduplicate(it) }?.map { KavitaTag(id = 0, title = it) } ?: currentChapter.tags,
        ageRating = currentChapter.ageRating,
        language = currentChapter.language,
        weblinks = links?.joinToString(",") { it.url } ?: currentChapter.webLinks,
        isbn = isbn ?: currentChapter.isbn,
        releaseDate = releaseDate?.atTime(LocalTime(0, 0, 0)) ?: currentChapter.releaseDate,
        titleName = title ?: currentChapter.titleName,
        sortOrder = numberSort ?: currentChapter.sortOrder,
        writers = authors
            ?.get(AuthorRole.WRITER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.writers } ?: currentChapter.writers,
        coverArtists = authors
            ?.get(AuthorRole.COVER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.coverArtists } ?: currentChapter.coverArtists,
        pencillers = authors
            ?.get(AuthorRole.PENCILLER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.pencillers } ?: currentChapter.pencillers,
        inkers = authors
            ?.get(AuthorRole.INKER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.inkers } ?: currentChapter.inkers,
        colorists = authors
            ?.get(AuthorRole.COLORIST.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.colorists } ?: currentChapter.colorists,
        letterers = authors
            ?.get(AuthorRole.LETTERER.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.letterers } ?: currentChapter.letterers,
        editors = authors
            ?.get(AuthorRole.EDITOR.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.editors } ?: currentChapter.editors,
        translators = authors
            ?.get(AuthorRole.TRANSLATOR.name.lowercase())
            ?.map { KavitaAuthor(id = 0, name = it.name) }?.toSet()
            ?.ifEmpty { currentChapter.translators } ?: currentChapter.translators,
        imprints = currentChapter.imprints,
        publishers = currentChapter.publishers,
        characters = currentChapter.characters,
        teams = currentChapter.teams,
        locations = currentChapter.locations,
        ageRatingLocked = currentChapter.ageRatingLocked,
        genresLocked = currentChapter.genresLocked,
        tagsLocked = tagsLock ?: currentChapter.tagsLocked,
        writerLocked = authorsLock ?: currentChapter.writerLocked,
        characterLocked = currentChapter.characterLocked,
        coloristLocked = authorsLock ?: currentChapter.coloristLocked,
        editorLocked = authorsLock ?: currentChapter.editorLocked,
        inkerLocked = authorsLock ?: currentChapter.inkerLocked,
        imprintLocked = currentChapter.imprintLocked,
        lettererLocked = authorsLock ?: currentChapter.lettererLocked,
        pencillerLocked = authorsLock ?: currentChapter.pencillerLocked,
        publisherLocked = currentChapter.publisherLocked,
        translatorLocked = authorsLock ?: currentChapter.translatorLocked,
        teamLocked = currentChapter.teamLocked,
        locationLocked = currentChapter.locationLocked,
        coverArtistLocked = authorsLock ?: currentChapter.coverArtistLocked,
        languageLocked = currentChapter.languageLocked,
        summaryLocked = summaryLock ?: currentChapter.summaryLocked,
        titleNameLocked = titleLock ?: currentChapter.titleNameLocked,
        isbnLocked = isbnLock ?: currentChapter.isbnLocked,
        releaseDateLocked = releaseDateLock ?: currentChapter.releaseDateLocked,
        sortOrderLocked = numberSortLock ?: currentChapter.sortOrderLocked
    )
}
