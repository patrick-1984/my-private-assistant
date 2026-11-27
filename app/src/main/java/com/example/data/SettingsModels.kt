package com.example.data

import java.util.UUID
import org.json.JSONObject

enum class LanguageOption(val code: String?, val label: String) {
  AUTO(null, "Auto"),
  POLISH("pl", "Polish"),
  ENGLISH("en", "English"),
}

enum class MicSource(val label: String) {
  AUTO("Auto"),
  REMOTE_STREAM("Remote stream"),
  BUILTIN_MIC("Built-in mic"),
}

enum class ServerFamily(val label: String) {
  OPENAI_V1("OpenAI-compatible"),
  OPENAI_NO_V1("OpenAI-compatible"),
  WHISPER_CPP("whisper.cpp"),
  WHISPER_ASR("whisper-asr-webservice"),
}

enum class AppHandoffStatus(val label: String) {
  UNKNOWN("Unknown"),
  CONFIRMED("Confirmed"),
  NOT_WORKING("Not working"),
}

enum class EndpointSlot(val priority: Int, val label: String, val shortLabel: String) {
  PRIMARY(1, "Primary", "1°"),
  SECONDARY(2, "Secondary", "2°"),
  TERTIARY(3, "Tertiary", "3°"),
}

enum class EndpointHealthStatus(val label: String) {
  UNKNOWN("Untested"),
  HEALTHY("Healthy"),
  DEGRADED("Unavailable"),
}

data class EndpointHealthRecord(
  val status: EndpointHealthStatus = EndpointHealthStatus.UNKNOWN,
  val lastCheckedAtMs: Long = 0L,
  val lastSuccessAtMs: Long = 0L,
  val lastError: String? = null,
  val fingerprint: String = "",
) {
  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("status", status.name)
      put("lastCheckedAtMs", lastCheckedAtMs)
      put("lastSuccessAtMs", lastSuccessAtMs)
      put("lastError", lastError ?: "")
      put("fingerprint", fingerprint)
    }
  }

  companion object {
    fun fromJson(json: JSONObject): EndpointHealthRecord {
      val statusName = json.optString("status", EndpointHealthStatus.UNKNOWN.name)
      val status =
        EndpointHealthStatus.entries.firstOrNull { it.name == statusName }
          ?: EndpointHealthStatus.UNKNOWN
      return EndpointHealthRecord(
        status = status,
        lastCheckedAtMs = json.optLong("lastCheckedAtMs", 0L),
        lastSuccessAtMs = json.optLong("lastSuccessAtMs", 0L),
        lastError = json.optString("lastError", "").takeIf { it.isNotBlank() },
        fingerprint = json.optString("fingerprint", ""),
      )
    }
  }
}

