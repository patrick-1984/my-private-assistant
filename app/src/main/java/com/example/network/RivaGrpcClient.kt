package com.example.network

import android.annotation.SuppressLint
import com.example.data.AppSettings
import com.example.data.EndpointPreset
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import io.grpc.CallOptions
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import java.net.IDN
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class GrpcTarget(
  val host: String,
  val port: Int,
  val useTls: Boolean,
  val canonicalUrl: String,
)

interface RivaRpcExecutor {
  @Throws(StatusRuntimeException::class)
  fun getModels(
    target: GrpcTarget,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
  ): List<String>

  @Throws(StatusRuntimeException::class)
  fun recognize(
    target: GrpcTarget,
    request: RivaProtoCodec.RecognizeRequestMsg,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
  ): RivaProtoCodec.RecognizeResponseMsg

  @Throws(StatusRuntimeException::class)
  fun streamingRecognize(
    target: GrpcTarget,
    config: RivaProtoCodec.StreamingRecognitionConfigMsg,
    pcmBytes: ByteArray,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
    onPartialText: ((String) -> Unit)?,
  ): String
}

object OkHttpRivaRpcExecutor : RivaRpcExecutor {

  private val AUTHORIZATION_KEY: Metadata.Key<String> =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
  private val FUNCTION_ID_KEY: Metadata.Key<String> =
    Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER)

  override fun getModels(
    target: GrpcTarget,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
  ): List<String> {
    return withChannel(target, apiKey, functionId, validatedOverrideIp, ignoreSsl) { channel, metadata ->
      val callOpts = CallOptions.DEFAULT.withDeadlineAfter(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
      val stubChannel =
        ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata))
      val resp =
        ClientCalls.blockingUnaryCall(
          stubChannel,
          RivaProtoCodec.getConfigMethod,
          callOpts,
          RivaProtoCodec.ConfigRequestMsg(),
        )
      resp.modelNames
    }
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
    return withChannel(target, apiKey, functionId, validatedOverrideIp, ignoreSsl) { channel, metadata ->
      val callOpts = CallOptions.DEFAULT.withDeadlineAfter(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
      val stubChannel =
        ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata))
      ClientCalls.blockingUnaryCall(stubChannel, RivaProtoCodec.recognizeMethod, callOpts, request)
    }
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
    return withChannel(target, apiKey, functionId, validatedOverrideIp, ignoreSsl) { channel, metadata ->
      val callOpts = CallOptions.DEFAULT.withDeadlineAfter(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
      val stubChannel =
        ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata))
      val call = stubChannel.newCall(RivaProtoCodec.streamingRecognizeMethod, callOpts)

      val latch = CountDownLatch(1)
      val finalSegments = mutableListOf<String>()
      var latestInterim = ""
      var streamError: Throwable? = null

      val responseObserver =
        object : StreamObserver<RivaProtoCodec.StreamingRecognizeResponseMsg> {
          override fun onNext(value: RivaProtoCodec.StreamingRecognizeResponseMsg) {
            val interimParts = mutableListOf<String>()
            for (res in value.results) {
              val t = res.transcript.trim()
              if (t.isEmpty()) continue
              if (res.isFinal) {
                finalSegments.add(t)
                latestInterim = ""
              } else {
                interimParts.add(t)
              }
            }
            if (interimParts.isNotEmpty()) {
              latestInterim = interimParts.joinToString(" ")
            }
            val liveText =
              (finalSegments + listOfNotNull(latestInterim.takeIf { it.isNotBlank() }))
                .joinToString(" ")
                .trim()
            if (liveText.isNotEmpty()) {
              onPartialText?.invoke(liveText)
            }
          }

          override fun onError(t: Throwable) {
            streamError = t
            latch.countDown()
          }

          override fun onCompleted() {
            latch.countDown()
          }
        }

      val requestObserver =
        ClientCalls.asyncBidiStreamingCall<
          RivaProtoCodec.StreamingRecognizeRequestMsg,
          RivaProtoCodec.StreamingRecognizeResponseMsg,
        >(call, responseObserver)
      try {
        requestObserver.onNext(RivaProtoCodec.StreamingRecognizeRequestMsg(streamingConfig = config))
        val chunkSize = 8192
        var offset = 0
        while (offset < pcmBytes.size) {
          val end = (offset + chunkSize).coerceAtMost(pcmBytes.size)
          requestObserver.onNext(
            RivaProtoCodec.StreamingRecognizeRequestMsg(
              audioContent = pcmBytes.copyOfRange(offset, end)
            )
          )
          offset = end
        }
        requestObserver.onCompleted()
      } catch (e: Throwable) {
        try {
          requestObserver.onError(e)
        } catch (_: Throwable) {}
        throw (e as? StatusRuntimeException ?: Status.INTERNAL.withCause(e).asRuntimeException())
      }

      if (!latch.await(timeoutMs.toLong() + 1000L, TimeUnit.MILLISECONDS)) {
        try {
          call.cancel("Timed out waiting for Riva stream", null)
        } catch (_: Throwable) {}
        throw Status.DEADLINE_EXCEEDED.withDescription("Streaming recognition timed out").asRuntimeException()
      }

      streamError?.let { err ->
        throw (err as? StatusRuntimeException
          ?: Status.fromThrowable(err).asRuntimeException())
      }

      (finalSegments + listOfNotNull(latestInterim.takeIf { it.isNotBlank() }))
        .joinToString(" ")
        .trim()
    }
  }

  private inline fun <T> withChannel(
    target: GrpcTarget,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    block: (ManagedChannel, Metadata) -> T,
  ): T {
    val connectHost = validatedOverrideIp?.takeIf { it.isNotBlank() } ?: target.host
    val builder = OkHttpChannelBuilder.forAddress(connectHost, target.port)

    if (!validatedOverrideIp.isNullOrBlank()) {
      builder.overrideAuthority("${target.host}:${target.port}")
    }

    if (!target.useTls) {
      builder.usePlaintext()
    } else {
      builder.useTransportSecurity()
      val sniHost = target.host.takeIf { isHostnameForSni(it) }
      if (ignoreSsl) {
        builder.sslSocketFactory(trustAllSslSocketFactory(sniHost))
        builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
      } else if (!validatedOverrideIp.isNullOrBlank() && sniHost != null) {
        builder.sslSocketFactory(sniAwareSslSocketFactory(sniHost))
        builder.hostnameVerifier(
          HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(sniHost, session)
          }
        )
      }
    }

    val channel = builder.build()
    val metadata =
      Metadata().apply {
        val cleanKey = apiKey.trim()
        if (cleanKey.isNotEmpty()) {
          put(AUTHORIZATION_KEY, "Bearer $cleanKey")
        }
        val cleanFuncId = functionId.trim()
        if (cleanFuncId.isNotEmpty()) {
          put(FUNCTION_ID_KEY, cleanFuncId)
        }
      }

    return try {
      block(channel, metadata)
    } finally {
      channel.shutdownNow()
      runCatching { channel.awaitTermination(500, TimeUnit.MILLISECONDS) }
    }
  }

  private fun isHostnameForSni(host: String): Boolean {
    if (host.isBlank() || host.contains(':')) return false
    val parts = host.split('.')
    return !(parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } })
  }

  private fun sniAwareSslSocketFactory(sniHost: String): SSLSocketFactory {
    val base = HttpsURLConnection.getDefaultSSLSocketFactory()
    val asciiSni = IDN.toASCII(sniHost)
    return object : SSLSocketFactory() {
      override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

      override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

      override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val sock = base.createSocket(s, asciiSni, port, autoClose)
        if (sock is SSLSocket) {
          sock.sslParameters = sock.sslParameters.apply { serverNames = listOf(SNIHostName(asciiSni)) }
        }
        return sock
      }

      override fun createSocket(host: String?, port: Int): Socket =
        createSocket(null, host, port, true)

      override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        base.createSocket(asciiSni, port, localHost, localPort)

      override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port)

      override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        base.createSocket(address, port, localAddress, localPort)
    }
  }

  @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
  private fun trustAllSslSocketFactory(sniHost: String?): SSLSocketFactory {
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
    val base = sslContext.socketFactory
    if (sniHost.isNullOrBlank()) return base
    val asciiSni = IDN.toASCII(sniHost)
    return object : SSLSocketFactory() {
      override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

      override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

      override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val sock = base.createSocket(s, asciiSni, port, autoClose)
        if (sock is SSLSocket) {
          sock.sslParameters = sock.sslParameters.apply { serverNames = listOf(SNIHostName(asciiSni)) }
        }
        return sock
      }

      override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port)

      override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        base.createSocket(host, port, localHost, localPort)

      override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port)

      override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        base.createSocket(address, port, localAddress, localPort)
    }
  }
}

