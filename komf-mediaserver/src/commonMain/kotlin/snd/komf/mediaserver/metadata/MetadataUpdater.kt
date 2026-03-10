package snd.komf.mediaserver.metadata

import io.github.oshai.kotlinlogging.KotlinLogging
import snd.komf.comicinfo.ComicInfoWriter
import snd.komf.mediaserver.MediaServerClient
import snd.komf.mediaserver.SeriesPassSnapshotAware
import snd.komf.mediaserver.VolumeCoverTargetResolver
import snd.komf.mediaserver.model.MediaServerBook
import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerBookMetadata
import snd.komf.mediaserver.model.MediaServerBookMetadataUpdate
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeriesMetadata
import snd.komf.mediaserver.model.MediaServerSeriesMetadataUpdate
import snd.komf.mediaserver.model.MediaServerSeries
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.mediaserver.model.MediaServerThumbnailId
import snd.komf.mediaserver.model.SeriesAndBookMetadata
import snd.komf.mediaserver.metadata.repository.BookThumbnailsRepository
import snd.komf.mediaserver.metadata.repository.SeriesThumbnailsRepository
import snd.komf.model.BookMetadata
import snd.komf.model.Image
import snd.komf.model.SeriesMetadata
import snd.komf.model.UpdateMode
import snd.komf.util.BookNameParser
import snd.komf.util.caseInsensitiveNatSortComparator
import snd.komf.model.SeriesTitle
import snd.komf.model.WebLink
import java.security.MessageDigest
import kotlin.math.floor

private val logger = KotlinLogging.logger {}

data class MetadataApplyOutcome(
    val desiredHashes: Map<LibraryApplyScope, String>,
    val perScope: Map<LibraryApplyScope, ScopeWriteCounters>,
    val planned: Int,
    val changed: Int,
    val skippedUnchanged: Int,
    val written: Int,
)

data class ScopeWriteCounters(
    val planned: Int,
    val changed: Int,
    val skippedUnchanged: Int,
    val written: Int,
)