data class EndpointPreset(
  val id: String = UUID.randomUUID().toString(),
  val customName: String = "",
  val endpointInput: String = "",
  val useIpOverride: Boolean = false,
  val overrideIp: String = "",
  val ignoreSsl: Boolean = false,
  val apiKey: String = "",
  val apiKeyNotNeeded: Boolean = false,
  val useCustomModel: Boolean = false,
  val modelName: String = AppSettings.DEFAULT_MODEL_NAME,
  val liveTranscription: Boolean = false,
  val detectedEndpointUrl: String = "",
  val detectedFamily: ServerFamily? = null,
  val detectedForInput: String = "",
) {
  val displayName: String
    get() = deriveDisplayName(customName, endpointInput, useIpOverride, overrideIp)

  val cacheKey: String
    get() = "${endpointInput.trim()}|$useIpOverride|${overrideIp.trim()}|$ignoreSsl"

  val healthFingerprint: String
    get() = "$cacheKey|$apiKeyNotNeeded|${apiKey.trim()}"

  val effectiveApiKey: String
    get() = if (apiKeyNotNeeded) "" else apiKey.trim()

  val effectiveModelName: String
    get() = if (useCustomModel && modelName.isNotBlank()) modelName.trim() else AppSettings.DEFAULT_MODEL_NAME

  val isMissingRequiredApiKey: Boolean
    get() = !apiKeyNotNeeded && apiKey.isBlank()

  val isUsable: Boolean
    get() = endpointInput.isNotBlank() && !isMissingRequiredApiKey

  val hasValidCachedEndpoint: Boolean
    get() =
      detectedEndpointUrl.isNotBlank() &&
        detectedFamily != null &&
        detectedForInput == cacheKey

  fun toProbeSettings(appPreset: AppSettingsPreset? = null): AppSettings {
    return AppSettings(
      endpointInput = endpointInput,
      useIpOverride = useIpOverride,
      overrideIp = overrideIp,
      ignoreSsl = ignoreSsl,
      useApiKey = !apiKeyNotNeeded,
      apiKey = apiKey,
      useCustomModel = useCustomModel,
      modelName = modelName,
      liveTranscription = liveTranscription,
      detectedEndpointUrl = detectedEndpointUrl,
      detectedFamily = detectedFamily,
      detectedForInput = detectedForInput,
      targetPackage = appPreset?.targetPackage ?: AppSettings.DEFAULT_TARGET_PACKAGE,
      targetLabel = appPreset?.targetLabel ?: AppSettings.DEFAULT_TARGET_LABEL,
      searchInCurrentApp = appPreset?.searchInCurrentApp ?: true,
      language = appPreset?.language ?: LanguageOption.AUTO,
      micSource = appPreset?.micSource ?: MicSource.AUTO,
    )
  }

  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("id", id)
      put("customName", customName)
      put("endpointInput", endpointInput)
      put("useIpOverride", useIpOverride)
      put("overrideIp", overrideIp)
      put("ignoreSsl", ignoreSsl)
      put("apiKey", apiKey)
      put("apiKeyNotNeeded", apiKeyNotNeeded)
      put("useCustomModel", useCustomModel)
      put("modelName", modelName)
      put("liveTranscription", liveTranscription)
      put("detectedEndpointUrl", detectedEndpointUrl)
      put("detectedFamily", detectedFamily?.name ?: "")
      put("detectedForInput", detectedForInput)
    }
  }

  companion object {
    const val NVIDIA_PRESET_ID = "preset_nvidia_asr"
    const val NVIDIA_ENDPOINT_URL = "https://integrate.api.nvidia.com/v1"
    const val NVIDIA_DETECTED_URL = "https://integrate.api.nvidia.com/v1/audio/transcriptions"
    const val NVIDIA_OLD_NVCF_URL =
      "https://2b940e91-a70e-4483-a958-d50408b96589.invocation.api.nvcf.nvidia.com/v1/audio/transcriptions"
    const val NVIDIA_DEFAULT_MODEL = "parakeet"

    fun deriveDisplayName(
      customName: String,
      endpointInput: String,
      useIpOverride: Boolean,
      overrideIp: String,
    ): String {
      val explicit = customName.trim()
      if (explicit.isNotEmpty()) return explicit

      val strippedEndpoint =
        endpointInput
          .trim()
          .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
          .trimEnd('/')
      val cleanIp = overrideIp.trim()

      val raw =
        when {
          strippedEndpoint.isNotEmpty() && useIpOverride && cleanIp.isNotEmpty() ->
            "$strippedEndpoint ($cleanIp)"
          strippedEndpoint.isNotEmpty() -> strippedEndpoint
          cleanIp.isNotEmpty() -> cleanIp
          else -> "New endpoint"
        }

      return if (raw.length <= 36) raw else raw.take(35) + "…"
    }

    fun defaultNvidiaPreset(): EndpointPreset {
      return EndpointPreset(
        id = NVIDIA_PRESET_ID,
        customName = "NVIDIA",
        endpointInput = NVIDIA_ENDPOINT_URL,
        useIpOverride = false,
        overrideIp = "",
        ignoreSsl = false,
        apiKey = "",
        apiKeyNotNeeded = false,
        useCustomModel = true,
        modelName = NVIDIA_DEFAULT_MODEL,
        liveTranscription = true,
        detectedEndpointUrl = NVIDIA_DETECTED_URL,
        detectedFamily = ServerFamily.OPENAI_V1,
        detectedForInput = "$NVIDIA_ENDPOINT_URL|false||false",
      )
    }

    fun fromJson(json: JSONObject): EndpointPreset {
      val familyName = json.optString("detectedFamily", "")
      val family = ServerFamily.entries.firstOrNull { it.name == familyName }

      return EndpointPreset(
        id = json.optString("id", "").ifBlank { UUID.randomUUID().toString() },
        customName = json.optString("customName", ""),
        endpointInput = json.optString("endpointInput", ""),
        useIpOverride = json.optBoolean("useIpOverride", false),
        overrideIp = json.optString("overrideIp", ""),
        ignoreSsl = json.optBoolean("ignoreSsl", false),
        apiKey = json.optString("apiKey", ""),
        apiKeyNotNeeded = json.optBoolean("apiKeyNotNeeded", false),
        useCustomModel = json.optBoolean("useCustomModel", false),
        modelName =
          json.optString("modelName", AppSettings.DEFAULT_MODEL_NAME).ifBlank {
            AppSettings.DEFAULT_MODEL_NAME
          },
        liveTranscription = json.optBoolean("liveTranscription", false),
        detectedEndpointUrl = json.optString("detectedEndpointUrl", ""),
        detectedFamily = family,
        detectedForInput = json.optString("detectedForInput", ""),
      )
    }
  }
}

