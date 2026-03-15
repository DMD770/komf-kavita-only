package snd.komf.providers.mangadex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import snd.komf.model.AuthorRole
import snd.komf.providers.BookMetadataConfig
import snd.komf.providers.SeriesMetadataConfig
import snd.komf.providers.mangadex.model.MangaDexAttributes
import snd.komf.providers.mangadex.model.MangaDexCoverArt
import snd.komf.providers.mangadex.model.MangaDexCoverArtAttributes
import snd.komf.providers.mangadex.model.MangaDexManga
import snd.komf.providers.mangadex.model.MangaDexMangaId
import kotlin.time.Instant

class MangaDexCoverSelectionTest {
    private val mapper = MangaDexMetadataMapper(
        seriesMetadataConfig = SeriesMetadataConfig(),
        bookMetadataConfig = BookMetadataConfig(),
        authorRoles = listOf(AuthorRole.WRITER),
        artistRoles = listOf(AuthorRole.COVER),
        coverLanguages = listOf("en", "ja"),
        linksFilter = emptyList()
    )

    @Test
    fun `series cover prefers default non volume cover`() {
        val defaultCover = cover(fileName = "default-en.jpg", volume = null, locale = "en")
        val volumeCover = cover(fileName = "vol-1-en.jpg", volume = "1", locale = "en")

        val selected = mapper.selectSeriesCoverArt(listOf(volumeCover, defaultCover))

        assertEquals("default-en.jpg", selected?.attributes?.fileName)
    }

    @Test
    fun `series cover falls back to volume cover when no default exists`() {
        val volumeOne = cover(fileName = "vol-1-fr.jpg", volume = "1", locale = "fr")
        val volumeTwo = cover(fileName = "vol-2-ja.jpg", volume = "2", locale = "ja")

        val selected = mapper.selectSeriesCoverArt(listOf(volumeOne, volumeTwo))

        assertEquals("vol-2-ja.jpg", selected?.attributes?.fileName)
    }

    @Test
    fun `volume covers remain available for volume matching`() {
        val series = testManga()
        val defaultCover = cover(fileName = "default-en.jpg", volume = null, locale = "en")
        val volumeNineCover = cover(fileName = "vol-9-en.jpg", volume = "9", locale = "en")

        val metadata = mapper.toSeriesMetadata(series, listOf(defaultCover, volumeNineCover), cover = null)
        val volumeNineBook = metadata.books.firstOrNull { it.number?.start == 9.0 }

        assertNotNull(volumeNineBook)
        assertEquals("9", volumeNineBook.name)
        assertEquals("vol-9-en.jpg", volumeNineBook.id.id)
    }

    @Test
    fun `volume covers fall back to non preferred locale per volume`() {
        val series = testManga()
        val covers = listOf(
            cover(fileName = "vol-1-ko.jpg", volume = "1", locale = "ko"),
            cover(fileName = "vol-1_5-ko.jpg", volume = "1.5", locale = "ko"),
            cover(fileName = "vol-2-ko.jpg", volume = "2", locale = "ko"),
            cover(fileName = "vol-2_5-ko.jpg", volume = "2.5", locale = "ko"),
            cover(fileName = "vol-3-ko.jpg", volume = "3", locale = "ko"),
            cover(fileName = "vol-3_1-ko.jpg", volume = "3.1", locale = "ko"),
            cover(fileName = "vol-4-ko.jpg", volume = "4", locale = "ko"),
            cover(fileName = "vol-5-ko.jpg", volume = "5", locale = "ko"),
        )

        val metadata = mapper.toSeriesMetadata(series, covers, cover = null)

        val exactVolumes = metadata.books
            .mapNotNull { it.number?.start }
            .filter { it == kotlin.math.floor(it) }
            .map { it.toInt() }
            .sorted()

        assertEquals(listOf(1, 2, 3, 4, 5), exactVolumes)
        assertFalse(metadata.books.isEmpty())
        assertEquals("vol-1-ko.jpg", metadata.books.first { it.name == "1" }.id.id)
        assertEquals("vol-5-ko.jpg", metadata.books.first { it.name == "5" }.id.id)
    }

    @Test
    fun `volume cover prefers configured language when same volume has multiple locales`() {
        val series = testManga()
        val covers = listOf(
            cover(fileName = "vol-2-ko.jpg", volume = "2", locale = "ko"),
            cover(fileName = "vol-2-ja.jpg", volume = "2", locale = "ja"),
        )

        val metadata = mapper.toSeriesMetadata(series, covers, cover = null)
        val volumeTwo = metadata.books.firstOrNull { it.name == "2" }

        assertNotNull(volumeTwo)
        assertEquals("vol-2-ja.jpg", volumeTwo.id.id)
    }

    private fun cover(fileName: String, volume: String?, locale: String?): MangaDexCoverArt {
        return MangaDexCoverArt(
            id = fileName,
            attributes = MangaDexCoverArtAttributes(
                fileName = fileName,
                volume = volume,
                locale = locale
            )
        )
    }

    private fun testManga(): MangaDexManga {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        return MangaDexManga(
            id = MangaDexMangaId("series-id"),
            type = "manga",
            attributes = MangaDexAttributes(
                title = mapOf("en" to "Series"),
                altTitles = emptyList(),
                description = mapOf("en" to "Description"),
                isLocked = false,
                links = null,
                originalLanguage = "ja",
                lastVolume = null,
                lastChapter = null,
                publicationDemographic = null,
                status = "ongoing",
                year = 2024,
                contentRating = "safe",
                tags = emptyList(),
                state = "published",
                chapterNumbersResetOnNewVolume = false,
                createdAt = now,
                updatedAt = now,
                version = 1,
                availableTranslatedLanguages = null,
                latestUploadedChapter = null
            ),
            relationships = emptyList()
        )
    }
}