class MetadataUpdater(
    private val mediaServerClient: MediaServerClient,
    private val seriesThumbnailsRepository: SeriesThumbnailsRepository,
    private val bookThumbnailsRepository: BookThumbnailsRepository,
    private val metadataUpdateMapper: MetadataMapper,
    private val postProcessor: MetadataPostProcessor,
    private val comicInfoWriter: ComicInfoWriter,

    private val updateModes: Set<UpdateMode>,
    private val overrideExistingCovers: Boolean,
    private val uploadBookCovers: Boolean,
    private val uploadSeriesCovers: Boolean,
    private val lockSeriesCover: Boolean,
    private val lockVolumeCover: Boolean,
) {
    private data class WriteCounters(
        var planned: Int = 0,
        var changed: Int = 0,
        var skippedUnchanged: Int = 0,
        var written: Int = 0,
    ) {
        fun merge(other: WriteCounters) {
            planned += other.planned
            changed += other.changed
            skippedUnchanged += other.skippedUnchanged
            written += other.written
        }
    }

    private data class BookUpdateOutcome(
        val chapterCounters: WriteCounters
    )

    private data class VolumeCoverApplyOutcome(
        val counters: WriteCounters,
        val candidateCount: Int,
        val uniqueTargetCount: Int,
        val skippedDuplicateCount: Int
    )

    private data class CoverReplaceOutcome(
        val thumbnailId: MediaServerThumbnailId?,
        val uploaded: Boolean,
        val counters: WriteCounters
    )

    private val requireMetadataRefresh = setOf(UpdateMode.COMIC_INFO)
    private val natSortComparator: Comparator<String> = caseInsensitiveNatSortComparator()

    fun desiredHash(seriesId: MediaServerSeriesId, metadata: SeriesAndBookMetadata): String {
        val processedMetadata = postProcessor.process(metadata)
        return processedMetadata.toDesiredHash(seriesId)
    }

    fun desiredHashes(seriesId: MediaServerSeriesId, metadata: SeriesAndBookMetadata): Map<LibraryApplyScope, String> {
        val processedMetadata = postProcessor.process(metadata)
        return processedMetadata.toScopeDesiredHashes(seriesId)
    }

    suspend fun updateMetadata(series: MediaServerSeries, metadata: SeriesAndBookMetadata): MetadataApplyOutcome {
        return updateMetadata(series, metadata, deferScan = false, applyMode = LibraryApplyMode.FULL)
    }

    suspend fun updateMetadata(
        series: MediaServerSeries,
        metadata: SeriesAndBookMetadata,
        deferScan: Boolean,
        applyMode: LibraryApplyMode = LibraryApplyMode.FULL
    ): MetadataApplyOutcome {
        val snapshotAwareClient = mediaServerClient as? SeriesPassSnapshotAware
        snapshotAwareClient?.beginSeriesPass(series.id)

        try {
            val processedMetadata = postProcessor.process(metadata)
            val desiredHashes = processedMetadata.toScopeDesiredHashes(series.id)
            val selectedScopes = applyMode.scopes()
            val counters = WriteCounters()
            val perScopeCounters = mutableMapOf<LibraryApplyScope, WriteCounters>()

            if (LibraryApplyScope.SERIES_METADATA in selectedScopes) {
                val scopeCounters = updateSeriesApiMetadata(series, processedMetadata.seriesMetadata)
                perScopeCounters[LibraryApplyScope.SERIES_METADATA] = scopeCounters
                counters.merge(scopeCounters)
            }

            if (LibraryApplyScope.SERIES_COVER in selectedScopes) {
                val scopeCounters = updateSeriesCover(series.id, processedMetadata.seriesMetadata)
                perScopeCounters[LibraryApplyScope.SERIES_COVER] = scopeCounters
                counters.merge(scopeCounters)
            }

            val bookScopeCounters = updateBookMetadata(
                series = series,
                unprocessedMetadata = metadata,
                processedMetadata = processedMetadata,
                selectedScopes = selectedScopes
            )
            perScopeCounters.putAll(bookScopeCounters)
            bookScopeCounters.values.forEach { counters.merge(it) }

            logger.info {
                "series ${series.id.value} write summary: mode=$applyMode, planned=${counters.planned}, changed=${counters.changed}, " +
                    "skipped_unchanged=${counters.skippedUnchanged}, written=${counters.written}, " +
                    "per_scope=${perScopeCounters.entries.joinToString { (scope, scopeCounters) -> "${scope.name}(p=${scopeCounters.planned},c=${scopeCounters.changed},s=${scopeCounters.skippedUnchanged},w=${scopeCounters.written})" }}"
            }

            if (updateModes.any { it in requireMetadataRefresh })
                mediaServerClient.refreshMetadata(series.libraryId, series.id, deferScan = deferScan)

            return MetadataApplyOutcome(
                desiredHashes = desiredHashes,
                perScope = perScopeCounters.mapValues { (_, value) ->
                    ScopeWriteCounters(
                        planned = value.planned,
                        changed = value.changed,
                        skippedUnchanged = value.skippedUnchanged,
                        written = value.written
                    )
                },
                planned = counters.planned,
                changed = counters.changed,
                skippedUnchanged = counters.skippedUnchanged,
                written = counters.written
            )
        } finally {
            snapshotAwareClient?.endSeriesPass(series.id)
        }
    }

    suspend fun resetLibraryMetadata(libraryId: MediaServerLibraryId, removeComicInfo: Boolean) {
        var pageNumber = 1
        do {
            val page = mediaServerClient.getSeries(libraryId, pageNumber)
            page.content.forEach { resetSeriesMetadata(it, removeComicInfo) }
            pageNumber++
        } while (page.pageNumber != page.totalPages && page.content.isNotEmpty())
    }

    suspend fun resetSeriesMetadata(seriesId: MediaServerSeriesId, removeComicInfo: Boolean) {
        val series = mediaServerClient.getSeries(seriesId)
        resetSeriesMetadata(series, removeComicInfo)
    }

    private suspend fun updateSeriesApiMetadata(series: MediaServerSeries, metadata: SeriesMetadata): WriteCounters {
        val counters = WriteCounters()
        updateModes.forEach {
            when (it) {
                UpdateMode.API -> {
                    logger.info { "updating series ${series.name}" }
                    val metadataUpdate = metadataUpdateMapper.toSeriesMetadataUpdate(metadata, series.metadata)
                    counters.planned += 1
                    if (metadataUpdate.hasChangesComparedTo(series.metadata)) {
                        counters.changed += 1
                        mediaServerClient.updateSeriesMetadata(series.id, metadataUpdate)
                        counters.written += 1
                    } else {
                        counters.skippedUnchanged += 1
                    }
                }

                UpdateMode.COMIC_INFO -> {}
            }
        }
        return counters
    }

    private suspend fun updateSeriesCover(seriesId: MediaServerSeriesId, metadata: SeriesMetadata): WriteCounters {
        val counters = WriteCounters()
        val newThumbnail = if (uploadSeriesCovers) metadata.thumbnail else null
        val seriesCoverOutcome = replaceSeriesThumbnail(seriesId, newThumbnail)
        counters.merge(seriesCoverOutcome.counters)

        if (seriesCoverOutcome.thumbnailId == null) {
            seriesThumbnailsRepository.delete(seriesId)
        } else {
            seriesThumbnailsRepository.save(
                seriesId = seriesId,
                thumbnailId = seriesCoverOutcome.thumbnailId,
            )
        }
        return counters
    }

    private suspend fun updateBookMetadata(
        series: MediaServerSeries,
        unprocessedMetadata: SeriesAndBookMetadata,
        processedMetadata: SeriesAndBookMetadata,
        selectedScopes: Set<LibraryApplyScope>
    ): Map<LibraryApplyScope, WriteCounters> {
        val bookIdToWriteSeriesMetadata = bookToWriteSeriesMetadata(unprocessedMetadata.bookMetadata)
        val chapterCounters = WriteCounters()
        val applyChapterMetadata = LibraryApplyScope.CHAPTER_METADATA in selectedScopes
        val applyVolumeCover = LibraryApplyScope.VOLUME_COVERS in selectedScopes

        processedMetadata.bookMetadata.forEach { (book, metadata) ->
            val outcome = updateBookMetadata(
                book,
                metadata,
                processedMetadata.seriesMetadata,
                book.id == bookIdToWriteSeriesMetadata,
                applyChapterMetadata = applyChapterMetadata
            )
            chapterCounters.merge(outcome.chapterCounters)
        }

        val volumeCoverOutcome = if (applyVolumeCover) {
            applyVolumeCoversDeduped(processedMetadata.bookMetadata)
        } else {
            VolumeCoverApplyOutcome(
                counters = WriteCounters(),
                candidateCount = 0,
                uniqueTargetCount = 0,
                skippedDuplicateCount = 0
            )
        }

        logVolumeCoverSummary(series, volumeCoverOutcome)

        val out = mutableMapOf<LibraryApplyScope, WriteCounters>()
        if (applyChapterMetadata) out[LibraryApplyScope.CHAPTER_METADATA] = chapterCounters
        if (applyVolumeCover) out[LibraryApplyScope.VOLUME_COVERS] = volumeCoverOutcome.counters
        return out
    }

    private suspend fun updateBookMetadata(
        book: MediaServerBook,
        metadata: BookMetadata?,
        seriesMeta: SeriesMetadata,
        writeSeriesMetadata: Boolean,
        applyChapterMetadata: Boolean
    ): BookUpdateOutcome {
        val chapterCounters = WriteCounters()
        logger.info { "updating book ${book.name}" }
        updateModes.forEach { mode ->
            when (mode) {
                UpdateMode.API -> {
                    if (!applyChapterMetadata) return@forEach
                    val patch = metadataUpdateMapper.toBookMetadataUpdate(metadata, seriesMeta, book)
                    chapterCounters.planned += 1
                    if (patch.hasChangesComparedTo(book.metadata)) {
                        chapterCounters.changed += 1
                        mediaServerClient.updateBookMetadata(book.id, patch)
                        chapterCounters.written += 1
                    } else {
                        chapterCounters.skippedUnchanged += 1
                    }
                }


                UpdateMode.COMIC_INFO -> {
                    if (book.deleted) return@forEach

                    val comicInfo =
                        if (writeSeriesMetadata) metadataUpdateMapper.toSeriesComicInfo(seriesMeta, metadata)
                        else metadataUpdateMapper.toComicInfo(metadata, seriesMeta)

                    comicInfo?.let { comicInfoWriter.writeMetadata(book.url, it) }
                }

//                UpdateMode.OPF -> {
//                    if (book.deleted) return@forEach
//
//                    if (writeSeriesMetadata) epubWriter.writeSeriesMetadata(Path.of(book.url), seriesMeta, metadata)
//                    else epubWriter.writeMetadata(Path.of(book.url), seriesMeta, metadata)
//                }
            }
        }
        return BookUpdateOutcome(
            chapterCounters = chapterCounters
        )
    }

    private suspend fun applyVolumeCoversDeduped(
        bookMetadata: Map<MediaServerBook, BookMetadata?>
    ): VolumeCoverApplyOutcome {
        val candidates = mutableListOf<VolumeCoverUploadCandidate>()
        bookMetadata.forEach { (book, metadata) ->
            val thumbnail = if (uploadBookCovers) metadata?.thumbnail else null
            if (thumbnail == null) return@forEach
            candidates += VolumeCoverUploadCandidate(
                sourceSeriesId = book.seriesId,
                sourceBookId = book.id,
                targetVolumeId = resolveVolumeTargetId(book.id),
                thumbnail = thumbnail
            )
        }

        val dedupe = dedupeVolumeCoverUploadCandidates(candidates)
        val counters = WriteCounters()
        dedupe.uploadPlans.forEach { plan ->
            val bookCoverOutcome = replaceBookThumbnail(plan.sourceBookId, plan.thumbnail)
            counters.merge(bookCoverOutcome.counters)

            if (bookCoverOutcome.thumbnailId == null) {
                bookThumbnailsRepository.delete(plan.sourceBookId)
            } else {
                bookThumbnailsRepository.save(
                    seriesId = plan.sourceSeriesId,
                    bookId = plan.sourceBookId,
                    thumbnailId = bookCoverOutcome.thumbnailId,
                )
            }
        }

        return VolumeCoverApplyOutcome(
            counters = counters,
            candidateCount = dedupe.candidateCount,
            uniqueTargetCount = dedupe.uploadPlans.size,
            skippedDuplicateCount = dedupe.skippedDuplicateCount
        )
    }

    private suspend fun resolveVolumeTargetId(bookId: MediaServerBookId): String {
        val resolver = mediaServerClient as? VolumeCoverTargetResolver ?: return "book:${bookId.value}"
        return runCatching { resolver.resolveVolumeTargetId(bookId) }
            .getOrElse {
                logger.warn(it) {
                    "failed to resolve effective volume target for bookId=${bookId.value}; " +
                        "falling back to book identity dedupe key"
                }
                "book:${bookId.value}"
            }
    }

    private fun logVolumeCoverSummary(series: MediaServerSeries, outcome: VolumeCoverApplyOutcome) {
        logger.info {
            "series ${series.id.value} volume-cover summary: " +
                "volume_cover_candidates=${outcome.candidateCount}, " +
                "volume_cover_unique_targets=${outcome.uniqueTargetCount}, " +
                "volume_cover_uploaded=${outcome.counters.written}, " +
                "volume_cover_skipped_duplicate=${outcome.skippedDuplicateCount}, " +
                "volume_cover_skipped_unchanged=${outcome.counters.skippedUnchanged}, " +
                "volume_cover_changed=${outcome.counters.changed}"
        }
    }

    private suspend fun replaceBookThumbnail(bookId: MediaServerBookId, thumbnail: Image?): CoverReplaceOutcome {
        val counters = WriteCounters()

        if (thumbnail == null) {
            return CoverReplaceOutcome(
                thumbnailId = null,
                uploaded = false,
                counters = counters
            )
        }

        counters.planned += 1
        val currentThumbnail = mediaServerClient.getBookThumbnail(bookId)
        if (currentThumbnail != null && thumbnail.bytes.contentHashCode() == currentThumbnail.bytes.contentHashCode()) {
            counters.skippedUnchanged += 1
            return CoverReplaceOutcome(
                thumbnailId = bookThumbnailsRepository.findFor(bookId)?.thumbnailId,
                uploaded = false,
                counters = counters
            )
        }

        counters.changed += 1
        val existingMatch = bookThumbnailsRepository.findFor(bookId)
        val thumbnails = mediaServerClient.getBookThumbnails(bookId)

        val selectThumbnail = overrideExistingCovers ||
                thumbnails.all { it.type == "GENERATED" || it.id == existingMatch?.thumbnailId }

        val uploadedThumbnail = mediaServerClient.uploadBookThumbnail(
            bookId = bookId,
            thumbnail = thumbnail,
            selected = selectThumbnail,
            lock = lockVolumeCover
        )
        counters.written += 1

        existingMatch?.thumbnailId?.let { thumb ->
            if (thumbnails.any { it.id == thumb }) {
                mediaServerClient.deleteBookThumbnail(bookId, thumb)
            }
        }

        return CoverReplaceOutcome(
            thumbnailId = uploadedThumbnail?.id,
            uploaded = true,
            counters = counters
        )
    }

    private suspend fun replaceSeriesThumbnail(
        seriesId: MediaServerSeriesId,
        thumbnail: Image?
    ): CoverReplaceOutcome {
        val counters = WriteCounters()

        if (thumbnail == null) {
            return CoverReplaceOutcome(
                thumbnailId = null,
                uploaded = false,
                counters = counters
            )
        }

        counters.planned += 1
        val currentThumbnail = mediaServerClient.getSeriesThumbnail(seriesId)
        if (currentThumbnail != null && thumbnail.bytes.contentHashCode() == currentThumbnail.bytes.contentHashCode()) {
            counters.skippedUnchanged += 1
            return CoverReplaceOutcome(
                thumbnailId = seriesThumbnailsRepository.findFor(seriesId)?.thumbnailId,
                uploaded = false,
                counters = counters
            )
        }

        counters.changed += 1
        val matchedSeries = seriesThumbnailsRepository.findFor(seriesId)
        val thumbnails = mediaServerClient.getSeriesThumbnails(seriesId)

        val selectThumbnail = overrideExistingCovers || thumbnails.isEmpty()

        val uploadedThumbnail = mediaServerClient.uploadSeriesThumbnail(
            seriesId = seriesId,
            thumbnail = thumbnail,
            selected = selectThumbnail,
            lock = lockSeriesCover,
        )
        counters.written += 1

        matchedSeries?.thumbnailId?.let { thumb ->
            if (thumbnails.any { it.id == thumb }) {
                mediaServerClient.deleteSeriesThumbnail(seriesId, thumb)
            }
        }

        return CoverReplaceOutcome(
            thumbnailId = uploadedThumbnail?.id,
            uploaded = true,
            counters = counters
        )
    }

    private suspend fun resetSeriesMetadata(series: MediaServerSeries, removeComicInfo: Boolean) {
        mediaServerClient.resetSeriesMetadata(series.id, series.name)

        mediaServerClient.getBooks(series.id)
            .sortedWith(compareBy(natSortComparator) { it.name })
            .forEachIndexed { index, book ->
                if (removeComicInfo) comicInfoWriter.removeComicInfo(book.url)
                resetBookMetadata(book, index + 1)
            }

        replaceSeriesThumbnail(series.id, null)
        seriesThumbnailsRepository.delete(series.id)
    }

    private suspend fun resetBookMetadata(book: MediaServerBook, sortNumber: Int?) {
        mediaServerClient.resetBookMetadata(book.id, book.name, sortNumber)

        replaceBookThumbnail(book.id, null)
        bookThumbnailsRepository.delete(book.id)
    }

    private fun bookToWriteSeriesMetadata(bookMetadata: Map<MediaServerBook, BookMetadata?>): MediaServerBookId? {
        if (updateModes.none { it == UpdateMode.COMIC_INFO }) return null

        val books = bookMetadata.keys.sortedWith(compareBy(natSortComparator) { it.name })
        val firstBook = books.asSequence()
            .mapNotNull { book -> BookNameParser.getVolumes(book.name)?.let { book to it } }
            .map { (book, range) -> book to range.start }
            .filter { (_, number) -> floor(number) == number && number.toInt() == 1 }
            .map { (book, _) -> book }
            .firstOrNull()

        return firstBook?.id
            ?: if (bookMetadata.any { it.value != null }) null
            else books.firstOrNull()?.id
    }
}

