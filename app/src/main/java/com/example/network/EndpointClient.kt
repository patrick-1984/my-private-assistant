package com.example.network

import android.annotation.SuppressLint
import com.example.audio.WavEncoder
import com.example.data.AppSettings
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EndpointCandidate(val url: String, val family: ServerFamily)

sealed class ProbeResult {
  data class Success(
    val url: String,
    val family: ServerFamily,
    val message: String,
    val availableModels: List<String> = emptyList(),
  ) : ProbeResult()

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
    ignoreSsl: Boolean = false,
    onSseLine: ((String) -> Unit)? = null,
  ): HttpResponseData

  @Throws(IOException::class)
  fun getJson(
    urlString: String,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    tlsServerName: String? = null,
    ignoreSsl: Boolean = false,
  ): HttpResponseData = HttpResponseData(404, "")
}

object UrlConnectionTransport : HttpTransport {

  @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
  private fun trustAllSslSocketFactory(): SSLSocketFactory {
    val trustAll =
      arrayOf<TrustManager>(
        object : X509TrustManager {
          override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

          override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

          override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
      )
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustAll, SecureRandom())
    return sslContext.socketFactory
  }

  private fun validateHostnameForSni(host: String): Boolean {
    if (host.isBlank()) return false
    if (host.contains(':')) return false // IPv6 literal
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) {
      return false // IPv4 literal
    }
    return true
  }

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
    ignoreSsl: Boolean,
    onSseLine: ((String) -> Unit)?,
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

    // When routing to an override IP (indicated by Host header or tlsServerName), use a direct
    // Socket / SSLSocket HTTP/1.1 connection so Android's HttpURLConnection/OkHttp layer cannot
    // overwrite TLS SNI with the IP literal (which causes TLSV1_ALERT_UNRECOGNIZED_NAME).
    if (!tlsServerName.isNullOrBlank() || headers.containsKey("Host")) {
      val requestHeaders =
        linkedMapOf<String, String>().apply {
          put("Content-Type", "multipart/form-data; boundary=$boundary")
          putAll(headers)
        }
      return sendDirectHttp11(
        method = "POST",
        urlString = urlString,
        headers = requestHeaders,
        payload = payload,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        tlsServerName = tlsServerName,
        ignoreSsl = ignoreSsl,
        onSseLine = onSseLine,
      )
    }

    val conn = (URL(urlString).openConnection() as HttpURLConnection)
    try {
      if (conn is HttpsURLConnection && ignoreSsl) {
        conn.sslSocketFactory = trustAllSslSocketFactory()
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
      }

      conn.requestMethod = "POST"
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = readTimeoutMs
      conn.doInput = true
      conn.doOutput = true
      conn.useCaches = false
      conn.setFixedLengthStreamingMode(payload.size)
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      conn.setRequestProperty(
        "Accept",
        if (onSseLine != null) "text/event-stream, application/json, text/plain;q=0.9"
        else "application/json, text/plain;q=0.9",
      )
      for ((k, v) in headers) {
        conn.setRequestProperty(k, v)
      }

      conn.outputStream.use { os ->
        os.write(payload)
        os.flush()
      }

      val status = conn.responseCode
      val contentType = conn.contentType.orEmpty().lowercase()
      val stream = if (status in 200..299) conn.inputStream else conn.errorStream
      val responseText =
        if (stream != null) {
          if (status in 200..299 && contentType.contains("text/event-stream") && onSseLine != null) {
            readSseStream(stream, onSseLine)
          } else {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
          }
        } else {
          ""
        }
      return HttpResponseData(status, responseText)
    } finally {
      conn.disconnect()
    }
  }

  override fun getJson(
    urlString: String,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    tlsServerName: String?,
    ignoreSsl: Boolean,
  ): HttpResponseData {
    if (!tlsServerName.isNullOrBlank() || headers.containsKey("Host")) {
      return sendDirectHttp11(
        method = "GET",
        urlString = urlString,
        headers = headers,
        payload = null,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        tlsServerName = tlsServerName,
        ignoreSsl = ignoreSsl,
      )
    }

    val conn = (URL(urlString).openConnection() as HttpURLConnection)
    try {
      if (conn is HttpsURLConnection && ignoreSsl) {
        conn.sslSocketFactory = trustAllSslSocketFactory()
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
      }

      conn.requestMethod = "GET"
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = readTimeoutMs
      conn.doInput = true
      conn.useCaches = false
      conn.setRequestProperty("Accept", "application/json, text/plain;q=0.9")
      for ((k, v) in headers) {
        conn.setRequestProperty(k, v)
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

  private fun readSseStream(input: InputStream, onSseLine: (String) -> Unit): String {
    val all = StringBuilder()
    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
      while (true) {
        val line = reader.readLine() ?: break
        all.append(line).append('\n')
        onSseLine(line)
      }
    }
    return all.toString()
  }

  private fun sendDirectHttp11(
    method: String,
    urlString: String,
    headers: Map<String, String>,
    payload: ByteArray?,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    tlsServerName: String?,
    ignoreSsl: Boolean,
    onSseLine: ((String) -> Unit)? = null,
  ): HttpResponseData {
    val uri = URI(urlString)
    val isHttps = uri.scheme.equals("https", ignoreCase = true)
    val connectIp = uri.host.removeSurrounding("[", "]")
    val port = if (uri.port != -1) uri.port else if (isHttps) 443 else 80
    val pathPart = uri.rawPath.orEmpty().ifEmpty { "/" }
    val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
    val requestTarget = "$pathPart$queryPart"
    val hostHeader = headers["Host"] ?: if (uri.port != -1) "$connectIp:$port" else connectIp

    val tcpSocket = Socket()
    var activeSocket: Socket = tcpSocket
    try {
      // Numeric IP InetAddress does not trigger DNS lookup
      val addr = InetAddress.getByName(connectIp)
      tcpSocket.connect(InetSocketAddress(addr, port), connectTimeoutMs)
      tcpSocket.soTimeout = readTimeoutMs

      if (isHttps) {
        val factory =
          if (ignoreSsl) trustAllSslSocketFactory()
          else HttpsURLConnection.getDefaultSSLSocketFactory()
        val sniHost = (tlsServerName ?: hostHeader.substringBefore(':')).trim()
        val asciiSni = IDN.toASCII(sniHost)
        val isHostname = asciiSni.isNotBlank() && validateHostnameForSni(asciiSni)
        val peerHost = if (isHostname) asciiSni else connectIp
        val sslSocket =
          factory.createSocket(tcpSocket, peerHost, port, true) as SSLSocket
        activeSocket = sslSocket

        val params = sslSocket.sslParameters
        if (isHostname) {
          params.serverNames = listOf(SNIHostName(asciiSni))
        }
        if (!ignoreSsl) {
          params.endpointIdentificationAlgorithm = "HTTPS"
        }
        sslSocket.sslParameters = params
        sslSocket.startHandshake()

        if (!ignoreSsl) {
          val ok =
            HttpsURLConnection.getDefaultHostnameVerifier().verify(asciiSni, sslSocket.session)
          if (!ok) {
            throw SSLPeerUnverifiedException("Certificate hostname mismatch for $asciiSni")
          }
        }
      }

      val out = activeSocket.getOutputStream()
      val headerText = buildString {
        append("$method $requestTarget HTTP/1.1\r\n")
        append("Host: $hostHeader\r\n")
        append("User-Agent: TVVoiceSearch/1.1\r\n")
        if (onSseLine != null) {
          append("Accept: text/event-stream, application/json, text/plain;q=0.9\r\n")
        } else {
          append("Accept: application/json, text/plain;q=0.9\r\n")
        }
        append("Accept-Encoding: identity\r\n")
        if (payload != null) {
          append("Content-Length: ${payload.size}\r\n")
        }
        for ((k, v) in headers) {
          if (!k.equals("Host", ignoreCase = true)) {
            append("$k: $v\r\n")
          }
        }
        append("Connection: close\r\n\r\n")
      }
      out.write(headerText.toByteArray(Charsets.US_ASCII))
      if (payload != null) {
        out.write(payload)
      }
      out.flush()

      return readHttp11Response(BufferedInputStream(activeSocket.getInputStream()), onSseLine)
    } finally {
      runCatching { activeSocket.close() }
      runCatching { tcpSocket.close() }
    }
  }

  internal fun readHttp11Response(
    input: InputStream,
    onSseLine: ((String) -> Unit)? = null,
  ): HttpResponseData {
    var statusCode: Int
    val responseHeaders = linkedMapOf<String, String>()

    while (true) {
      val statusLine = readAsciiLine(input) ?: throw EOFException("Empty HTTP response")
      if (statusLine.isEmpty()) continue
      val parts = statusLine.split(' ', limit = 3)
      statusCode =
        parts.getOrNull(1)?.toIntOrNull()
          ?: throw IOException("Malformed HTTP status line: $statusLine")

      responseHeaders.clear()
      while (true) {
        val line = readAsciiLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          val name = line.substring(0, idx).trim().lowercase()
          val value = line.substring(idx + 1).trim()
          val existing = responseHeaders[name]
          responseHeaders[name] = if (existing == null) value else "$existing, $value"
        }
      }

      if (statusCode != 100) break
    }

    val transferEncoding = responseHeaders["transfer-encoding"].orEmpty().lowercase()
    val contentType = responseHeaders["content-type"].orEmpty().lowercase()
    val contentLength = responseHeaders["content-length"]?.toIntOrNull()

    if (
      statusCode in 200..299 &&
        contentType.contains("text/event-stream") &&
        !transferEncoding.contains("chunked") &&
        onSseLine != null
    ) {
      val body = readSseStream(input, onSseLine)
      return HttpResponseData(statusCode, body)
    }

    val bodyBytes =
      when {
        transferEncoding.contains("chunked") -> readChunkedBody(input)
        contentLength != null && contentLength >= 0 -> readFixedBody(input, contentLength)
        else -> input.readBytes()
      }

    val bodyText = bodyBytes.toString(Charsets.UTF_8)
    if (statusCode in 200..299 && contentType.contains("text/event-stream") && onSseLine != null) {
      bodyText.lineSequence().forEach { onSseLine(it) }
    }
    return HttpResponseData(statusCode, bodyText)
  }

  private fun readFixedBody(input: InputStream, length: Int): ByteArray {
    if (length == 0) return ByteArray(0)
    val out = ByteArrayOutputStream(length)
    val buf = ByteArray(minOf(8192, length))
    var remaining = length
    while (remaining > 0) {
      val read = input.read(buf, 0, minOf(buf.size, remaining))
      if (read <= 0) break
      out.write(buf, 0, read)
      remaining -= read
    }
    return out.toByteArray()
  }

  private fun readChunkedBody(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
      val sizeLine = readAsciiLine(input) ?: break
      val chunkSizeHex = sizeLine.substringBefore(';').trim()
      val chunkSize = chunkSizeHex.toIntOrNull(16) ?: break
      if (chunkSize == 0) {
        // Consume trailing headers if any
        while (true) {
          val trailer = readAsciiLine(input)
          if (trailer.isNullOrEmpty()) break
        }
        break
      }
      val buf = ByteArray(chunkSize)
      var offset = 0
      while (offset < chunkSize) {
        val read = input.read(buf, offset, chunkSize - offset)
        if (read <= 0) throw EOFException("Truncated HTTP chunk")
        offset += read
      }
      out.write(buf)
      readAsciiLine(input) // consume CRLF after chunk data
    }
    return out.toByteArray()
  }

  private fun readAsciiLine(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    var prevWasCr = false
    while (true) {
      val b = input.read()
      if (b == -1) {
        return if (out.size() == 0) null else out.toByteArray().toString(Charsets.US_ASCII)
      }
      if (b == '\n'.code) {
        val bytes = out.toByteArray()
        val len = if (prevWasCr && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.US_ASCII)
      }
      prevWasCr = (b == '\r'.code)
      out.write(b)
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
    ignoreSsl: Boolean = false,
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
          ignoreSsl = ignoreSsl,
        )
      if (primaryOutcome.result != null) {
        val enriched =
          enrichSuccessWithModels(
            result = primaryOutcome.result,
            apiKey = apiKey,
            validatedOverrideIp = validatedIp,
            ignoreSsl = ignoreSsl,
          )
        return@withContext enriched
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
            ignoreSsl = ignoreSsl,
          )
        if (httpsOutcome.result != null) {
          val enriched =
            enrichSuccessWithModels(
              result = httpsOutcome.result,
              apiKey = apiKey,
              validatedOverrideIp = validatedIp,
              ignoreSsl = ignoreSsl,
            )
          return@withContext enriched
        }
        return@withContext ProbeResult.Failure(
          httpsOutcome.lastError ?: primaryOutcome.lastError ?: "Could not connect to server."
        )
      }

      ProbeResult.Failure(
        primaryOutcome.lastError ?: "No transcription endpoint found at ${normalized.primaryBase}."
      )
    }

  private fun enrichSuccessWithModels(
    result: ProbeResult,
    apiKey: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
  ): ProbeResult {
    if (result !is ProbeResult.Success) return result
    val models = fetchModelsForEndpoint(result.url, apiKey, validatedOverrideIp, ignoreSsl)
    return result.copy(availableModels = models)
  }

  suspend fun fetchModels(
    detectedUrl: String,
    rawInput: String,
    apiKey: String,
    useIpOverride: Boolean,
    overrideIp: String,
    ignoreSsl: Boolean,
  ): List<String> =
    withContext(Dispatchers.IO) {
      val validatedIp = if (useIpOverride) validateNumericIp(overrideIp) else null
      val baseUrl =
        detectedUrl.ifBlank {
          normalizeInput(rawInput)?.let { "${it.primaryBase}${it.path}" }.orEmpty()
        }
      if (baseUrl.isBlank()) return@withContext emptyList()
      fetchModelsForEndpoint(baseUrl, apiKey, validatedIp, ignoreSsl)
    }

  internal fun fetchModelsForEndpoint(
    endpointUrl: String,
    apiKey: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
  ): List<String> {
    val modelUrls = buildOpenAiModelUrls(endpointUrl)
    for (url in modelUrls) {
      try {
        val target = resolveTarget(url, validatedOverrideIp)
        val headers = buildMap {
          target.hostHeader?.let { put("Host", it) }
          if (apiKey.isNotBlank()) {
            put("Authorization", "Bearer ${apiKey.trim()}")
          }
        }
        val response =
          transport.getJson(
            urlString = target.connectUrl,
            headers = headers,
            connectTimeoutMs = PROBE_TIMEOUT_MS,
            readTimeoutMs = PROBE_TIMEOUT_MS,
            tlsServerName = target.tlsServerName,
            ignoreSsl = ignoreSsl,
          )
        if (response.statusCode == 200) {
          val parsed = parseOpenAiModelsJson(response.body)
          if (parsed.isNotEmpty()) return filterRelevantModels(endpointUrl, parsed)
        }
      } catch (_: Exception) {
        // Ignore model discovery failure; endpoint transcription still works
      }
    }
    return emptyList()
  }

  internal fun filterRelevantModels(endpointUrl: String, models: List<String>): List<String> {
    if (models.size <= 12 && !endpointUrl.contains("nvidia.com", ignoreCase = true)) {
      return models
    }
    val audioKeywords =
      listOf("whisper", "parakeet", "canary", "asr", "stt", "transcrib", "speech", "audio")
    val audioModels =
      models.filter { id -> audioKeywords.any { kw -> id.contains(kw, ignoreCase = true) } }
    if (audioModels.isNotEmpty()) {
      return audioModels.take(15)
    }
    return models.take(12)
  }

  internal fun buildOpenAiModelUrls(endpointUrl: String): List<String> {
    return try {
      val uri = URI(endpointUrl)
      val portPart = if (uri.port != -1) ":${uri.port}" else ""
      val origin = "${uri.scheme}://${uri.host}$portPart"
      val rawPath = (uri.rawPath ?: "").trimEnd('/')
      val v1Idx = rawPath.indexOf("/v1")
      val urls = linkedSetOf<String>()
      if (v1Idx >= 0) {
        val prefix = rawPath.substring(0, v1Idx)
        urls.add("$origin$prefix/v1/models")
      }
      val audioIdx = rawPath.indexOf("/audio/transcriptions")
      if (audioIdx > 0 && v1Idx < 0) {
        val prefix = rawPath.substring(0, audioIdx)
        urls.add("$origin$prefix/models")
      }
      urls.add("$origin/v1/models")
      urls.add("$origin/models")
      urls.toList()
    } catch (_: Exception) {
      emptyList()
    }
  }

  internal fun parseOpenAiModelsJson(body: String): List<String> {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{")) return emptyList()
    return try {
      val json = JSONObject(trimmed)
      val arr = json.optJSONArray("data") ?: return emptyList()
      val ids = linkedSetOf<String>()
      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val id = item.optString("id", "").trim()
        if (id.isNotEmpty()) ids.add(id)
      }
      ids.toList()
    } catch (_: Exception) {
      emptyList()
    }
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
    ignoreSsl: Boolean,
  ): ProbeBatchOutcome {
    var hadConnectionError = false
    var lastError: String? = null
    val viaSuffix = validatedOverrideIp?.let { " (via $it)" }.orEmpty()

    for (candidate in candidates) {
      try {
        var response =
          sendAudioRequest(
            url = candidate.url,
            family = candidate.family,
            wavBytes = silentWav,
            apiKey = apiKey,
            modelName = modelName,
            language = language,
            validatedOverrideIp = validatedOverrideIp,
            ignoreSsl = ignoreSsl,
            connectTimeoutMs = PROBE_TIMEOUT_MS,
            readTimeoutMs = PROBE_TIMEOUT_MS,
          )
        if (
          isNvidiaNimUrl(candidate.url) &&
            (response.statusCode == 400 || response.statusCode == 422) &&
            !bodyMentionsAudioOrFileError(response.body) &&
            modelName.trim().let { it.isNotEmpty() && it != AppSettings.DEFAULT_MODEL_NAME }
        ) {
          response =
            sendAudioRequest(
              url = candidate.url,
              family = candidate.family,
              wavBytes = silentWav,
              apiKey = apiKey,
              modelName = "",
              language = language,
              validatedOverrideIp = validatedOverrideIp,
              ignoreSsl = ignoreSsl,
              connectTimeoutMs = PROBE_TIMEOUT_MS,
              readTimeoutMs = PROBE_TIMEOUT_MS,
            )
        }
        when (response.statusCode) {
          200 -> {
            if (isJsonWithTextField(response.body) || looksLikeSseTranscript(response.body)) {
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
                    "API key needed (HTTP ${response.statusCode}). Enter a key or uncheck 'API key not needed'.",
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
              val detail = extractErrorMessage(response.body)
              lastError =
                if (detail.isNotBlank()) "HTTP ${response.statusCode}: $detail"
                else "HTTP ${response.statusCode} at ${candidate.url}"
            }
          }
          404, 405 -> {
            lastError = "HTTP ${response.statusCode} at ${candidate.url}"
          }
          else -> {
            val detail = extractErrorMessage(response.body)
            lastError =
              if (detail.isNotBlank()) "HTTP ${response.statusCode}: $detail"
              else "HTTP ${response.statusCode} at ${candidate.url}"
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
    onPartialText: ((String) -> Unit)? = null,
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
              modelName = settings.effectiveModelName,
              language = settings.language,
              useIpOverride = settings.useIpOverride,
              overrideIp = settings.overrideIp,
              ignoreSsl = settings.ignoreSsl,
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
              "API key needed. Enter an API key or uncheck 'API key not needed' in Settings.",
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
          modelName = settings.effectiveModelName,
          language = settings.language,
          validatedOverrideIp = validatedIp,
          ignoreSsl = settings.ignoreSsl,
          preferStreamingSse = settings.liveTranscription && onPartialText != null,
          onPartialText = onPartialText,
          refreshed = refreshed,
        )

      if (firstTry is InternalTranscribeOutcome.NotFound && !refreshed) {
        onEndpointInvalidated()
        when (
          val reProbe =
            detectEndpoint(
              rawInput = settings.endpointInput,
              apiKey = settings.effectiveApiKey,
              modelName = settings.effectiveModelName,
              language = settings.language,
              useIpOverride = settings.useIpOverride,
              overrideIp = settings.overrideIp,
              ignoreSsl = settings.ignoreSsl,
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
                  modelName = settings.effectiveModelName,
                  language = settings.language,
                  validatedOverrideIp = validatedIp,
                  ignoreSsl = settings.ignoreSsl,
                  preferStreamingSse = settings.liveTranscription && onPartialText != null,
                  onPartialText = onPartialText,
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
              "API key needed. Enter an API key or uncheck 'API key not needed' in Settings.",
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
    ignoreSsl: Boolean,
    preferStreamingSse: Boolean,
    onPartialText: ((String) -> Unit)?,
    refreshed: Boolean,
  ): InternalTranscribeOutcome {
    return try {
      val sseAccumulator = StringBuilder()
      val sseLineHandler: ((String) -> Unit)? =
        if (onPartialText != null) {
          { line ->
            val delta = extractSseTextFragment(line)
            if (delta != null) {
              if (delta.isFinalReplacement) {
                sseAccumulator.clear()
                sseAccumulator.append(delta.text)
              } else {
                sseAccumulator.append(delta.text)
              }
              val current = sseAccumulator.toString().trim()
              if (current.isNotEmpty()) {
                onPartialText(current)
              }
            }
          }
        } else {
          null
        }

      var response =
        sendAudioRequest(
          url = url,
          family = family,
          wavBytes = wavBytes,
          apiKey = apiKey,
          modelName = modelName,
          language = language,
          validatedOverrideIp = validatedOverrideIp,
          ignoreSsl = ignoreSsl,
          streamSse = preferStreamingSse,
          onSseLine = sseLineHandler,
          connectTimeoutMs = TRANSCRIBE_CONNECT_TIMEOUT_MS,
          readTimeoutMs = TRANSCRIBE_READ_TIMEOUT_MS,
        )

      // Graceful fallback if endpoint rejects stream=true or explicit NIM model with 400/422/501
      if (
        preferStreamingSse &&
          (response.statusCode == 400 || response.statusCode == 422 || response.statusCode == 501)
      ) {
        sseAccumulator.clear()
        response =
          sendAudioRequest(
            url = url,
            family = family,
            wavBytes = wavBytes,
            apiKey = apiKey,
            modelName = modelName,
            language = language,
            validatedOverrideIp = validatedOverrideIp,
            ignoreSsl = ignoreSsl,
            streamSse = false,
            onSseLine = null,
            connectTimeoutMs = TRANSCRIBE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = TRANSCRIBE_READ_TIMEOUT_MS,
          )
      }

      if (
        isNvidiaNimUrl(url) &&
          (response.statusCode == 400 || response.statusCode == 422) &&
          modelName.trim().let { it.isNotEmpty() && it != AppSettings.DEFAULT_MODEL_NAME }
      ) {
        response =
          sendAudioRequest(
            url = url,
            family = family,
            wavBytes = wavBytes,
            apiKey = apiKey,
            modelName = "",
            language = language,
            validatedOverrideIp = validatedOverrideIp,
            ignoreSsl = ignoreSsl,
            streamSse = false,
            onSseLine = null,
            connectTimeoutMs = TRANSCRIBE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = TRANSCRIBE_READ_TIMEOUT_MS,
          )
      }

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

  private fun isNvidiaNimUrl(url: String): Boolean {
    return url.contains("nvcf.nvidia.com", ignoreCase = true) ||
      url.contains("integrate.api.nvidia.com", ignoreCase = true)
  }

  private fun nvidiaBcp47Language(language: LanguageOption): String {
    return when (language) {
      LanguageOption.AUTO -> "multi"
      LanguageOption.POLISH -> "pl-PL"
      LanguageOption.ENGLISH -> "en-US"
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
    ignoreSsl: Boolean,
    streamSse: Boolean = false,
    onSseLine: ((String) -> Unit)? = null,
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
        if (isNvidiaNimUrl(url)) {
          val explicitModel =
            modelName.trim().takeIf { it.isNotEmpty() && it != AppSettings.DEFAULT_MODEL_NAME }
          if (explicitModel != null) {
            fields["model"] = explicitModel
          }
          fields["language"] = nvidiaBcp47Language(language)
        } else {
          fields["model"] = modelName.ifBlank { AppSettings.DEFAULT_MODEL_NAME }
          language.code?.let { fields["language"] = it }
        }
        fields["response_format"] = "json"
        if (streamSse) {
          fields["stream"] = "true"
        }
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
      ignoreSsl = ignoreSsl,
      onSseLine = onSseLine,
    )
  }

  private data class SseFragment(val text: String, val isFinalReplacement: Boolean)

  private fun extractSseTextFragment(rawLine: String): SseFragment? {
    val trimmed = rawLine.trim()
    if (!trimmed.startsWith("data:")) return null
    val payload = trimmed.removePrefix("data:").trim()
    if (payload.isEmpty() || payload == "[DONE]") return null
    if (!payload.startsWith("{")) return SseFragment(payload, isFinalReplacement = false)

    return try {
      val json = JSONObject(payload)
      val type = json.optString("type", "")
      when {
        type == "transcript.text.done" && json.has("text") ->
          SseFragment(json.optString("text", ""), isFinalReplacement = true)
        type == "transcript.text.delta" && json.has("delta") ->
          SseFragment(json.optString("delta", ""), isFinalReplacement = false)
        json.has("delta") ->
          SseFragment(json.optString("delta", ""), isFinalReplacement = false)
        json.has("text") ->
          SseFragment(json.optString("text", ""), isFinalReplacement = true)
        json.has("transcript") ->
          SseFragment(json.optString("transcript", ""), isFinalReplacement = true)
        else -> {
          val choices = json.optJSONArray("choices")
          val deltaObj = choices?.optJSONObject(0)?.optJSONObject("delta")
          val content = deltaObj?.optString("content", "").orEmpty()
          if (content.isNotEmpty()) SseFragment(content, isFinalReplacement = false) else null
        }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun looksLikeSseTranscript(body: String): Boolean {
    return body.lineSequence().any { it.trimStart().startsWith("data:") }
  }

  internal fun isJsonWithTextField(body: String): Boolean {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{")) return false
    return try {
      val json = JSONObject(trimmed)
      json.has("text") || json.has("transcript")
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
      lower.contains("too short") ||
      lower.contains("speech")
  }

  internal fun parseTranscriptionText(body: String): String {
    val trimmed = body.trim()
    if (looksLikeSseTranscript(trimmed)) {
      val builder = StringBuilder()
      var finalOverride: String? = null
      for (line in trimmed.lineSequence()) {
        val frag = extractSseTextFragment(line) ?: continue
        if (frag.isFinalReplacement) {
          finalOverride = frag.text
        } else {
          builder.append(frag.text)
        }
      }
      val assembled = (finalOverride ?: builder.toString()).trim()
      if (assembled.isNotEmpty()) return assembled
    }
    if (trimmed.startsWith("{")) {
      try {
        val json = JSONObject(trimmed)
        if (json.has("text")) {
          return json.optString("text", "").trim()
        }
        if (json.has("transcript")) {
          return json.optString("transcript", "").trim()
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
