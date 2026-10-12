package org.voxis.dictation.audio

data class AudioChunk(
    val samples: ShortArray,
    val length: Int,
    val rmsDecibels: Float,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        if (length != other.length) return false
        if (rmsDecibels != other.rmsDecibels) return false
        if (timestampMs != other.timestampMs) return false
        return samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + rmsDecibels.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + samples.contentHashCode()
        return result
    }
}