internal data class VolumeCoverUploadCandidate(
    val sourceSeriesId: MediaServerSeriesId,
    val sourceBookId: MediaServerBookId,
    val targetVolumeId: String,
    val thumbnail: Image,
)

internal data class VolumeCoverUploadPlan(
    val sourceSeriesId: MediaServerSeriesId,
    val sourceBookId: MediaServerBookId,
    val targetVolumeId: String,
    val imageHash: String,
    val thumbnail: Image,
)

internal data class VolumeCoverUploadDedupeResult(
    val candidateCount: Int,
    val skippedDuplicateCount: Int,
    val uploadPlans: List<VolumeCoverUploadPlan>,
)

internal fun dedupeVolumeCoverUploadCandidates(
    candidates: List<VolumeCoverUploadCandidate>
): VolumeCoverUploadDedupeResult {
    val uniquePlans = LinkedHashMap<String, VolumeCoverUploadPlan>()
    var duplicateCount = 0

    candidates.forEach { candidate ->
        val imageHash = hashBytes(candidate.thumbnail.bytes)
        val dedupeKey = "${candidate.targetVolumeId}|$imageHash"
        val plan = VolumeCoverUploadPlan(
            sourceSeriesId = candidate.sourceSeriesId,
            sourceBookId = candidate.sourceBookId,
            targetVolumeId = candidate.targetVolumeId,
            imageHash = imageHash,
            thumbnail = candidate.thumbnail
        )
        if (uniquePlans.putIfAbsent(dedupeKey, plan) != null) {
            duplicateCount += 1
        }
    }

    return VolumeCoverUploadDedupeResult(
        candidateCount = candidates.size,
        skippedDuplicateCount = duplicateCount,
        uploadPlans = uniquePlans.values.toList()
    )
}