class RivaGrpcClient(private val executor: RivaRpcExecutor = OkHttpRivaRpcExecutor) {

  fun isGrpcEndpoint(
    rawInput: String,
    functionId: String = "",
    detectedFamily: ServerFamily? = null,
  ): Boolean {
    if (detectedFamily == ServerFamily.NVIDIA_RIVA_GRPC) return true
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return false
    if (
      trimmed.startsWith("grpc://", ignoreCase = true) ||
        trimmed.startsWith("grpcs://", ignoreCase = true)
    ) {
      return true
    }
    val hasHttpScheme =
      trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    if (hasHttpScheme) return false
    if (trimmed.contains("nvcf.nvidia.com", ignoreCase = true)) return true
    if (functionId.isNotBlank()) return true
    return trimmed.endsWith(":50051")
  }

  fun parseTarget(rawInput: String): GrpcTarget? {
    val trimmed = rawInput.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null

    val explicitGrpcs = trimmed.startsWith("grpcs://", ignoreCase = true)
    val explicitGrpc = trimmed.startsWith("grpc://", ignoreCase = true)
    val normalized =
      when {
        explicitGrpcs -> "https://${trimmed.substring(8)}"
        explicitGrpc -> "http://${trimmed.substring(7)}"
        trimmed.startsWith("http://", ignoreCase = true) ||
          trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
      }

    val uri =
      try {
        URI(normalized)
      } catch (_: Exception) {
        return null
      }

    val host = uri.host ?: return null
    val isNvcf = host.contains("nvcf.nvidia.com", ignoreCase = true)
    val defaultPort =
      when {
        explicitGrpcs -> 443
        explicitGrpc -> if (isNvcf) 443 else 50051
        else -> 443
      }
    val port = if (uri.port != -1) uri.port else defaultPort
    val useTls =
      when {
        explicitGrpcs -> true
        isNvcf -> true
        explicitGrpc -> port == 443
        else -> port != 50051
      }

    val authorityHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return GrpcTarget(
      host = host,
      port = port,
      useTls = useTls,
      canonicalUrl = "$authorityHost:$port",
    )
  }

