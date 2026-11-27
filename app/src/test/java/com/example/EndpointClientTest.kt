package com.example

import com.example.audio.WavEncoder
import com.example.data.AppSettings
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import com.example.network.EndpointClient
import com.example.network.HttpResponseData
import com.example.network.HttpTransport
import com.example.network.ProbeResult
import com.example.network.TranscribeResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndpointClientTest {

  private class FakeTransport(
    private val getHandler:
      (
        url: String,
        headers: Map<String, String>,
        tlsServerName: String?,
        ignoreSsl: Boolean,
      ) -> HttpResponseData =
      { _, _, _, _ -> HttpResponseData(404, "") },
    private val handler:
      (
        url: String,
        headers: Map<String, String>,
        fileField: String,
        fields: Map<String, String>,
        tlsServerName: String?,
      ) -> HttpResponseData,
  ) : HttpTransport {
    val calledUrls = mutableListOf<String>()
    var lastHeaders: Map<String, String> = emptyMap()
    var lastTlsServerName: String? = null
    var lastIgnoreSsl: Boolean = false
    var lastFields: Map<String, String> = emptyMap()

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
      calledUrls.add(urlString)
      lastHeaders = headers
      lastTlsServerName = tlsServerName
      lastIgnoreSsl = ignoreSsl
      lastFields = fields
      val resp = handler(urlString, headers, fileFieldName, fields, tlsServerName)
      if (resp.statusCode in 200..299 && onSseLine != null && resp.body.contains("data:")) {
        resp.body.lineSequence().forEach { onSseLine(it) }
      }
      return resp
    }

    override fun getJson(
      urlString: String,
      headers: Map<String, String>,
      connectTimeoutMs: Int,
      readTimeoutMs: Int,
      tlsServerName: String?,
      ignoreSsl: Boolean,
    ): HttpResponseData {
      calledUrls.add("GET:$urlString")
      lastHeaders = headers
      lastTlsServerName = tlsServerName
      lastIgnoreSsl = ignoreSsl
      return getHandler(urlString, headers, tlsServerName, ignoreSsl)
    }
  }

  @Test
  fun `all endpoint input forms resolve to exact same endpoint without doubling v1`() = runTest {
    val transport = FakeTransport { url, _, _, _, _ ->
      if (url == "http://10.3.3.20:8000/v1/audio/transcriptions") {
        HttpResponseData(200, """{"text":""}""")
      } else {
        HttpResponseData(404, "Not found")
      }
    }
    val client = EndpointClient(transport)

    val inputs =
      listOf(
        "10.3.3.20:8000",
        "http://10.3.3.20:8000",
        "http://10.3.3.20:8000/",
        "http://10.3.3.20:8000/v1",
        "http://10.3.3.20:8000/v1/",
        "http://10.3.3.20:8000/v1/audio/transcriptions",
      )

    for (input in inputs) {
      val result = client.detectEndpoint(input, "", "whisper-1", LanguageOption.AUTO)
      assertTrue("Expected success for $input but got $result", result is ProbeResult.Success)
      assertEquals(
        "http://10.3.3.20:8000/v1/audio/transcriptions",
        (result as ProbeResult.Success).url,
      )
    }
    assertTrue(transport.calledUrls.none { it.contains("/v1/v1") })
  }

  @Test
  fun `no-scheme host falls back to https on http connection error`() = runTest {
    val transport = FakeTransport { url, _, _, _, _ ->
      if (url.startsWith("http://")) {
        throw IOException("Connection refused")
      }
      if (url == "https://stt.example.com:8443/v1/audio/transcriptions") {
        HttpResponseData(200, """{"text":""}""")
      } else {
        HttpResponseData(404, "Not found")
      }
    }
    val client = EndpointClient(transport)

    val result =
      client.detectEndpoint("stt.example.com:8443", "", "whisper-1", LanguageOption.AUTO)
    assertTrue(result is ProbeResult.Success)
    assertEquals(
      "https://stt.example.com:8443/v1/audio/transcriptions",
      (result as ProbeResult.Success).url,
    )
  }

  @Test
  fun `ip override connects to override IP while sending FQDN Host header and TLS SNI`() =
    runTest {
      val transport = FakeTransport { url, headers, _, _, sni ->
        if (
          url == "https://10.3.3.20:8443/v1/audio/transcriptions" &&
            headers["Host"] == "stt.example.com:8443" &&
            sni == "stt.example.com" &&
            headers["Authorization"] == "Bearer sk-test-123"
        ) {
          HttpResponseData(200, """{"text":"działa świetnie"}""")
        } else {
          HttpResponseData(404, "Not found")
        }
      }
      val client = EndpointClient(transport)

      val probe =
        client.detectEndpoint(
          rawInput = "https://stt.example.com:8443",
          apiKey = "sk-test-123",
          modelName = "whisper-1",
          language = LanguageOption.AUTO,
          useIpOverride = true,
          overrideIp = "10.3.3.20",
        )
      assertTrue(probe is ProbeResult.Success)
      assertEquals(
        "https://stt.example.com:8443/v1/audio/transcriptions",
        (probe as ProbeResult.Success).url,
      )
      assertEquals("stt.example.com:8443", transport.lastHeaders["Host"])
      assertEquals("stt.example.com", transport.lastTlsServerName)
      assertEquals("Bearer sk-test-123", transport.lastHeaders["Authorization"])
    }

  @Test
  fun `api key is only included in Authorization header when useApiKey is enabled`() = runTest {
    val transport = FakeTransport { _, _, _, _, _ -> HttpResponseData(200, """{"text":"ok"}""") }
    val client = EndpointClient(transport)

    val disabledSettings =
      AppSettings(
        endpointInput = "http://10.3.3.20:8000",
        useApiKey = false,
        apiKey = "sk-secret",
        detectedEndpointUrl = "http://10.3.3.20:8000/v1/audio/transcriptions",
        detectedFamily = ServerFamily.OPENAI_V1,
        detectedForInput = "http://10.3.3.20:8000|false||false",
      )

    client.transcribe(WavEncoder.silentWav(100), disabledSettings, { _, _ -> }, {})
    assertFalse(transport.lastHeaders.containsKey("Authorization"))

    val enabledSettings = disabledSettings.copy(useApiKey = true)
    client.transcribe(WavEncoder.silentWav(100), enabledSettings, { _, _ -> }, {})
    assertEquals("Bearer sk-secret", transport.lastHeaders["Authorization"])
  }

  @Test
  fun `rejects invalid override IP address`() = runTest {
    val client =
      EndpointClient(FakeTransport { _, _, _, _, _ -> HttpResponseData(200, """{"text":""}""") })
    assertNull(client.validateNumericIp("10.3.3.20:8000"))
    assertNull(client.validateNumericIp("http://10.3.3.20"))
    assertEquals("100.64.1.5", client.validateNumericIp("100.64.1.5"))

    val probe =
      client.detectEndpoint(
        rawInput = "https://stt.example.com",
        apiKey = "",
        modelName = "whisper-1",
        language = LanguageOption.AUTO,
        useIpOverride = true,
        overrideIp = "10.3.3.20:8000",
      )
    assertTrue(probe is ProbeResult.Failure)
  }

  @Test
  fun `detects 401 as NeedsApiKey and 400 audio error as Success`() = runTest {
    val authClient =
      EndpointClient(
        FakeTransport { _, _, _, _, _ -> HttpResponseData(401, """{"error":"unauthorized"}""") }
      )
    val authRes = authClient.detectEndpoint("10.3.3.20:8000", "", "whisper-1", LanguageOption.AUTO)
    assertTrue(authRes is ProbeResult.NeedsApiKey)

    val validationClient =
      EndpointClient(
        FakeTransport { url, _, _, _, _ ->
          if (url.endsWith("/inference")) HttpResponseData(400, "invalid audio file duration")
          else HttpResponseData(404, "not found")
        }
      )
    val valRes =
      validationClient.detectEndpoint("10.3.3.20:8080", "", "whisper-1", LanguageOption.AUTO)
    assertTrue(valRes is ProbeResult.Success)
    assertEquals(ServerFamily.WHISPER_CPP, (valRes as ProbeResult.Success).family)
  }

  @Test
  fun `transcribe preserves Polish UTF-8 characters and re-probes once on 404`() = runTest {
    var failOldOnce = true
    val transport = FakeTransport { url, _, _, fields, _ ->
      if (url.endsWith("/audio/transcriptions") && !url.contains("/v1/") && failOldOnce) {
        failOldOnce = false
        HttpResponseData(404, "Moved")
      } else if (url.endsWith("/v1/audio/transcriptions")) {
        assertEquals("pl", fields["language"])
        HttpResponseData(200, """{"text":"polskie piosenki zażółć gęślą jaźń"}""")
      } else {
        HttpResponseData(404, "Not found")
      }
    }
    val client = EndpointClient(transport)
    val settings =
      AppSettings(
        endpointInput = "10.3.3.20:8000",
        detectedEndpointUrl = "http://10.3.3.20:8000/audio/transcriptions",
        detectedFamily = ServerFamily.OPENAI_NO_V1,
        detectedForInput = "10.3.3.20:8000|false||false",
        language = LanguageOption.POLISH,
      )

    var invalidated = false
    var updatedUrl = ""
    val res =
      client.transcribe(
        wavBytes = WavEncoder.silentWav(200),
        settings = settings,
        onEndpointDetected = { url, _ -> updatedUrl = url },
        onEndpointInvalidated = { invalidated = true },
      )

    assertTrue(res is TranscribeResult.Success)
    assertEquals("polskie piosenki zażółć gęślą jaźń", (res as TranscribeResult.Success).text)
    assertTrue(invalidated)
    assertEquals("http://10.3.3.20:8000/v1/audio/transcriptions", updatedUrl)
  }

  @Test
  fun `wav encoder writes valid RIFF WAVE header`() {
    val wav = WavEncoder.silentWav(500)
    assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
    assertEquals(44 + 16000, wav.size)
  }

  @Test
  fun `ignoreSsl setting is propagated to transport during detectEndpoint and transcribe`() =
    runTest {
      val transport = FakeTransport { _, _, _, _, _ -> HttpResponseData(200, """{"text":"ok"}""") }
      val client = EndpointClient(transport)

      client.detectEndpoint(
        rawInput = "https://stt.internal.lan",
        apiKey = "",
        modelName = "whisper-1",
        language = LanguageOption.AUTO,
        useIpOverride = true,
        overrideIp = "10.3.3.20",
        ignoreSsl = true,
      )
      assertTrue(transport.lastIgnoreSsl)

      val settings =
        AppSettings(
          endpointInput = "https://stt.internal.lan",
          useIpOverride = true,
          overrideIp = "10.3.3.20",
          ignoreSsl = true,
          detectedEndpointUrl = "https://stt.internal.lan/v1/audio/transcriptions",
          detectedFamily = ServerFamily.OPENAI_V1,
          detectedForInput = "https://stt.internal.lan|true|10.3.3.20|true",
        )
      transport.lastIgnoreSsl = false
      client.transcribe(WavEncoder.silentWav(100), settings, { _, _ -> }, {})
      assertTrue(transport.lastIgnoreSsl)
    }

  @Test
  fun `readHttp11Response parses Content-Length, 100 Continue, chunked, and Polish UTF-8`() {
    val json = """{"text":"zażółć gęślą jaźń"}"""
    val jsonBytes = json.toByteArray(Charsets.UTF_8)

    val fixedRaw =
      ("HTTP/1.1 100 Continue\r\n\r\n" +
          "HTTP/1.1 200 OK\r\n" +
          "Content-Type: application/json; charset=utf-8\r\n" +
          "Content-Length: ${jsonBytes.size}\r\n" +
          "\r\n")
        .toByteArray(Charsets.US_ASCII) + jsonBytes

    val fixedResp =
      com.example.network.UrlConnectionTransport.readHttp11Response(fixedRaw.inputStream())
    assertEquals(200, fixedResp.statusCode)
    assertEquals(json, fixedResp.body)

    val part1 = """{"text":"zażółć """.toByteArray(Charsets.UTF_8)
    val part2 = """gęślą jaźń"}""".toByteArray(Charsets.UTF_8)
    val chunkedRaw =
      ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
          "${part1.size.toString(16)}\r\n")
        .toByteArray(Charsets.US_ASCII) +
        part1 +
        "\r\n${part2.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII) +
        part2 +
        "\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    val chunkedResp =
      com.example.network.UrlConnectionTransport.readHttp11Response(chunkedRaw.inputStream())
    assertEquals(200, chunkedResp.statusCode)
    assertEquals(json, chunkedResp.body)
  }

  @Test
  fun `detectEndpoint queries OpenAI v1 models with override headers and populates availableModels`() =
    runTest {
      val transport =
        FakeTransport(
          getHandler = { url, headers, sni, ignoreSsl ->
            if (
              url == "https://10.3.3.20:8443/v1/models" &&
                headers["Host"] == "stt.internal.lan:8443" &&
                sni == "stt.internal.lan" &&
                ignoreSsl
            ) {
              HttpResponseData(
                200,
                """{"data":[{"id":"whisper-1"},{"id":"Systran/faster-whisper-large-v3"}]}""",
              )
            } else {
              HttpResponseData(404, "")
            }
          },
          handler = { url, _, _, _, _ ->
            if (url == "https://10.3.3.20:8443/v1/audio/transcriptions") {
              HttpResponseData(200, """{"text":""}""")
            } else {
              HttpResponseData(404, "")
            }
          },
        )
      val client = EndpointClient(transport)

      val probe =
        client.detectEndpoint(
          rawInput = "https://stt.internal.lan:8443",
          apiKey = "sk-key",
          modelName = "whisper-1",
          language = LanguageOption.AUTO,
          useIpOverride = true,
          overrideIp = "10.3.3.20",
          ignoreSsl = true,
        )

      assertTrue(probe is ProbeResult.Success)
      assertEquals(
        listOf("whisper-1", "Systran/faster-whisper-large-v3"),
        (probe as ProbeResult.Success).availableModels,
      )
    }

  @Test
  fun `transcribe uses custom model only when useCustomModel is enabled`() = runTest {
    val transport = FakeTransport { _, _, _, _, _ -> HttpResponseData(200, """{"text":"ok"}""") }
    val client = EndpointClient(transport)

    val base =
      AppSettings(
        endpointInput = "http://10.3.3.20:8000",
        modelName = "Systran/faster-whisper-large-v3",
        useCustomModel = false,
        detectedEndpointUrl = "http://10.3.3.20:8000/v1/audio/transcriptions",
        detectedFamily = ServerFamily.OPENAI_V1,
        detectedForInput = "http://10.3.3.20:8000|false||false",
      )

    client.transcribe(WavEncoder.silentWav(100), base, { _, _ -> }, {})
    assertEquals("whisper-1", transport.lastFields["model"])

    client.transcribe(WavEncoder.silentWav(100), base.copy(useCustomModel = true), { _, _ -> }, {})
    assertEquals("Systran/faster-whisper-large-v3", transport.lastFields["model"])
  }

  @Test
  fun `nvidia catalog filters to speech models and works with default integrate api nvidia v1 and SSE streaming`() =
    runTest {
      val nvidiaBase = "https://integrate.api.nvidia.com/v1"
      val nvidiaBody =
        """{"data":[{"id":"meta/llama-3.1-70b-instruct"},{"id":"mistralai/mixtral-8x7b"},{"id":"nvidia/parakeet-tdt-0.6b"},{"id":"openai/whisper-large-v3"},{"id":"nvidia/canary-1b"}]}"""
      val transport =
        FakeTransport(
          getHandler = { url, headers, _, _ ->
            if (
              url == "$nvidiaBase/models" &&
                headers["Authorization"] == "Bearer nvapi-secret-key"
            ) {
              HttpResponseData(200, nvidiaBody)
            } else {
              HttpResponseData(404, "")
            }
          },
          handler = { url, headers, _, fields, _ ->
            assertEquals("$nvidiaBase/audio/transcriptions", url)
            assertEquals("Bearer nvapi-secret-key", headers["Authorization"])
            assertEquals("nvidia/parakeet-tdt-0.6b", fields["model"])
            assertEquals("pl-PL", fields["language"])
            if (fields["stream"] == "true") {
              HttpResponseData(
                200,
                """
                data: {"type":"transcript.text.delta","delta":"polskie "}
                data: {"type":"transcript.text.delta","delta":"piosenki"}
                data: {"type":"transcript.text.done","text":"polskie piosenki"}
                data: [DONE]
                """.trimIndent(),
              )
            } else {
              HttpResponseData(200, """{"text":"polskie piosenki"}""")
            }
          },
        )
      val client = EndpointClient(transport)
      val nvidiaSettings =
        com.example.data.EndpointProfile.defaultNvidiaProfile()
          .copy(apiKey = "nvapi-secret-key", language = LanguageOption.POLISH)
          .toProbeSettings()

      assertEquals("https://integrate.api.nvidia.com/v1", nvidiaSettings.endpointInput)

      val probe =
        client.detectEndpoint(
          rawInput = nvidiaSettings.endpointInput,
          apiKey = nvidiaSettings.effectiveApiKey,
          modelName = nvidiaSettings.effectiveModelName,
          language = nvidiaSettings.language,
        )
      assertTrue(probe is ProbeResult.Success)
      assertEquals(
        listOf("nvidia/parakeet-tdt-0.6b", "openai/whisper-large-v3", "nvidia/canary-1b"),
        (probe as ProbeResult.Success).availableModels,
      )

      val partials = mutableListOf<String>()
      val result =
        client.transcribe(
          wavBytes = WavEncoder.silentWav(200),
          settings = nvidiaSettings,
          onEndpointDetected = { _, _ -> },
          onEndpointInvalidated = {},
          onPartialText = { partials.add(it) },
        )
      assertTrue(result is TranscribeResult.Success)
      assertEquals("polskie piosenki", (result as TranscribeResult.Success).text)
      assertTrue(partials.isNotEmpty())
      assertEquals("polskie piosenki", partials.last())
    }

  @Test
  fun `nvidia NIM retries without model when custom model or stream is rejected with 400 or 422`() =
    runTest {
      val seenFields = mutableListOf<Map<String, String>>()
      val transport = FakeTransport { _, _, _, fields, _ ->
        seenFields.add(fields.toMap())
        when {
          fields["stream"] == "true" ->
            HttpResponseData(422, """{"detail":"Extra inputs are not permitted: stream"}""")
          fields.containsKey("model") ->
            HttpResponseData(400, """{"detail":"Unknown model identifier"}""")
          else -> {
            assertEquals("multi", fields["language"])
            HttpResponseData(200, """{"text":"fallback worked"}""")
          }
        }
      }
      val client = EndpointClient(transport)
      val settings =
        com.example.data.EndpointProfile.defaultNvidiaProfile()
          .copy(apiKey = "nvapi-test", useCustomModel = true, liveTranscription = true)
          .toProbeSettings()

      val res =
        client.transcribe(
          wavBytes = WavEncoder.silentWav(200),
          settings = settings,
          onEndpointDetected = { _, _ -> },
          onEndpointInvalidated = {},
          onPartialText = {},
        )
      assertTrue(res is TranscribeResult.Success)
      assertEquals("fallback worked", (res as TranscribeResult.Success).text)
      assertEquals(3, seenFields.size)
    }
}
