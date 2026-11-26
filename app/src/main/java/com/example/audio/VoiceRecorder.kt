package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.MicSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

object AssistAudioBridge {
  @Volatile private var pendingDescriptor: ParcelFileDescriptor? = null

  fun offerStream(pfd: ParcelFileDescriptor?) {
    synchronized(this) {
      runCatching { pendingDescriptor?.close() }
      pendingDescriptor = pfd
    }
  }

  fun consumeStream(): ParcelFileDescriptor? {
    synchronized(this) {
      val pfd = pendingDescriptor
      pendingDescriptor = null
      return pfd
    }
  }
}

sealed class RecordingOutcome {
  data class Success(val wavBytes: ByteArray, val usedRemoteStream: Boolean) : RecordingOutcome()

  data class Error(val message: String) : RecordingOutcome()
}

class VoiceRecorder(private val context: Context) {

  private val _level = MutableStateFlow(0f)
  val level: StateFlow<Float> = _level.asStateFlow()

  private val _speechStarted = MutableStateFlow(false)
  val speechStarted: StateFlow<Boolean> = _speechStarted.asStateFlow()

  @Volatile private var stopRequested = false

  fun requestStop() {
    stopRequested = true
  }

  @SuppressLint("MissingPermission")
  suspend fun record(
    micSource: MicSource,
    remoteStreamPfd: ParcelFileDescriptor? = null,
    onSpeechStart: (() -> Unit)? = null,
    onRmsChanged: ((Float) -> Unit)? = null,
    onAudioChunk: ((ByteArray) -> Unit)? = null,
  ): RecordingOutcome =
    withContext(Dispatchers.IO) {
      stopRequested = false
      _level.value = 0f
      _speechStarted.value = false

      if (micSource != MicSource.BUILTIN_MIC && remoteStreamPfd != null) {
        val streamResult =
          tryReadRemoteStream(remoteStreamPfd, onSpeechStart, onRmsChanged, onAudioChunk)
        if (streamResult != null) {
          _level.value = 0f
          return@withContext streamResult
        }
      } else {
        runCatching { remoteStreamPfd?.close() }
      }

      if (micSource == MicSource.REMOTE_STREAM && remoteStreamPfd == null) {
        // Fallback to AudioRecord if no remote stream was delivered by the system
      }

      val result = recordFromAudioRecord(micSource, onSpeechStart, onRmsChanged, onAudioChunk)
      _level.value = 0f
      result
    }

