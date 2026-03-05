package snd.komf.mediaserver.kavita

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import snd.komf.mediaserver.kavita.model.KavitaSeriesId
import snd.komf.mediaserver.kavita.model.KavitaVolume
import snd.komf.mediaserver.kavita.model.KavitaVolumeId
import snd.komf.mediaserver.kavita.model.effectiveVolumeNumber

class KavitaVolumeNumberTest {

    @Test
    fun `effectiveVolumeNumber parses numeric name`() {
        val volume = testVolume(name = "9")
        assertEquals(9, volume.effectiveVolumeNumber())
    }

    @Test
    fun `effectiveVolumeNumber parses volume prefix`() {
        val volume = testVolume(name = "Vol 10")
        assertEquals(10, volume.effectiveVolumeNumber())
    }

    @Test
    fun `effectiveVolumeNumber prefers valid minNumber`() {
        val volume = testVolume(minNumber = 8f, maxNumber = 8f, name = "whatever")
        assertEquals(8, volume.effectiveVolumeNumber())
    }

    @Test
    fun `effectiveVolumeNumber returns null when name has no number`() {
        val volume = testVolume(name = "Special")
        assertNull(volume.effectiveVolumeNumber())
    }

    @Test
    fun `book number resolver uses volume fallback for mapping`() {
        val volume = testVolume(name = "9")
        assertEquals(9, resolveKavitaBookNumber(volume, chapterNumber = null))
    }

    private fun testVolume(
        minNumber: Float = 0f,
        maxNumber: Float = 0f,
        name: String,
    ): KavitaVolume {
        return KavitaVolume(
            id = KavitaVolumeId(1),
            minNumber = minNumber,
            maxNumber = maxNumber,
            name = name,
            pages = 0,
            seriesId = KavitaSeriesId(1),
            chapters = emptyList(),
        )
    }
}
