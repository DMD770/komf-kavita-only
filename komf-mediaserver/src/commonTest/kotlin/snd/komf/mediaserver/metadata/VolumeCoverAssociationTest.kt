package snd.komf.mediaserver.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import snd.komf.mediaserver.kavita.resolveKavitaBookNumber
import snd.komf.mediaserver.kavita.model.KavitaSeriesId
import snd.komf.mediaserver.kavita.model.KavitaVolume
import snd.komf.mediaserver.kavita.model.KavitaVolumeId
import snd.komf.mediaserver.model.MediaServerBook
import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerBookMetadata
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.model.BookRange
import snd.komf.model.MediaType
import snd.komf.model.ProviderBookId
import snd.komf.model.SeriesBook

class VolumeCoverAssociationTest {

    @Test
    fun `volumes with zero numeric fields still match mangadex volume covers`() {
        val kavitaVolume = KavitaVolume(
            id = KavitaVolumeId(1429),
            minNumber = 0f,
            maxNumber = 0f,
            name = "9",
            pages = 0,
            seriesId = KavitaSeriesId(217),
            chapters = emptyList(),
        )
        val effectiveNumber = resolveKavitaBookNumber(kavitaVolume)
        assertEquals(9, effectiveNumber)

        val mediaBook = MediaServerBook(
            id = MediaServerBookId("1"),
            seriesId = MediaServerSeriesId("217"),
            libraryId = null,
            seriesTitle = "Series",
            name = "Special Name Without Number",
            url = "/tmp/vol9.cbz",
            number = effectiveNumber,
            oneshot = false,
            metadata = emptyBookMetadata(),
            deleted = false
        )
        val providerBook = SeriesBook(
            id = ProviderBookId("mdx-vol-9-cover.jpg"),
            number = BookRange(9),
            name = "9",
            type = null,
            edition = null
        )

        val association = associateBookMetadataByNumber(
            books = listOf(mediaBook),
            providerBooks = listOf(providerBook),
            edition = null,
            libraryType = MediaType.MANGA
        )

        assertNotNull(association[mediaBook])
        assertEquals("mdx-vol-9-cover.jpg", association[mediaBook]?.id?.id)
    }

    @Test
    fun `fractional mangadex-only covers do not block exact integer volume matches`() {
        val volumeOne = mediaBook(id = "1", name = "Vol. 1", number = 1)
        val volumeTwo = mediaBook(id = "2", name = "Vol. 2", number = 2)
        val providerBooks = listOf(
            providerBook("mdx-vol-1-cover.jpg", "1", 1.0),
            providerBook("mdx-vol-1_5-cover.jpg", "1.5", 1.5),
            providerBook("mdx-vol-2-cover.jpg", "2", 2.0),
            providerBook("mdx-vol-2_5-cover.jpg", "2.5", 2.5),
        )

        val association = associateBookMetadataByNumber(
            books = listOf(volumeOne, volumeTwo),
            providerBooks = providerBooks,
            edition = null,
            libraryType = MediaType.MANGA
        )

        assertEquals("mdx-vol-1-cover.jpg", association[volumeOne]?.id?.id)
        assertEquals("mdx-vol-2-cover.jpg", association[volumeTwo]?.id?.id)
        assertNull(association.values.firstOrNull { it?.name == "1.5" })
        assertNull(association.values.firstOrNull { it?.name == "2.5" })
    }

    private fun mediaBook(id: String, name: String, number: Int) = MediaServerBook(
        id = MediaServerBookId(id),
        seriesId = MediaServerSeriesId("217"),
        libraryId = null,
        seriesTitle = "Series",
        name = name,
        url = "/tmp/$id.cbz",
        number = number,
        oneshot = false,
        metadata = emptyBookMetadata(),
        deleted = false
    )

    private fun providerBook(id: String, name: String, number: Double) = SeriesBook(
        id = ProviderBookId(id),
        number = BookRange(number, number),
        name = name,
        type = null,
        edition = null
    )

    private fun emptyBookMetadata() = MediaServerBookMetadata(
        title = "",
        summary = null,
        number = "0",
        numberSort = null,
        releaseDate = null,
        authors = emptyList(),
        tags = emptyList(),
        isbn = null,
        links = emptyList(),
        titleLock = false,
        summaryLock = false,
        numberLock = false,
        numberSortLock = false,
        releaseDateLock = false,
        authorsLock = false,
        tagsLock = false,
        isbnLock = false,
        linksLock = false
    )
}
