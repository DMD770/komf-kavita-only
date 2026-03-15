package snd.komf.mediaserver.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import snd.komf.mediaserver.model.MediaServerBook
import snd.komf.mediaserver.model.MediaServerBookId
import snd.komf.mediaserver.model.MediaServerBookMetadata
import snd.komf.mediaserver.model.MediaServerSeriesId
import snd.komf.model.BookRange
import snd.komf.model.ProviderBookId
import snd.komf.model.SeriesBook

class ProviderBookFetchPlanTest {

    @Test
    fun `multiple local books mapped to same provider volume are fetched once`() {
        val localBookA = mediaBook("a", "Chapter 1", 1)
        val localBookB = mediaBook("b", "Chapter 2", 1)
        val localBookC = mediaBook("c", "Chapter 3", 1)
        val providerVolumeOne = providerBook("mdx-vol-1-cover.jpg", "1", 1.0)

        val plan = buildProviderBookFetchPlan(
            linkedMapOf(
                localBookA to providerVolumeOne,
                localBookB to providerVolumeOne,
                localBookC to providerVolumeOne,
            )
        )

        assertEquals(1, plan.size)
        assertEquals("mdx-vol-1-cover.jpg", plan.first().providerBook.id.id)
        assertEquals(listOf(localBookA, localBookB, localBookC), plan.first().localBooks)
    }

    @Test
    fun `different provider volumes remain separate fetches`() {
        val localBookOne = mediaBook("a", "Vol. 1", 1)
        val localBookTwo = mediaBook("b", "Vol. 2", 2)
        val providerVolumeOne = providerBook("mdx-vol-1-cover.jpg", "1", 1.0)
        val providerVolumeTwo = providerBook("mdx-vol-2-cover.jpg", "2", 2.0)

        val plan = buildProviderBookFetchPlan(
            linkedMapOf(
                localBookOne to providerVolumeOne,
                localBookTwo to providerVolumeTwo,
            )
        )

        assertEquals(2, plan.size)
        assertEquals(listOf("mdx-vol-1-cover.jpg", "mdx-vol-2-cover.jpg"), plan.map { it.providerBook.id.id })
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
