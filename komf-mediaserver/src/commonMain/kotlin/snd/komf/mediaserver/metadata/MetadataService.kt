package snd.komf.mediaserver.metadata

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import snd.komf.mediaserver.MediaServerClient
import snd.komf.mediaserver.jobs.KomfJobTracker
import snd.komf.mediaserver.jobs.MetadataJobEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.CompletionEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.PostProcessingStartEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.ProcessingErrorEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.ProviderBookEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.ProviderCompletedEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.ProviderErrorEvent
import snd.komf.mediaserver.jobs.MetadataJobEvent.ProviderSeriesEvent
import snd.komf.mediaserver.jobs.MetadataJobId
import snd.komf.mediaserver.metadata.repository.SeriesMatchRepository
import snd.komf.mediaserver.model.MediaServerBook
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeries
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.mediaserver.model.SeriesAndBookMetadata
import snd.komf.model.BookMetadata
import snd.komf.model.BookQualifier
import snd.komf.model.BookRange
import snd.komf.model.Image
import snd.komf.model.MatchQuery
import snd.komf.model.MatchType.MANUAL
import snd.komf.model.MediaType
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.SeriesBook
import snd.komf.model.SeriesSearchResult
import snd.komf.providers.CoreProviders
import snd.komf.providers.MetadataProvider
import snd.komf.providers.ProvidersModule
import snd.komf.util.BookNameParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

internal fun bookNumberFromName(bookName: String, libraryType: MediaType): BookRange? {
    return when (libraryType) {
        MediaType.MANGA -> BookNameParser.getVolumes(bookName)
        MediaType.NOVEL, MediaType.COMIC -> BookNameParser.getBookNumber(bookName)
        MediaType.WEBTOON -> BookNameParser.getChapters(bookName)
            ?: BookNameParser.getBookNumber(bookName)
    }
}

internal fun bookNumberForMatching(book: MediaServerBook, libraryType: MediaType): BookRange? {
    val parsedFromName = bookNumberFromName(book.name, libraryType)
    return parsedFromName ?: if (book.number > 0) BookRange(book.number) else null
}

internal fun associateBookMetadataByNumber(
    books: Collection<MediaServerBook>,
    providerBooks: Collection<SeriesBook>,
    edition: String? = null,
    libraryType: MediaType
): Map<MediaServerBook, SeriesBook?> {
    val editionBooks = providerBooks.groupBy { it.edition }

    if (edition != null) {
        val editionName = edition.replace("(?i)\\s?[EÉ]dition\\s?".toRegex(), "").lowercase()
        return books.associateWith { book ->
            val bookNumber = bookNumberForMatching(book, libraryType)
            editionBooks[editionName]?.firstOrNull { it.number != null && bookNumber == it.number }
        }
    }

    if (books.size == 1 && providerBooks.size == 1) {
        val mediaServerBook = books.first()
        val chapterNumber = BookNameParser.getChapters(mediaServerBook.name)

        return if (chapterNumber == null) {
            mapOf(books.first() to providerBooks.first())
        } else {
            mapOf(books.first() to null)
        }
    }

    return books.associateWith { book ->
        val bookExtraData = BookNameParser.getExtraData(book.name).map { it.lowercase() }
        editionBooks.keys.firstOrNull { bookExtraData.contains(it) }
    }.map { (book, editionName) ->
        val bookNumber = bookNumberForMatching(book, libraryType)
        val providerBook = editionBooks[editionName]
            ?.firstOrNull { it.number != null && it.number == bookNumber }
        book to providerBook
    }.toMap()
}

data class SkippedSeriesEntry(
    val oldSeriesId: MediaServerSeriesId,
    val hintedName: String?,
    val hintedSortName: String?,
    val reason: String,
    val observedAtEpochMs: Long,
)

data class RetrySkippedSeriesResult(
    val totalSkipped: Int,
    val resolved: Int,
    val retried: Int,
    val unresolved: Int,
    val unresolvedSeriesIds: Collection<MediaServerSeriesId>,
    val retryJobIds: Collection<MetadataJobId>,
)

data class LibraryRunSummary(
    val libraryId: MediaServerLibraryId,
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
    val skippedSeriesIds: Collection<MediaServerSeriesId>,
)

data class LibraryRunCheckpoint(
    val pageNumber: Int,
    val startIndexInPage: Int,
    val dryRun: Boolean,
    val updatedAtEpochMs: Long,
)

enum class LibraryRunResumeMode {
    CONTINUE,
    NEW,
}

data class LibraryRunControlStatus(
    val active: Boolean,
    val paused: Boolean,
    val stopRequested: Boolean,
    val hasCheckpoint: Boolean,
    val checkpoint: LibraryRunCheckpoint?,
)

private data class ActiveLibraryRun(
    val pauseRequested: AtomicBoolean = AtomicBoolean(false),
    val stopRequested: AtomicBoolean = AtomicBoolean(false),
)