  fun probe(
    rawInput: String,
    apiKey: String,
    functionId: String,
    modelName: String,
    language: LanguageOption,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    silentWavBytes: ByteArray,
    probeTimeoutMs: Int,
  ): ProbeResult {
    val target =
      parseTarget(rawInput)
        ?: return ProbeResult.Failure("Enter a valid gRPC host:port (e.g. grpc.nvcf.nvidia.com:443).")

    if (target.host.contains("nvcf.nvidia.com", ignoreCase = true) && functionId.isBlank()) {
      return ProbeResult.Failure(
        "NVIDIA Cloud gRPC requires a Function ID UUID (e.g. ${EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID})."
      )
    }

    var discoveredModels = emptyList<String>()
    try {
      discoveredModels =
        executor.getModels(
          target = target,
          apiKey = apiKey,
          functionId = functionId,
          validatedOverrideIp = validatedOverrideIp,
          ignoreSsl = ignoreSsl,
          timeoutMs = probeTimeoutMs,
        )
    } catch (e: StatusRuntimeException) {
      when (e.status.code) {
        Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED ->
          return ProbeResult.NeedsApiKey(
            url = target.canonicalUrl,
            family = ServerFamily.NVIDIA_RIVA_GRPC,
            message = "API key rejected (${formatStatusError(e)}). Check your key.",
          )
        Status.Code.UNIMPLEMENTED, Status.Code.NOT_FOUND -> {
          // Some NVCF ASR NIMs expose Recognize without GetRivaSpeechRecognitionConfig; continue to Recognize probe.
        }
        Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED ->
          return ProbeResult.Failure("gRPC connection failed (${formatStatusError(e)})")
        else -> {
          // Verify with Recognize call below for clearer error message
        }
      }
    }

    val pcm = RivaProtoCodec.extractLinearPcm16(silentWavBytes)
    val explicitModel = effectiveExplicitRivaModel(modelName, discoveredModels)
    val initialLang = rivaLanguageCode(language)

    return try {
      recognizeWithSmartFallbacks(
        target = target,
        pcm = pcm,
        initialLang = initialLang,
        languageOption = language,
        initialModel = explicitModel,
        apiKey = apiKey,
        functionId = functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = ignoreSsl,
        timeoutMs = probeTimeoutMs,
      )
      val viaSuffix = validatedOverrideIp?.let { " (via $it)" }.orEmpty()
      val fnSuffix = functionId.trim().takeIf { it.isNotEmpty() }?.let { " • fn ${it.take(8)}…" }.orEmpty()
      ProbeResult.Success(
        url = target.canonicalUrl,
        family = ServerFamily.NVIDIA_RIVA_GRPC,
        message = "OK: NVIDIA Riva gRPC at ${target.canonicalUrl}$fnSuffix$viaSuffix",
        availableModels = discoveredModels,
      )
    } catch (e: StatusRuntimeException) {
      when (e.status.code) {
        Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED ->
          ProbeResult.NeedsApiKey(
            url = target.canonicalUrl,
            family = ServerFamily.NVIDIA_RIVA_GRPC,
            message = "API key needed (${formatStatusError(e)}).",
          )
        else -> ProbeResult.Failure("gRPC error: ${formatStatusError(e)}")
      }
    }
  }

