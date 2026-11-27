package com.example.network

import io.grpc.MethodDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin Protobuf wire-format encoder/decoder and gRPC MethodDescriptors for NVIDIA Riva ASR
 * (`nvidia.riva.asr.RivaSpeechRecognition`, `riva_asr.proto` & `riva_audio.proto`).
 *
 * Avoids `protobuf-javalite` codegen and R8 reflection issues while keeping the APK compact.
 */
object RivaProtoCodec {

  const val SERVICE_NAME = "nvidia.riva.asr.RivaSpeechRecognition"
  const val AUDIO_ENCODING_LINEAR_PCM = 1

  data class RecognitionConfigMsg(
    val encoding: Int = AUDIO_ENCODING_LINEAR_PCM,
    val sampleRateHertz: Int = 16_000,
    val languageCode: String = "multi",
    val maxAlternatives: Int = 1,
    val audioChannelCount: Int = 1,
    val enableAutomaticPunctuation: Boolean = true,
    val model: String = "",
  )

  data class RecognizeRequestMsg(
    val config: RecognitionConfigMsg,
    val audio: ByteArray,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is RecognizeRequestMsg) return false
      return config == other.config && audio.contentEquals(other.audio)
    }

    override fun hashCode(): Int = 31 * config.hashCode() + audio.contentHashCode()
  }

  data class SpeechAlternativeMsg(
    val transcript: String = "",
    val confidence: Float = 0f,
  )

  data class RecognizeResponseMsg(
    val transcripts: List<String> = emptyList(),
  ) {
    val combinedText: String
      get() = transcripts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
  }

  data class StreamingRecognitionConfigMsg(
    val config: RecognitionConfigMsg,
    val singleUtterance: Boolean = false,
    val interimResults: Boolean = true,
  )

  data class StreamingRecognizeRequestMsg(
    val streamingConfig: StreamingRecognitionConfigMsg? = null,
    val audioContent: ByteArray? = null,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is StreamingRecognizeRequestMsg) return false
      if (streamingConfig != other.streamingConfig) return false
      val a = audioContent
      val b = other.audioContent
      return if (a == null && b == null) true
      else if (a != null && b != null) a.contentEquals(b)
      else false
    }

    override fun hashCode(): Int {
      var result = streamingConfig?.hashCode() ?: 0
      result = 31 * result + (audioContent?.contentHashCode() ?: 0)
      return result
    }
  }

  data class StreamingRecognitionResultMsg(
    val transcript: String = "",
    val isFinal: Boolean = false,
  )

  data class StreamingRecognizeResponseMsg(
    val results: List<StreamingRecognitionResultMsg> = emptyList(),
  )

  data class ConfigRequestMsg(
    val modelName: String = "",
  )

  data class ConfigResponseMsg(
    val modelNames: List<String> = emptyList(),
  )

  // --- gRPC Method Descriptors ---

  val recognizeMethod: MethodDescriptor<RecognizeRequestMsg, RecognizeResponseMsg> =
    MethodDescriptor.newBuilder<RecognizeRequestMsg, RecognizeResponseMsg>()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Recognize"))
      .setSampledToLocalTracing(true)
      .setRequestMarshaller(marshaller(::encodeRecognizeRequest, ::decodeRecognizeRequest))
      .setResponseMarshaller(marshaller(::encodeRecognizeResponse, ::decodeRecognizeResponse))
      .build()

  val streamingRecognizeMethod:
    MethodDescriptor<StreamingRecognizeRequestMsg, StreamingRecognizeResponseMsg> =
    MethodDescriptor.newBuilder<StreamingRecognizeRequestMsg, StreamingRecognizeResponseMsg>()
      .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
      .setFullMethodName(
        MethodDescriptor.generateFullMethodName(SERVICE_NAME, "StreamingRecognize")
      )
      .setSampledToLocalTracing(true)
      .setRequestMarshaller(
        marshaller(::encodeStreamingRecognizeRequest, ::decodeStreamingRecognizeRequest)
      )
      .setResponseMarshaller(
        marshaller(::encodeStreamingRecognizeResponse, ::decodeStreamingRecognizeResponse)
      )
      .build()

  val getConfigMethod: MethodDescriptor<ConfigRequestMsg, ConfigResponseMsg> =
    MethodDescriptor.newBuilder<ConfigRequestMsg, ConfigResponseMsg>()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName(
        MethodDescriptor.generateFullMethodName(SERVICE_NAME, "GetRivaSpeechRecognitionConfig")
      )
      .setSampledToLocalTracing(true)
      .setRequestMarshaller(marshaller(::encodeConfigRequest, ::decodeConfigRequest))
      .setResponseMarshaller(marshaller(::encodeConfigResponse, ::decodeConfigResponse))
      .build()

  private fun <T> marshaller(
    encode: (T) -> ByteArray,
    decode: (ByteArray) -> T,
  ): MethodDescriptor.Marshaller<T> {
    return object : MethodDescriptor.Marshaller<T> {
      override fun stream(value: T): InputStream = ByteArrayInputStream(encode(value))

      override fun parse(stream: InputStream): T = decode(stream.readBytes())
    }
  }

  // --- Encoders ---

  fun encodeRecognitionConfig(cfg: RecognitionConfigMsg): ByteArray {
    val out = ByteArrayOutputStream()
    if (cfg.encoding != 0) writeVarintField(out, 1, cfg.encoding.toLong())
    if (cfg.sampleRateHertz != 0) writeVarintField(out, 2, cfg.sampleRateHertz.toLong())
    if (cfg.languageCode.isNotEmpty()) writeBytesField(out, 3, cfg.languageCode.toByteArray(Charsets.UTF_8))
    if (cfg.maxAlternatives != 0) writeVarintField(out, 4, cfg.maxAlternatives.toLong())
    if (cfg.audioChannelCount != 0) writeVarintField(out, 7, cfg.audioChannelCount.toLong())
    if (cfg.enableAutomaticPunctuation) writeVarintField(out, 11, 1L)
    if (cfg.model.isNotEmpty()) writeBytesField(out, 13, cfg.model.toByteArray(Charsets.UTF_8))
    return out.toByteArray()
  }

  fun encodeRecognizeRequest(req: RecognizeRequestMsg): ByteArray {
    val out = ByteArrayOutputStream(req.audio.size + 64)
    val cfgBytes = encodeRecognitionConfig(req.config)
    if (cfgBytes.isNotEmpty()) writeBytesField(out, 1, cfgBytes)
    if (req.audio.isNotEmpty()) writeBytesField(out, 2, req.audio)
    return out.toByteArray()
  }

  fun decodeRecognizeRequest(bytes: ByteArray): RecognizeRequestMsg {
    var audio = ByteArray(0)
    var language = "multi"
    var sampleRate = 16_000
    var model = ""
    parseFields(bytes) { fieldNumber, wireType, rawBytes, varint ->
      when {
        fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null -> {
          parseFields(rawBytes) { cfgField, cfgWire, cfgRaw, cfgVarint ->
            when {
              cfgField == 2 && cfgWire == WIRE_VARINT -> sampleRate = cfgVarint.toInt()
              cfgField == 3 && cfgWire == WIRE_LENGTH_DELIMITED && cfgRaw != null ->
                language = cfgRaw.toString(Charsets.UTF_8)
              cfgField == 13 && cfgWire == WIRE_LENGTH_DELIMITED && cfgRaw != null ->
                model = cfgRaw.toString(Charsets.UTF_8)
            }
          }
        }
        fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null -> audio = rawBytes
      }
    }
    return RecognizeRequestMsg(
      config = RecognitionConfigMsg(sampleRateHertz = sampleRate, languageCode = language, model = model),
      audio = audio,
    )
  }

  fun encodeRecognizeResponse(resp: RecognizeResponseMsg): ByteArray {
    val out = ByteArrayOutputStream()
    for (transcript in resp.transcripts) {
      val altBytes = ByteArrayOutputStream().apply {
        writeBytesField(this, 1, transcript.toByteArray(Charsets.UTF_8))
      }.toByteArray()
      val resultBytes = ByteArrayOutputStream().apply {
        writeBytesField(this, 1, altBytes)
      }.toByteArray()
      writeBytesField(out, 1, resultBytes)
    }
    return out.toByteArray()
  }

  fun decodeRecognizeResponse(bytes: ByteArray): RecognizeResponseMsg {
    val transcripts = mutableListOf<String>()
    parseFields(bytes) { fieldNumber, wireType, rawBytes, _ ->
      if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null) {
        val altText = decodeBestAlternativeTranscript(rawBytes)
        if (altText.isNotBlank()) transcripts.add(altText)
      }
    }
    return RecognizeResponseMsg(transcripts = transcripts)
  }

  fun encodeStreamingRecognitionConfig(cfg: StreamingRecognitionConfigMsg): ByteArray {
    val out = ByteArrayOutputStream()
    val recBytes = encodeRecognitionConfig(cfg.config)
    if (recBytes.isNotEmpty()) writeBytesField(out, 1, recBytes)
    if (cfg.singleUtterance) writeVarintField(out, 2, 1L)
    if (cfg.interimResults) writeVarintField(out, 3, 1L)
    return out.toByteArray()
  }

  fun encodeStreamingRecognizeRequest(req: StreamingRecognizeRequestMsg): ByteArray {
    val out = ByteArrayOutputStream((req.audioContent?.size ?: 0) + 64)
    req.streamingConfig?.let { sc ->
      val scBytes = encodeStreamingRecognitionConfig(sc)
      writeBytesField(out, 1, scBytes)
    }
    req.audioContent?.let { audio ->
      if (audio.isNotEmpty()) writeBytesField(out, 2, audio)
    }
    return out.toByteArray()
  }

  fun decodeStreamingRecognizeRequest(bytes: ByteArray): StreamingRecognizeRequestMsg {
    var hasConfig = false
    var audio: ByteArray? = null
    parseFields(bytes) { fieldNumber, wireType, rawBytes, _ ->
      when {
        fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> hasConfig = true
        fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null -> audio = rawBytes
      }
    }
    return StreamingRecognizeRequestMsg(
      streamingConfig = if (hasConfig) StreamingRecognitionConfigMsg(RecognitionConfigMsg()) else null,
      audioContent = audio,
    )
  }

  fun encodeStreamingRecognizeResponse(resp: StreamingRecognizeResponseMsg): ByteArray {
    val out = ByteArrayOutputStream()
    for (res in resp.results) {
      val altBytes = ByteArrayOutputStream().apply {
        writeBytesField(this, 1, res.transcript.toByteArray(Charsets.UTF_8))
      }.toByteArray()
      val resBytes = ByteArrayOutputStream().apply {
        writeBytesField(this, 1, altBytes)
        if (res.isFinal) writeVarintField(this, 2, 1L)
      }.toByteArray()
      writeBytesField(out, 1, resBytes)
    }
    return out.toByteArray()
  }

  fun decodeStreamingRecognizeResponse(bytes: ByteArray): StreamingRecognizeResponseMsg {
    val results = mutableListOf<StreamingRecognitionResultMsg>()
    parseFields(bytes) { fieldNumber, wireType, rawBytes, _ ->
      if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null) {
        var firstTranscript: String? = null
        var isFinal = false
        parseFields(rawBytes) { rf, rw, rRaw, rVarint ->
          when {
            rf == 1 && rw == WIRE_LENGTH_DELIMITED && rRaw != null && firstTranscript == null -> {
              firstTranscript = decodeAlternativeTranscript(rRaw)
            }
            rf == 2 && rw == WIRE_VARINT -> {
              isFinal = (rVarint != 0L)
            }
          }
        }
        val text = firstTranscript.orEmpty()
        if (text.isNotBlank() || isFinal) {
          results.add(StreamingRecognitionResultMsg(transcript = text, isFinal = isFinal))
        }
      }
    }
    return StreamingRecognizeResponseMsg(results = results)
  }

  fun encodeConfigRequest(req: ConfigRequestMsg): ByteArray {
    if (req.modelName.isBlank()) return ByteArray(0)
    val out = ByteArrayOutputStream()
    writeBytesField(out, 1, req.modelName.toByteArray(Charsets.UTF_8))
    return out.toByteArray()
  }

  fun decodeConfigRequest(bytes: ByteArray): ConfigRequestMsg {
    var modelName = ""
    parseFields(bytes) { fieldNumber, wireType, rawBytes, _ ->
      if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null) {
        modelName = rawBytes.toString(Charsets.UTF_8)
      }
    }
    return ConfigRequestMsg(modelName = modelName)
  }

  fun encodeConfigResponse(resp: ConfigResponseMsg): ByteArray {
    val out = ByteArrayOutputStream()
    for (name in resp.modelNames) {
      val cfgEntry = ByteArrayOutputStream().apply {
        writeBytesField(this, 1, name.toByteArray(Charsets.UTF_8))
      }.toByteArray()
      writeBytesField(out, 1, cfgEntry)
    }
    return out.toByteArray()
  }

  fun decodeConfigResponse(bytes: ByteArray): ConfigResponseMsg {
    val names = linkedSetOf<String>()
    parseFields(bytes) { fieldNumber, wireType, rawBytes, _ ->
      if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null) {
        parseFields(rawBytes) { cf, cw, cRaw, _ ->
          if (cf == 1 && cw == WIRE_LENGTH_DELIMITED && cRaw != null) {
            val name = cRaw.toString(Charsets.UTF_8).trim()
            if (name.isNotEmpty()) names.add(name)
          }
        }
      }
    }
    return ConfigResponseMsg(modelNames = names.toList())
  }

  private fun decodeBestAlternativeTranscript(resultBytes: ByteArray): String {
    var bestTranscript: String? = null
    parseFields(resultBytes) { fieldNumber, wireType, rawBytes, _ ->
      if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null && bestTranscript == null) {
        bestTranscript = decodeAlternativeTranscript(rawBytes)
      }
    }
    return bestTranscript.orEmpty()
  }

  private fun decodeAlternativeTranscript(altBytes: ByteArray): String {
    var transcript = ""
    parseFields(altBytes) { fieldNumber, wireType, rawBytes, _ ->
      if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED && rawBytes != null) {
        transcript = rawBytes.toString(Charsets.UTF_8)
      }
    }
    return transcript
  }

  // --- Audio helper ---

  data class LinearPcmPayload(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is LinearPcmPayload) return false
      return sampleRateHz == other.sampleRateHz &&
        channelCount == other.channelCount &&
        pcmBytes.contentEquals(other.pcmBytes)
    }

    override fun hashCode(): Int =
      31 * (31 * pcmBytes.contentHashCode() + sampleRateHz) + channelCount
  }

  /**
   * Strips standard RIFF/WAVE headers so NVIDIA Riva receives raw 16-bit LE PCM samples when
   * `AudioEncoding.LINEAR_PCM` is set, reading the sample rate and channel count from `fmt `.
   */
  fun extractLinearPcm16(wavOrPcmBytes: ByteArray): LinearPcmPayload {
    if (
      wavOrPcmBytes.size < 44 ||
        wavOrPcmBytes[0] != 'R'.code.toByte() ||
        wavOrPcmBytes[1] != 'I'.code.toByte() ||
        wavOrPcmBytes[2] != 'F'.code.toByte() ||
        wavOrPcmBytes[3] != 'F'.code.toByte() ||
        wavOrPcmBytes[8] != 'W'.code.toByte() ||
        wavOrPcmBytes[9] != 'A'.code.toByte() ||
        wavOrPcmBytes[10] != 'V'.code.toByte() ||
        wavOrPcmBytes[11] != 'E'.code.toByte()
    ) {
      return LinearPcmPayload(pcmBytes = wavOrPcmBytes, sampleRateHz = 16_000, channelCount = 1)
    }

    var sampleRate = 16_000
    var channels = 1
    var offset = 12
    val buf = ByteBuffer.wrap(wavOrPcmBytes).order(ByteOrder.LITTLE_ENDIAN)

    while (offset + 8 <= wavOrPcmBytes.size) {
      val chunkId = String(wavOrPcmBytes, offset, 4, Charsets.US_ASCII)
      val chunkSize = buf.getInt(offset + 4).coerceAtLeast(0)
      val dataStart = offset + 8
      if (chunkId == "fmt " && chunkSize >= 16 && dataStart + 16 <= wavOrPcmBytes.size) {
        channels = buf.getShort(dataStart + 2).toInt().coerceAtLeast(1)
        sampleRate = buf.getInt(dataStart + 4).coerceAtLeast(8_000)
      } else if (chunkId == "data" && dataStart <= wavOrPcmBytes.size) {
        val safeEnd = (dataStart + chunkSize).coerceAtMost(wavOrPcmBytes.size)
        return LinearPcmPayload(
          pcmBytes = wavOrPcmBytes.copyOfRange(dataStart, safeEnd),
          sampleRateHz = sampleRate,
          channelCount = channels,
        )
      }
      val paddedSize = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
      offset = dataStart + paddedSize
    }

    return LinearPcmPayload(
      pcmBytes = wavOrPcmBytes.copyOfRange(44, wavOrPcmBytes.size),
      sampleRateHz = sampleRate,
      channelCount = channels,
    )
  }

  // --- Low-level Protobuf Wire Primitives ---

  private const val WIRE_VARINT = 0
  private const val WIRE_FIXED64 = 1
  private const val WIRE_LENGTH_DELIMITED = 2
  private const val WIRE_FIXED32 = 5

  private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
    writeVarint(out, ((fieldNumber shl 3) or WIRE_VARINT).toLong())
    writeVarint(out, value)
  }

  private fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, data: ByteArray) {
    writeVarint(out, ((fieldNumber shl 3) or WIRE_LENGTH_DELIMITED).toLong())
    writeVarint(out, data.size.toLong())
    out.write(data)
  }

  private fun writeVarint(out: ByteArrayOutputStream, initialValue: Long) {
    var v = initialValue
    while (true) {
      if ((v and 0x7FL.inv()) == 0L) {
        out.write(v.toInt())
        return
      }
      out.write(((v.toInt() and 0x7F) or 0x80))
      v = v ushr 7
    }
  }

  private inline fun parseFields(
    bytes: ByteArray,
    onField: (fieldNumber: Int, wireType: Int, rawBytes: ByteArray?, varint: Long) -> Unit,
  ) {
    var pos = 0
    while (pos < bytes.size) {
      val (tag, nextPos) = readVarint(bytes, pos)
      pos = nextPos
      val fieldNumber = (tag ushr 3).toInt()
      val wireType = (tag and 0x7L).toInt()
      if (fieldNumber <= 0) break

      when (wireType) {
        WIRE_VARINT -> {
          val (value, afterVal) = readVarint(bytes, pos)
          pos = afterVal
          onField(fieldNumber, wireType, null, value)
        }
        WIRE_FIXED64 -> {
          pos = (pos + 8).coerceAtMost(bytes.size)
          onField(fieldNumber, wireType, null, 0L)
        }
        WIRE_LENGTH_DELIMITED -> {
          val (lenLong, afterLen) = readVarint(bytes, pos)
          val len = lenLong.toInt().coerceAtLeast(0)
          pos = afterLen
          val end = (pos + len).coerceAtMost(bytes.size)
          val slice = bytes.copyOfRange(pos, end)
          pos = end
          onField(fieldNumber, wireType, slice, 0L)
        }
        WIRE_FIXED32 -> {
          pos = (pos + 4).coerceAtMost(bytes.size)
          onField(fieldNumber, wireType, null, 0L)
        }
        else -> break
      }
    }
  }

  private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var pos = start
    while (pos < bytes.size && shift < 64) {
      val b = bytes[pos++].toInt() and 0xFF
      result = result or ((b and 0x7F).toLong() shl shift)
      if ((b and 0x80) == 0) {
        return result to pos
      }
      shift += 7
    }
    return result to pos
  }
}
