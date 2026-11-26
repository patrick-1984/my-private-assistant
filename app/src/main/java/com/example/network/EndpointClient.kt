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
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
    tlsServerName: String? = null,
  ): HttpResponseData
}

private class SniSocketFactory(
  private val delegate: SSLSocketFactory,
  private val sniHostname: String,
) : SSLSocketFactory() {

  override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

  override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

  override fun createSocket(): Socket = configureSni(delegate.createSocket())

  override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
    configureSni(delegate.createSocket(s, sniHostname, port, autoClose))

  override fun createSocket(host: String?, port: Int): Socket =
    configureSni(delegate.createSocket(host, port))

  override fun createSocket(
    host: String?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int,
  ): Socket = configureSni(delegate.createSocket(host, port, localHost, localPort))

  override fun createSocket(host: InetAddress?, port: Int): Socket =
    configureSni(delegate.createSocket(host, port))

  override fun createSocket(
    address: InetAddress?,
    port: Int,
    localAddress: InetAddress?,
    localPort: Int,
  ): Socket = configureSni(delegate.createSocket(address, port, localAddress, localPort))

  private fun configureSni(socket: Socket): Socket {
    if (socket is SSLSocket) {
      val params = socket.sslParameters
      params.serverNames = listOf(SNIHostName(sniHostname))
      socket.sslParameters = params
    }
    return socket
  }
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
    tlsServerName: String?,
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
      if (conn is HttpsURLConnection && !tlsServerName.isNullOrBlank()) {
        conn.sslSocketFactory =
          SniSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), tlsServerName)
        conn.hostnameVerifier =
          HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(tlsServerName, session)
          }
      }

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

  data class ResolvedTarget(
    val connectUrl: String,
    val hostHeader: String?,
    val tlsServerName: String?,
  )

  fun validateNumericIp(rawIp: String): String? {
    val trimmed = rawIp.trim().removeSurrounding("[", "]")
    if (trimmed.isEmpty()) return null
    if (
      trimmed.contains("://") ||
        trimmed.contains('/') ||
        trimmed.contains('?') ||
        trimmed.contains('#') ||
        trimmed.any { it.isWhitespace() }
    ) {
      return null
    }
    if (isValidIpv4(trimmed)) return trimmed
    if (isValidIpv6(trimmed)) return trimmed
    return null
  }

  private fun isValidIpv4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
      if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return@all false
      val num = part.toIntOrNull() ?: return@all false
      num in 0..255
    }
  }

  private fun isValidIpv6(ip: String): Boolean {
    val colons = ip.count { it == ':' }
    if (colons < 2 || colons > 7) return false
    if (ip.split("::").size > 2) return false
    if (!ip.all { ch -> ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F' || ch == ':' || ch == '.' }) {
      return false
    }
    if (ip.contains('.')) {
      val lastColon = ip.lastIndexOf(':')
      val ipv4Tail = ip.substring(lastColon + 1)
      if (!isValidIpv4(ipv4Tail)) return false
    }
    return true
  }

  fun resolveTarget(logicalUrl: String, validatedOverrideIp: String?): ResolvedTarget {
    if (validatedOverrideIp.isNullOrBlank()) {
      return ResolvedTarget(connectUrl = logicalUrl, hostHeader = null, tlsServerName = null)
    }

    val uri = URI(logicalUrl)
    val originalHost = uri.host ?: return ResolvedTarget(logicalUrl, null, null)
    val port = uri.port
    val hostHeader = if (port != -1) "$originalHost:$port" else originalHost
    val ipForAuthority =
      if (validatedOverrideIp.contains(':')) "[$validatedOverrideIp]" else validatedOverrideIp
    val portPart = if (port != -1) ":$port" else ""
    val pathPart = uri.rawPath.orEmpty()
    val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
    val connectUrl = "${uri.scheme}://$ipForAuthority$portPart$pathPart$queryPart"
    val sni = if (uri.scheme.equals("https", ignoreCase = true)) originalHost else null

    return ResolvedTarget(
      connectUrl = connectUrl,
      hostHeader = hostHeader,
      tlsServerName = sni,
    )
  }

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
    useIpOverride: Boolean = false,
    overrideIp: String = "",
  ): ProbeResult =
    withContext(Dispatchers.IO) {
      val normalized =
        normalizeInput(rawInput)
          ?: return@withContext ProbeResult.Failure("Enter a valid server host or URL.")

      val validatedIp =
        if (useIpOverride) {
          validateNumericIp(overrideIp)
            ?: return@withContext ProbeResult.Failure(
              "Enter a valid IP address (no scheme, port, or path) or uncheck IP override."
            )
        } else {
          null
        }

      val silentWav = WavEncoder.silentWav(durationMillis = 500)
      val primaryCandidates = buildCandidates(normalized.primaryBase, normalized.path)

      val primaryOutcome =
        probeCandidateList(
          candidates = primaryCandidates,
          silentWav = silentWav,
          apiKey = apiKey,
          modelName = modelName,
          language = language,
          validatedOverrideIp = validatedIp,
        )
      if (primaryOutcome.result != null) {
        return@withContext primaryOutcome.result
      }

      if (primaryOutcome.hadConnectionError && normalized.fallbackHttpsBase != null) {
        val httpsCandidates = buildCandidates(normalized.fallbackHttpsBase, normalized.path)
        val httpsOutcome =
          probeCandidateList(
            candidates = httpsCandidates,
            silentWav = silentWav,
            apiKey = apiKey,
            modelName = modelName,
            language = language,
            validatedOverrideIp = validatedIp,
          )
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
    validatedOverrideIp: String?,
  ): ProbeBatchOutcome {
    var hadConnectionError = false
    var lastError: String? = null
    val viaSuffix = validatedOverrideIp?.let { " (via $it)" }.orEmpty()

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
            validatedOverrideIp = validatedOverrideIp,
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
                    message = "OK: ${candidate.family.label} at ${candidate.url}$viaSuffix",
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
                  message =
                    "Needs an API key (HTTP ${response.statusCode}). Fill in Advanced -> API key.",
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
                    message = "OK: ${candidate.family.label} at ${candidate.url}$viaSuffix",
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

      val validatedIp =
        if (settings.useIpOverride) {
          validateNumericIp(settings.overrideIp)
            ?: return@withContext TranscribeResult.Failure(
              "Invalid override IP address in Settings."
            )
        } else {
          null
        }

      var targetUrl = settings.detectedEndpointUrl
      var targetFamily = settings.detectedFamily
      var refreshed = false

      if (!settings.hasValidCachedEndpoint || targetFamily == null) {
        when (
          val probe =
            detectEndpoint(
              rawInput = settings.endpointInput,
              apiKey = settings.effectiveApiKey,
              modelName = settings.modelName,
              language = settings.language,
              useIpOverride = settings.useIpOverride,
              overrideIp = settings.overrideIp,
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
              "Needs an API key. Enable 'Provide API key' in Settings.",
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
          apiKey = settings.effectiveApiKey,
          modelName = settings.modelName,
          language = settings.language,
          validatedOverrideIp = validatedIp,
          refreshed = refreshed,
        )

      if (firstTry is InternalTranscribeOutcome.NotFound && !refreshed) {
        onEndpointInvalidated()
        when (
          val reProbe =
            detectEndpoint(
              rawInput = settings.endpointInput,
              apiKey = settings.effectiveApiKey,
              modelName = settings.modelName,
              language = settings.language,
              useIpOverride = settings.useIpOverride,
              overrideIp = settings.overrideIp,
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
                  apiKey = settings.effectiveApiKey,
                  modelName = settings.modelName,
                  language = settings.language,
                  validatedOverrideIp = validatedIp,
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
              "Needs an API key. Enable 'Provide API key' in Settings.",
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
    validatedOverrideIp: String?,
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
          validatedOverrideIp = validatedOverrideIp,
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
    validatedOverrideIp: String?,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
  ): HttpResponseData {
    val logicalUrl: String
    val fileFieldName: String
    val fields = linkedMapOf<String, String>()

    when (family) {
      ServerFamily.OPENAI_V1, ServerFamily.OPENAI_NO_V1 -> {
        logicalUrl = url
        fileFieldName = "file"
        fields["model"] = modelName.ifBlank { AppSettings.DEFAULT_MODEL_NAME }
        fields["response_format"] = "json"
        language.code?.let { fields["language"] = it }
      }
      ServerFamily.WHISPER_CPP -> {
        logicalUrl = url
        fileFieldName = "file"
        fields["response_format"] = "json"
        language.code?.let { fields["language"] = it }
      }
      ServerFamily.WHISPER_ASR -> {
        val separator = if (url.contains("?")) "&" else "?"
        val langQuery =
          language.code?.let { "&language=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        logicalUrl = "${url}${separator}output=json&task=transcribe$langQuery"
        fileFieldName = "audio_file"
      }
    }

    val target = resolveTarget(logicalUrl, validatedOverrideIp)

    val headers = buildMap {
      target.hostHeader?.let { put("Host", it) }
      if (apiKey.isNotBlank()) {
        put("Authorization", "Bearer ${apiKey.trim()}")
      }
    }

    return transport.postMultipart(
      urlString = target.connectUrl,
      headers = headers,
      fileFieldName = fileFieldName,
      fileName = "audio.wav",
      fileBytes = wavBytes,
      fields = fields,
      connectTimeoutMs = connectTimeoutMs,
      readTimeoutMs = readTimeoutMs,
      tlsServerName = target.tlsServerName,
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
