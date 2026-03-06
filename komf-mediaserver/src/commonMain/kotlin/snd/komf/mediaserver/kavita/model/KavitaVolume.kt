package snd.komf.mediaserver.kavita.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class KavitaVolumeId(val value: Int) {
    override fun toString() = value.toString()
}

@Serializable
data class KavitaVolume(
    val id: KavitaVolumeId,
    val minNumber: Float,
    val maxNumber: Float,
    val name: String,
    val pages: Int,
    val seriesId: KavitaSeriesId,
    val chapters: Collection<KavitaChapter>,
)

private val volumeNameNumberRegex = """(?i)\b(?:vol(?:ume)?\.?\s*)?0*(\d+)\b""".toRegex()

enum class KavitaVolumeNumberSource(val logValue: String) {
    NUMERIC_FIELD("numeric-field"),
    NAME_FALLBACK("name-fallback"),
    NONE("none"),
}

data class KavitaVolumeNumberResolution(
    val effectiveNumber: Int?,
    val source: KavitaVolumeNumberSource,
)

fun KavitaVolume.resolveVolumeNumber(): KavitaVolumeNumberResolution {
    if (minNumber.isFinite() && minNumber > 0f) {
        val asInt = minNumber.toInt()
        if (minNumber == asInt.toFloat()) {
            return KavitaVolumeNumberResolution(asInt, KavitaVolumeNumberSource.NUMERIC_FIELD)
        }
    }

    val parsed = volumeNameNumberRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return if (parsed != null) {
        KavitaVolumeNumberResolution(parsed, KavitaVolumeNumberSource.NAME_FALLBACK)
    } else {
        KavitaVolumeNumberResolution(null, KavitaVolumeNumberSource.NONE)
    }
}

fun KavitaVolume.effectiveVolumeNumber(): Int? {
    return resolveVolumeNumber().effectiveNumber
}
