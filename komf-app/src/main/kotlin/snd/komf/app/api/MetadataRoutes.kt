package snd.komf.app.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import snd.komf.api.KomfErrorResponse
import snd.komf.api.KomfProviderSeriesId
import snd.komf.api.job.KomfMetadataJobId
import snd.komf.api.metadata.KomfIdentifyRequest
import snd.komf.api.metadata.KomfClearSkippedSeriesResponse
import snd.komf.api.metadata.KomfMetadataJobResponse
import snd.komf.api.metadata.KomfLibraryRunSummary
import snd.komf.api.metadata.KomfLibraryRunControlStatus
import snd.komf.api.metadata.KomfLibraryRunCheckpoint
import snd.komf.api.metadata.KomfLibraryApplyMode
import snd.komf.api.metadata.KomfLibraryRunResumeMode
import snd.komf.api.metadata.KomfMetadataSeriesSearchResult
import snd.komf.api.metadata.KomfRetrySkippedSeriesResponse
import snd.komf.api.metadata.KomfSkippedSeriesEntry
import snd.komf.app.api.mappers.fromProvider
import snd.komf.app.api.mappers.toProvider
import snd.komf.comicinfo.ComicInfoWriter.ComicInfoException
import snd.komf.mediaserver.MediaServerClient
import snd.komf.mediaserver.MetadataServiceProvider
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.model.ProviderSeriesId
import snd.komf.providers.CoreProviders

private val logger = KotlinLogging.logger {}

