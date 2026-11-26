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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndpointClientTest {

  private class FakeTransport(
    private val handler: (url: String, fileField: String, fields: Map<String, String>) -> HttpResponseData
  ) : HttpTransport {
    val calledUrls = mutableListOf<String>()

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
      calledUrls.add(urlString)
      return handler(urlString, fileFieldName, fields)
    }
  }

  @Test
  fun `all endpoint input forms resolve to exact same endpoint without doubling v1`() = runTest {
    val transport = FakeTransport { url, _, _ ->
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
    val transport = FakeTransport { url, _, _ ->
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
  fun `detects 401 as NeedsApiKey and 400 audio error as Success`() = runTest {
    val authClient =
      EndpointClient(FakeTransport { _, _, _ -> HttpResponseData(401, """{"error":"unauthorized"}""") })
    val authRes = authClient.detectEndpoint("10.3.3.20:8000", "", "whisper-1", LanguageOption.AUTO)
    assertTrue(authRes is ProbeResult.NeedsApiKey)

    val validationClient =
      EndpointClient(
        FakeTransport { url, _, _ ->
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
    val transport = FakeTransport { url, _, fields ->
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
        detectedForInput = "10.3.3.20:8000",
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
}