class MetadataService(
    private val mediaServerClient: MediaServerClient,
    private val metadataProviders: ProvidersModule.MetadataProviders,
    private val aggregateMetadata: Boolean,
    private val metadataMerger: MetadataMerger,
    private val metadataUpdateService: MetadataUpdater,
    private val seriesMatchRepository: SeriesMatchRepository,
    private val libraryType: MediaType,
    private val jobTracker: KomfJobTracker,
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val skippedSeriesByLibrary = ConcurrentHashMap<String, MutableList<SkippedSeriesEntry>>()
    private val libraryRunSummaries = ConcurrentHashMap<String, MutableList<LibraryRunSummary>>()
    private val activeLibraryRuns = ConcurrentHashMap<String, ActiveLibraryRun>()
    private val libraryRunCheckpoints = ConcurrentHashMap<String, LibraryRunCheckpoint>()

    fun availableProviders(libraryId: MediaServerLibraryId) = metadataProviders.providers(libraryId.value)
    fun availableProviders() = metadataProviders.defaultProvidersList()

    suspend fun searchSeriesMetadata(
        seriesName: String,
        libraryId: MediaServerLibraryId
    ): Collection<SeriesSearchResult> {
        val providers = metadataProviders.providers(libraryId.value)

        return providers
            .map { coroutineScope.async { it.searchSeries(seriesName) } }
            .flatMap { it.await() }
    }

    suspend fun searchSeriesMetadata(seriesName: String): Collection<SeriesSearchResult> {
        val providers = metadataProviders.defaultProvidersList()
        return providers
            .map { coroutineScope.async { it.searchSeries(seriesName) } }
            .flatMap { it.await() }
    }

    suspend fun getSeriesCover(
        libraryId: MediaServerLibraryId,
        providerName: CoreProviders,
        providerSeriesId: ProviderSeriesId,
    ): Image? {
        val provider = checkNotNull(metadataProviders.provider(libraryId.value, providerName)) {
            "Provider $providerName is not enabled for library $libraryId"
        }
        return provider.getSeriesCover(providerSeriesId)
    }

    fun setSeriesMetadata(
        seriesId: MediaServerSeriesId,
        providerName: CoreProviders,
        providerSeriesId: ProviderSeriesId,
        edition: String?
    ): MetadataJobId {
        val jobId = launchJob(seriesId) { eventFlow ->
            val context = loadSeriesContextOrSkip(seriesId) ?: return@launchJob
            val (series, books) = context
            val seriesTitle = series.metadata.title.ifBlank { series.name }
            logger.info { "Setting metadata for series \"${seriesTitle}\" ${series.id} using $providerName $providerSeriesId" }
            val provider =
                metadataProviders.provider(series.libraryId.value, providerName) ?: throw RuntimeException()

            val seriesMetadata = getSeriesMetadata(provider, providerSeriesId, eventFlow)
            val bookMetadata = getBookMetadata(books, seriesMetadata, provider, edition, eventFlow)
            eventFlow.emit(ProviderCompletedEvent(providerName))

            val metadata = if (aggregateMetadata) {
                aggregateMetadataFromProviders(
                    series = series,
                    books = books,
                    metadata = SeriesAndBookMetadata(seriesMetadata.metadata, bookMetadata),
                    providers = metadataProviders.providers(series.libraryId.value).filter { it != provider },
                    edition = edition,
                    eventFlow = eventFlow
                )
            } else SeriesAndBookMetadata(seriesMetadata.metadata, bookMetadata)

            eventFlow.emit(PostProcessingStartEvent)
            metadataUpdateService.updateMetadata(series, metadata)
            seriesMatchRepository.save(
                seriesId = series.id,
                type = MANUAL,
                provider = providerName,
                providerSeriesId = providerSeriesId,
            )
            logger.info { "finished metadata update of series \"${seriesTitle}\" ${series.id}" }
        }
        return jobId
    }

    fun matchLibraryMetadata(libraryId: MediaServerLibraryId, dryRun: Boolean = false) {
        if (activeLibraryRuns.containsKey(libraryId.value)) {
            logger.warn { "Library match already active for ${libraryId.value}; ignoring duplicate start request." }
            return
        }
        coroutineScope.launch {
            val runControl = ActiveLibraryRun()
            activeLibraryRuns[libraryId.value] = runControl
            try {
                val checkpoint = libraryRunCheckpoints.remove(libraryId.value)
                val startPageNumber = 1
                val startIndexInPage = 0
                val effectiveDryRun = dryRun

                if (runControl.stopRequested.get()) {
                    logger.warn { "Skipping library match for ${libraryId.value}: stop was requested before start." }
                    saveCheckpoint(libraryId, startPageNumber, startIndexInPage, effectiveDryRun)
                    return@launch
                }
                if (!waitForRunWindow("match library", libraryId, runControl)) {
                    logger.warn { "Skipping library match for ${libraryId.value}: interrupted before start." }
                    saveCheckpoint(libraryId, startPageNumber, startIndexInPage, effectiveDryRun)
                    return@launch
                }

                if (checkpoint == null || checkpoint.dryRun != effectiveDryRun) {
                    clearSkippedSeries(libraryId)
                } else {
                    logger.info {
                        "Resuming from checkpoint for ${libraryId.value} at page ${checkpoint.pageNumber}, index ${checkpoint.startIndexInPage}"
                    }
                }
                val startedAtEpochMs = System.currentTimeMillis()
                var errorCount = 0
                var pageNumber = checkpoint?.pageNumber ?: startPageNumber
                var pageStartIndex = checkpoint?.startIndexInPage ?: startIndexInPage
                var totalSeries = 0
                var processedSeries = 0
                var updatedSeries = 0
                var unmatchedSeries = 0
                var providerErrors = 0
                var processingErrors = 0
                var unexpectedErrors = 0
                val deferredScans = mutableListOf<Pair<MediaServerLibraryId, MediaServerSeriesId>>()
                var abortedBySafetyTimeout = false
                var stoppedByUser = false
                var hasMorePages = true
                pageLoop@ do {
                    if (runControl.stopRequested.get()) {
                        stoppedByUser = true
                        saveCheckpoint(libraryId, pageNumber, pageStartIndex, effectiveDryRun)
                        break@pageLoop
                    }
                    if (!waitForRunWindow("match library", libraryId, runControl)) {
                        abortedBySafetyTimeout = true
                        saveCheckpoint(libraryId, pageNumber, pageStartIndex, effectiveDryRun)
                        break@pageLoop
                    }
                    val page = mediaServerClient.getSeries(libraryId, pageNumber)
                    totalSeries += page.content.size
                    if (page.content.isEmpty()) {
                        hasMorePages = false
                        break@pageLoop
                    }
                    var nextIndexForCheckpoint = 0
                    for (seriesEntry in page.content) {
                        if (nextIndexForCheckpoint < pageStartIndex) {
                            nextIndexForCheckpoint++
                            continue
                        }
                        if (runControl.stopRequested.get()) {
                            stoppedByUser = true
                            saveCheckpoint(libraryId, pageNumber, nextIndexForCheckpoint, effectiveDryRun)
                            break@pageLoop
                        }
                        if (!waitForRunWindow("match library", libraryId, runControl)) {
                            abortedBySafetyTimeout = true
                            saveCheckpoint(libraryId, pageNumber, nextIndexForCheckpoint, effectiveDryRun)
                            break
                        }
                        runCatching {
                        var sawPostProcessing = false
                        var sawProviderCompleted = false
                        var sawProviderError = false
                        var sawProcessingError = false

                        jobTracker.getMetadataJobEvents(
                            matchSeriesMetadata(
                                seriesEntry.id,
                                deferScans = true,
                                dryRun = effectiveDryRun,
                                libraryIdHint = libraryId,
                                seriesHint = seriesEntry
                            )
                        )
                            ?.takeWhile { it !is CompletionEvent }
                            ?.collect { event ->
                                when (event) {
                                    is PostProcessingStartEvent -> sawPostProcessing = true
                                    is ProviderCompletedEvent -> sawProviderCompleted = true
                                    is ProviderErrorEvent -> sawProviderError = true
                                    is ProcessingErrorEvent -> sawProcessingError = true
                                    else -> {}
                                }
                            }

                        processedSeries += 1
                        val wasSkipped = getSkippedSeries(libraryId).any { entry -> entry.oldSeriesId == seriesEntry.id }
                        if (wasSkipped) return@runCatching

                        if (!effectiveDryRun && sawPostProcessing) updatedSeries += 1
                        if (!sawPostProcessing && !sawProviderCompleted && !sawProviderError && !sawProcessingError) {
                            unmatchedSeries += 1
                        }
                        if (sawProviderError) providerErrors += 1
                        if (sawProcessingError) processingErrors += 1
                    }
                            .onFailure {
                            logger.error(it) { }
                            errorCount += 1
                            unexpectedErrors += 1
                        }
                        if (!effectiveDryRun) deferredScans.add(libraryId to seriesEntry.id)
                        nextIndexForCheckpoint++
                    }
                    if (abortedBySafetyTimeout) break@pageLoop
                    if (stoppedByUser) break@pageLoop
                    pageStartIndex = 0
                    pageNumber++
                    hasMorePages = page.pageNumber != page.totalPages
                } while (hasMorePages)
            
            // Execute all deferred scans after library processing is complete
            if (!effectiveDryRun && !stoppedByUser && !abortedBySafetyTimeout && mediaServerClient is snd.komf.mediaserver.kavita.KavitaMediaServerClientAdapter) {
                mediaServerClient.executeDeferredScans(deferredScans)
            }
            
            if (effectiveDryRun) {
                logger.info { "Finished dry-run library match for $libraryId. Encountered $errorCount errors. No metadata was written and no scans were triggered." }
            } else {
                logger.info { "Finished library scan. Encountered $errorCount errors" }
            }
            if (abortedBySafetyTimeout) {
                logger.warn {
                    "Library match for ${libraryId.value} stopped early due to active Kavita activity wait timeout."
                }
            }
            if (stoppedByUser) {
                logger.warn { "Library match for ${libraryId.value} stopped by user request." }
            }
            if (!stoppedByUser && !abortedBySafetyTimeout) {
                libraryRunCheckpoints.remove(libraryId.value)
            }

            val skippedSeriesIds = getSkippedSeries(libraryId).map { it.oldSeriesId }
            val summary = LibraryRunSummary(
                libraryId = libraryId,
                startedAtEpochMs = startedAtEpochMs,
                finishedAtEpochMs = System.currentTimeMillis(),
                dryRun = effectiveDryRun,
                totalSeries = totalSeries,
                processedSeries = processedSeries,
                updatedSeries = updatedSeries,
                skippedSeries = skippedSeriesIds.size,
                unmatchedSeries = unmatchedSeries,
                providerErrors = providerErrors,
                processingErrors = processingErrors,
                unexpectedErrors = unexpectedErrors,
                skippedSeriesIds = skippedSeriesIds
            )
                rememberLibraryRunSummary(summary)
            } finally {
                activeLibraryRuns.remove(libraryId.value)
            }
        }
    }

    fun matchSeriesMetadata(
        seriesId: MediaServerSeriesId,
        deferScans: Boolean = false,
        dryRun: Boolean = false,
        libraryIdHint: MediaServerLibraryId? = null,
        seriesHint: MediaServerSeries? = null,
    ): MetadataJobId {

        val jobId = launchJob(seriesId) { eventFlow ->
            val context = loadSeriesContextOrSkip(
                seriesId = seriesId,
                libraryIdHint = libraryIdHint,
                seriesHint = seriesHint
            ) ?: return@launchJob
            val (series, books) = context
            val seriesTitle = series.metadata.title.ifBlank { series.name }

            val existingMatch = seriesMatchRepository.findManualFor(seriesId)
            val matchProvider =
                existingMatch?.provider?.let { metadataProviders.provider(series.libraryId.value, it) }

            val matchResult = if (existingMatch != null && matchProvider != null) {
                logger.info { "using ${matchProvider.providerName()} from previous manual identification for $seriesTitle ${series.id}" }
                val seriesMetadata = getSeriesMetadata(matchProvider, existingMatch.providerSeriesId, eventFlow)
                val bookMetadata = getBookMetadata(books, seriesMetadata, matchProvider, null, eventFlow)
                matchProvider to SeriesAndBookMetadata(seriesMetadata.metadata, bookMetadata)
            } else {
                val searchTitles = listOfNotNull(
                    seriesTitle,
                    removeParentheses(seriesTitle).let { if (it == seriesTitle) null else it }
                ).plus(series.metadata.alternativeTitles.map { it.title })

                logger.info { "attempting to match series \"${seriesTitle}\" ${series.id}" }

                metadataProviders.providers(series.libraryId.value).firstNotNullOfOrNull { provider ->
                    matchSeries(series, books, searchTitles, provider, null, eventFlow)
                        ?.let { provider to it }
                }
            }

            if (matchResult == null) {
                logger.info { "no match found for series $seriesTitle ${series.id}" }
                return@launchJob
            }
            eventFlow.emit(ProviderCompletedEvent(matchResult.first.providerName()))

            val metadata = matchResult.let { (provider, metadata) ->
                if (aggregateMetadata) {
                    aggregateMetadataFromProviders(
                        series = series,
                        books = books,
                        metadata = metadata,
                        providers = metadataProviders.providers(series.libraryId.value).filter { it != provider },
                        edition = null,
                        eventFlow = eventFlow
                    )
                } else metadata
            }

            if (dryRun) {
                logger.info {
                    "dry-run match for series \"${seriesTitle}\" ${series.id}: " +
                        "would update using provider ${matchResult.first.providerName()} " +
                        "(books=${books.size}, deferScan=$deferScans)"
                }
                return@launchJob
            }

            eventFlow.emit(PostProcessingStartEvent)
            metadataUpdateService.updateMetadata(series, metadata, deferScan = deferScans)
            logger.info { "finished metadata update of series \"${seriesTitle}\" ${series.id}" }
        }

        return jobId
    }

    fun getSkippedSeries(libraryId: MediaServerLibraryId): List<SkippedSeriesEntry> {
        return skippedSeriesByLibrary[libraryId.value]?.toList().orEmpty()
    }

    fun clearSkippedSeries(libraryId: MediaServerLibraryId): Int {
        return skippedSeriesByLibrary.remove(libraryId.value)?.size ?: 0
    }

    fun pauseLibraryRun(libraryId: MediaServerLibraryId): Boolean {
        val run = activeLibraryRuns[libraryId.value] ?: return false
        run.pauseRequested.set(true)
        return true
    }

    fun stopLibraryRun(libraryId: MediaServerLibraryId): Boolean {
        val run = activeLibraryRuns[libraryId.value] ?: return false
        run.stopRequested.set(true)
        return true
    }

    fun resumeLibraryRun(libraryId: MediaServerLibraryId, mode: LibraryRunResumeMode): Boolean {
        val run = activeLibraryRuns[libraryId.value]
        if (run != null) {
            run.pauseRequested.set(false)
            if (mode == LibraryRunResumeMode.NEW) {
                run.stopRequested.set(true)
                libraryRunCheckpoints.remove(libraryId.value)
                matchLibraryMetadata(libraryId, dryRun = false)
            }
            return true
        }

        return when (mode) {
            LibraryRunResumeMode.CONTINUE -> {
                if (libraryRunCheckpoints.containsKey(libraryId.value)) {
                    matchLibraryMetadata(libraryId, dryRun = libraryRunCheckpoints[libraryId.value]?.dryRun ?: false)
                    true
                } else false
            }
            LibraryRunResumeMode.NEW -> {
                libraryRunCheckpoints.remove(libraryId.value)
                matchLibraryMetadata(libraryId, dryRun = false)
                true
            }
        }
    }

    fun libraryRunControlStatus(libraryId: MediaServerLibraryId): LibraryRunControlStatus {
        val run = activeLibraryRuns[libraryId.value]
        val checkpoint = libraryRunCheckpoints[libraryId.value]
        return LibraryRunControlStatus(
            active = run != null,
            paused = run?.pauseRequested?.get() == true,
            stopRequested = run?.stopRequested?.get() == true,
            hasCheckpoint = checkpoint != null,
            checkpoint = checkpoint
        )
    }

    fun latestLibraryRunSummary(libraryId: MediaServerLibraryId): LibraryRunSummary? {
        return libraryRunSummaries[libraryId.value]?.lastOrNull()
    }

    fun libraryRunSummaries(libraryId: MediaServerLibraryId, limit: Int = 10): List<LibraryRunSummary> {
        return libraryRunSummaries[libraryId.value]
            ?.takeLast(limit.coerceAtLeast(1))
            ?.asReversed()
            .orEmpty()
    }

    suspend fun retrySkippedSeries(
        libraryId: MediaServerLibraryId,
        dryRun: Boolean = false
    ): RetrySkippedSeriesResult {
        if (!waitForSafeScanWindow("retry skipped series", libraryId)) {
            throw IllegalStateException(
                "Timed out waiting for active Kavita scan to finish before retrying skipped series for library ${libraryId.value}."
            )
        }

        val skipped = getSkippedSeries(libraryId)
        if (skipped.isEmpty()) {
            return RetrySkippedSeriesResult(
                totalSkipped = 0,
                resolved = 0,
                retried = 0,
                unresolved = 0,
                unresolvedSeriesIds = emptyList(),
                retryJobIds = emptyList()
            )
        }

        val currentSeries = getAllLibrarySeries(libraryId)
        val byId = currentSeries.associateBy { it.id.value }
        val byName = currentSeries.groupBy { normalizeSeriesLookupKey(it.metadata.title.ifBlank { it.name }) }
        val bySortName = currentSeries.groupBy { normalizeSeriesLookupKey(it.metadata.titleSort) }

        val resolvedSeries = linkedMapOf<String, MediaServerSeries>()
        val unresolved = mutableListOf<MediaServerSeriesId>()

        skipped.forEach { entry ->
            val existingById = byId[entry.oldSeriesId.value]
            if (existingById != null) {
                resolvedSeries[existingById.id.value] = existingById
                return@forEach
            }

            val nameCandidates = byName[normalizeSeriesLookupKey(entry.hintedName)].orEmpty()
            val sortCandidates = bySortName[normalizeSeriesLookupKey(entry.hintedSortName)].orEmpty()
            val candidates = (nameCandidates + sortCandidates).distinctBy { it.id.value }

            when (candidates.size) {
                1 -> resolvedSeries[candidates.first().id.value] = candidates.first()
                else -> unresolved.add(entry.oldSeriesId)
            }
        }

        val retryJobIds = mutableListOf<MetadataJobId>()
        resolvedSeries.values.forEach { targetSeries ->
            if (!waitForSafeScanWindow("retry skipped series", libraryId)) {
                throw IllegalStateException(
                    "Timed out waiting for active Kavita activity to finish during retry for library ${libraryId.value}."
                )
            }
            retryJobIds += matchSeriesMetadata(
                seriesId = targetSeries.id,
                deferScans = true,
                dryRun = dryRun,
                libraryIdHint = libraryId,
                seriesHint = targetSeries
            )
        }

        if (!dryRun && retryJobIds.isNotEmpty() && mediaServerClient is snd.komf.mediaserver.kavita.KavitaMediaServerClientAdapter) {
            coroutineScope.launch {
                mediaServerClient.executeDeferredScans(resolvedSeries.values.map { libraryId to it.id })
            }
        }

        val unresolvedSet = unresolved.map { it.value }.toMutableSet()
        val remaining = skipped.filter { it.oldSeriesId.value in unresolvedSet }
        if (remaining.isEmpty()) {
            skippedSeriesByLibrary.remove(libraryId.value)
        } else {
            skippedSeriesByLibrary[libraryId.value] = remaining.toMutableList()
        }

        return RetrySkippedSeriesResult(
            totalSkipped = skipped.size,
            resolved = resolvedSeries.size,
            retried = retryJobIds.size,
            unresolved = unresolvedSet.size,
            unresolvedSeriesIds = unresolvedSet.map { MediaServerSeriesId(it) },
            retryJobIds = retryJobIds
        )
    }

    private suspend fun matchSeries(
        series: MediaServerSeries,
        books: Collection<MediaServerBook>,
        searchTitles: Collection<String>,
        provider: MetadataProvider,
        bookEdition: String?,
        eventFlow: MutableSharedFlow<MetadataJobEvent>
    ): SeriesAndBookMetadata? {
        for (searchTitle in searchTitles) {
            logger.info { "searching \"$searchTitle\" using ${provider.providerName()}" }

            eventFlow.emit(ProviderSeriesEvent(provider.providerName()))
            val result = try {
                provider.matchSeriesMetadata(createMatchQuery(searchTitle, series, books))
            } catch (e: Exception) {
                throw ProviderException(provider.providerName(), e)
            }

            if (result != null) {
                logger.info { "found match: \"${result.metadata.titles.firstOrNull()?.name}\" from ${provider.providerName()}  ${result.id}" }
                val bookMetadata = getBookMetadata(books, result, provider, bookEdition, eventFlow)
                return SeriesAndBookMetadata(result.metadata, bookMetadata)
            }
        }
        return null
    }

    private suspend fun getBookMetadata(
        books: Collection<MediaServerBook>,
        seriesMeta: ProviderSeriesMetadata,
        provider: MetadataProvider,
        bookEdition: String?,
        eventFlow: MutableSharedFlow<MetadataJobEvent>,
    ): Map<MediaServerBook, BookMetadata?> {
        val metadataMatch = associateBookMetadata(books, seriesMeta.books, bookEdition)
        if (provider.providerName() == CoreProviders.MANGADEX && libraryType == MediaType.MANGA) {
            metadataMatch.forEach { (book, providerBook) ->
                if (providerBook != null) {
                    logger.info {
                        "matched mangadex volume=${providerBook.number?.start?.toInt()} " +
                            "cover=${providerBook.id.id} bookId=${book.id.value} bookNumber=${book.number}"
                    }
                } else {
                    logger.info {
                        "no volume cover match bookId=${book.id.value} reason=no-provider-volume-for-number " +
                            "bookNumber=${book.number} parsedFromName=${bookNumberFromName(book.name, libraryType)}"
                    }
                }
            }
        }

        val fetchSize = metadataMatch.filterValues { it != null }.size
        var progress = 1
        return try {
            metadataMatch.map { (book, seriesBookMeta) ->
                if (seriesBookMeta != null) {
                    logger.info { "(${provider.providerName()}) fetching metadata for book ${seriesBookMeta.name}" }
                    eventFlow.emit(ProviderBookEvent(provider.providerName(), fetchSize, progress))
                    progress += 1

                    book to provider.getBookMetadata(seriesMeta.id, seriesBookMeta.id).metadata
                } else {
                    book to null
                }
            }.toMap()

        } catch (e: Exception) {
            throw ProviderException(provider.providerName(), e)
        }
    }

    private fun associateBookMetadata(
        books: Collection<MediaServerBook>,
        providerBooks: Collection<SeriesBook>,
        edition: String? = null
    ): Map<MediaServerBook, SeriesBook?> {
        return associateBookMetadataByNumber(
            books = books,
            providerBooks = providerBooks,
            edition = edition,
            libraryType = libraryType
        )
    }

    private suspend fun aggregateMetadataFromProviders(
        series: MediaServerSeries,
        books: Collection<MediaServerBook>,
        metadata: SeriesAndBookMetadata,
        providers: Collection<MetadataProvider>,
        edition: String?,
        eventFlow: MutableSharedFlow<MetadataJobEvent>
    ): SeriesAndBookMetadata {
        if (providers.isEmpty()) return metadata
        logger.info { "launching metadata aggregation using ${providers.map { it.providerName() }}" }

        val searchTitles = metadata.seriesMetadata.titles
            .map { it.name }

        return providers
            .map { provider ->
                coroutineScope.async {
                    matchSeries(
                        series = series,
                        books = books,
                        searchTitles = searchTitles,
                        provider = provider,
                        bookEdition = edition,
                        eventFlow = eventFlow
                    ).also {
                        eventFlow.emit(ProviderCompletedEvent(provider.providerName()))
                    }

                }
            }
            .mapNotNull { it.await() }
            .fold(metadata) { oldMetadata, newMetadata -> mergeMetadata(oldMetadata, newMetadata) }
    }

    private fun mergeMetadata(
        originalMetadata: SeriesAndBookMetadata,
        newMetadata: SeriesAndBookMetadata
    ): SeriesAndBookMetadata {
        val mergedSeries =
            metadataMerger.mergeSeriesMetadata(originalMetadata.seriesMetadata, newMetadata.seriesMetadata)

        val books = originalMetadata.bookMetadata.keys.associateBy { it.id }
        val mergedBooks = metadataMerger.mergeBookMetadata(
            originalMetadata.bookMetadata.map { it.key.id to it.value }.toMap(),
            newMetadata.bookMetadata.map { it.key.id to it.value }.toMap()
        ).map { (bookId, metadata) -> books[bookId]!! to metadata }.toMap()

        return SeriesAndBookMetadata(mergedSeries, mergedBooks)
    }

    private fun removeParentheses(name: String): String {
        return name.replace("[(\\[{]([^)\\]}]+)[)\\]}]".toRegex(), "").trim()
    }

    private suspend fun createMatchQuery(
        searchTitle: String,
        series: MediaServerSeries,
        books: Collection<MediaServerBook>
    ): MatchQuery {
        val (firstBook, range) = books.sortedBy { it.number }.map { book ->
            book to (BookNameParser.getVolumes(book.name) ?: BookRange(book.number))
        }.minBy { (_, number) -> number.start }
        val cover = mediaServerClient.getBookThumbnail(firstBook.id)
        val releaseYear = series.metadata.releaseYear?.let { if (it == 0) null else it }

        return MatchQuery(searchTitle, releaseYear, BookQualifier(firstBook.name, range, cover), series.url)
    }

    private suspend fun getSeriesMetadata(
        provider: MetadataProvider,
        providerSeriesId: ProviderSeriesId,
        eventFlow: MutableSharedFlow<MetadataJobEvent>
    ): ProviderSeriesMetadata {
        eventFlow.emit(ProviderSeriesEvent(provider.providerName()))

        return try {
            provider.getSeriesMetadata(providerSeriesId)
        } catch (e: Exception) {
            throw ProviderException(provider.providerName(), e)
        }

    }

    private fun launchJob(
        seriesId: MediaServerSeriesId,
        block: suspend (eventFlow: MutableSharedFlow<MetadataJobEvent>) -> Unit
    ): MetadataJobId {
        val eventFlow = MutableSharedFlow<MetadataJobEvent>(
            replay = Int.MAX_VALUE,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val jobId = jobTracker.registerMetadataJob(seriesId, eventFlow)

        coroutineScope.launch {
            try {
                block(eventFlow)
            } catch (providerException: ProviderException) {
                val errorMessage = providerException.cause?.let { cause ->
                    if (cause is ResponseException) {
                        "${cause::class.simpleName}: status code ${cause.response.status} ${cause.response.bodyAsText()}"
                    } else {
                        "${cause::class.simpleName}: ${cause.message}"
                    }
                } ?: "Unknown error"

                eventFlow.emit(
                    ProviderErrorEvent(
                        provider = providerException.provider,
                        message = errorMessage
                    )
                )
                logger.catching(providerException)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ResponseException) {
                eventFlow.emit(
                    ProcessingErrorEvent(
                        "${exception::class.simpleName}: status code ${exception.response.status} ${exception.response.bodyAsText()}"
                    )
                )
                logger.catching(exception)
            } catch (exception: Exception) {
                eventFlow.emit(ProcessingErrorEvent("${exception::class.simpleName}: ${exception.message}"))
                logger.catching(exception)
            } finally {
                eventFlow.emit(CompletionEvent)
            }
        }

        return jobId
    }

    private suspend fun loadSeriesContextOrSkip(
        seriesId: MediaServerSeriesId,
        libraryIdHint: MediaServerLibraryId? = null,
        seriesHint: MediaServerSeries? = null,
    ): Pair<MediaServerSeries, Collection<MediaServerBook>>? {
        return try {
            val series = mediaServerClient.getSeries(seriesId)
            val books = mediaServerClient.getBooks(seriesId)
            series to books
        } catch (e: Exception) {
            if (isMissingSeriesError(e)) {
                logger.warn { "Skipping series ${seriesId.value}: not found (likely stale ID / 204)." }
                val libraryId = libraryIdHint ?: seriesHint?.libraryId
                if (libraryId != null) {
                    rememberSkippedSeries(
                        libraryId = libraryId,
                        oldSeriesId = seriesId,
                        hintedName = seriesHint?.metadata?.title?.ifBlank { seriesHint.name },
                        hintedSortName = seriesHint?.metadata?.titleSort,
                        reason = "not found (likely stale ID / 204)"
                    )
                }
                null
            } else {
                throw e
            }
        }
    }

    private fun isMissingSeriesError(e: Throwable): Boolean {
        if (e::class.simpleName == "KavitaResourceNotFoundException") return true

        if (e is ResponseException) {
            val s = e.response.status
            if (s == HttpStatusCode.NoContent || s == HttpStatusCode.NotFound) return true
        }

        if (e::class.simpleName == "NoTransformationFoundException" &&
            (e.message?.contains("204 No Content") == true)
        ) {
            return true
        }

        val c = e.cause
        return c != null && c !== e && isMissingSeriesError(c)
    }

    private suspend fun getAllLibrarySeries(libraryId: MediaServerLibraryId): List<MediaServerSeries> {
        val result = mutableListOf<MediaServerSeries>()
        var pageNumber = 1
        do {
            val page = mediaServerClient.getSeries(libraryId, pageNumber)
            result += page.content
            pageNumber++
        } while (page.pageNumber != page.totalPages && page.content.isNotEmpty())
        return result
    }

    private fun rememberSkippedSeries(
        libraryId: MediaServerLibraryId,
        oldSeriesId: MediaServerSeriesId,
        hintedName: String?,
        hintedSortName: String?,
        reason: String,
    ) {
        val list = skippedSeriesByLibrary.computeIfAbsent(libraryId.value) { mutableListOf() }
        val duplicateIndex = list.indexOfFirst { it.oldSeriesId == oldSeriesId }
        val newEntry = SkippedSeriesEntry(
            oldSeriesId = oldSeriesId,
            hintedName = hintedName,
            hintedSortName = hintedSortName,
            reason = reason,
            observedAtEpochMs = System.currentTimeMillis()
        )
        if (duplicateIndex >= 0) {
            list[duplicateIndex] = newEntry
        } else {
            list.add(newEntry)
        }
    }

    private fun rememberLibraryRunSummary(summary: LibraryRunSummary) {
        val list = libraryRunSummaries.computeIfAbsent(summary.libraryId.value) { mutableListOf() }
        list.add(summary)
        if (list.size > MAX_LIBRARY_RUN_SUMMARY_HISTORY) {
            list.removeAt(0)
        }
    }

    private fun saveCheckpoint(
        libraryId: MediaServerLibraryId,
        pageNumber: Int,
        startIndexInPage: Int,
        dryRun: Boolean
    ) {
        libraryRunCheckpoints[libraryId.value] = LibraryRunCheckpoint(
            pageNumber = pageNumber.coerceAtLeast(1),
            startIndexInPage = startIndexInPage.coerceAtLeast(0),
            dryRun = dryRun,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun waitForRunWindow(
        operation: String,
        libraryId: MediaServerLibraryId,
        runControl: ActiveLibraryRun
    ): Boolean {
        while (runControl.pauseRequested.get()) {
            if (runControl.stopRequested.get()) return false
            logger.warn { "Library run paused for ${libraryId.value}; waiting for resume." }
            kotlinx.coroutines.delay(1000)
        }
        if (runControl.stopRequested.get()) return false
        return waitForSafeScanWindow(operation, libraryId)
    }

    private fun normalizeSeriesLookupKey(value: String?): String {
        if (value == null) return ""
        return value
            .trim()
            .lowercase()
            .replace(SERIES_LOOKUP_NORMALIZE_REGEX, "")
    }

    private suspend fun waitForSafeScanWindow(
        operation: String,
        libraryId: MediaServerLibraryId
    ): Boolean {
        val client = mediaServerClient as? snd.komf.mediaserver.kavita.KavitaMediaServerClientAdapter
            ?: return true
        return client.waitForSafeScanWindow(operation, libraryId)
    }

    companion object {
        private val SERIES_LOOKUP_NORMALIZE_REGEX = "[^\\p{L}0-9]+".toRegex()
        private const val MAX_LIBRARY_RUN_SUMMARY_HISTORY = 50
    }


    private class ProviderException(
        val provider: CoreProviders,
        cause: Throwable,
    ) : RuntimeException(cause)

}
