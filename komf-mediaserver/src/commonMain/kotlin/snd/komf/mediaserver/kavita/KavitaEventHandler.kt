package snd.komf.mediaserver.kavita

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import snd.komf.mediaserver.BookEvent
import snd.komf.mediaserver.MediaServerEventListener
import snd.komf.mediaserver.SeriesEvent
import snd.komf.mediaserver.kavita.model.KavitaSeriesId
import snd.komf.mediaserver.kavita.model.KavitaVolumeId
import snd.komf.mediaserver.kavita.model.toKavitaSeriesId
import snd.komf.mediaserver.kavita.model.events.CoverUpdateEvent
import snd.komf.mediaserver.kavita.model.events.NotificationProgressEvent
import snd.komf.mediaserver.kavita.model.events.SeriesAddedEvent
import snd.komf.mediaserver.kavita.model.events.SeriesRemovedEvent
import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerLibraryId
import snd.komf.mediaserver.model.MediaServerSeriesId
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

class KavitaEventHandler(
    private val baseUrl: URLBuilder,
    private val kavitaClient: KavitaClient,
    private val tokenProvider: KavitaTokenProvider,
    private val clock: Clock,
    private val scanState: KavitaScanState,
    private val eventListeners: List<MediaServerEventListener>
) {
    private val eventHandlerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lock = ReentrantLock()
    private var hubConnection: HubConnection? = null
    private val volumesChanged: MutableList<Int> = ArrayList()
    private val seriesAddedDuringScan: MutableList<SeriesAddedRef> = ArrayList()

    private var lastScan: Instant = clock.now()
    private var isActive: Boolean = false

    @Synchronized
    fun start() {
        isActive = true
        val url = baseUrl.appendPathSegments("hubs", "messages")
        val hubConnection: HubConnection = HubConnectionBuilder
            .create(url.buildString())
            .withAccessTokenProvider(Single.defer { Single.just(runBlocking { tokenProvider.getToken() }) })
            .build()
        hubConnection.on("NotificationProgress", ::processProgressNotification, NotificationProgressEvent::class.java)
        hubConnection.on("CoverUpdate", ::processCoverUpdate, CoverUpdateEvent::class.java)
        hubConnection.on("SeriesAdded", ::seriesAdded, SeriesAddedEvent::class.java)
        hubConnection.on("SeriesRemoved", ::seriesRemoved, SeriesRemovedEvent::class.java)

        hubConnection.onClosed { reconnect(hubConnection) }
        registerInvocations(hubConnection)

        Completable.defer {
            hubConnection.start()
                .delaySubscription(10, SECONDS, Schedulers.trampoline())
                .doOnError { logger.error(it) { } }
        }
            .retry().subscribeOn(Schedulers.io()).subscribe()
        logger.info { "connecting to Kavita event listener ${url.buildString()}" }
        this.hubConnection = hubConnection
    }

    private fun reconnect(hubConnection: HubConnection) {
        if (isActive) {
            Completable.defer {
                hubConnection.start().delaySubscription(10, SECONDS, Schedulers.trampoline())
                    .doOnError { logger.error(it) { "Failed to reconnect to Kavita" } }
            }
                .retry { _ -> isActive }
                .subscribeOn(Schedulers.io()).subscribe()
        }
    }

    @Synchronized
    fun stop() {
        isActive = false
        hubConnection?.close()
    }

    private fun seriesRemoved(event: SeriesRemovedEvent) {
        val seriesEvent = SeriesEvent(
            MediaServerLibraryId(event.body.libraryId.toString()),
            MediaServerSeriesId(event.body.seriesId.toString()),
        )
        eventHandlerScope.launch {
            eventListeners.forEach { it.onSeriesDeleted(listOf(seriesEvent)) }
        }
    }

    private fun seriesAdded(event: SeriesAddedEvent) {
        lock.withLock {
            seriesAddedDuringScan.add(
                SeriesAddedRef(
                    libraryId = event.body.libraryId,
                    seriesId = event.body.seriesId
                )
            )
        }
    }

    private fun processProgressNotification(notification: NotificationProgressEvent) {
        val eventName = notification.name
        if (eventName == "ScanProgress") {
            when (notification.eventType?.lowercase()) {
                "started" -> scanState.markScanStarted()
                "ended" -> scanState.markScanEnded()
            }
        }
        if (eventName in maintenanceEvents) {
            when (notification.eventType?.lowercase()) {
                "started" -> scanState.markMaintenanceStarted(eventName!!)
                "ended" -> scanState.markMaintenanceEnded(eventName!!)
            }
        }

        if (notification.name == "ScanProgress" && notification.eventType == "ended") {
            val now = clock.now()
            val lastScan = this.lastScan
            lock.withLock {
                val volumes = volumesChanged.toList()
                val addedSeries = seriesAddedDuringScan.toList()
                eventHandlerScope.launch { processEvents(volumes, addedSeries, lastScan) }
                volumesChanged.clear()
                seriesAddedDuringScan.clear()
                this.lastScan = now
            }
        }
    }

    private fun processCoverUpdate(event: CoverUpdateEvent) {
        if (event.body?.get("entityType") == "volume") {
            lock.withLock { volumesChanged.add((event.body["id"] as Double).toInt()) }
        }
    }

    private suspend fun processEvents(
        volumeIds: Collection<Int>,
        addedSeries: Collection<SeriesAddedRef>,
        lastScan: Instant
    ) {
        val volumes = volumeIds.distinct().mapNotNull {
            try {
                kavitaClient.getVolume(KavitaVolumeId(it))
            } catch (exception: KavitaResourceNotFoundException) {
                null
            }
        }

        val newVolumes = volumes.mapNotNull { volume ->
            val newChapters = volume.chapters
                .filter { it.createdUtc.toInstant(TimeZone.UTC) > lastScan }
            if (newChapters.isEmpty()) null
            else volume to newChapters
        }.toMap()

        val seriesToChaptersMap = newVolumes.keys
            .groupBy { it.seriesId }
            .mapKeys { (s, _) -> kavitaClient.getSeries(s) }
            .mapValues { (_, v) -> v.flatMap { newVolumes[it]!! } }

        val bookEvents = seriesToChaptersMap.flatMap { (series, chapters) ->
            chapters.map {
                BookEvent(
                    MediaServerLibraryId(series.libraryId.value.toString()),
                    MediaServerSeriesId(series.id.value.toString()),
                    MediaServerBookId(it.id.value.toString())
                )
            }
        }
        val addedSeriesBookEvents = addedSeries.distinct().flatMap { added ->
            try {
                val series = kavitaClient.getSeries(KavitaSeriesId(added.seriesId))
                kavitaClient.getVolumes(series.id).flatMap { volume ->
                    volume.chapters.map { chapter ->
                        BookEvent(
                            libraryId = MediaServerLibraryId(added.libraryId.toString()),
                            seriesId = MediaServerSeriesId(added.seriesId.toString()),
                            bookId = MediaServerBookId(chapter.id.value.toString())
                        )
                    }
                }
            } catch (exception: KavitaResourceNotFoundException) {
                emptyList()
            }
        }

        val eventsToDispatch = (bookEvents + addedSeriesBookEvents)
            .distinctBy { "${it.libraryId.value}:${it.seriesId.value}:${it.bookId.value}" }

        if (eventsToDispatch.isEmpty()) return
        eventListeners.forEach { it.onBooksAdded(eventsToDispatch) }
    }

    private fun registerInvocations(hubConnection: HubConnection) {
        // need to add all invocation targets in order to avoid errors in logs
        // register noop handlers
        registerNoopInvocation(hubConnection, "BackupDatabaseProgress")
        registerNoopInvocation(hubConnection, "BookThemeProgress")
        registerNoopInvocation(hubConnection, "ConvertBookmarksProgress")
        registerNoopInvocation(hubConnection, "CleanupProgress")
        registerNoopInvocation(hubConnection, "CollectionUpdated")
        registerNoopInvocation(hubConnection, "ChapterRemoved")
        registerNoopInvocation(hubConnection, "ChapterUpdated")
        registerNoopInvocation(hubConnection, "CoverUpdateProgress")
        registerNoopInvocation(hubConnection, "DashboardUpdate")
        registerNoopInvocation(hubConnection, "DownloadProgress")
        registerNoopInvocation(hubConnection, "Error")
        registerNoopInvocation(hubConnection, "ExternalMatchRateLimitError")
        registerNoopInvocation(hubConnection, "FileScanProgress")
        registerNoopInvocation(hubConnection, "Info")
        registerNoopInvocation(hubConnection, "LibraryModified")
        registerNoopInvocation(hubConnection, "OnlineUsers")
        registerNoopInvocation(hubConnection, "PersonMerged")
        registerNoopInvocation(hubConnection, "ReadingSessionClose")
        registerNoopInvocation(hubConnection, "ReadingSessionUpdate")
        registerNoopInvocation(hubConnection, "ScrobblingKeyExpired")
        registerNoopInvocation(hubConnection, "ScanSeries")
        registerNoopInvocation(hubConnection, "ScanProgress")
        registerNoopInvocation(hubConnection, "SendingToDevice")
        registerNoopInvocation(hubConnection, "SeriesAdded")
        registerNoopInvocation(hubConnection, "SeriesAddedToCollection")
        registerNoopInvocation(hubConnection, "SideNavUpdate")
        registerNoopInvocation(hubConnection, "SiteThemeProgress")
        registerNoopInvocation(hubConnection, "SiteThemeUpdated")
        registerNoopInvocation(hubConnection, "SmartCollectionSync")
        registerNoopInvocation(hubConnection, "UpdateAvailable")
        registerNoopInvocation(hubConnection, "AnnotationUpdate")
        registerNoopInvocation(hubConnection, "AuthKeyDeleted")
        registerNoopInvocation(hubConnection, "AuthKeyUpdate")
        registerNoopInvocation(hubConnection, "UserUpdate")
        registerNoopInvocation(hubConnection, "UserProgressUpdate")
        registerNoopInvocation(hubConnection, "VolumeRemoved")
        registerNoopInvocation(hubConnection, "VolumeUpdated")
        registerNoopInvocation(hubConnection, "WordCountAnalyzerProgress")
    }

    private fun registerNoopInvocation(hubConnection: HubConnection, method: String) {
        hubConnection.on(method, { _: Any? -> }, Object::class.java)
    }

    companion object {
        private val maintenanceEvents = setOf(
            "BackupDatabaseProgress",
            "CleanupProgress",
            "FileScanProgress"
        )
    }

    private data class SeriesAddedRef(
        val libraryId: Int,
        val seriesId: Int
    )
}
