package snd.komf.mediaserver

import snd.komf.mediaserver.model.MediaServerSeriesId

/**
 * Optional hook for clients that can safely reuse read snapshots within a single series apply pass.
 */
interface SeriesPassSnapshotAware {
    suspend fun beginSeriesPass(seriesId: MediaServerSeriesId)
    suspend fun endSeriesPass(seriesId: MediaServerSeriesId)
}