data class AppSettingsPreset(
  val id: String = UUID.randomUUID().toString(),
  val customName: String = "",
  val targetPackage: String = AppSettings.DEFAULT_TARGET_PACKAGE,
  val targetLabel: String = AppSettings.DEFAULT_TARGET_LABEL,
  val searchInCurrentApp: Boolean = true,
  val language: LanguageOption = LanguageOption.AUTO,
  val micSource: MicSource = MicSource.AUTO,
) {
  val displayName: String
    get() {
      val explicit = customName.trim()
      if (explicit.isNotEmpty()) return explicit
      val route = if (searchInCurrentApp) targetLabel else "$targetLabel only"
      return "$route • ${language.label}"
    }

  val summaryLine: String
    get() {
      val currentTag = if (searchInCurrentApp) "Current app + $targetLabel" else "Always $targetLabel"
      return "$currentTag • ${language.label} • ${micSource.label}"
    }

  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("id", id)
      put("customName", customName)
      put("targetPackage", targetPackage)
      put("targetLabel", targetLabel)
      put("searchInCurrentApp", searchInCurrentApp)
      put("language", language.name)
      put("micSource", micSource.name)
    }
  }

  companion object {
    const val DEFAULT_APP_PRESET_ID = "preset_app_default"

    fun defaultPreset(): AppSettingsPreset {
      return AppSettingsPreset(
        id = DEFAULT_APP_PRESET_ID,
        customName = "Default TV",
        targetPackage = AppSettings.DEFAULT_TARGET_PACKAGE,
        targetLabel = AppSettings.DEFAULT_TARGET_LABEL,
        searchInCurrentApp = true,
        language = LanguageOption.AUTO,
        micSource = MicSource.AUTO,
      )
    }

    fun fromJson(json: JSONObject): AppSettingsPreset {
      val langName = json.optString("language", LanguageOption.AUTO.name)
      val language =
        LanguageOption.entries.firstOrNull { it.name == langName } ?: LanguageOption.AUTO
      val micName = json.optString("micSource", MicSource.AUTO.name)
      val micSource = MicSource.entries.firstOrNull { it.name == micName } ?: MicSource.AUTO
      return AppSettingsPreset(
        id = json.optString("id", "").ifBlank { UUID.randomUUID().toString() },
        customName = json.optString("customName", ""),
        targetPackage =
          json.optString("targetPackage", AppSettings.DEFAULT_TARGET_PACKAGE).ifBlank {
            AppSettings.DEFAULT_TARGET_PACKAGE
          },
        targetLabel =
          json.optString("targetLabel", AppSettings.DEFAULT_TARGET_LABEL).ifBlank {
            AppSettings.DEFAULT_TARGET_LABEL
          },
        searchInCurrentApp = json.optBoolean("searchInCurrentApp", true),
        language = language,
        micSource = micSource,
      )
    }
  }
}

