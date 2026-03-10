package snd.komf.mediaserver.metadata

import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.model.Image
import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeCoverUploadDedupeTest {

    @Test
    fun `dedupe uses volume target id and image hash`() {
        val coverA = Image(bytes = byteArrayOf(1, 2, 3), mimeType = "image/jpeg")
        val coverB = Image(bytes = byteArrayOf(9, 9, 9), mimeType = "image/jpeg")

        val result = dedupeVolumeCoverUploadCandidates(
            listOf(
                VolumeCoverUploadCandidate(
                    sourceSeriesId = MediaServerSeriesId("10"),
                    sourceBookId = MediaServerBookId("22358"),
                    targetVolumeId = "1434",
                    thumbnail = coverA
                ),
                VolumeCoverUploadCandidate(
                    sourceSeriesId = MediaServerSeriesId("10"),
                    sourceBookId = MediaServerBookId("22359"),
                    targetVolumeId = "1434",
                    thumbnail = coverA
                ),
                VolumeCoverUploadCandidate(
                    sourceSeriesId = MediaServerSeriesId("10"),
                    sourceBookId = MediaServerBookId("22360"),
                    targetVolumeId = "1434",
                    thumbnail = coverB
                ),
                VolumeCoverUploadCandidate(
                    sourceSeriesId = MediaServerSeriesId("10"),
                    sourceBookId = MediaServerBookId("22361"),
                    targetVolumeId = "2000",
                    thumbnail = coverA
                )
            )
        )

        assertEquals(4, result.candidateCount)
        assertEquals(1, result.skippedDuplicateCount)
        assertEquals(3, result.uploadPlans.size)
        assertEquals(MediaServerBookId("22358"), result.uploadPlans.first().sourceBookId)
    }
}
