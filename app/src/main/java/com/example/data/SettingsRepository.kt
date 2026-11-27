package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
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

data class EndpointProfile(
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
  val targetPackage: String = AppSettings.DEFAULT_TARGET_PACKAGE,
  val targetLabel: String = AppSettings.DEFAULT_TARGET_LABEL,
  val searchInCurrentApp: Boolean = true,
  val language: LanguageOption = LanguageOption.AUTO,
  val micSource: MicSource = MicSource.AUTO,
) {
  val displayName: String
    get() = deriveDisplayName(customName, endpointInput, useIpOverride, overrideIp)

  val cacheKey: String
    get() = "${endpointInput.trim()}|$useIpOverride|${overrideIp.trim()}|$ignoreSsl"

  val effectiveApiKey: String
    get() = if (apiKeyNotNeeded) "" else apiKey.trim()

  val effectiveModelName: String
    get() = if (useCustomModel && modelName.isNotBlank()) modelName.trim() else AppSettings.DEFAULT_MODEL_NAME

  val isMissingRequiredApiKey: Boolean
    get() = !apiKeyNotNeeded && apiKey.isBlank()

  val hasValidCachedEndpoint: Boolean
    get() =
      detectedEndpointUrl.isNotBlank() &&
        detectedFamily != null &&
        detectedForInput == cacheKey

  fun toProbeSettings(): AppSettings {
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
      targetPackage = targetPackage,
      targetLabel = targetLabel,
      searchInCurrentApp = searchInCurrentApp,
      language = language,
      micSource = micSource,
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
      put("targetPackage", targetPackage)
      put("targetLabel", targetLabel)
      put("searchInCurrentApp", searchInCurrentApp)
      put("language", language.name)
      put("micSource", micSource.name)
    }
  }

  companion object {
    const val NVIDIA_PRESET_ID = "preset_nvidia_asr"
    const val NVIDIA_ENDPOINT_URL = "https://integrate.api.nvidia.com/v1"
    const val NVIDIA_DETECTED_URL = "https://integrate.api.nvidia.com/v1/audio/transcriptions"
    const val NVIDIA_OLD_NVCF_URL =
      "https://2b940e91-a70e-4483-a958-d50408b96589.invocation.api.nvcf.nvidia.com/v1/audio/transcriptions"
    const val NVIDIA_DEFAULT_MODEL = "nvidia/parakeet-tdt-0.6b"

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
          else -> "New configuration"
        }

      return if (raw.length <= 36) raw else raw.take(35) + "…"
    }

    fun defaultNvidiaProfile(): EndpointProfile {
      return EndpointProfile(
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
        targetPackage = AppSettings.DEFAULT_TARGET_PACKAGE,
        targetLabel = AppSettings.DEFAULT_TARGET_LABEL,
        searchInCurrentApp = true,
        language = LanguageOption.AUTO,
        micSource = MicSource.AUTO,
      )
    }

    fun fromJson(json: JSONObject): EndpointProfile {
      val familyName = json.optString("detectedFamily", "")
      val family = ServerFamily.entries.firstOrNull { it.name == familyName }
      val langName = json.optString("language", LanguageOption.AUTO.name)
      val language =
        LanguageOption.entries.firstOrNull { it.name == langName } ?: LanguageOption.AUTO
      val micName = json.optString("micSource", MicSource.AUTO.name)
      val micSource = MicSource.entries.firstOrNull { it.name == micName } ?: MicSource.AUTO

      return EndpointProfile(
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

sealed class ActivateProfileResult {
  data class Activated(val profile: EndpointProfile) : ActivateProfileResult()

  data class MissingApiKey(val profile: EndpointProfile) : ActivateProfileResult()

  data class InvalidEndpoint(val profile: EndpointProfile) : ActivateProfileResult()
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
  val profiles: List<EndpointProfile> = emptyList(),
  val activeProfileId: String? = null,
) {
  val activeProfile: EndpointProfile?
    get() = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }

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

class SettingsRepository private constructor(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  init {
    ensureInitializedProfiles()
  }

  private val _settings = MutableStateFlow(readFromPrefs())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  private val listener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = readFromPrefs() }

  init {
    prefs.registerOnSharedPreferenceChangeListener(listener)
  }

  private fun ensureInitializedProfiles() {
    if (prefs.contains(KEY_PROFILES_JSON)) return

    val legacyEndpoint = prefs.getString(KEY_ENDPOINT_INPUT, "")?.trim().orEmpty()
    val nvidia = EndpointProfile.defaultNvidiaProfile()

    if (legacyEndpoint.isBlank()) {
      val arr = JSONArray().apply { put(nvidia.toJson()) }
      prefs.edit().apply {
        putString(KEY_PROFILES_JSON, arr.toString())
        remove(KEY_ACTIVE_PROFILE_ID)
        commit()
      }
      return
    }

    val langName = prefs.getString(KEY_LANGUAGE, LanguageOption.AUTO.name)
    val language =
      LanguageOption.entries.firstOrNull { it.name == langName } ?: LanguageOption.AUTO
    val micName = prefs.getString(KEY_MIC_SOURCE, MicSource.AUTO.name)
    val micSource = MicSource.entries.firstOrNull { it.name == micName } ?: MicSource.AUTO
    val familyName = prefs.getString(KEY_DETECTED_FAMILY, null)
    val family = ServerFamily.entries.firstOrNull { it.name == familyName }
    val savedApiKey = prefs.getString(KEY_API_KEY, "") ?: ""
    val useApiKey =
      if (prefs.contains(KEY_USE_API_KEY)) prefs.getBoolean(KEY_USE_API_KEY, false)
      else savedApiKey.isNotBlank()
    val savedModel =
      prefs.getString(KEY_MODEL_NAME, AppSettings.DEFAULT_MODEL_NAME)?.ifBlank {
        AppSettings.DEFAULT_MODEL_NAME
      } ?: AppSettings.DEFAULT_MODEL_NAME
    val useCustomModel =
      if (prefs.contains(KEY_USE_CUSTOM_MODEL)) prefs.getBoolean(KEY_USE_CUSTOM_MODEL, false)
      else savedModel != AppSettings.DEFAULT_MODEL_NAME

    val migrated =
      EndpointProfile(
        id = "migrated_default",
        customName = "",
        endpointInput = legacyEndpoint,
        useIpOverride = prefs.getBoolean(KEY_USE_IP_OVERRIDE, false),
        overrideIp = prefs.getString(KEY_OVERRIDE_IP, "") ?: "",
        ignoreSsl = prefs.getBoolean(KEY_IGNORE_SSL, false),
        apiKey = savedApiKey,
        apiKeyNotNeeded = !useApiKey,
        useCustomModel = useCustomModel,
        modelName = savedModel,
        liveTranscription = prefs.getBoolean(KEY_LIVE_TRANSCRIPTION, false),
        detectedEndpointUrl = prefs.getString(KEY_DETECTED_URL, "") ?: "",
        detectedFamily = family,
        detectedForInput = prefs.getString(KEY_DETECTED_FOR_INPUT, "") ?: "",
        targetPackage =
          prefs.getString(KEY_TARGET_PACKAGE, AppSettings.DEFAULT_TARGET_PACKAGE)
            ?: AppSettings.DEFAULT_TARGET_PACKAGE,
        targetLabel =
          prefs.getString(KEY_TARGET_LABEL, AppSettings.DEFAULT_TARGET_LABEL)
            ?: AppSettings.DEFAULT_TARGET_LABEL,
        searchInCurrentApp = prefs.getBoolean(KEY_SEARCH_IN_CURRENT_APP, true),
        language = language,
        micSource = micSource,
      )

    val arr = JSONArray().apply {
      put(nvidia.toJson())
      put(migrated.toJson())
    }
    val initialActiveId = if (migrated.isMissingRequiredApiKey) null else migrated.id
    prefs.edit().apply {
      putString(KEY_PROFILES_JSON, arr.toString())
      if (initialActiveId != null) putString(KEY_ACTIVE_PROFILE_ID, initialActiveId)
      else remove(KEY_ACTIVE_PROFILE_ID)
      commit()
    }
  }

  private fun readFromPrefs(): AppSettings {
    val profiles = decodeProfiles(prefs.getString(KEY_PROFILES_JSON, null))
    val storedActiveId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)?.takeIf { it.isNotBlank() }
    val active = storedActiveId?.let { id -> profiles.firstOrNull { it.id == id } }

    val micButtonCapture = prefs.getBoolean(KEY_MIC_BUTTON_CAPTURE, true)
    val lastAssistArgsLog = prefs.getString(KEY_LAST_ASSIST_ARGS, "") ?: ""
    val statuses = decodeStatuses(prefs.getString(KEY_APP_HANDOFF_STATUSES, null))

    if (active != null) {
      return AppSettings(
        endpointInput = active.endpointInput,
        useIpOverride = active.useIpOverride,
        overrideIp = active.overrideIp,
        ignoreSsl = active.ignoreSsl,
        useApiKey = !active.apiKeyNotNeeded,
        useCustomModel = active.useCustomModel,
        liveTranscription = active.liveTranscription,
        micButtonCapture = micButtonCapture,
        searchInCurrentApp = active.searchInCurrentApp,
        detectedEndpointUrl = active.detectedEndpointUrl,
        detectedFamily = active.detectedFamily,
        detectedForInput = active.detectedForInput,
        targetPackage = active.targetPackage,
        targetLabel = active.targetLabel,
        language = active.language,
        apiKey = active.apiKey,
        modelName = active.modelName,
        micSource = active.micSource,
        lastAssistArgsLog = lastAssistArgsLog,
        appHandoffStatuses = statuses,
        profiles = profiles,
        activeProfileId = active.id,
      )
    }

    val langName = prefs.getString(KEY_LANGUAGE, LanguageOption.AUTO.name)
    val language =
      LanguageOption.entries.firstOrNull { it.name == langName } ?: LanguageOption.AUTO
    val micName = prefs.getString(KEY_MIC_SOURCE, MicSource.AUTO.name)
    val micSource = MicSource.entries.firstOrNull { it.name == micName } ?: MicSource.AUTO

    return AppSettings(
      endpointInput = "",
      micButtonCapture = micButtonCapture,
      searchInCurrentApp = prefs.getBoolean(KEY_SEARCH_IN_CURRENT_APP, true),
      targetPackage =
        prefs.getString(KEY_TARGET_PACKAGE, AppSettings.DEFAULT_TARGET_PACKAGE)
          ?: AppSettings.DEFAULT_TARGET_PACKAGE,
      targetLabel =
        prefs.getString(KEY_TARGET_LABEL, AppSettings.DEFAULT_TARGET_LABEL)
          ?: AppSettings.DEFAULT_TARGET_LABEL,
      language = language,
      micSource = micSource,
      lastAssistArgsLog = lastAssistArgsLog,
      appHandoffStatuses = statuses,
      profiles = profiles,
      activeProfileId = null,
    )
  }

  private fun decodeProfiles(rawJson: String?): List<EndpointProfile> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(rawJson)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val parsed = EndpointProfile.fromJson(obj)
          if (
            parsed.id == EndpointProfile.NVIDIA_PRESET_ID &&
              (parsed.endpointInput == EndpointProfile.NVIDIA_OLD_NVCF_URL ||
                parsed.endpointInput == EndpointProfile.NVIDIA_DETECTED_URL) &&
              parsed.apiKey.isBlank()
          ) {
            add(EndpointProfile.defaultNvidiaProfile())
          } else {
            add(parsed)
          }
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun decodeStatuses(rawJson: String?): Map<String, AppHandoffStatus> {
    if (rawJson.isNullOrBlank()) return emptyMap()
    return try {
      val json = JSONObject(rawJson)
      buildMap {
        for (key in json.keys()) {
          val valueName = json.optString(key, AppHandoffStatus.UNKNOWN.name)
          val parsed =
            AppHandoffStatus.entries.firstOrNull { it.name == valueName }
              ?: AppHandoffStatus.UNKNOWN
          if (parsed != AppHandoffStatus.UNKNOWN) {
            put(key, parsed)
          }
        }
      }
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun persistProfiles(profiles: List<EndpointProfile>, activeProfileId: String?) {
    val arr = JSONArray()
    profiles.forEach { arr.put(it.toJson()) }
    prefs.edit().apply {
      putString(KEY_PROFILES_JSON, arr.toString())
      if (activeProfileId != null && profiles.any { it.id == activeProfileId }) {
        putString(KEY_ACTIVE_PROFILE_ID, activeProfileId)
      } else {
        remove(KEY_ACTIVE_PROFILE_ID)
      }
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun saveProfile(profile: EndpointProfile, activateIfEligible: Boolean = true): ActivateProfileResult? {
    val sanitized =
      profile.copy(
        customName = profile.customName.trim(),
        endpointInput = profile.endpointInput.trim(),
        overrideIp = profile.overrideIp.trim(),
        apiKey = profile.apiKey.trim(),
        modelName = profile.modelName.trim().ifBlank { AppSettings.DEFAULT_MODEL_NAME },
      )

    val current = _settings.value
    val existingIdx = current.profiles.indexOfFirst { it.id == sanitized.id }
    val updatedProfiles =
      if (existingIdx >= 0) {
        current.profiles.toMutableList().apply { set(existingIdx, sanitized) }
      } else {
        current.profiles + sanitized
      }

    val wasActive = current.activeProfileId == sanitized.id
    val nextActiveId: String? =
      when {
        sanitized.isMissingRequiredApiKey || sanitized.endpointInput.isBlank() -> {
          if (wasActive) null else current.activeProfileId
        }
        activateIfEligible || wasActive -> sanitized.id
        else -> current.activeProfileId
      }

    persistProfiles(updatedProfiles, nextActiveId)

    return when {
      sanitized.endpointInput.isBlank() && (activateIfEligible || wasActive) ->
        ActivateProfileResult.InvalidEndpoint(sanitized)
      sanitized.isMissingRequiredApiKey && (activateIfEligible || wasActive) ->
        ActivateProfileResult.MissingApiKey(sanitized)
      nextActiveId == sanitized.id -> ActivateProfileResult.Activated(sanitized)
      else -> null
    }
  }

  fun activateProfile(profileId: String): ActivateProfileResult {
    val current = _settings.value
    val target =
      current.profiles.firstOrNull { it.id == profileId }
        ?: return ActivateProfileResult.InvalidEndpoint(EndpointProfile(id = profileId))

    if (target.endpointInput.isBlank()) {
      return ActivateProfileResult.InvalidEndpoint(target)
    }
    if (target.isMissingRequiredApiKey) {
      return ActivateProfileResult.MissingApiKey(target)
    }

    persistProfiles(current.profiles, target.id)
    return ActivateProfileResult.Activated(target)
  }

  fun deleteProfile(profileId: String) {
    val current = _settings.value
    val updated = current.profiles.filterNot { it.id == profileId }
    val nextActiveId =
      if (current.activeProfileId == profileId) null else current.activeProfileId
    persistProfiles(updated, nextActiveId)
  }

  private inline fun mutateActiveProfileOrPrefs(
    legacyWrite: SharedPreferences.Editor.() -> Unit,
    profileTransform: (EndpointProfile) -> EndpointProfile,
  ) {
    val current = _settings.value
    val active = current.activeProfile
    if (active != null) {
      val next = profileTransform(active)
      val updated = current.profiles.map { if (it.id == active.id) next else it }
      val nextActive =
        if (next.isMissingRequiredApiKey || next.endpointInput.isBlank()) null else active.id
      persistProfiles(updated, nextActive)
    } else {
      prefs.edit().apply(legacyWrite).apply()
      _settings.value = readFromPrefs()
    }
  }

  fun updateEndpointInput(input: String) {
    val trimmed = input.trim()
    mutateActiveProfileOrPrefs(
      legacyWrite = { putString(KEY_ENDPOINT_INPUT, trimmed) },
    ) { active ->
      val nextCacheKey =
        "$trimmed|${active.useIpOverride}|${active.overrideIp.trim()}|${active.ignoreSsl}"
      val keepDetected = active.detectedForInput == nextCacheKey
      active.copy(
        endpointInput = trimmed,
        detectedEndpointUrl = if (keepDetected) active.detectedEndpointUrl else "",
        detectedFamily = if (keepDetected) active.detectedFamily else null,
        detectedForInput = if (keepDetected) active.detectedForInput else "",
      )
    }
  }

  fun setUseIpOverride(enabled: Boolean) {
    mutateActiveProfileOrPrefs(legacyWrite = { putBoolean(KEY_USE_IP_OVERRIDE, enabled) }) { active ->
      val nextCacheKey =
        "${active.endpointInput.trim()}|$enabled|${active.overrideIp.trim()}|${active.ignoreSsl}"
      val keepDetected = active.detectedForInput == nextCacheKey
      active.copy(
        useIpOverride = enabled,
        detectedEndpointUrl = if (keepDetected) active.detectedEndpointUrl else "",
        detectedFamily = if (keepDetected) active.detectedFamily else null,
        detectedForInput = if (keepDetected) active.detectedForInput else "",
      )
    }
  }

  fun updateOverrideIp(ip: String) {
    val trimmed = ip.trim()
    mutateActiveProfileOrPrefs(legacyWrite = { putString(KEY_OVERRIDE_IP, trimmed) }) { active ->
      val nextCacheKey =
        "${active.endpointInput.trim()}|${active.useIpOverride}|$trimmed|${active.ignoreSsl}"
      val keepDetected = active.detectedForInput == nextCacheKey
      active.copy(
        overrideIp = trimmed,
        detectedEndpointUrl = if (keepDetected) active.detectedEndpointUrl else "",
        detectedFamily = if (keepDetected) active.detectedFamily else null,
        detectedForInput = if (keepDetected) active.detectedForInput else "",
      )
    }
  }

  fun setIgnoreSsl(enabled: Boolean) {
    mutateActiveProfileOrPrefs(legacyWrite = { putBoolean(KEY_IGNORE_SSL, enabled) }) { active ->
      val nextCacheKey =
        "${active.endpointInput.trim()}|${active.useIpOverride}|${active.overrideIp.trim()}|$enabled"
      val keepDetected = active.detectedForInput == nextCacheKey
      active.copy(
        ignoreSsl = enabled,
        detectedEndpointUrl = if (keepDetected) active.detectedEndpointUrl else "",
        detectedFamily = if (keepDetected) active.detectedFamily else null,
        detectedForInput = if (keepDetected) active.detectedForInput else "",
      )
    }
  }

  fun setUseApiKey(enabled: Boolean) {
    mutateActiveProfileOrPrefs(legacyWrite = { putBoolean(KEY_USE_API_KEY, enabled) }) { active ->
      active.copy(apiKeyNotNeeded = !enabled)
    }
  }

  fun setUseCustomModel(enabled: Boolean) {
    mutateActiveProfileOrPrefs(legacyWrite = { putBoolean(KEY_USE_CUSTOM_MODEL, enabled) }) { active ->
      active.copy(useCustomModel = enabled)
    }
  }

  fun setLiveTranscription(enabled: Boolean) {
    mutateActiveProfileOrPrefs(legacyWrite = { putBoolean(KEY_LIVE_TRANSCRIPTION, enabled) }) { active ->
      active.copy(liveTranscription = enabled)
    }
  }

  fun setMicButtonCapture(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_MIC_BUTTON_CAPTURE, enabled).apply()
    _settings.value = readFromPrefs()
  }

  fun setSearchInCurrentApp(enabled: Boolean) {
    mutateActiveProfileOrPrefs(legacyWrite = { putBoolean(KEY_SEARCH_IN_CURRENT_APP, enabled) }) { active ->
      active.copy(searchInCurrentApp = enabled)
    }
  }

  fun setAppHandoffStatus(packageName: String, status: AppHandoffStatus) {
    val updated = _settings.value.appHandoffStatuses.toMutableMap()
    if (status == AppHandoffStatus.UNKNOWN) {
      updated.remove(packageName)
    } else {
      updated[packageName] = status
    }
    val json = JSONObject()
    for ((pkg, st) in updated) {
      json.put(pkg, st.name)
    }
    prefs.edit().putString(KEY_APP_HANDOFF_STATUSES, json.toString()).apply()
    _settings.value = readFromPrefs()
  }

  fun saveDetectedEndpoint(cacheKey: String, fullUrl: String, family: ServerFamily) {
    mutateActiveProfileOrPrefs(
      legacyWrite = {
        putString(KEY_DETECTED_URL, fullUrl)
        putString(KEY_DETECTED_FAMILY, family.name)
        putString(KEY_DETECTED_FOR_INPUT, cacheKey)
      },
    ) { active ->
      if (active.cacheKey == cacheKey) {
        active.copy(
          detectedEndpointUrl = fullUrl,
          detectedFamily = family,
          detectedForInput = cacheKey,
        )
      } else {
        active
      }
    }
  }

  fun clearDetectedEndpoint() {
    mutateActiveProfileOrPrefs(
      legacyWrite = {
        remove(KEY_DETECTED_URL)
        remove(KEY_DETECTED_FAMILY)
        remove(KEY_DETECTED_FOR_INPUT)
      },
    ) { active ->
      active.copy(
        detectedEndpointUrl = "",
        detectedFamily = null,
        detectedForInput = "",
      )
    }
  }

  fun setTargetApp(packageName: String, label: String) {
    mutateActiveProfileOrPrefs(
      legacyWrite = {
        putString(KEY_TARGET_PACKAGE, packageName)
        putString(KEY_TARGET_LABEL, label)
      },
    ) { active ->
      active.copy(targetPackage = packageName, targetLabel = label)
    }
  }

  fun setLanguage(language: LanguageOption) {
    mutateActiveProfileOrPrefs(legacyWrite = { putString(KEY_LANGUAGE, language.name) }) { active ->
      active.copy(language = language)
    }
  }

  fun setApiKey(apiKey: String) {
    mutateActiveProfileOrPrefs(legacyWrite = { putString(KEY_API_KEY, apiKey.trim()) }) { active ->
      active.copy(apiKey = apiKey.trim())
    }
  }

  fun setModelName(modelName: String) {
    val value = modelName.trim().ifBlank { AppSettings.DEFAULT_MODEL_NAME }
    mutateActiveProfileOrPrefs(legacyWrite = { putString(KEY_MODEL_NAME, value) }) { active ->
      active.copy(modelName = value)
    }
  }

  fun setMicSource(micSource: MicSource) {
    mutateActiveProfileOrPrefs(legacyWrite = { putString(KEY_MIC_SOURCE, micSource.name) }) { active ->
      active.copy(micSource = micSource)
    }
  }

  fun setLastAssistArgsLog(summary: String) {
    prefs.edit().putString(KEY_LAST_ASSIST_ARGS, summary).apply()
    _settings.value = readFromPrefs()
  }

  companion object {
    private const val PREFS_NAME = "tv_voice_search_prefs"
    private const val KEY_PROFILES_JSON = "endpoint_profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "active_endpoint_profile_id"
    private const val KEY_ENDPOINT_INPUT = "endpoint_input"
    private const val KEY_USE_IP_OVERRIDE = "use_ip_override"
    private const val KEY_OVERRIDE_IP = "override_ip"
    private const val KEY_IGNORE_SSL = "ignore_ssl"
    private const val KEY_USE_API_KEY = "use_api_key"
    private const val KEY_USE_CUSTOM_MODEL = "use_custom_model"
    private const val KEY_LIVE_TRANSCRIPTION = "live_transcription"
    private const val KEY_MIC_BUTTON_CAPTURE = "mic_button_capture"
    private const val KEY_SEARCH_IN_CURRENT_APP = "search_in_current_app"
    private const val KEY_APP_HANDOFF_STATUSES = "app_handoff_statuses"
    private const val KEY_DETECTED_URL = "detected_url"
    private const val KEY_DETECTED_FAMILY = "detected_family"
    private const val KEY_DETECTED_FOR_INPUT = "detected_for_input"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_TARGET_LABEL = "target_label"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_MIC_SOURCE = "mic_source"
    private const val KEY_LAST_ASSIST_ARGS = "last_assist_args"

    @Volatile private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository {
      return instance
        ?: synchronized(this) {
          instance ?: SettingsRepository(context.applicationContext).also { instance = it }
        }
    }
  }
}
