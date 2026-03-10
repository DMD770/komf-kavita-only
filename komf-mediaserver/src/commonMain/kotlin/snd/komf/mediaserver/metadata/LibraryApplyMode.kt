package snd.komf.mediaserver.metadata

enum class LibraryApplyMode {
    CORE,
    CHAPTERS,
    FULL,
}

enum class LibraryApplyScope {
    SERIES_METADATA,
    SERIES_COVER,
    VOLUME_COVERS,
    CHAPTER_METADATA,
}

fun LibraryApplyMode.scopes(): Set<LibraryApplyScope> {
    return when (this) {
        LibraryApplyMode.CORE -> setOf(
            LibraryApplyScope.SERIES_METADATA,
            LibraryApplyScope.SERIES_COVER,
            LibraryApplyScope.VOLUME_COVERS
        )
        LibraryApplyMode.CHAPTERS -> setOf(LibraryApplyScope.CHAPTER_METADATA)
        LibraryApplyMode.FULL -> LibraryApplyScope.entries.toSet()
    }
}
