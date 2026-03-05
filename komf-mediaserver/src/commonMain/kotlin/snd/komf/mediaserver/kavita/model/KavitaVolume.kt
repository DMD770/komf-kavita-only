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

fun KavitaVolume.effectiveVolumeNumber(): Int? {
    if (minNumber.isFinite() && minNumber > 0f) {
        val asInt = minNumber.toInt()
        if (minNumber == asInt.toFloat()) return asInt
    }

    return volumeNameNumberRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
