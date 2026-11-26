package com.example.network

import com.example.audio.WavEncoder
import com.example.data.AppSettings
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EndpointCandidate(val url: String, val family: ServerFamily)

sealed class ProbeResult {
  data class Success(val url: String, val family: ServerFamily, val message: String) :
    ProbeResult()

  data class NeedsApiKey(val url: String, val family: ServerFamily, val message: String) :
    ProbeResult()

  data class Failure(val message: String) : ProbeResult()
}

sealed class TranscribeResult {
  data class Success(
    val text: String,
    val usedUrl: String,
    val usedFamily: ServerFamily,
    val refreshedEndpoint: Boolean = false,
  ) : TranscribeResult()

  data class Failure(val message: String, val isAuthError: Boolean = false) : TranscribeResult()
}

data class HttpResponseData(val statusCode: Int, val body: String)

interface HttpTransport {
  @Throws(IOException::class)
  fun postMultipart(
    urlString: String,
    headers: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    fileBytes: ByteArray,
    fields: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
  ): HttpResponseData
}

object UrlConnectionTransport : HttpTransport {
  override fun postMultipart(
    urlString: String,
    headers: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    fileBytes: ByteArray,
    fields: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
  ): HttpResponseData {
    val boundary = "----TvVoiceSearch${UUID.randomUUID().toString().replace("-", "")}"
    val bodyOut = ByteArrayOutputStream()

    fun writeUtf8(text: String) {
      bodyOut.write(text.toByteArray(Charsets.UTF_8))
    }

    for ((name, value) in fields) {
      writeUtf8("--$boundary\r\n")
      writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
      writeUtf8("$value\r\n")
    }

    writeUtf8("--$boundary\r\n")
    writeUtf8(
      "Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n"
    )
    writeUtf8("Content-Type: audio/wav\r\n\r\n")
    bodyOut.write(fileBytes)
    writeUtf8("\r\n--$boundary--\r\n")

    val payload = bodyOut.toByteArray()
    val conn = (URL(urlString).openConnection() as HttpURLConnection)
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = readTimeoutMs
      conn.doInput = true
      conn.doOutput = true
      conn.useCaches = false
      conn.setFixedLengthStreamingMode(payload.size)
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      conn.setRequestProperty("Accept", "application/json, text/plain;q=0.9")
      for ((k, v) in headers) {
        conn.setRequestProperty(k, v)
      }

      conn.outputStream.use { os ->
        os.write(payload)
        os.flush()
      }

      val status = conn.responseCode
      val stream = if (status in 200..299) conn.inputStream else conn.errorStream
      val responseText =
        if (stream != null) {
          BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } else {
          ""
        }
      return HttpResponseData(status, responseText)
    } finally {
      conn.disconnect()
    }
  }
}

class EndpointClient(private val transport: HttpTransport = UrlConnectionTransport) {

  data class NormalizedInput(
    val primaryBase: String,
    val fallbackHttpsBase: String?,
    val path: String,
  )

  fun normalizeInput(rawInput: String): NormalizedInput? {
    var trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.dropLast(1)
    }
    if (trimmed.isEmpty()) return null

