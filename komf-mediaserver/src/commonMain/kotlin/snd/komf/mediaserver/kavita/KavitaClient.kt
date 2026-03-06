package snd.komf.mediaserver.kavita

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import snd.komf.ktor.rateLimiter
import snd.komf.mediaserver.kavita.model.KavitaChapter
import snd.komf.mediaserver.kavita.model.KavitaChapterId
import snd.komf.mediaserver.kavita.model.KavitaLibrary
import snd.komf.mediaserver.kavita.model.KavitaLibraryId
import snd.komf.mediaserver.kavita.model.KavitaPublicationStatus
import snd.komf.mediaserver.kavita.model.KavitaSeries
import snd.komf.mediaserver.kavita.model.KavitaSeriesDetails
import snd.komf.mediaserver.kavita.model.KavitaSeriesId
import snd.komf.mediaserver.kavita.model.KavitaSeriesMetadata
import snd.komf.mediaserver.kavita.model.KavitaVolume
import snd.komf.mediaserver.kavita.model.KavitaVolumeId
import snd.komf.mediaserver.kavita.model.KavitaAgeRating.UNKNOWN
import snd.komf.mediaserver.kavita.model.request.KavitaChapterMetadataUpdateRequest
import snd.komf.mediaserver.kavita.model.request.KavitaCoverUploadRequest
import snd.komf.mediaserver.kavita.model.request.KavitaSeriesMetadataUpdateRequest
import snd.komf.mediaserver.kavita.model.request.KavitaSeriesUpdateRequest
import snd.komf.model.Image
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class KavitaClient(
    private val ktor: HttpClient,
    private val json: Json,
    private val apiKey: String,
    updateEventsPerMinute: Int = 120,
    scanEventsPerMinute: Int = 30,
) {
    private val updatesRateLimiter = rateLimiter(
        eventsPerInterval = updateEventsPerMinute.coerceAtLeast(1),
        interval = 60.seconds
    )
    private val scanRateLimiter = rateLimiter(
        eventsPerInterval = scanEventsPerMinute.coerceAtLeast(1),
        interval = 60.seconds
    )

    suspend fun getSeries(seriesId: KavitaSeriesId): KavitaSeries {
        val response = ktor.get("api/series/${seriesId.value}")
        if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.NotFound) {
            throw KavitaResourceNotFoundException()
        }
        return response.body()
    }

    suspend fun getSeries(libraryId: KavitaLibraryId, page: Int): KavitaPage<KavitaSeries> {
        val response = ktor.post("api/series/v2") {
            parameter("pageNumber", page)
            parameter("pageSize", "500")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    putJsonArray("statements") {
                        add(
                            buildJsonObject {
                                put("field", 19)
                                put("value", libraryId.value.toString())
                                put("comparison", 0)
                            }
                        )
                    }

                }
            )
        }

        val pagination = response.headers["Pagination"]?.let { json.decodeFromString<KavitaPagination>(it) }
        return KavitaPage(
            content = response.body(),
            currentPage = pagination?.currentPage ?: page,
            itemsPerPage = pagination?.itemsPerPage,
            totalItems = pagination?.totalItems,
            totalPages = pagination?.totalPages
        )
    }

    suspend fun getSeriesCover(seriesId: KavitaSeriesId): Image {
        val response: HttpResponse = ktor.get("api/image/series-cover") {
            parameter("seriesId", seriesId.value)
            parameter("apiKey", apiKey)
        }
        val contentType = response.contentType()
        return Image(response.body<ByteArray>(), contentType?.toString())
    }

    suspend fun updateSeries(seriesUpdate: KavitaSeriesUpdateRequest) {
        updatesRateLimiter.acquire()
        withTransientRetry("api/series/update") {
            ktor.post("api/series/update") {
                contentType(ContentType.Application.Json)
                setBody(seriesUpdate)
            }
        }
    }

    suspend fun updateSeriesMetadata(metadata: KavitaSeriesMetadataUpdateRequest) {
        updatesRateLimiter.acquire()
        withTransientRetry("api/series/metadata") {
            ktor.post("api/series/metadata") {
                contentType(ContentType.Application.Json)
                setBody(metadata)
            }
        }
    }

    suspend fun updateChapterMetadata(metadata: KavitaChapterMetadataUpdateRequest) {
        updatesRateLimiter.acquire()
        withTransientRetry("api/chapter/update") {
            ktor.post("api/chapter/update") {
                contentType(ContentType.Application.Json)
                setBody(metadata)
            }
        }
    }


    suspend fun getSeriesMetadata(seriesId: KavitaSeriesId): KavitaSeriesMetadata {
        val response = ktor.get("api/series/metadata") {
            parameter("seriesId", seriesId.value)
        }
        if (response.status == HttpStatusCode.NoContent) {
            return emptySeriesMetadata(seriesId)
        }
        if (response.status == HttpStatusCode.NotFound) {
            throw KavitaResourceNotFoundException()
        }
        return response.body()
    }

    suspend fun getSeriesDetails(seriesId: KavitaSeriesId): KavitaSeriesDetails {
        return ktor.get("api/series/series-detail") {
            parameter("seriesId", seriesId.value)
        }.body()
    }

    suspend fun getVolumes(seriesId: KavitaSeriesId): Collection<KavitaVolume> {
        return ktor.get("api/series/volumes") {
            parameter("seriesId", seriesId.value)
        }.body()
    }

    suspend fun getVolume(volumeId: KavitaVolumeId): KavitaVolume {
        val response = ktor.get("api/series/volume") {
            parameter("volumeId", volumeId.value)
        }
        if (response.status == HttpStatusCode.NoContent) throw snd.komf.mediaserver.kavita.KavitaResourceNotFoundException()

        return response.body()
    }

    suspend fun getChapter(chapterId: KavitaChapterId): KavitaChapter {
        val response = ktor.get("api/series/chapter") {
            parameter("chapterId", chapterId.value)
        }
        if (response.status == HttpStatusCode.NoContent) throw snd.komf.mediaserver.kavita.KavitaResourceNotFoundException()
        return response.body()
    }

    suspend fun getChapterCover(chapterId: KavitaChapterId): Image {
        val response = ktor.get("api/image/chapter-cover") {
            parameter("chapterId", chapterId.value)
            parameter("apiKey", apiKey)
        }

        val contentType = response.contentType()
        return Image(response.body(), contentType.toString())
    }

    suspend fun uploadSeriesCover(seriesId: KavitaSeriesId, cover: Image, lockCover: Boolean) {
        updatesRateLimiter.acquire()
        val base64Image = Base64.getEncoder().encodeToString(cover.bytes)
        withTransientRetry("api/upload/series") {
            ktor.post("api/upload/series") {
                contentType(ContentType.Application.Json)
                setBody(KavitaCoverUploadRequest(id = seriesId.value, url = base64Image, lockCover))
            }
        }
    }

    suspend fun uploadVolumeCover(volumeId: KavitaVolumeId, cover: Image, lockCover: Boolean) {
        updatesRateLimiter.acquire()
        val base64Image = Base64.getEncoder().encodeToString(cover.bytes)
        logger.info { "POST /api/upload/volume volumeId=${volumeId.value}" }
        withTransientRetry("api/upload/volume") {
            ktor.post("api/upload/volume") {
                contentType(ContentType.Application.Json)
                setBody(KavitaCoverUploadRequest(id = volumeId.value, url = base64Image, lockCover))
            }
        }
    }

    suspend fun getLibraries(): Collection<KavitaLibrary> {
        return ktor.get("api/library/libraries").body()
    }

    suspend fun scanSeries(libraryId: KavitaLibraryId, seriesId: KavitaSeriesId) {
        scanRateLimiter.acquire()
        withTransientRetry("api/series/scan") {
            ktor.post("api/series/scan") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("libraryId", libraryId.value)
                        put("seriesId", seriesId.value)
                    }
                )

            }
        }
    }

    suspend fun scanLibrary(libraryId: KavitaLibraryId) {
        scanRateLimiter.acquire()
        withTransientRetry("api/library/scan") {
            ktor.post("api/library/scan") {
                parameter("libraryId", libraryId.value)
            }
        }
    }

    suspend fun resetChapterLock(chapterId: KavitaChapterId) {
        if (resetChapterLockWarningLogged.compareAndSet(false, true)) {
            logger.warn {
                "Using deprecated Kavita endpoint api/upload/reset-chapter-lock. " +
                    "If cover lock reset breaks after a Kavita upgrade, verify replacement endpoint."
            }
        }
        withTransientRetry("api/upload/reset-chapter-lock") {
            ktor.post("api/upload/reset-chapter-lock") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("id", chapterId.value)
                    put("url", "")
                })

            }
        }
    }

    private suspend fun <T> withTransientRetry(
        operation: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        block: suspend () -> T
    ): T {
        var currentDelayMs = initialDelayMs
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                val shouldRetry = isTransientError(e)
                val isLastAttempt = attempt == maxAttempts - 1
                if (!shouldRetry || isLastAttempt) {
                    lastError = e
                    return@repeat
                }
                logger.warn {
                    "Transient error during $operation (attempt ${attempt + 1}/$maxAttempts). " +
                        "Retrying in ${currentDelayMs}ms"
                }
                delay(currentDelayMs)
                currentDelayMs *= 2
            }
        }

        throw checkNotNull(lastError) { "Retry failed with unknown error for operation $operation" }
    }

    private fun isTransientError(error: Throwable): Boolean {
        return when (error) {
            is ResponseException -> {
                val status = error.response.status
                status == HttpStatusCode.TooManyRequests || status.value in 500..599
            }

            is IOException -> true
            else -> false
        }
    }

    private fun emptySeriesMetadata(seriesId: KavitaSeriesId): KavitaSeriesMetadata {
        return KavitaSeriesMetadata(
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
            releaseYearLocked = false
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private val resetChapterLockWarningLogged = AtomicBoolean(false)
    }
}

data class KavitaPage<T>(
    val content: Collection<T>,
    val currentPage: Int,
    val itemsPerPage: Int?,
    val totalItems: Int?,
    val totalPages: Int?,
)

@Serializable
data class KavitaPagination(
    val currentPage: Int,
    val itemsPerPage: Int,
    val totalItems: Int,
    val totalPages: Int,
)
