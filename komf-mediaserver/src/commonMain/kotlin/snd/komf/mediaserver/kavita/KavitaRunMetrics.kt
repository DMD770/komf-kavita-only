package snd.komf.mediaserver.kavita

import snd.komf.mediaserver.metadata.LibraryApplyScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class KavitaEndpoint(val key: String) {
    SERIES("series"),
    SERIES_METADATA("series_metadata"),
    SERIES_DETAIL("series_detail"),
    VOLUMES("volumes"),
    VOLUME("volume"),
    CHAPTER("chapter"),
    LIBRARIES("libraries"),
    SERIES_COVER("series_cover"),
    CHAPTER_COVER("chapter_cover"),
    CHAPTER_UPDATE("chapter_update"),
    SERIES_METADATA_POST("series_metadata_post"),
    SERIES_UPDATE("series_update"),
    UPLOAD_SERIES("upload_series"),
    UPLOAD_VOLUME("upload_volume"),
    SCAN_SERIES("scan_series"),
    SCAN_LIBRARY("scan_library"),
    RESET_CHAPTER_LOCK("reset_chapter_lock"),
}

data class KavitaRunMetricsSnapshot(
    val libraryId: String,
    val dryRun: Boolean,
    val resumedFromCheckpoint: Boolean,
    val applyMode: String,
    val kavitaReadsTotal: Int,
    val kavitaReadsCacheHits: Int,
    val kavitaReadsCacheMisses: Int,
    val kavitaWritesTotal: Int,
    val seriesSkippedUnchanged: Int,
    val seriesApplied: Int,
    val seriesResumedSkipped: Int,
    val scansIssued: Int,
    val seriesAppliedByScope: Map<String, Int>,
    val seriesSkippedUnchangedByScope: Map<String, Int>,
    val readsByEndpoint: Map<String, Int>,
    val writesByEndpoint: Map<String, Int>,
    val cacheHitsByEndpoint: Map<String, Int>,
    val cacheMissesByEndpoint: Map<String, Int>,
)

class KavitaRunMetrics(
    private val libraryId: String,
    private val dryRun: Boolean,
    private val resumedFromCheckpoint: Boolean,
    private val applyMode: String,
) {
    private val readsTotal = AtomicInteger(0)
    private val readsCacheHits = AtomicInteger(0)
    private val readsCacheMisses = AtomicInteger(0)
    private val writesTotal = AtomicInteger(0)
    private val seriesSkippedUnchanged = AtomicInteger(0)
    private val seriesApplied = AtomicInteger(0)
    private val seriesResumedSkipped = AtomicInteger(0)
    private val scansIssued = AtomicInteger(0)
    private val seriesAppliedByScope = ConcurrentHashMap<String, AtomicInteger>()
    private val seriesSkippedByScope = ConcurrentHashMap<String, AtomicInteger>()

    private val readsByEndpoint = ConcurrentHashMap<String, AtomicInteger>()
    private val writesByEndpoint = ConcurrentHashMap<String, AtomicInteger>()
    private val cacheHitsByEndpoint = ConcurrentHashMap<String, AtomicInteger>()
    private val cacheMissesByEndpoint = ConcurrentHashMap<String, AtomicInteger>()

    fun recordRead(endpoint: KavitaEndpoint) {
        readsTotal.incrementAndGet()
        increment(readsByEndpoint, endpoint.key)
    }

    fun recordWrite(endpoint: KavitaEndpoint) {
        writesTotal.incrementAndGet()
        increment(writesByEndpoint, endpoint.key)
    }

    fun recordCacheHit(endpoint: KavitaEndpoint) {
        readsCacheHits.incrementAndGet()
        increment(cacheHitsByEndpoint, endpoint.key)
    }

    fun recordCacheMiss(endpoint: KavitaEndpoint) {
        readsCacheMisses.incrementAndGet()
        increment(cacheMissesByEndpoint, endpoint.key)
    }

    fun incrementSeriesSkippedUnchanged() {
        seriesSkippedUnchanged.incrementAndGet()
    }

    fun incrementSeriesApplied() {
        seriesApplied.incrementAndGet()
    }

    fun incrementSeriesAppliedScope(scope: LibraryApplyScope) {
        increment(seriesAppliedByScope, scope.name)
    }

    fun incrementSeriesSkippedUnchangedScope(scope: LibraryApplyScope) {
        increment(seriesSkippedByScope, scope.name)
    }

    fun incrementSeriesResumedSkipped() {
        seriesResumedSkipped.incrementAndGet()
    }

    fun markScanIssued() {
        scansIssued.incrementAndGet()
    }

    fun snapshot(): KavitaRunMetricsSnapshot {
        return KavitaRunMetricsSnapshot(
            libraryId = libraryId,
            dryRun = dryRun,
            resumedFromCheckpoint = resumedFromCheckpoint,
            applyMode = applyMode,
            kavitaReadsTotal = readsTotal.get(),
            kavitaReadsCacheHits = readsCacheHits.get(),
            kavitaReadsCacheMisses = readsCacheMisses.get(),
            kavitaWritesTotal = writesTotal.get(),
            seriesSkippedUnchanged = seriesSkippedUnchanged.get(),
            seriesApplied = seriesApplied.get(),
            seriesResumedSkipped = seriesResumedSkipped.get(),
            scansIssued = scansIssued.get(),
            seriesAppliedByScope = toPlainMap(seriesAppliedByScope),
            seriesSkippedUnchangedByScope = toPlainMap(seriesSkippedByScope),
            readsByEndpoint = toPlainMap(readsByEndpoint),
            writesByEndpoint = toPlainMap(writesByEndpoint),
            cacheHitsByEndpoint = toPlainMap(cacheHitsByEndpoint),
            cacheMissesByEndpoint = toPlainMap(cacheMissesByEndpoint)
        )
    }

    fun currentApplyMode(): String = applyMode

    private fun increment(target: ConcurrentHashMap<String, AtomicInteger>, key: String) {
        target.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun toPlainMap(source: ConcurrentHashMap<String, AtomicInteger>): Map<String, Int> {
        return source.entries
            .associate { (key, value) -> key to value.get() }
            .toSortedMap()
    }
}