private fun MediaServerSeriesMetadataUpdate.hasChangesComparedTo(current: MediaServerSeriesMetadata): Boolean {
    val currentAltTitles = current.alternativeTitles
        .map { it.title.trim().lowercase() }
        .toSet()
    val currentAuthors = current.authors
        .map { it.name.trim().lowercase() to it.role.trim().lowercase() }
        .toSet()
    val currentLinks = current.links.map { it.url.trim().lowercase() }.toSet()

    fun linksChanged(update: Collection<WebLink>) = update.map { it.url.trim().lowercase() }.toSet() != currentLinks
    fun titlesChanged(update: Collection<SeriesTitle>) =
        update.map { it.name.trim().lowercase() }.toSet() != currentAltTitles

    return (status != null && status != current.status) ||
        (title != null && title.name != current.title) ||
        (titleSort != null && titleSort.name != current.titleSort) ||
        (alternativeTitles != null && titlesChanged(alternativeTitles)) ||
        (summary != null && summary != current.summary) ||
        (publisher != null && publisher != current.publisher) ||
        (alternativePublishers != null && alternativePublishers.toSet() != current.alternativePublishers) ||
        (ageRating != null && ageRating != current.ageRating) ||
        (language != null && language != current.language) ||
        (genres != null && genres.toSet() != current.genres.toSet()) ||
        (tags != null && tags.toSet() != current.tags.toSet()) ||
        (totalBookCount != null && totalBookCount != current.totalBookCount) ||
        (authors != null && authors.map { it.name.trim().lowercase() to it.role.trim().lowercase() }.toSet() != currentAuthors) ||
        (releaseYear != null && releaseYear != current.releaseYear) ||
        (links != null && linksChanged(links)) ||
        (statusLock != null && statusLock != current.statusLock) ||
        (titleLock != null && titleLock != current.titleLock) ||
        (titleSortLock != null && titleSortLock != current.titleSortLock) ||
        (alternativeTitlesLock != null && alternativeTitlesLock != current.alternativeTitlesLock) ||
        (summaryLock != null && summaryLock != current.summaryLock) ||
        (readingDirectionLock != null && readingDirectionLock != current.readingDirectionLock) ||
        (publisherLock != null && publisherLock != current.publisherLock) ||
        (ageRatingLock != null && ageRatingLock != current.ageRatingLock) ||
        (languageLock != null && languageLock != current.languageLock) ||
        (genresLock != null && genresLock != current.genresLock) ||
        (tagsLock != null && tagsLock != current.tagsLock) ||
        (totalBookCountLock != null && totalBookCountLock != current.totalBookCountLock) ||
        (authorsLock != null && authorsLock != current.authorsLock) ||
        (releaseYearLock != null && releaseYearLock != current.releaseYearLock) ||
        (linksLock != null && linksLock != current.linksLock)
}