  private suspend fun tryReadRemoteStream(
    pfd: ParcelFileDescriptor,
    onSpeechStart: (() -> Unit)?,
    onRmsChanged: ((Float) -> Unit)?,
    onAudioChunk: ((ByteArray) -> Unit)?,
  ): RecordingOutcome? {
    Log.i(TAG, "Audio source: REMOTE_STREAM (AssistSession ParcelFileDescriptor)")
    return try {
      ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
        readStreamWithSilenceDetection(
          input = input,
          usedRemoteStream = true,
          onSpeechStart = onSpeechStart,
          onRmsChanged = onRmsChanged,
          onAudioChunk = onAudioChunk,
        )
      }
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun readStreamWithSilenceDetection(
    input: InputStream,
    usedRemoteStream: Boolean,
    onSpeechStart: (() -> Unit)?,
    onRmsChanged: ((Float) -> Unit)?,
    onAudioChunk: ((ByteArray) -> Unit)?,
  ): RecordingOutcome {
    val pcmOut = ByteArrayOutputStream()
    val buffer = ByteArray(FRAME_BYTES)
    val startMs = System.currentTimeMillis()
    var hasSpeechStarted = false
    var silenceStartMs = 0L
    var lastChunkAtMs = startMs
    var lastChunkBytes = 0

    while (currentCoroutineContext().isActive && !stopRequested) {
      val now = System.currentTimeMillis()
      if (now - startMs >= MAX_DURATION_MS) break

      val read = input.read(buffer)
      if (read <= 0) break
      pcmOut.write(buffer, 0, read)

      val rms = computeRms16Bit(buffer, read)
      val normLevel = (rms / 4000f).coerceIn(0f, 1f)
      _level.value = normLevel
      onRmsChanged?.invoke(normLevel * 10f)

      if (rms >= SPEECH_RMS_THRESHOLD) {
        if (!hasSpeechStarted) {
          hasSpeechStarted = true
          _speechStarted.value = true
          onSpeechStart?.invoke()
        }
        silenceStartMs = 0L
      } else if (hasSpeechStarted) {
        if (silenceStartMs == 0L) {
          silenceStartMs = now
        } else if (now - silenceStartMs >= SILENCE_DURATION_MS) {
          break
        }
      }

      if (
        onAudioChunk != null &&
          hasSpeechStarted &&
          now - lastChunkAtMs >= LIVE_CHUNK_INTERVAL_MS &&
          pcmOut.size() - lastChunkBytes >= LIVE_MIN_DELTA_BYTES
      ) {
        lastChunkAtMs = now
        lastChunkBytes = pcmOut.size()
        onAudioChunk.invoke(WavEncoder.encodePcm16(pcmOut.toByteArray()))
      }
    }

    val pcm = pcmOut.toByteArray()
    if (pcm.isEmpty()) {
      return RecordingOutcome.Error("No audio captured from remote mic.")
    }
    return RecordingOutcome.Success(WavEncoder.encodePcm16(pcm), usedRemoteStream)
  }

  @SuppressLint("MissingPermission")
  private suspend fun recordFromAudioRecord(
    micSource: MicSource,
    onSpeechStart: (() -> Unit)?,
    onRmsChanged: ((Float) -> Unit)?,
    onAudioChunk: ((ByteArray) -> Unit)?,
  ): RecordingOutcome {
    val minBuf =
      AudioRecord.getMinBufferSize(
        WavEncoder.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBuf <= 0) {
      return RecordingOutcome.Error("Microphone buffer unavailable on this device.")
    }
    val bufferSize = maxOf(minBuf * 2, FRAME_BYTES * 4)

    var chosenSourceName = "VOICE_RECOGNITION"
    val recorder =
      createAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize)
        ?: run {
          chosenSourceName = "MIC"
          createAudioRecord(MediaRecorder.AudioSource.MIC, bufferSize)
        }
        ?: return RecordingOutcome.Error("Unable to open microphone.")

    var preferredDeviceLabel = "system_default"
    if (micSource == MicSource.BUILTIN_MIC) {
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      val builtin =
        audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
      if (builtin != null) {
        recorder.setPreferredDevice(builtin)
        preferredDeviceLabel = "TYPE_BUILTIN_MIC"
      }
    }
    Log.i(
      TAG,
      "Audio source: AudioRecord.$chosenSourceName (micSetting=${micSource.name}, device=$preferredDeviceLabel)",
    )

    val pcmOut = ByteArrayOutputStream()
    val buffer = ByteArray(FRAME_BYTES)

    return try {
      recorder.startRecording()
      if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        return RecordingOutcome.Error("Microphone failed to start recording.")
      }

      val startMs = System.currentTimeMillis()
      var hasSpeechStarted = false
      var silenceStartMs = 0L
      var lastChunkAtMs = startMs
      var lastChunkBytes = 0

      while (currentCoroutineContext().isActive && !stopRequested) {
        val now = System.currentTimeMillis()
        if (now - startMs >= MAX_DURATION_MS) break

        val read = recorder.read(buffer, 0, buffer.size)
        if (read < 0) {
          return RecordingOutcome.Error("Microphone read failed ($read).")
        }
        if (read == 0) continue

        pcmOut.write(buffer, 0, read)
        val rms = computeRms16Bit(buffer, read)
        val normLevel = (rms / 4000f).coerceIn(0f, 1f)
        _level.value = normLevel
        onRmsChanged?.invoke(normLevel * 10f)

        if (rms >= SPEECH_RMS_THRESHOLD) {
          if (!hasSpeechStarted) {
            hasSpeechStarted = true
            _speechStarted.value = true
            onSpeechStart?.invoke()
          }
          silenceStartMs = 0L
        } else if (hasSpeechStarted) {
          if (silenceStartMs == 0L) {
            silenceStartMs = now
          } else if (now - silenceStartMs >= SILENCE_DURATION_MS) {
            break
          }
        }

        if (
          onAudioChunk != null &&
            hasSpeechStarted &&
            now - lastChunkAtMs >= LIVE_CHUNK_INTERVAL_MS &&
            pcmOut.size() - lastChunkBytes >= LIVE_MIN_DELTA_BYTES
        ) {
          lastChunkAtMs = now
          lastChunkBytes = pcmOut.size()
          onAudioChunk.invoke(WavEncoder.encodePcm16(pcmOut.toByteArray()))
        }
      }

      val pcm = pcmOut.toByteArray()
      if (pcm.isEmpty()) {
        RecordingOutcome.Error("No audio recorded.")
      } else {
        RecordingOutcome.Success(WavEncoder.encodePcm16(pcm), usedRemoteStream = false)
      }
    } catch (e: SecurityException) {
      RecordingOutcome.Error("Microphone permission denied.")
    } catch (e: Exception) {
      RecordingOutcome.Error("Recording failed: ${e.message ?: e.javaClass.simpleName}")
    } finally {
      runCatching { recorder.stop() }
      runCatching { recorder.release() }
    }
  }

  @SuppressLint("MissingPermission")
  private fun createAudioRecord(source: Int, bufferSize: Int): AudioRecord? {
    return try {
      val record =
        AudioRecord(
          source,
          WavEncoder.SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bufferSize,
        )
      if (record.state == AudioRecord.STATE_INITIALIZED) {
        record
      } else {
        record.release()
        null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun computeRms16Bit(bytes: ByteArray, length: Int): Float {
    val sampleCount = length / 2
    if (sampleCount <= 0) return 0f
    var sumSquares = 0.0
    var i = 0
    while (i + 1 < length) {
      val low = bytes[i].toInt() and 0xFF
      val high = bytes[i + 1].toInt()
      val sample = (high shl 8) or low
      sumSquares += (sample * sample).toDouble()
      i += 2
    }
    return sqrt(sumSquares / sampleCount).toFloat()
  }

  companion object {
    private const val TAG = "TVVoiceRecorder"
    private const val FRAME_BYTES = 1024 // ~32ms at 16kHz 16-bit mono
    private const val SPEECH_RMS_THRESHOLD = 420f
    private const val LIVE_CHUNK_INTERVAL_MS = 1100L
    private const val LIVE_MIN_DELTA_BYTES = 16000 // ~0.5s new 16kHz mono PCM
    const val SILENCE_DURATION_MS = 1200L
    const val MAX_DURATION_MS = 10000L
  }
}