data class ConfigurationPreset(
  val id: String = UUID.randomUUID().toString(),
  val customName: String = "",
  val primaryEndpointId: String? = null,
  val secondaryEndpointId: String? = null,
  val tertiaryEndpointId: String? = null,
  val appSettingsPresetId: String? = null,
  val allowEndpointFailover: Boolean = true,
) {
  fun resolveDisplayName(
    endpoints: List<EndpointPreset>,
    appPresets: List<AppSettingsPreset>,
  ): String {
    val explicit = customName.trim()
    if (explicit.isNotEmpty()) return explicit
    val primary = endpoints.firstOrNull { it.id == primaryEndpointId }
    val app = appPresets.firstOrNull { it.id == appSettingsPresetId }
    val raw =
      when {
        primary != null && app != null -> "${primary.displayName} • ${app.displayName}"
        primary != null -> primary.displayName
        app != null -> app.displayName
        else -> "New configuration"
      }
    return if (raw.length <= 40) raw else raw.take(39) + "…"
  }

  fun orderedConfiguredSlots(endpoints: List<EndpointPreset>): List<Pair<EndpointSlot, EndpointPreset>> {
    val seen = hashSetOf<String>()
    val result = mutableListOf<Pair<EndpointSlot, EndpointPreset>>()

    fun addIfPresent(slot: EndpointSlot, id: String?) {
      if (id.isNullOrBlank()) return
      if (!seen.add(id)) return
      val found = endpoints.firstOrNull { it.id == id } ?: return
      result.add(slot to found)
    }

    addIfPresent(EndpointSlot.PRIMARY, primaryEndpointId)
    if (allowEndpointFailover) {
      addIfPresent(EndpointSlot.SECONDARY, secondaryEndpointId)
      addIfPresent(EndpointSlot.TERTIARY, tertiaryEndpointId)
    }
    return result
  }

  fun orderedUsableSlots(endpoints: List<EndpointPreset>): List<Pair<EndpointSlot, EndpointPreset>> {
    return orderedConfiguredSlots(endpoints).filter { (_, ep) -> ep.isUsable }
  }

  fun slotForEndpoint(endpointId: String): EndpointSlot? {
    return when (endpointId) {
      primaryEndpointId -> EndpointSlot.PRIMARY
      secondaryEndpointId -> if (allowEndpointFailover) EndpointSlot.SECONDARY else null
      tertiaryEndpointId -> if (allowEndpointFailover) EndpointSlot.TERTIARY else null
      else -> null
    }
  }

  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("id", id)
      put("customName", customName)
      put("primaryEndpointId", primaryEndpointId ?: "")
      put("secondaryEndpointId", secondaryEndpointId ?: "")
      put("tertiaryEndpointId", tertiaryEndpointId ?: "")
      put("appSettingsPresetId", appSettingsPresetId ?: "")
      put("allowEndpointFailover", allowEndpointFailover)
    }
  }

  companion object {
    const val DEFAULT_CONFIG_ID = "config_default"

    fun fromJson(json: JSONObject): ConfigurationPreset {
      return ConfigurationPreset(
        id = json.optString("id", "").ifBlank { UUID.randomUUID().toString() },
        customName = json.optString("customName", ""),
        primaryEndpointId = json.optString("primaryEndpointId", "").takeIf { it.isNotBlank() },
        secondaryEndpointId = json.optString("secondaryEndpointId", "").takeIf { it.isNotBlank() },
        tertiaryEndpointId = json.optString("tertiaryEndpointId", "").takeIf { it.isNotBlank() },
        appSettingsPresetId = json.optString("appSettingsPresetId", "").takeIf { it.isNotBlank() },
        allowEndpointFailover = json.optBoolean("allowEndpointFailover", true),
      )
    }
  }
}

