package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
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

  private val _settings = MutableStateFlow(readFromPrefs())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  private val listener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = readFromPrefs() }

  init {
    prefs.registerOnSharedPreferenceChangeListener(listener)
  }

  private fun readFromPrefs(): AppSettings {
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

    return AppSettings(
      endpointInput = prefs.getString(KEY_ENDPOINT_INPUT, "") ?: "",
      useIpOverride = prefs.getBoolean(KEY_USE_IP_OVERRIDE, false),
      overrideIp = prefs.getString(KEY_OVERRIDE_IP, "") ?: "",
      ignoreSsl = prefs.getBoolean(KEY_IGNORE_SSL, false),
      useApiKey = useApiKey,
      useCustomModel = useCustomModel,
      liveTranscription = prefs.getBoolean(KEY_LIVE_TRANSCRIPTION, false),
      micButtonCapture = prefs.getBoolean(KEY_MIC_BUTTON_CAPTURE, true),
      searchInCurrentApp = prefs.getBoolean(KEY_SEARCH_IN_CURRENT_APP, true),
      detectedEndpointUrl = prefs.getString(KEY_DETECTED_URL, "") ?: "",
      detectedFamily = family,
      detectedForInput = prefs.getString(KEY_DETECTED_FOR_INPUT, "") ?: "",
      targetPackage =
        prefs.getString(KEY_TARGET_PACKAGE, AppSettings.DEFAULT_TARGET_PACKAGE)
          ?: AppSettings.DEFAULT_TARGET_PACKAGE,
      targetLabel =
        prefs.getString(KEY_TARGET_LABEL, AppSettings.DEFAULT_TARGET_LABEL)
          ?: AppSettings.DEFAULT_TARGET_LABEL,
      language = language,
      apiKey = savedApiKey,
      modelName = savedModel,
      micSource = micSource,
      lastAssistArgsLog = prefs.getString(KEY_LAST_ASSIST_ARGS, "") ?: "",
      appHandoffStatuses = decodeStatuses(prefs.getString(KEY_APP_HANDOFF_STATUSES, null)),
    )
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

  fun updateEndpointInput(input: String) {
    val trimmed = input.trim()
    val current = _settings.value
    val nextCacheKey =
      "$trimmed|${current.useIpOverride}|${current.overrideIp.trim()}|${current.ignoreSsl}"
    prefs.edit().apply {
      putString(KEY_ENDPOINT_INPUT, trimmed)
      if (current.detectedForInput != nextCacheKey) {
        remove(KEY_DETECTED_URL)
        remove(KEY_DETECTED_FAMILY)
        remove(KEY_DETECTED_FOR_INPUT)
      }
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun setUseIpOverride(enabled: Boolean) {
    val current = _settings.value
    val nextCacheKey =
      "${current.endpointInput.trim()}|$enabled|${current.overrideIp.trim()}|${current.ignoreSsl}"
    prefs.edit().apply {
      putBoolean(KEY_USE_IP_OVERRIDE, enabled)
      if (current.detectedForInput != nextCacheKey) {
        remove(KEY_DETECTED_URL)
        remove(KEY_DETECTED_FAMILY)
        remove(KEY_DETECTED_FOR_INPUT)
      }
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun updateOverrideIp(ip: String) {
    val trimmed = ip.trim()
    val current = _settings.value
    val nextCacheKey =
      "${current.endpointInput.trim()}|${current.useIpOverride}|$trimmed|${current.ignoreSsl}"
    prefs.edit().apply {
      putString(KEY_OVERRIDE_IP, trimmed)
      if (current.detectedForInput != nextCacheKey) {
        remove(KEY_DETECTED_URL)
        remove(KEY_DETECTED_FAMILY)
        remove(KEY_DETECTED_FOR_INPUT)
      }
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun setIgnoreSsl(enabled: Boolean) {
    val current = _settings.value
    val nextCacheKey =
      "${current.endpointInput.trim()}|${current.useIpOverride}|${current.overrideIp.trim()}|$enabled"
    prefs.edit().apply {
      putBoolean(KEY_IGNORE_SSL, enabled)
      if (current.detectedForInput != nextCacheKey) {
        remove(KEY_DETECTED_URL)
        remove(KEY_DETECTED_FAMILY)
        remove(KEY_DETECTED_FOR_INPUT)
      }
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun setUseApiKey(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_USE_API_KEY, enabled).apply()
    _settings.value = readFromPrefs()
  }

  fun setUseCustomModel(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_USE_CUSTOM_MODEL, enabled).apply()
    _settings.value = readFromPrefs()
  }

  fun setLiveTranscription(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_LIVE_TRANSCRIPTION, enabled).apply()
    _settings.value = readFromPrefs()
  }

  fun setMicButtonCapture(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_MIC_BUTTON_CAPTURE, enabled).apply()
    _settings.value = readFromPrefs()
  }

  fun setSearchInCurrentApp(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_SEARCH_IN_CURRENT_APP, enabled).apply()
    _settings.value = readFromPrefs()
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
    prefs.edit().apply {
      putString(KEY_DETECTED_URL, fullUrl)
      putString(KEY_DETECTED_FAMILY, family.name)
      putString(KEY_DETECTED_FOR_INPUT, cacheKey)
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun clearDetectedEndpoint() {
    prefs.edit().apply {
      remove(KEY_DETECTED_URL)
      remove(KEY_DETECTED_FAMILY)
      remove(KEY_DETECTED_FOR_INPUT)
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun setTargetApp(packageName: String, label: String) {
    prefs.edit().apply {
      putString(KEY_TARGET_PACKAGE, packageName)
      putString(KEY_TARGET_LABEL, label)
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun setLanguage(language: LanguageOption) {
    prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    _settings.value = readFromPrefs()
  }

  fun setApiKey(apiKey: String) {
    prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    _settings.value = readFromPrefs()
  }

  fun setModelName(modelName: String) {
    val value = modelName.trim().ifBlank { AppSettings.DEFAULT_MODEL_NAME }
    prefs.edit().putString(KEY_MODEL_NAME, value).apply()
    _settings.value = readFromPrefs()
  }

  fun setMicSource(micSource: MicSource) {
    prefs.edit().putString(KEY_MIC_SOURCE, micSource.name).apply()
    _settings.value = readFromPrefs()
  }

  fun setLastAssistArgsLog(summary: String) {
    prefs.edit().putString(KEY_LAST_ASSIST_ARGS, summary).apply()
    _settings.value = readFromPrefs()
  }

  companion object {
    private const val PREFS_NAME = "tv_voice_search_prefs"
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
