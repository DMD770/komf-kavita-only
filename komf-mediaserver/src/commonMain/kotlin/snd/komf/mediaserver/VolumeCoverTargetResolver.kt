package snd.komf.mediaserver

import snd.komf.mediaserver.model.MediaServerBookId

/**
 * Resolves the effective volume target identity for a book/chapter cover upload.
 * For Kavita this is volumeId.
 */
interface VolumeCoverTargetResolver {
    suspend fun resolveVolumeTargetId(bookId: MediaServerBookId): String
}