class MetadataRoutes(
    private val metadataServiceProvider: Flow<MetadataServiceProvider>,
    private val mediaServerClient: Flow<MediaServerClient>,
    private val requestRateLimiter: Flow<RequestRateLimiter>,
) {

    fun registerRoutes(routing: Route) {
        routing.route("/metadata") {
            getProvidersRoute()
            searchSeriesRoute()
            getSeriesCoverRoute()
            identifySeriesRoute()

            matchSeriesRoute()
            matchLibraryRoute()
            getSkippedSeriesRoute()
            retrySkippedSeriesRoute()
            clearSkippedSeriesRoute()
            latestLibrarySummaryRoute()
            librarySummaryHistoryRoute()
            libraryRunControlStatusRoute()
            pauseLibraryRunRoute()
            resumeLibraryRunRoute()
            stopLibraryRunRoute()

            resetSeriesRoute()
            resetLibraryRoute()
        }
    }

    private fun Route.getProvidersRoute() {
        get("/providers") {
            if (!call.checkRateLimit()) return@get
            val libraryId = call.request.queryParameters["libraryId"]?.let { MediaServerLibraryId(it) }

            val providers = (
                    libraryId
                        ?.let { metadataServiceProvider.first().metadataServiceFor(it.value).availableProviders(it) }
                        ?: metadataServiceProvider.first().defaultMetadataService().availableProviders()
                    )
                .map { it.providerName().name }

            call.respond(providers)
        }
    }

    private fun Route.searchSeriesRoute() {
        get("/search") {
            if (!call.checkRateLimit()) return@get
            val seriesName = call.request.queryParameters["name"]
                ?: return@get call.response.status(HttpStatusCode.BadRequest)

            val seriesId = call.request.queryParameters["seriesId"]?.let { MediaServerSeriesId(it) }
            val libraryId = call.request.queryParameters["libraryId"]
                ?.let { MediaServerLibraryId(it) }
                ?: seriesId?.let { mediaServerClient.first().getSeries(it).libraryId }

            try {
                val searchResults = libraryId
                    ?.let {
                        metadataServiceProvider.first().metadataServiceFor(it.value).searchSeriesMetadata(seriesName, it)
                    }
                    ?: metadataServiceProvider.first().defaultMetadataService().searchSeriesMetadata(seriesName)

                call.respond(HttpStatusCode.OK, searchResults.map {
                    KomfMetadataSeriesSearchResult(
                        url = it.url,
                        imageUrl = it.imageUrl,
                        title = it.title,
                        provider = it.provider.fromProvider(),
                        resultId = KomfProviderSeriesId(it.resultId)
                    )
                })
            } catch (exception: ResponseException) {
                call.respond(exception.response.status, KomfErrorResponse(exception.response.bodyAsText()))
                logger.catching(exception)
            } catch (exception: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    KomfErrorResponse("${exception::class.simpleName} :${exception.message}")
                )
                logger.catching(exception)
            }
        }
    }

    private fun Route.getSeriesCoverRoute() {
        get("/series-cover") {
            if (!call.checkRateLimit()) return@get
            val libraryId = MediaServerLibraryId(call.request.queryParameters.getOrFail("libraryId"))
            val provider = CoreProviders.valueOf(call.request.queryParameters.getOrFail("provider"))
            val providerSeriesId = ProviderSeriesId(call.request.queryParameters.getOrFail("providerSeriesId"))

            val metadataService = metadataServiceProvider.first().metadataServiceFor(libraryId.value)
            val image = metadataService.getSeriesCover(
                libraryId = libraryId,
                providerName = provider,
                providerSeriesId = providerSeriesId
            )
            image?.bytes?.let { call.respondBytes { it } }
                ?: call.response.status(HttpStatusCode.NotFound)

        }
    }

    private fun Route.identifySeriesRoute() {
        post("/identify") {
            if (!call.checkRateLimit()) return@post
            val request = call.receive<KomfIdentifyRequest>()

            val libraryId = request.libraryId?.value
                ?: mediaServerClient.first().getSeries(MediaServerSeriesId(request.seriesId.value)).libraryId.value

            val applyMode = request.applyMode
                ?.let {
                    when (it) {
                        KomfLibraryApplyMode.CORE -> snd.komf.mediaserver.metadata.LibraryApplyMode.CORE
                        KomfLibraryApplyMode.CHAPTERS -> snd.komf.mediaserver.metadata.LibraryApplyMode.CHAPTERS
                        KomfLibraryApplyMode.FULL -> snd.komf.mediaserver.metadata.LibraryApplyMode.FULL
                    }
                }

            val metadataService = metadataServiceProvider.first().metadataServiceFor(libraryId)
            val jobId = if (applyMode != null) {
                metadataService.setSeriesMetadata(
                    MediaServerSeriesId(request.seriesId.value),
                    request.provider.toProvider(),
                    ProviderSeriesId(request.providerSeriesId.value),
                    null,
                    applyMode
                )
            } else {
                metadataService.setSeriesMetadata(
                    MediaServerSeriesId(request.seriesId.value),
                    request.provider.toProvider(),
                    ProviderSeriesId(request.providerSeriesId.value),
                    null
                )
            }

            call.respond(
                KomfMetadataJobResponse(KomfMetadataJobId(jobId.value.toString()))
            )
        }
    }

    private fun Route.matchSeriesRoute() {
        post("/match/library/{libraryId}/series/{seriesId}") {
            if (!call.checkRateLimit()) return@post

            val libraryId = call.parameters.getOrFail("libraryId")
            val seriesId = MediaServerSeriesId(call.parameters.getOrFail("seriesId"))
            val applyModeRaw = call.queryParameters["applyMode"]
            val applyModeDto = applyModeRaw
                ?.let { runCatching { KomfLibraryApplyMode.valueOf(it.uppercase()) }.getOrNull() }
            if (applyModeRaw != null && applyModeDto == null) {
                call.respond(HttpStatusCode.BadRequest, KomfErrorResponse("Invalid applyMode '$applyModeRaw'. Expected CORE|CHAPTERS|FULL"))
                return@post
            }
            val applyMode = applyModeDto
                ?.let {
                    when (it) {
                        KomfLibraryApplyMode.CORE -> snd.komf.mediaserver.metadata.LibraryApplyMode.CORE
                        KomfLibraryApplyMode.CHAPTERS -> snd.komf.mediaserver.metadata.LibraryApplyMode.CHAPTERS
                        KomfLibraryApplyMode.FULL -> snd.komf.mediaserver.metadata.LibraryApplyMode.FULL
                    }
                }
            val metadataService = metadataServiceProvider.first().metadataServiceFor(libraryId)
            val jobId = if (applyMode != null) {
                metadataService.matchSeriesMetadata(seriesId, applyMode = applyMode)
            } else {
                metadataService.matchSeriesMetadata(seriesId)
            }

            call.respond(
                KomfMetadataJobResponse(KomfMetadataJobId(jobId.value.toString()))
            )
        }
    }

    private fun Route.matchLibraryRoute() {
        post("/match/library/{libraryId}") {
            if (!call.checkRateLimit()) return@post
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val dryRun = call.queryParameters["dryRun"].toBoolean()
            val applyModeRaw = call.queryParameters["applyMode"]
            val applyModeDto = applyModeRaw
                ?.let { runCatching { KomfLibraryApplyMode.valueOf(it.uppercase()) }.getOrNull() }
            if (applyModeRaw != null && applyModeDto == null) {
                call.respond(HttpStatusCode.BadRequest, KomfErrorResponse("Invalid applyMode '$applyModeRaw'. Expected CORE|CHAPTERS|FULL"))
                return@post
            }
            val applyMode = applyModeDto
                ?.let {
                    when (it) {
                        KomfLibraryApplyMode.CORE -> snd.komf.mediaserver.metadata.LibraryApplyMode.CORE
                        KomfLibraryApplyMode.CHAPTERS -> snd.komf.mediaserver.metadata.LibraryApplyMode.CHAPTERS
                        KomfLibraryApplyMode.FULL -> snd.komf.mediaserver.metadata.LibraryApplyMode.FULL
                    }
                }
            metadataServiceProvider.first().metadataServiceFor(libraryId.value)
                .matchLibraryMetadata(libraryId, dryRun = dryRun, applyModeOverride = applyMode)
            call.response.status(HttpStatusCode.Accepted)
        }
    }

    private fun Route.getSkippedSeriesRoute() {
        get("/skipped/library/{libraryId}") {
            if (!call.checkRateLimit()) return@get
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val entries = metadataServiceProvider.first().metadataServiceFor(libraryId.value).getSkippedSeries(libraryId)
            call.respond(
                HttpStatusCode.OK,
                entries.map {
                    KomfSkippedSeriesEntry(
                        oldSeriesId = snd.komf.api.KomfServerSeriesId(it.oldSeriesId.value),
                        hintedName = it.hintedName,
                        hintedSortName = it.hintedSortName,
                        reason = it.reason,
                        observedAtEpochMs = it.observedAtEpochMs
                    )
                }
            )
        }
    }

    private fun Route.retrySkippedSeriesRoute() {
        post("/retry-skipped/library/{libraryId}") {
            if (!call.checkRateLimit()) return@post
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val dryRun = call.queryParameters["dryRun"].toBoolean()
            val result = metadataServiceProvider.first()
                .metadataServiceFor(libraryId.value)
                .retrySkippedSeries(libraryId, dryRun)

            call.respond(
                HttpStatusCode.OK,
                KomfRetrySkippedSeriesResponse(
                    totalSkipped = result.totalSkipped,
                    resolved = result.resolved,
                    retried = result.retried,
                    unresolved = result.unresolved,
                    unresolvedSeriesIds = result.unresolvedSeriesIds.map { snd.komf.api.KomfServerSeriesId(it.value) },
                    retryJobIds = result.retryJobIds.map { KomfMetadataJobId(it.value.toString()) }
                )
            )
        }
    }

    private fun Route.clearSkippedSeriesRoute() {
        delete("/skipped/library/{libraryId}") {
            if (!call.checkRateLimit()) return@delete
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val cleared = metadataServiceProvider.first().metadataServiceFor(libraryId.value).clearSkippedSeries(libraryId)
            call.respond(HttpStatusCode.OK, KomfClearSkippedSeriesResponse(cleared))
        }
    }

    private fun Route.latestLibrarySummaryRoute() {
        get("/summary/library/{libraryId}/latest") {
            if (!call.checkRateLimit()) return@get
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val summary = metadataServiceProvider.first().metadataServiceFor(libraryId.value).latestLibraryRunSummary(libraryId)
            if (summary == null) {
                call.respond(HttpStatusCode.NotFound, KomfErrorResponse("No summary found for library ${libraryId.value}"))
                return@get
            }
            call.respond(HttpStatusCode.OK, summary.toDto())
        }
    }

    private fun Route.librarySummaryHistoryRoute() {
        get("/summary/library/{libraryId}") {
            if (!call.checkRateLimit()) return@get
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 10
            val summaries = metadataServiceProvider.first()
                .metadataServiceFor(libraryId.value)
                .libraryRunSummaries(libraryId, limit)
                .map { it.toDto() }
            call.respond(HttpStatusCode.OK, summaries)
        }
    }

    private fun Route.libraryRunControlStatusRoute() {
        get("/control/library/{libraryId}/status") {
            if (!call.checkRateLimit()) return@get
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val status = metadataServiceProvider.first().metadataServiceFor(libraryId.value).libraryRunControlStatus(libraryId)
            call.respond(HttpStatusCode.OK, status.toDto())
        }
    }

    private fun Route.pauseLibraryRunRoute() {
        post("/control/library/{libraryId}/pause") {
            if (!call.checkRateLimit()) return@post
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val accepted = metadataServiceProvider.first().metadataServiceFor(libraryId.value).pauseLibraryRun(libraryId)
            if (!accepted) {
                call.respond(HttpStatusCode.Conflict, KomfErrorResponse("No active library run to pause"))
                return@post
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }

    private fun Route.resumeLibraryRunRoute() {
        post("/control/library/{libraryId}/resume") {
            if (!call.checkRateLimit()) return@post
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val mode = call.queryParameters["mode"]
                ?.let { runCatching { KomfLibraryRunResumeMode.valueOf(it.uppercase()) }.getOrNull() }
                ?: KomfLibraryRunResumeMode.CONTINUE
            val accepted = metadataServiceProvider.first().metadataServiceFor(libraryId.value).resumeLibraryRun(
                libraryId,
                when (mode) {
                    KomfLibraryRunResumeMode.CONTINUE -> snd.komf.mediaserver.metadata.LibraryRunResumeMode.CONTINUE
                    KomfLibraryRunResumeMode.NEW -> snd.komf.mediaserver.metadata.LibraryRunResumeMode.NEW
                }
            )
            if (!accepted) {
                call.respond(HttpStatusCode.Conflict, KomfErrorResponse("No paused/checkpointed run to resume"))
                return@post
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }

    private fun Route.stopLibraryRunRoute() {
        post("/control/library/{libraryId}/stop") {
            if (!call.checkRateLimit()) return@post
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val accepted = metadataServiceProvider.first().metadataServiceFor(libraryId.value).stopLibraryRun(libraryId)
            if (!accepted) {
                call.respond(HttpStatusCode.Conflict, KomfErrorResponse("No active library run to stop"))
                return@post
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }

    private fun Route.resetSeriesRoute() {
        post("/reset/library/{libraryId}/series/{seriesId}") {
            if (!call.checkRateLimit()) return@post
            val libraryId = call.parameters.getOrFail("libraryId")
            val seriesId = MediaServerSeriesId(call.parameters.getOrFail("seriesId"))
            val removeComicInfo = call.queryParameters["removeComicInfo"].toBoolean()
            try {
                metadataServiceProvider.first().updateServiceFor(libraryId).resetSeriesMetadata(seriesId, removeComicInfo)
            } catch (e: ComicInfoException) {
                call.respond(HttpStatusCode.UnprocessableEntity, KomfErrorResponse(e.message))
                return@post
            }
            call.respond(HttpStatusCode.NoContent, "")
        }
    }

    private fun Route.resetLibraryRoute() {
        post("/reset/library/{libraryId}") {
            if (!call.checkRateLimit()) return@post
            val libraryId = MediaServerLibraryId(call.parameters.getOrFail("libraryId"))
            val removeComicInfo = call.queryParameters["removeComicInfo"].toBoolean()
            metadataServiceProvider.first().updateServiceFor(libraryId.value)
                .resetLibraryMetadata(libraryId, removeComicInfo)
            call.response.status(HttpStatusCode.NoContent)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.checkRateLimit(): Boolean {
        if (requestRateLimiter.first().tryAcquire()) return true
        respond(
            HttpStatusCode.TooManyRequests,
            KomfErrorResponse("Too many requests. Reduce request rate and retry.")
        )
        return false
    }

    private fun snd.komf.mediaserver.metadata.LibraryRunSummary.toDto() = KomfLibraryRunSummary(
        libraryId = snd.komf.api.KomfServerLibraryId(libraryId.value),
        startedAtEpochMs = startedAtEpochMs,
        finishedAtEpochMs = finishedAtEpochMs,
        dryRun = dryRun,
        applyMode = KomfLibraryApplyMode.valueOf(applyMode.name),
        applyModeSource = applyModeSource,
        totalSeries = totalSeries,
        processedSeries = processedSeries,
        updatedSeries = updatedSeries,
        skippedSeries = skippedSeries,
        unmatchedSeries = unmatchedSeries,
        providerErrors = providerErrors,
        processingErrors = processingErrors,
        unexpectedErrors = unexpectedErrors,
        skippedSeriesIds = skippedSeriesIds.map { snd.komf.api.KomfServerSeriesId(it.value) }
    )

    private fun snd.komf.mediaserver.metadata.LibraryRunControlStatus.toDto() = KomfLibraryRunControlStatus(
        active = active,
        paused = paused,
        stopRequested = stopRequested,
        hasCheckpoint = hasCheckpoint,
        checkpoint = checkpoint?.let {
            KomfLibraryRunCheckpoint(
                pageNumber = it.pageNumber,
                startIndexInPage = it.startIndexInPage,
                dryRun = it.dryRun,
                applyMode = KomfLibraryApplyMode.valueOf(it.applyMode.name),
                updatedAtEpochMs = it.updatedAtEpochMs
            )
        }
    )
}