    val hasExplicitScheme =
      trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)

    val withScheme = if (hasExplicitScheme) trimmed else "http://$trimmed"

    val uri =
      try {
        URI(withScheme)
      } catch (_: Exception) {
        return null
      }

    val host = uri.host ?: return null
    val scheme = uri.scheme?.lowercase() ?: "http"
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    var path = (uri.rawPath ?: "").trimEnd('/')
    if (path == "/") path = ""

    val primaryBase = "$scheme://$host$portPart"
    val fallbackHttpsBase = if (!hasExplicitScheme) "https://$host$portPart" else null

    return NormalizedInput(
      primaryBase = primaryBase,
      fallbackHttpsBase = fallbackHttpsBase,
      path = path,
    )
  }

  fun buildCandidates(baseUrl: String, path: String): List<EndpointCandidate> {
    val cleanBase = baseUrl.trimEnd('/')
    val cleanPath = path.trimEnd('/')

    return when {
      cleanPath.isEmpty() -> standardCandidates(cleanBase)
      cleanPath == "/v1" || cleanPath.endsWith("/v1") ->
        listOf(EndpointCandidate("$cleanBase$cleanPath/audio/transcriptions", ServerFamily.OPENAI_V1))
      cleanPath.endsWith("/v1/audio/transcriptions") ->
        listOf(EndpointCandidate("$cleanBase$cleanPath", ServerFamily.OPENAI_V1))
      cleanPath.endsWith("/audio/transcriptions") ->
        listOf(EndpointCandidate("$cleanBase$cleanPath", ServerFamily.OPENAI_NO_V1))
      cleanPath.endsWith("/inference") ->
        listOf(EndpointCandidate("$cleanBase$cleanPath", ServerFamily.WHISPER_CPP))
      cleanPath.endsWith("/asr") ->
        listOf(EndpointCandidate("$cleanBase$cleanPath", ServerFamily.WHISPER_ASR))
      else -> {
        val fullPathBase = "$cleanBase$cleanPath"
        buildList {
          add(EndpointCandidate(fullPathBase, ServerFamily.OPENAI_V1))
          addAll(standardCandidates(fullPathBase))
        }
      }
    }
  }

  private fun standardCandidates(base: String): List<EndpointCandidate> {
    val clean = base.trimEnd('/')
    return listOf(
      EndpointCandidate("$clean/v1/audio/transcriptions", ServerFamily.OPENAI_V1),
      EndpointCandidate("$clean/audio/transcriptions", ServerFamily.OPENAI_NO_V1),
      EndpointCandidate("$clean/inference", ServerFamily.WHISPER_CPP),
      EndpointCandidate("$clean/asr", ServerFamily.WHISPER_ASR),
    )
  }

  suspend fun detectEndpoint(
    rawInput: String,
    apiKey: String,
    modelName: String,
    language: LanguageOption,
  ): ProbeResult =
    withContext(Dispatchers.IO) {
      val normalized =
        normalizeInput(rawInput)
          ?: return@withContext ProbeResult.Failure("Enter a valid server host or URL.")

      val silentWav = WavEncoder.silentWav(durationMillis = 500)
      val primaryCandidates = buildCandidates(normalized.primaryBase, normalized.path)

      val primaryOutcome =
        probeCandidateList(primaryCandidates, silentWav, apiKey, modelName, language)
      if (primaryOutcome.result != null) {
        return@withContext primaryOutcome.result
      }

      if (primaryOutcome.hadConnectionError && normalized.fallbackHttpsBase != null) {
        val httpsCandidates = buildCandidates(normalized.fallbackHttpsBase, normalized.path)
        val httpsOutcome =
          probeCandidateList(httpsCandidates, silentWav, apiKey, modelName, language)
        if (httpsOutcome.result != null) {
          return@withContext httpsOutcome.result
        }
        return@withContext ProbeResult.Failure(
          httpsOutcome.lastError ?: primaryOutcome.lastError ?: "Could not connect to server."
        )
      }

      ProbeResult.Failure(
        primaryOutcome.lastError ?: "No transcription endpoint found at ${normalized.primaryBase}."
      )
    }

  private data class ProbeBatchOutcome(
    val result: ProbeResult?,
    val hadConnectionError: Boolean,
    val lastError: String?,
  )

  private fun probeCandidateList(
    candidates: List<EndpointCandidate>,
    silentWav: ByteArray,
    apiKey: String,
    modelName: String,
    language: LanguageOption,
  ): ProbeBatchOutcome {
    var hadConnectionError = false
    var lastError: String? = null

    for (candidate in candidates) {
      try {
        val response =
          sendAudioRequest(
            url = candidate.url,
            family = candidate.family,
            wavBytes = silentWav,
            apiKey = apiKey,
            modelName = modelName,
            language = language,
            connectTimeoutMs = PROBE_TIMEOUT_MS,
            readTimeoutMs = PROBE_TIMEOUT_MS,
          )
        when (response.statusCode) {
          200 -> {
            if (isJsonWithTextField(response.body)) {
              return ProbeBatchOutcome(
                result =
                  ProbeResult.Success(
                    url = candidate.url,
                    family = candidate.family,
                    message = "OK: ${candidate.family.label} at ${candidate.url}",
                  ),
                hadConnectionError = false,
                lastError = null,
              )
            } else {
              lastError = "Unexpected 200 response (no 'text' JSON field) at ${candidate.url}"
            }
          }
          401, 403 -> {
            return ProbeBatchOutcome(
              result =
                ProbeResult.NeedsApiKey(
                  url = candidate.url,
                  family = candidate.family,
                  message = "Needs an API key (HTTP ${response.statusCode}). Fill in Advanced -> API key.",
                ),
              hadConnectionError = false,
              lastError = null,
            )
          }
          400, 422 -> {
            if (bodyMentionsAudioOrFileError(response.body)) {
              return ProbeBatchOutcome(
                result =
                  ProbeResult.Success(
                    url = candidate.url,
                    family = candidate.family,
                    message = "OK: ${candidate.family.label} at ${candidate.url}",
                  ),
                hadConnectionError = false,
                lastError = null,
              )
            } else {
              lastError = "HTTP ${response.statusCode} at ${candidate.url}"
            }
          }
          404, 405 -> {
            lastError = "HTTP ${response.statusCode} at ${candidate.url}"
          }
          else -> {
            lastError = "HTTP ${response.statusCode} at ${candidate.url}"
          }
        }
      } catch (e: IOException) {
        hadConnectionError = true
        lastError = "Connection error (${e.message ?: e.javaClass.simpleName})"
      }
    }

    return ProbeBatchOutcome(null, hadConnectionError, lastError)
  }

  suspend fun transcribe(
    wavBytes: ByteArray,
    settings: AppSettings,
    onEndpointDetected: (String, ServerFamily) -> Unit,
    onEndpointInvalidated: () -> Unit,
  ): TranscribeResult =
    withContext(Dispatchers.IO) {
      if (settings.endpointInput.isBlank()) {
        return@withContext TranscribeResult.Failure("No transcription server set. Open Settings.")
      }

      var targetUrl = settings.detectedEndpointUrl
      var targetFamily = settings.detectedFamily
      var refreshed = false

      if (!settings.hasValidCachedEndpoint || targetFamily == null) {
        when (
          val probe =
            detectEndpoint(
              rawInput = settings.endpointInput,
              apiKey = settings.apiKey,
              modelName = settings.modelName,
              language = settings.language,
            )
        ) {
          is ProbeResult.Success -> {
            targetUrl = probe.url
            targetFamily = probe.family
            onEndpointDetected(probe.url, probe.family)
            refreshed = true
          }
          is ProbeResult.NeedsApiKey -> {
            onEndpointDetected(probe.url, probe.family)
            return@withContext TranscribeResult.Failure(
              "Needs an API key. Fill in Advanced -> API key.",
              isAuthError = true,
            )
          }
          is ProbeResult.Failure -> {
            return@withContext TranscribeResult.Failure(probe.message)
          }
        }
      }

      val firstTry =
        executeTranscription(
          url = targetUrl,
          family = targetFamily,
          wavBytes = wavBytes,
          apiKey = settings.apiKey,
          modelName = settings.modelName,
          language = settings.language,
          refreshed = refreshed,
        )

      if (firstTry is InternalTranscribeOutcome.NotFound && !refreshed) {
        onEndpointInvalidated()
        when (
          val reProbe =
            detectEndpoint(
              rawInput = settings.endpointInput,
              apiKey = settings.apiKey,
              modelName = settings.modelName,
              language = settings.language,
            )
        ) {
          is ProbeResult.Success -> {
            onEndpointDetected(reProbe.url, reProbe.family)
            return@withContext when (
              val retry =
                executeTranscription(
                  url = reProbe.url,
                  family = reProbe.family,
                  wavBytes = wavBytes,
                  apiKey = settings.apiKey,
                  modelName = settings.modelName,
                  language = settings.language,
                  refreshed = true,
                )
            ) {
              is InternalTranscribeOutcome.Done -> retry.result
              is InternalTranscribeOutcome.NotFound ->
                TranscribeResult.Failure("Endpoint not found (HTTP 404).")
            }
          }
          is ProbeResult.NeedsApiKey -> {
            return@withContext TranscribeResult.Failure(
              "Needs an API key. Fill in Advanced -> API key.",
              isAuthError = true,
            )
          }
          is ProbeResult.Failure -> {
            return@withContext TranscribeResult.Failure(reProbe.message)
          }
        }
      }

      when (firstTry) {
        is InternalTranscribeOutcome.Done -> firstTry.result
        is InternalTranscribeOutcome.NotFound ->
          TranscribeResult.Failure("Endpoint not found (HTTP 404).")
      }
    }

  private sealed class InternalTranscribeOutcome {
    data class Done(val result: TranscribeResult) : InternalTranscribeOutcome()

    data object NotFound : InternalTranscribeOutcome()
  }

  private fun executeTranscription(
    url: String,
    family: ServerFamily,
    wavBytes: ByteArray,
    apiKey: String,
    modelName: String,
    language: LanguageOption,
    refreshed: Boolean,
  ): InternalTranscribeOutcome {
    return try {
      val response =
        sendAudioRequest(
          url = url,
          family = family,
          wavBytes = wavBytes,
          apiKey = apiKey,
          modelName = modelName,
          language = language,
          connectTimeoutMs = TRANSCRIBE_CONNECT_TIMEOUT_MS,
          readTimeoutMs = TRANSCRIBE_READ_TIMEOUT_MS,
        )
      when (response.statusCode) {
        200 -> {
          val text = parseTranscriptionText(response.body)
          InternalTranscribeOutcome.Done(
            TranscribeResult.Success(
              text = text,
              usedUrl = url,
              usedFamily = family,
              refreshedEndpoint = refreshed,
            )
          )
        }
        401, 403 -> {
          InternalTranscribeOutcome.Done(
            TranscribeResult.Failure(
              "Unauthorized (HTTP ${response.statusCode}). Check API key in Settings.",
              isAuthError = true,
            )
          )
        }
        404 -> InternalTranscribeOutcome.NotFound
        else -> {
          val detail = extractErrorMessage(response.body)
          val msg =
            if (detail.isNotBlank()) "Server error ${response.statusCode}: $detail"
            else "Server error (HTTP ${response.statusCode})"
          InternalTranscribeOutcome.Done(TranscribeResult.Failure(msg))
        }
      }
    } catch (e: IOException) {
      InternalTranscribeOutcome.Done(
        TranscribeResult.Failure("Network error: ${e.message ?: e.javaClass.simpleName}")
      )
    }
  }

  private fun sendAudioRequest(
    url: String,
    family: ServerFamily,
    wavBytes: ByteArray,
    apiKey: String,
    modelName: String,
    language: LanguageOption,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
  ): HttpResponseData {
    val headers = buildMap {
      if (apiKey.isNotBlank()) {
        put("Authorization", "Bearer ${apiKey.trim()}")
      }
    }

    val effectiveUrl: String
    val fileFieldName: String
    val fields = linkedMapOf<String, String>()

    when (family) {
      ServerFamily.OPENAI_V1, ServerFamily.OPENAI_NO_V1 -> {
        effectiveUrl = url
        fileFieldName = "file"
        fields["model"] = modelName.ifBlank { AppSettings.DEFAULT_MODEL_NAME }
        fields["response_format"] = "json"
        language.code?.let { fields["language"] = it }
      }
      ServerFamily.WHISPER_CPP -> {
        effectiveUrl = url
        fileFieldName = "file"
        fields["response_format"] = "json"
        language.code?.let { fields["language"] = it }
      }
      ServerFamily.WHISPER_ASR -> {
        val separator = if (url.contains("?")) "&" else "?"
        val langQuery =
          language.code?.let { "&language=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        effectiveUrl = "${url}${separator}output=json&task=transcribe$langQuery"
        fileFieldName = "audio_file"
      }
    }

    return transport.postMultipart(
      urlString = effectiveUrl,
      headers = headers,
      fileFieldName = fileFieldName,
      fileName = "audio.wav",
      fileBytes = wavBytes,
      fields = fields,
      connectTimeoutMs = connectTimeoutMs,
      readTimeoutMs = readTimeoutMs,
    )
  }

  internal fun isJsonWithTextField(body: String): Boolean {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{")) return false
    return try {
      JSONObject(trimmed).has("text")
    } catch (_: Exception) {
      false
    }
  }

  internal fun bodyMentionsAudioOrFileError(body: String): Boolean {
    val lower = body.lowercase()
    return lower.contains("audio") ||
      lower.contains("file") ||
      lower.contains("multipart") ||
      lower.contains("wav") ||
      lower.contains("duration") ||
      lower.contains("too short")
  }

  internal fun parseTranscriptionText(body: String): String {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
      try {
        val json = JSONObject(trimmed)
        if (json.has("text")) {
          return json.optString("text", "").trim()
        }
      } catch (_: Exception) {
        // Fallback to plain text
      }
    }
    return trimmed
  }

  private fun extractErrorMessage(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("{")) {
      try {
        val json = JSONObject(trimmed)
        val err = json.opt("error")
        if (err is JSONObject) {
          val msg = err.optString("message", "")
          if (msg.isNotBlank()) return msg.take(120)
        } else if (err is String && err.isNotBlank()) {
          return err.take(120)
        }
        val detail = json.optString("detail", "")
        if (detail.isNotBlank()) return detail.take(120)
      } catch (_: Exception) {
        // ignore
      }
    }
    return trimmed.lineSequence().firstOrNull()?.take(120) ?: ""
  }

  companion object {
    private const val PROBE_TIMEOUT_MS = 5000
    private const val TRANSCRIBE_CONNECT_TIMEOUT_MS = 5000
    private const val TRANSCRIBE_READ_TIMEOUT_MS = 25000
  }
}