  fun quickHealthCheck(
    settings: AppSettings,
    validatedOverrideIp: String?,
    healthTimeoutMs: Int,
    tinySilentWav: ByteArray,
  ): HealthCheckResult {
    val target =
      parseTarget(settings.endpointInput)
        ?: return HealthCheckResult.Unhealthy("Invalid gRPC endpoint URL.")

    if (target.host.contains("nvcf.nvidia.com", ignoreCase = true) && settings.functionId.isBlank()) {
      return HealthCheckResult.Unhealthy("Missing NVIDIA gRPC Function ID.")
    }

    return try {
      executor.getModels(
        target = target,
        apiKey = settings.effectiveApiKey,
        functionId = settings.functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = settings.ignoreSsl,
        timeoutMs = healthTimeoutMs,
      )
      HealthCheckResult.Healthy("Riva gRPC reachable")
    } catch (e: StatusRuntimeException) {
      when (e.status.code) {
        Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED ->
          HealthCheckResult.Unhealthy(formatStatusError(e), isAuthError = true)
        Status.Code.UNIMPLEMENTED, Status.Code.NOT_FOUND -> {
          // Fallback to 150ms silent Recognize
          try {
            val pcm = RivaProtoCodec.extractLinearPcm16(tinySilentWav)
            recognizeWithSmartFallbacks(
              target = target,
              pcm = pcm,
              initialLang = rivaLanguageCode(settings.language),
              languageOption = settings.language,
              initialModel = effectiveExplicitRivaModel(settings.effectiveModelName, emptyList()),
              apiKey = settings.effectiveApiKey,
              functionId = settings.functionId,
              validatedOverrideIp = validatedOverrideIp,
              ignoreSsl = settings.ignoreSsl,
              timeoutMs = healthTimeoutMs,
            )
            HealthCheckResult.Healthy("Riva gRPC reachable")
          } catch (inner: StatusRuntimeException) {
            val isAuth =
              inner.status.code == Status.Code.UNAUTHENTICATED ||
                inner.status.code == Status.Code.PERMISSION_DENIED
            HealthCheckResult.Unhealthy(formatStatusError(inner), isAuthError = isAuth)
          }
        }
        else -> HealthCheckResult.Unhealthy(formatStatusError(e))
      }
    }
  }