private fun MediaServerBookMetadataUpdate.hasChangesComparedTo(current: MediaServerBookMetadata): Boolean {
    val currentAuthors = current.authors
        .map { it.name.trim().lowercase() to it.role.trim().lowercase() }
        .toSet()
    val currentTags = current.tags.map { it.trim().lowercase() }.toSet()
    val currentLinks = current.links.map { it.url.trim().lowercase() }.toSet()

    fun numberSortAsDouble(value: String?) = value?.toDoubleOrNull()

    return (title != null && title != current.title) ||
        (summary != null && summary != current.summary) ||
        (number != null && number != current.number) ||
        (numberSort != null && numberSort != numberSortAsDouble(current.numberSort)) ||
        (releaseDate != null && releaseDate != current.releaseDate) ||
        (authors != null && authors.map { it.name.trim().lowercase() to it.role.trim().lowercase() }.toSet() != currentAuthors) ||
        (tags != null && tags.map { it.trim().lowercase() }.toSet() != currentTags) ||
        (isbn != null && isbn != current.isbn) ||
        (links != null && links.map { it.url.trim().lowercase() }.toSet() != currentLinks) ||
        (titleLock != null && titleLock != current.titleLock) ||
        (summaryLock != null && summaryLock != current.summaryLock) ||
        (numberLock != null && numberLock != current.numberLock) ||
        (numberSortLock != null && numberSortLock != current.numberSortLock) ||
        (releaseDateLock != null && releaseDateLock != current.releaseDateLock) ||
        (authorsLock != null && authorsLock != current.authorsLock) ||
        (tagsLock != null && tagsLock != current.tagsLock) ||
        (isbnLock != null && isbnLock != current.isbnLock) ||
        (linksLock != null && linksLock != current.linksLock)
}