sealed class ActivateConfigResult {
  data class Activated(val config: ConfigurationPreset, val displayName: String) :
    ActivateConfigResult()

  data class MissingApiKey(
    val config: ConfigurationPreset,
    val endpoint: EndpointPreset,
  ) : ActivateConfigResult()

  data class InvalidEndpoint(val config: ConfigurationPreset) : ActivateConfigResult()
}

data class AppSettings(
  val endpointInput: String = "",
  val useIpOverride: Boolean = false,
  val overrideIp: String = "",
  val ignoreSsl: Boolean = false,
  val useApiKey: Boolean = false,
  val useCustomModel: Boolean = false,
  val liveTranscription: Boolean = false,
  val micButtonCapture: Boolean = true,
  val searchInCurrentApp: Boolean = true,
  val detectedEndpointUrl: String = "",
  val detectedFamily: ServerFamily? = null,
  val detectedForInput: String = "",
  val targetPackage: String = DEFAULT_TARGET_PACKAGE,
  val targetLabel: String = DEFAULT_TARGET_LABEL,
  val language: LanguageOption = LanguageOption.AUTO,
  val apiKey: String = "",
  val modelName: String = DEFAULT_MODEL_NAME,
  val micSource: MicSource = MicSource.AUTO,
  val lastAssistArgsLog: String = "",
  val appHandoffStatuses: Map<String, AppHandoffStatus> = emptyMap(),
  val endpointPresets: List<EndpointPreset> = emptyList(),
  val appSettingsPresets: List<AppSettingsPreset> = emptyList(),
  val configurationPresets: List<ConfigurationPreset> = emptyList(),
  val activeConfigurationId: String? = null,
) {
  val activeConfiguration: ConfigurationPreset?
    get() = activeConfigurationId?.let { id -> configurationPresets.firstOrNull { it.id == id } }

  val activePrimaryEndpoint: EndpointPreset?
    get() = activeConfiguration?.primaryEndpointId?.let { id -> endpointPresets.firstOrNull { it.id == id } }

  val activeAppPreset: AppSettingsPreset?
    get() =
      activeConfiguration?.appSettingsPresetId?.let { id ->
        appSettingsPresets.firstOrNull { it.id == id }
      } ?: appSettingsPresets.firstOrNull()

  fun endpointById(id: String?): EndpointPreset? =
    id?.let { targetId -> endpointPresets.firstOrNull { it.id == targetId } }

  fun appPresetById(id: String?): AppSettingsPreset? =
    id?.let { targetId -> appSettingsPresets.firstOrNull { it.id == targetId } }

  fun orderedActiveEndpoints(): List<Pair<EndpointSlot, EndpointPreset>> {
    val cfg = activeConfiguration ?: return emptyList()
    return cfg.orderedUsableSlots(endpointPresets)
  }

  fun handoffStatusFor(packageName: String): AppHandoffStatus {
    return appHandoffStatuses[packageName] ?: AppHandoffStatus.UNKNOWN
  }

  val cacheKey: String
    get() = "${endpointInput.trim()}|$useIpOverride|${overrideIp.trim()}|$ignoreSsl"

  val effectiveApiKey: String
    get() = if (useApiKey) apiKey.trim() else ""

  val effectiveModelName: String
    get() = if (useCustomModel && modelName.isNotBlank()) modelName.trim() else DEFAULT_MODEL_NAME

  val hasValidCachedEndpoint: Boolean
    get() =
      detectedEndpointUrl.isNotBlank() &&
        detectedFamily != null &&
        detectedForInput == cacheKey

  companion object {
    const val DEFAULT_TARGET_PACKAGE = "org.smarttube.stable"
    const val DEFAULT_TARGET_LABEL = "SmartTube"
    const val DEFAULT_MODEL_NAME = "whisper-1"
  }
}