  fun fetchModels(
    rawInput: String,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
  ): List<String> {
    val target = parseTarget(rawInput) ?: return emptyList()
    return try {
      executor.getModels(
        target = target,
        apiKey = apiKey,
        functionId = functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = ignoreSsl,
        timeoutMs = timeoutMs,
      )
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun transcribe(
    rawInput: String,
    wavBytes: ByteArray,
    apiKey: String,
    functionId: String,
    modelName: String,
    language: LanguageOption,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    preferStreaming: Boolean,
    onPartialText: ((String) -> Unit)?,
    timeoutMs: Int,
  ): TranscribeResult {
    val target =
      parseTarget(rawInput)
        ?: return TranscribeResult.Failure("Invalid gRPC endpoint URL.")

    if (target.host.contains("nvcf.nvidia.com", ignoreCase = true) && functionId.isBlank()) {
      return TranscribeResult.Failure("NVIDIA Cloud gRPC requires a Function ID in Endpoint Settings.")
    }

    val pcm = RivaProtoCodec.extractLinearPcm16(wavBytes)
    val explicitModel = effectiveExplicitRivaModel(modelName, emptyList())
    val initialLang = rivaLanguageCode(language)

    return try {
      val text =
        if (preferStreaming && onPartialText != null) {
          try {
            streamingWithSmartFallbacks(
              target = target,
              pcm = pcm,
              initialLang = initialLang,
              languageOption = language,
              initialModel = explicitModel,
              apiKey = apiKey,
              functionId = functionId,
              validatedOverrideIp = validatedOverrideIp,
              ignoreSsl = ignoreSsl,
              timeoutMs = timeoutMs,
              onPartialText = onPartialText,
            )
          } catch (streamErr: StatusRuntimeException) {
            if (
              streamErr.status.code == Status.Code.UNIMPLEMENTED ||
                streamErr.status.code == Status.Code.INVALID_ARGUMENT
            ) {
              recognizeWithSmartFallbacks(
                target = target,
                pcm = pcm,
                initialLang = initialLang,
                languageOption = language,
                initialModel = explicitModel,
                apiKey = apiKey,
                functionId = functionId,
                validatedOverrideIp = validatedOverrideIp,
                ignoreSsl = ignoreSsl,
                timeoutMs = timeoutMs,
              ).combinedText
            } else {
              throw streamErr
            }
          }
        } else {
          recognizeWithSmartFallbacks(
            target = target,
            pcm = pcm,
            initialLang = initialLang,
            languageOption = language,
            initialModel = explicitModel,
            apiKey = apiKey,
            functionId = functionId,
            validatedOverrideIp = validatedOverrideIp,
            ignoreSsl = ignoreSsl,
            timeoutMs = timeoutMs,
          ).combinedText
        }

      TranscribeResult.Success(
        text = text.trim(),
        usedUrl = target.canonicalUrl,
        usedFamily = ServerFamily.NVIDIA_RIVA_GRPC,
      )
    } catch (e: StatusRuntimeException) {
      val isAuth =
        e.status.code == Status.Code.UNAUTHENTICATED ||
          e.status.code == Status.Code.PERMISSION_DENIED
      TranscribeResult.Failure(
        message =
          if (isAuth) "Unauthorized (${formatStatusError(e)}). Check API key in Settings."
          else "gRPC error: ${formatStatusError(e)}",
        isAuthError = isAuth,
      )
    }
  }

  private fun recognizeWithSmartFallbacks(
    target: GrpcTarget,
    pcm: RivaProtoCodec.LinearPcmPayload,
    initialLang: String,
    languageOption: LanguageOption,
    initialModel: String,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
  ): RivaProtoCodec.RecognizeResponseMsg {
    fun buildReq(lang: String, model: String) =
      RivaProtoCodec.RecognizeRequestMsg(
        config =
          RivaProtoCodec.RecognitionConfigMsg(
            encoding = RivaProtoCodec.AUDIO_ENCODING_LINEAR_PCM,
            sampleRateHertz = pcm.sampleRateHz,
            languageCode = lang,
            maxAlternatives = 1,
            audioChannelCount = pcm.channelCount,
            enableAutomaticPunctuation = true,
            model = model,
          ),
        audio = pcm.pcmBytes,
      )

    return try {
      executor.recognize(
        target = target,
        request = buildReq(initialLang, initialModel),
        apiKey = apiKey,
        functionId = functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = ignoreSsl,
        timeoutMs = timeoutMs,
      )
    } catch (e: StatusRuntimeException) {
      // 1) English-only NVCF Parakeet deployments reject "multi" -> retry cleanly with "en-US"
      // 2) Or deployment rejects explicit model name -> retry cleanly with empty model
      val invalidArg = e.status.code == Status.Code.INVALID_ARGUMENT || e.status.code == Status.Code.NOT_FOUND
      if (!invalidArg) throw e

      val fallbackLang =
        if (languageOption == LanguageOption.AUTO && initialLang == "multi") "en-US" else initialLang
      val fallbackModel = ""
      if (fallbackLang == initialLang && fallbackModel == initialModel) throw e

      executor.recognize(
        target = target,
        request = buildReq(fallbackLang, fallbackModel),
        apiKey = apiKey,
        functionId = functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = ignoreSsl,
        timeoutMs = timeoutMs,
      )
    }
  }

  private fun streamingWithSmartFallbacks(
    target: GrpcTarget,
    pcm: RivaProtoCodec.LinearPcmPayload,
    initialLang: String,
    languageOption: LanguageOption,
    initialModel: String,
    apiKey: String,
    functionId: String,
    validatedOverrideIp: String?,
    ignoreSsl: Boolean,
    timeoutMs: Int,
    onPartialText: ((String) -> Unit)?,
  ): String {
    fun buildCfg(lang: String, model: String) =
      RivaProtoCodec.StreamingRecognitionConfigMsg(
        config =
          RivaProtoCodec.RecognitionConfigMsg(
            encoding = RivaProtoCodec.AUDIO_ENCODING_LINEAR_PCM,
            sampleRateHertz = pcm.sampleRateHz,
            languageCode = lang,
            maxAlternatives = 1,
            audioChannelCount = pcm.channelCount,
            enableAutomaticPunctuation = true,
            model = model,
          ),
        singleUtterance = false,
        interimResults = true,
      )

    return try {
      executor.streamingRecognize(
        target = target,
        config = buildCfg(initialLang, initialModel),
        pcmBytes = pcm.pcmBytes,
        apiKey = apiKey,
        functionId = functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = ignoreSsl,
        timeoutMs = timeoutMs,
        onPartialText = onPartialText,
      )
    } catch (e: StatusRuntimeException) {
      val invalidArg = e.status.code == Status.Code.INVALID_ARGUMENT || e.status.code == Status.Code.NOT_FOUND
      if (!invalidArg) throw e
      val fallbackLang =
        if (languageOption == LanguageOption.AUTO && initialLang == "multi") "en-US" else initialLang
      val fallbackModel = ""
      if (fallbackLang == initialLang && fallbackModel == initialModel) throw e
      executor.streamingRecognize(
        target = target,
        config = buildCfg(fallbackLang, fallbackModel),
        pcmBytes = pcm.pcmBytes,
        apiKey = apiKey,
        functionId = functionId,
        validatedOverrideIp = validatedOverrideIp,
        ignoreSsl = ignoreSsl,
        timeoutMs = timeoutMs,
        onPartialText = onPartialText,
      )
    }
  }

  internal fun effectiveExplicitRivaModel(
    rawModelName: String,
    discoveredModels: List<String>,
  ): String {
    val trimmed = rawModelName.trim()
    if (
      trimmed.isEmpty() ||
        trimmed.equals("parakeet", ignoreCase = true) ||
        trimmed.equals(AppSettings.DEFAULT_MODEL_NAME, ignoreCase = true)
    ) {
      // On NVCF gRPC, leaving model blank lets the container selected by `function-id` use its loaded pipeline;
      // only send an explicit model name if GetRivaSpeechRecognitionConfig returned one matching parakeet.
      return if (trimmed.equals("parakeet", ignoreCase = true) && discoveredModels.isNotEmpty()) {
        discoveredModels.firstOrNull { it.contains("parakeet", ignoreCase = true) } ?: ""
      } else {
        ""
      }
    }
    return trimmed
  }

  internal fun rivaLanguageCode(language: LanguageOption): String {
    return when (language) {
      LanguageOption.AUTO -> "multi"
      LanguageOption.POLISH -> "pl-PL"
      LanguageOption.ENGLISH -> "en-US"
    }
  }

  private fun formatStatusError(e: StatusRuntimeException): String {
    val code = e.status.code.name
    val desc = e.status.description?.trim().orEmpty()
    val cause = e.status.cause?.message?.trim().orEmpty()
    val detail = desc.ifBlank { cause }
    return if (detail.isNotBlank()) "$code: ${detail.take(120)}" else code
  }
}