private fun SeriesAndBookMetadata.toDesiredHash(seriesId: MediaServerSeriesId): String {
    val scopeHashes = toScopeDesiredHashes(seriesId)
    val normalized = buildString {
        append("seriesId=").append(seriesId.value).append('\n')
        LibraryApplyScope.entries.forEach { scope ->
            append(scope.name).append('=').append(scopeHashes[scope]).append('\n')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun SeriesAndBookMetadata.toScopeDesiredHashes(seriesId: MediaServerSeriesId): Map<LibraryApplyScope, String> {
    val seriesMetaBase = normalizeSeriesMetadataWithoutThumbnail(seriesMetadata)
    val seriesThumbnailValue = normalizeSeriesThumbnail(seriesMetadata)
    val volumeCoverValue = bookMetadata.entries
        .sortedBy { it.key.id.value }
        .joinToString(separator = "|") { (book, metadata) ->
            "${book.id.value}:${normalizeBookThumbnail(metadata)}"
        }
    val chapterMetadataValue = bookMetadata.entries
        .sortedBy { it.key.id.value }
        .joinToString(separator = "|") { (book, metadata) ->
            "${book.id.value}:${normalizeBookMetadataWithoutThumbnail(metadata)}"
        }

    return mapOf(
        LibraryApplyScope.SERIES_METADATA to hashValue("seriesId=${seriesId.value}|seriesMeta=$seriesMetaBase"),
        LibraryApplyScope.SERIES_COVER to hashValue("seriesId=${seriesId.value}|seriesCover=$seriesThumbnailValue"),
        LibraryApplyScope.VOLUME_COVERS to hashValue("seriesId=${seriesId.value}|volumeCovers=$volumeCoverValue"),
        LibraryApplyScope.CHAPTER_METADATA to hashValue("seriesId=${seriesId.value}|chapterMeta=$chapterMetadataValue")
    )
}

private fun hashValue(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun hashBytes(value: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value)
    return digest.joinToString("") { "%02x".format(it) }
}

private fun normalizeSeriesThumbnail(metadata: SeriesMetadata): String {
    return metadata.thumbnail?.bytes?.contentHashCode()?.toString() ?: "null"
}

private fun normalizeBookThumbnail(metadata: BookMetadata?): String {
    return metadata?.thumbnail?.bytes?.contentHashCode()?.toString() ?: "null"
}

private fun normalizeSeriesMetadataWithoutThumbnail(metadata: SeriesMetadata): String {
    fun sorted(values: Collection<String>) = values.map { it.trim().lowercase() }.sorted().joinToString(",")
    val titles = metadata.titles
        .sortedWith(compareBy({ it.type?.name ?: "" }, { it.language ?: "" }, { it.name }))
        .joinToString(";") { "${it.type}:${it.language}:${it.name.trim()}" }
    val authors = metadata.authors
        .sortedWith(compareBy({ it.role.name }, { it.name }))
        .joinToString(";") { "${it.role.name}:${it.name.trim()}" }
    val links = metadata.links.sortedBy { it.url }.joinToString(";") { "${it.label}:${it.url}" }
    return listOf(
        metadata.status?.name ?: "",
        metadata.title?.name ?: "",
        titles,
        metadata.summary ?: "",
        metadata.publisher?.name ?: "",
        sorted(metadata.alternativePublishers.map { it.name }),
        metadata.readingDirection?.name ?: "",
        metadata.ageRating?.toString() ?: "",
        metadata.language ?: "",
        sorted(metadata.genres),
        sorted(metadata.tags),
        metadata.totalBookCount?.toString() ?: "",
        authors,
        metadata.releaseDate?.year?.toString() ?: "",
        links
    ).joinToString("|")
}

private fun normalizeBookMetadataWithoutThumbnail(metadata: BookMetadata?): String {
    if (metadata == null) return "null"
    fun sorted(values: Collection<String>) = values.map { it.trim().lowercase() }.sorted().joinToString(",")
    val authors = metadata.authors
        .sortedWith(compareBy({ it.role.name }, { it.name }))
        .joinToString(";") { "${it.role.name}:${it.name.trim()}" }
    val links = metadata.links.sortedBy { it.url }.joinToString(";") { "${it.label}:${it.url}" }
    val arcs = metadata.storyArcs
        ?.sortedWith(compareBy({ it.number }, { it.name }))
        ?.joinToString(";") { "${it.number}:${it.name}" }
        ?: ""
    return listOf(
        metadata.title ?: "",
        metadata.summary ?: "",
        metadata.number?.toString() ?: "",
        metadata.numberSort?.toString() ?: "",
        metadata.releaseDate?.toString() ?: "",
        authors,
        sorted(metadata.tags),
        metadata.isbn ?: "",
        links,
        arcs
    ).joinToString("|")
}
