package org.voxis.dictation.audio

import android.media.AudioFormat
import android.media.AudioRecord

object AudioConfig {
    const val SAMPLE_RATE_HZ = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE = 2

    // 100ms chunk at 16kHz 16-bit mono = 1600 samples = 3200 bytes
    const val CHUNK_DURATION_MS = 100
    const val SAMPLES_PER_CHUNK = (SAMPLE_RATE_HZ * CHUNK_DURATION_MS) / 1000
    const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * BYTES_PER_SAMPLE

    fun getMinBufferSize(): Int {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        return maxOf(minSize, BYTES_PER_CHUNK * 4)
    }
}
