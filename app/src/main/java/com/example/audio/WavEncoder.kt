package com.example.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
  const val SAMPLE_RATE = 16000
  const val CHANNELS = 1
  const val BITS_PER_SAMPLE = 16

  fun encodePcm16(pcmBytes: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
    val out = ByteArrayOutputStream(44 + pcmBytes.size)
    val byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8)
    val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)
    val totalDataLen = pcmBytes.size + 36

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(totalDataLen)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16) // PCM chunk size
    header.putShort(1.toShort()) // AudioFormat = PCM (1)
    header.putShort(CHANNELS.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(BITS_PER_SAMPLE.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(pcmBytes.size)

    out.write(header.array())
    out.write(pcmBytes)
    return out.toByteArray()
  }

  fun silentWav(durationMillis: Int = 500, sampleRate: Int = SAMPLE_RATE): ByteArray {
    val numSamples = (sampleRate * durationMillis) / 1000
    val pcmBytes = ByteArray(numSamples * 2) // 16-bit mono zeros
    return encodePcm16(pcmBytes, sampleRate)
  }
}
