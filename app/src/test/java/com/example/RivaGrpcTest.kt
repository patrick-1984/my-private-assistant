package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.WavEncoder
import com.example.data.AppSettings
import com.example.data.EndpointPreset
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import com.example.data.SettingsRepository
import com.example.network.EndpointClient
import com.example.network.GrpcTarget
import com.example.network.HealthCheckResult
import com.example.network.HttpResponseData
import com.example.network.HttpTransport
import com.example.network.ProbeResult
import com.example.network.RivaGrpcClient
import com.example.network.RivaProtoCodec
import com.example.network.RivaRpcExecutor
import com.example.network.TranscribeResult
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RivaGrpcTest {

  private class FakeRivaExecutor(
    var getModelsHandler: (GrpcTarget, String, String) -> List<String> = { _, _, _ -> emptyList() },
    var recognizeHandler: (GrpcTarget, RivaProtoCodec.RecognizeRequestMsg, String, String) -> RivaProtoCodec.RecognizeResponseMsg =
      { _, _, _, _ -> RivaProtoCodec.RecognizeResponseMsg(listOf("ok")) },
    var streamingHandler:
      (
        GrpcTarget,
        RivaProtoCodec.StreamingRecognitionConfigMsg,
        ByteArray,
        String,
        String,
        ((String) -> Unit)?,
      ) -> String = { _, _, _, _, _, _ -> "" },
  ) : RivaRpcExecutor {
    val getModelCalls = mutableListOf<Pair<GrpcTarget, String>>()
    val recognizeRequests = mutableListOf<RivaProtoCodec.RecognizeRequestMsg>()
    val streamingConfigs = mutableListOf<RivaProtoCodec.StreamingRecognitionConfigMsg>()
    var lastValidatedOverrideIp: String? = null
    var lastIgnoreSsl: Boolean = false

    override fun getModels(
      target: GrpcTarget,
      apiKey: String,
      functionId: String,
      validatedOverrideIp: String?,
      ignoreSsl: Boolean,
      timeoutMs: Int,
    ): List<String> {
      lastValidatedOverrideIp = validatedOverrideIp
      lastIgnoreSsl = ignoreSsl
      getModelCalls.add(target to functionId)
      return getModelsHandler(target, apiKey, functionId)
    }

    override fun recognize(
      target: GrpcTarget,
      request: RivaProtoCodec.RecognizeRequestMsg,
      apiKey: String,
      functionId: String,
      validatedOverrideIp: String?,
      ignoreSsl: Boolean,
      timeoutMs: Int,
    ): RivaProtoCodec.RecognizeResponseMsg {
      lastValidatedOverrideIp = validatedOverrideIp
      lastIgnoreSsl = ignoreSsl
      recognizeRequests.add(request)
      return recognizeHandler(target, request, apiKey, functionId)
    }

    override fun streamingRecognize(
      target: GrpcTarget,
      config: RivaProtoCodec.StreamingRecognitionConfigMsg,
      pcmBytes: ByteArray,
      apiKey: String,
      functionId: String,
      validatedOverrideIp: String?,
      ignoreSsl: Boolean,
      timeoutMs: Int,
      onPartialText: ((String) -> Unit)?,
    ): String {
      lastValidatedOverrideIp = validatedOverrideIp
      lastIgnoreSsl = ignoreSsl
      streamingConfigs.add(config)
      return streamingHandler(target, config, pcmBytes, apiKey, functionId, onPartialText)
    }
  }

  private val forbidHttpTransport =
    object : HttpTransport {
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
        throw AssertionError("Unexpected HTTP POST for gRPC endpoint: $urlString")
      }

      override fun getJson(
        urlString: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tlsServerName: String?,
        ignoreSsl: Boolean,
      ): HttpResponseData {
        throw AssertionError("Unexpected HTTP GET for gRPC endpoint: $urlString")
      }
    }

  @Test
  fun `RivaProtoCodec round-trips RecognizeRequest, RecognizeResponse, StreamingRecognizeResponse, and ConfigResponse`() {
    val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
    val req =
      RivaProtoCodec.RecognizeRequestMsg(
        config =
          RivaProtoCodec.RecognitionConfigMsg(
            sampleRateHertz = 16_000,
            languageCode = "pl-PL",
            model = "parakeet-1.1b",
          ),
        audio = pcm,
      )
    val encodedReq = RivaProtoCodec.encodeRecognizeRequest(req)
    val decodedReq = RivaProtoCodec.decodeRecognizeRequest(encodedReq)
    assertEquals(16_000, decodedReq.config.sampleRateHertz)
    assertEquals("pl-PL", decodedReq.config.languageCode)
    assertEquals("parakeet-1.1b", decodedReq.config.model)
    assertArrayEquals(pcm, decodedReq.audio)

    val resp = RivaProtoCodec.RecognizeResponseMsg(listOf("polskie piosenki", "zażółć gęślą jaźń"))
    val decodedResp = RivaProtoCodec.decodeRecognizeResponse(RivaProtoCodec.encodeRecognizeResponse(resp))
    assertEquals(resp.transcripts, decodedResp.transcripts)
    assertEquals("polskie piosenki zażółć gęślą jaźń", decodedResp.combinedText)

    val streamResp =
      RivaProtoCodec.StreamingRecognizeResponseMsg(
        listOf(
          RivaProtoCodec.StreamingRecognitionResultMsg("polskie ", isFinal = false),
          RivaProtoCodec.StreamingRecognitionResultMsg("zażółć gęślą jaźń", isFinal = true),
        )
      )
    val decodedStream =
      RivaProtoCodec.decodeStreamingRecognizeResponse(
        RivaProtoCodec.encodeStreamingRecognizeResponse(streamResp)
      )
    assertEquals(streamResp.results, decodedStream.results)

    val cfgResp =
      RivaProtoCodec.ConfigResponseMsg(listOf("parakeet-1.1b-rnnt-multilingual", "conformer-en-US"))
    val decodedCfg = RivaProtoCodec.decodeConfigResponse(RivaProtoCodec.encodeConfigResponse(cfgResp))
    assertEquals(cfgResp.modelNames, decodedCfg.modelNames)
  }

  @Test
  fun `extractLinearPcm16 strips RIFF WAV header while preserving sample rate and channels`() {
    val wav = WavEncoder.silentWav(250) // 16kHz mono 16-bit -> 44 header + 8000 PCM
    val extracted = RivaProtoCodec.extractLinearPcm16(wav)
    assertEquals(16_000, extracted.sampleRateHz)
    assertEquals(1, extracted.channelCount)
    assertEquals(8_000, extracted.pcmBytes.size)
  }

  @Test
  fun `RivaGrpcClient parses NVCF, grpcs, and local insecure targets accurately`() {
    val client = RivaGrpcClient(FakeRivaExecutor())

    assertTrue(client.isGrpcEndpoint("grpc.nvcf.nvidia.com:443"))
    assertTrue(client.isGrpcEndpoint("grpcs://riva.internal.lan"))
    assertTrue(client.isGrpcEndpoint("10.3.3.20:50051"))
    assertFalse(client.isGrpcEndpoint("https://integrate.api.nvidia.com/v1"))
    assertFalse(client.isGrpcEndpoint("http://10.3.3.20:8000"))

    val nvcf = client.parseTarget("grpc.nvcf.nvidia.com:443")
    assertNotNull(nvcf)
    assertEquals("grpc.nvcf.nvidia.com", nvcf!!.host)
    assertEquals(443, nvcf.port)
    assertTrue(nvcf.useTls)
    assertEquals("grpc.nvcf.nvidia.com:443", nvcf.canonicalUrl)

    val local = client.parseTarget("grpc://10.3.3.20:50051")
    assertNotNull(local)
    assertEquals("10.3.3.20", local!!.host)
    assertEquals(50051, local.port)
    assertFalse(local.useTls)

    val grpcs = client.parseTarget("grpcs://stt.lan")
    assertNotNull(grpcs)
    assertEquals(443, grpcs!!.port)
    assertTrue(grpcs.useTls)
  }

  @Test
  fun `EndpointClient routes NVIDIA gRPC preset through RivaGrpcClient without HTTP calls`() = runTest {
    val executor =
      FakeRivaExecutor(
        getModelsHandler = { _, _, _ ->
          throw StatusRuntimeException(Status.UNIMPLEMENTED.withDescription("config disabled"))
        },
        recognizeHandler = { _, req, apiKey, fnId ->
          assertEquals("nvapi-dummy-test", apiKey)
          assertEquals(EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID, fnId)
          if (req.config.languageCode == "multi") {
            throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("en-US only"))
          }
          assertEquals("en-US", req.config.languageCode)
          RivaProtoCodec.RecognizeResponseMsg(emptyList())
        },
      )
    val grpcClient = RivaGrpcClient(executor)
    val client = EndpointClient(forbidHttpTransport, grpcClient)

    val nvidia = EndpointPreset.defaultNvidiaPreset().copy(apiKey = "nvapi-dummy-test")
    val probe =
      client.detectEndpoint(
        rawInput = nvidia.endpointInput,
        apiKey = nvidia.effectiveApiKey,
        modelName = nvidia.effectiveModelName,
        language = LanguageOption.AUTO,
        functionId = nvidia.functionId,
      )
    assertTrue("Expected Success but got $probe", probe is ProbeResult.Success)
    assertEquals(ServerFamily.NVIDIA_RIVA_GRPC, (probe as ProbeResult.Success).family)
    assertEquals(EndpointPreset.NVIDIA_DETECTED_URL, probe.url)
    assertEquals(2, executor.recognizeRequests.size) // "multi" -> "en-US" fallback
  }

  @Test
  fun `RivaGrpcClient maps UNAUTHENTICATED to NeedsApiKey and requires functionId on nvcf`() {
    val executor =
      FakeRivaExecutor(
        getModelsHandler = { _, _, _ ->
          throw StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid nvapi key"))
        }
      )
    val grpcClient = RivaGrpcClient(executor)

    val missingFn =
      grpcClient.probe(
        rawInput = "grpc.nvcf.nvidia.com:443",
        apiKey = "nvapi-dummy-test",
        functionId = "",
        modelName = "parakeet",
        language = LanguageOption.AUTO,
        validatedOverrideIp = null,
        ignoreSsl = false,
        silentWavBytes = WavEncoder.silentWav(200),
        probeTimeoutMs = 2000,
      )
    assertTrue(missingFn is ProbeResult.Failure)

    val badKey =
      grpcClient.probe(
        rawInput = "grpc.nvcf.nvidia.com:443",
        apiKey = "nvapi-bad",
        functionId = EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID,
        modelName = "parakeet",
        language = LanguageOption.AUTO,
        validatedOverrideIp = null,
        ignoreSsl = false,
        silentWavBytes = WavEncoder.silentWav(200),
        probeTimeoutMs = 2000,
      )
    assertTrue(badKey is ProbeResult.NeedsApiKey)
  }

  @Test
  fun `RivaGrpcClient transcribe streams partial results and falls back to unary Recognize on UNIMPLEMENTED`() =
    runTest {
      val executor =
        FakeRivaExecutor(
          streamingHandler = { _, cfg, _, apiKey, fnId, onPartial ->
            assertEquals("nvapi-dummy-test", apiKey)
            assertEquals(EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID, fnId)
            assertEquals("pl-PL", cfg.config.languageCode)
            onPartial?.invoke("polskie")
            onPartial?.invoke("polskie piosenki")
            "polskie piosenki"
          }
        )
      val client = EndpointClient(forbidHttpTransport, RivaGrpcClient(executor))
      val settings =
        EndpointPreset.defaultNvidiaPreset()
          .copy(apiKey = "nvapi-dummy-test", liveTranscription = true)
          .toProbeSettings(
            com.example.data.AppSettingsPreset.defaultPreset().copy(language = LanguageOption.POLISH)
          )

      val partials = mutableListOf<String>()
      val streamRes =
        client.transcribe(
          wavBytes = WavEncoder.silentWav(200),
          settings = settings,
          onEndpointDetected = { _, _ -> },
          onEndpointInvalidated = {},
          onPartialText = { partials.add(it) },
        )
      assertTrue(streamRes is TranscribeResult.Success)
      assertEquals("polskie piosenki", (streamRes as TranscribeResult.Success).text)
      assertEquals(listOf("polskie", "polskie piosenki"), partials)

      // Now simulate StreamingRecognize UNIMPLEMENTED -> unary Recognize fallback
      executor.streamingHandler = { _, _, _, _, _, _ ->
        throw StatusRuntimeException(Status.UNIMPLEMENTED.withDescription("streaming off"))
      }
      executor.recognizeHandler = { _, req, _, _ ->
        assertEquals("pl-PL", req.config.languageCode)
        RivaProtoCodec.RecognizeResponseMsg(listOf("zażółć gęślą jaźń"))
      }
      val unaryRes =
        client.transcribe(
          wavBytes = WavEncoder.silentWav(200),
          settings = settings,
          onEndpointDetected = { _, _ -> },
          onEndpointInvalidated = {},
          onPartialText = {},
        )
      assertTrue(unaryRes is TranscribeResult.Success)
      assertEquals("zażółć gęślą jaźń", (unaryRes as TranscribeResult.Success).text)
    }

  @Test
  fun `RivaGrpcClient quickHealthCheck handles healthy config, UNIMPLEMENTED fallback, and auth error`() =
    runTest {
      val executor =
        FakeRivaExecutor(
          getModelsHandler = { _, _, _ -> listOf("parakeet-1.1b") }
        )
      val client = EndpointClient(forbidHttpTransport, RivaGrpcClient(executor))
      val settings =
        EndpointPreset.defaultNvidiaPreset().copy(apiKey = "nvapi-dummy-test").toProbeSettings()

      assertTrue(client.quickHealthCheck(settings) is HealthCheckResult.Healthy)

      executor.getModelsHandler = { _, _, _ ->
        throw StatusRuntimeException(Status.UNIMPLEMENTED)
      }
      executor.recognizeHandler = { _, _, _, _ -> RivaProtoCodec.RecognizeResponseMsg(emptyList()) }
      assertTrue(client.quickHealthCheck(settings) is HealthCheckResult.Healthy)

      executor.getModelsHandler = { _, _, _ ->
        throw StatusRuntimeException(Status.PERMISSION_DENIED)
      }
      val authErr = client.quickHealthCheck(settings)
      assertTrue(authErr is HealthCheckResult.Unhealthy && authErr.isAuthError)
    }

  @Test
  fun `SettingsRepository migrates legacy integrate nvidia v1 preset to Riva gRPC while preserving user apiKey`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = SettingsRepository.get(context)
    val prefs = context.getSharedPreferences("tv_voice_search_prefs", Context.MODE_PRIVATE)
    val prevJson = prefs.getString("endpoint_presets_v2_json", null)

    try {
      val legacyNvidia =
        EndpointPreset(
          id = EndpointPreset.NVIDIA_PRESET_ID,
          customName = "NVIDIA",
          endpointInput = EndpointPreset.NVIDIA_OLD_INTEGRATE_URL,
          apiKey = "nvapi-user-preserved-key",
          apiKeyNotNeeded = false,
          useCustomModel = true,
          modelName = "parakeet",
          detectedEndpointUrl = EndpointPreset.NVIDIA_OLD_INTEGRATE_DETECTED_URL,
          detectedFamily = ServerFamily.OPENAI_V1,
          detectedForInput = "${EndpointPreset.NVIDIA_OLD_INTEGRATE_URL}|false||false",
        )
      val arr = JSONArray().apply { put(legacyNvidia.toJson()) }
      prefs.edit().putString("endpoint_presets_v2_json", arr.toString()).commit()

      val migrated =
        repo.settings.value.endpointPresets.firstOrNull { it.id == EndpointPreset.NVIDIA_PRESET_ID }
      assertNotNull(migrated)
      assertEquals(EndpointPreset.NVIDIA_ENDPOINT_URL, migrated!!.endpointInput)
      assertEquals(EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID, migrated.functionId)
      assertEquals(ServerFamily.NVIDIA_RIVA_GRPC, migrated.detectedFamily)
      assertEquals("nvapi-user-preserved-key", migrated.apiKey)
      assertTrue(migrated.isGrpcEndpoint)
      assertTrue(migrated.hasValidCachedEndpoint)
    } finally {
      if (prevJson != null) {
        prefs.edit().putString("endpoint_presets_v2_json", prevJson).commit()
      }
    }
  }
}
