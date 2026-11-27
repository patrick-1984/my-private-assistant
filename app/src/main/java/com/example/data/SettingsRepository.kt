package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository private constructor(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  init {
    ensureInitializedHierarchy()
  }

  private val _settings = MutableStateFlow(readFromPrefs())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  private val listener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = readFromPrefs() }

  init {
    prefs.registerOnSharedPreferenceChangeListener(listener)
  }

  private fun ensureInitializedHierarchy() {
    if (
      prefs.contains(KEY_ENDPOINTS_JSON) &&
        prefs.contains(KEY_APP_PRESETS_JSON) &&
        prefs.contains(KEY_CONFIGS_JSON)
    ) {
      return
    }

    val nvidia = EndpointPreset.defaultNvidiaPreset()
    val defaultAppPreset = AppSettingsPreset.defaultPreset()

    // 1. Migrate from v2 EndpointProfile list if present
    val legacyProfilesJson = prefs.getString(KEY_PROFILES_JSON, null)
    if (!legacyProfilesJson.isNullOrBlank()) {
      val migratedEndpoints = mutableListOf<EndpointPreset>()
      val migratedApps = mutableListOf<AppSettingsPreset>(defaultAppPreset)
      val migratedConfigs = mutableListOf<ConfigurationPreset>()
      val oldActiveId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)?.takeIf { it.isNotBlank() }
      var nextActiveConfigId: String? = null

      try {
        val arr = JSONArray(legacyProfilesJson)
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val parsedEp = EndpointPreset.fromJson(obj)
          val ep = normalizeNvidiaIfUntouched(parsedEp)
          migratedEndpoints.add(ep)

          val targetPkg =
            obj.optString("targetPackage", AppSettings.DEFAULT_TARGET_PACKAGE).ifBlank {
              AppSettings.DEFAULT_TARGET_PACKAGE
            }
          val targetLbl =
            obj.optString("targetLabel", AppSettings.DEFAULT_TARGET_LABEL).ifBlank {
              AppSettings.DEFAULT_TARGET_LABEL
            }
          val searchCurrent = obj.optBoolean("searchInCurrentApp", true)
          val lang =
            LanguageOption.entries.firstOrNull {
              it.name == obj.optString("language", LanguageOption.AUTO.name)
            } ?: LanguageOption.AUTO
          val mic =
            MicSource.entries.firstOrNull {
              it.name == obj.optString("micSource", MicSource.AUTO.name)
            } ?: MicSource.AUTO

          val matchingApp =
            migratedApps.firstOrNull {
              it.targetPackage == targetPkg &&
                it.searchInCurrentApp == searchCurrent &&
                it.language == lang &&
                it.micSource == mic
            }
              ?: AppSettingsPreset(
                  id = "app_${ep.id}",
                  customName = "",
                  targetPackage = targetPkg,
                  targetLabel = targetLbl,
                  searchInCurrentApp = searchCurrent,
                  language = lang,
                  micSource = mic,
                )
                .also { migratedApps.add(it) }

          val cfgId =
            if (ep.id == EndpointPreset.NVIDIA_PRESET_ID && arr.length() == 1) {
              ConfigurationPreset.DEFAULT_CONFIG_ID
            } else {
              "cfg_${ep.id}"
            }
          val cfg =
            ConfigurationPreset(
              id = cfgId,
              customName = ep.customName,
              primaryEndpointId = ep.id,
              appSettingsPresetId = matchingApp.id,
              allowEndpointFailover = true,
            )
          migratedConfigs.add(cfg)
          if (oldActiveId == ep.id && ep.isUsable) {
            nextActiveConfigId = cfg.id
          }
        }
      } catch (_: Exception) {
        // Fallback below if malformed
      }

      if (migratedEndpoints.isNotEmpty()) {
        persistHierarchySync(migratedEndpoints, migratedApps, migratedConfigs, nextActiveConfigId)
        return
      }
    }

    // 2. Migrate from v1 flat preferences if present
    val legacyEndpoint = prefs.getString(KEY_ENDPOINT_INPUT, "")?.trim().orEmpty()
    if (legacyEndpoint.isNotBlank()) {
      val lang =
        LanguageOption.entries.firstOrNull {
          it.name == prefs.getString(KEY_LANGUAGE, LanguageOption.AUTO.name)
        } ?: LanguageOption.AUTO
      val mic =
        MicSource.entries.firstOrNull {
          it.name == prefs.getString(KEY_MIC_SOURCE, MicSource.AUTO.name)
        } ?: MicSource.AUTO
      val family =
        ServerFamily.entries.firstOrNull {
          it.name == prefs.getString(KEY_DETECTED_FAMILY, null)
        }
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

      val migratedEp =
        EndpointPreset(
          id = "migrated_default",
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
        )
      val migratedApp =
        AppSettingsPreset(
          id = AppSettingsPreset.DEFAULT_APP_PRESET_ID,
          customName = "Default TV",
          targetPackage =
            prefs.getString(KEY_TARGET_PACKAGE, AppSettings.DEFAULT_TARGET_PACKAGE)
              ?: AppSettings.DEFAULT_TARGET_PACKAGE,
          targetLabel =
            prefs.getString(KEY_TARGET_LABEL, AppSettings.DEFAULT_TARGET_LABEL)
              ?: AppSettings.DEFAULT_TARGET_LABEL,
          searchInCurrentApp = prefs.getBoolean(KEY_SEARCH_IN_CURRENT_APP, true),
          language = lang,
          micSource = mic,
        )
      val migratedConfig =
        ConfigurationPreset(
          id = "cfg_migrated_default",
          customName = "",
          primaryEndpointId = migratedEp.id,
          appSettingsPresetId = migratedApp.id,
          allowEndpointFailover = true,
        )
      val activeId = if (migratedEp.isUsable) migratedConfig.id else null
      persistHierarchySync(
        endpoints = listOf(nvidia, migratedEp),
        appPresets = listOf(defaultAppPreset.takeIf { it.id != migratedApp.id } ?: migratedApp)
          .distinctBy { it.id },
        configs = listOf(migratedConfig),
        activeConfigId = activeId,
      )
      return
    }

    // 3. Fresh install defaults: inactive NVIDIA preset + Default TV app preset + Default config
    val defaultConfig =
      ConfigurationPreset(
        id = ConfigurationPreset.DEFAULT_CONFIG_ID,
        customName = "Default",
        primaryEndpointId = nvidia.id,
        appSettingsPresetId = defaultAppPreset.id,
        allowEndpointFailover = true,
      )
    persistHierarchySync(
      endpoints = listOf(nvidia),
      appPresets = listOf(defaultAppPreset),
      configs = listOf(defaultConfig),
      activeConfigId = null,
    )
  }

  private fun normalizeNvidiaIfUntouched(ep: EndpointPreset): EndpointPreset {
    if (ep.id != EndpointPreset.NVIDIA_PRESET_ID) return ep
    val cleanInput = ep.endpointInput.trim().trimEnd('/')
    val isOldBrokenNvidiaUrl =
      cleanInput == EndpointPreset.NVIDIA_OLD_NVCF_URL ||
        cleanInput == EndpointPreset.NVIDIA_OLD_INTEGRATE_URL ||
        cleanInput == EndpointPreset.NVIDIA_OLD_INTEGRATE_DETECTED_URL

    if (!isOldBrokenNvidiaUrl) return ep

    val defaults = EndpointPreset.defaultNvidiaPreset()
    return ep.copy(
      endpointInput = defaults.endpointInput,
      useCustomModel = true,
      modelName = EndpointPreset.NVIDIA_DEFAULT_MODEL,
      functionId = ep.functionId.ifBlank { defaults.functionId },
      liveTranscription = true,
      detectedEndpointUrl = defaults.detectedEndpointUrl,
      detectedFamily = defaults.detectedFamily,
      detectedForInput =
        "${defaults.endpointInput}|${ep.useIpOverride}|${ep.overrideIp.trim()}|${ep.ignoreSsl}|${ep.functionId.ifBlank { defaults.functionId }}",
    )
  }

  private fun readFromPrefs(): AppSettings {
    val endpoints = decodeEndpoints(prefs.getString(KEY_ENDPOINTS_JSON, null))
    val appPresets =
      decodeAppPresets(prefs.getString(KEY_APP_PRESETS_JSON, null)).ifEmpty {
        listOf(AppSettingsPreset.defaultPreset())
      }
    val configs = decodeConfigs(prefs.getString(KEY_CONFIGS_JSON, null))
    val storedActiveId = prefs.getString(KEY_ACTIVE_CONFIG_ID, null)?.takeIf { it.isNotBlank() }
    val activeConfig = storedActiveId?.let { id -> configs.firstOrNull { it.id == id } }
    val primaryEp =
      activeConfig?.primaryEndpointId?.let { id -> endpoints.firstOrNull { it.id == id } }
    val activeApp =
      activeConfig?.appSettingsPresetId?.let { id -> appPresets.firstOrNull { it.id == id } }
        ?: appPresets.firstOrNull()

    val micButtonCapture = prefs.getBoolean(KEY_MIC_BUTTON_CAPTURE, true)
    val lastAssistArgsLog = prefs.getString(KEY_LAST_ASSIST_ARGS, "") ?: ""
    val statuses = decodeStatuses(prefs.getString(KEY_APP_HANDOFF_STATUSES, null))

    val resolvedApp = activeApp ?: AppSettingsPreset.defaultPreset()

    return AppSettings(
      endpointInput = primaryEp?.endpointInput.orEmpty(),
      useIpOverride = primaryEp?.useIpOverride ?: false,
      overrideIp = primaryEp?.overrideIp.orEmpty(),
      ignoreSsl = primaryEp?.ignoreSsl ?: false,
      useApiKey = primaryEp?.let { !it.apiKeyNotNeeded } ?: false,
      useCustomModel = primaryEp?.useCustomModel ?: false,
      liveTranscription = primaryEp?.liveTranscription ?: false,
      micButtonCapture = micButtonCapture,
      searchInCurrentApp = resolvedApp.searchInCurrentApp,
      detectedEndpointUrl = primaryEp?.detectedEndpointUrl.orEmpty(),
      detectedFamily = primaryEp?.detectedFamily,
      detectedForInput = primaryEp?.detectedForInput.orEmpty(),
      targetPackage = resolvedApp.targetPackage,
      targetLabel = resolvedApp.targetLabel,
      language = resolvedApp.language,
      apiKey = primaryEp?.apiKey.orEmpty(),
      modelName = primaryEp?.modelName ?: AppSettings.DEFAULT_MODEL_NAME,
      functionId = primaryEp?.functionId.orEmpty(),
      micSource = resolvedApp.micSource,
      lastAssistArgsLog = lastAssistArgsLog,
      appHandoffStatuses = statuses,
      endpointPresets = endpoints,
      appSettingsPresets = appPresets,
      configurationPresets = configs,
      activeConfigurationId = activeConfig?.id,
    )
  }

  private fun decodeEndpoints(rawJson: String?): List<EndpointPreset> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(rawJson)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          add(normalizeNvidiaIfUntouched(EndpointPreset.fromJson(obj)))
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun decodeAppPresets(rawJson: String?): List<AppSettingsPreset> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(rawJson)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          add(AppSettingsPreset.fromJson(obj))
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun decodeConfigs(rawJson: String?): List<ConfigurationPreset> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(rawJson)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          add(ConfigurationPreset.fromJson(obj))
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

  private fun persistHierarchySync(
    endpoints: List<EndpointPreset>,
    appPresets: List<AppSettingsPreset>,
    configs: List<ConfigurationPreset>,
    activeConfigId: String?,
  ) {
    val epArr = JSONArray().apply { endpoints.forEach { put(it.toJson()) } }
    val appArr = JSONArray().apply { appPresets.forEach { put(it.toJson()) } }
    val cfgArr = JSONArray().apply { configs.forEach { put(it.toJson()) } }
    val validActive = activeConfigId?.takeIf { id -> configs.any { it.id == id } }
    prefs.edit().apply {
      putString(KEY_ENDPOINTS_JSON, epArr.toString())
      putString(KEY_APP_PRESETS_JSON, appArr.toString())
      putString(KEY_CONFIGS_JSON, cfgArr.toString())
      if (validActive != null) putString(KEY_ACTIVE_CONFIG_ID, validActive)
      else remove(KEY_ACTIVE_CONFIG_ID)
      commit()
    }
  }

  private fun persistHierarchy(
    endpoints: List<EndpointPreset> = _settings.value.endpointPresets,
    appPresets: List<AppSettingsPreset> = _settings.value.appSettingsPresets,
    configs: List<ConfigurationPreset> = _settings.value.configurationPresets,
    activeConfigId: String? = _settings.value.activeConfigurationId,
  ) {
    val epArr = JSONArray().apply { endpoints.forEach { put(it.toJson()) } }
    val appArr = JSONArray().apply { appPresets.forEach { put(it.toJson()) } }
    val cfgArr = JSONArray().apply { configs.forEach { put(it.toJson()) } }
    val validActive = activeConfigId?.takeIf { id -> configs.any { it.id == id } }
    prefs.edit().apply {
      putString(KEY_ENDPOINTS_JSON, epArr.toString())
      putString(KEY_APP_PRESETS_JSON, appArr.toString())
      putString(KEY_CONFIGS_JSON, cfgArr.toString())
      if (validActive != null) putString(KEY_ACTIVE_CONFIG_ID, validActive)
      else remove(KEY_ACTIVE_CONFIG_ID)
      apply()
    }
    _settings.value = readFromPrefs()
  }

  fun saveEndpointPreset(
    preset: EndpointPreset,
    autoActivateIfNoActiveConfig: Boolean = false,
  ): ActivateConfigResult? {
    val sanitized =
      preset.copy(
        customName = preset.customName.trim(),
        endpointInput = preset.endpointInput.trim(),
        overrideIp = preset.overrideIp.trim(),
        apiKey = preset.apiKey.trim(),
        modelName = preset.modelName.trim().ifBlank { AppSettings.DEFAULT_MODEL_NAME },
        functionId = preset.functionId.trim(),
      )

    val current = _settings.value
    val existingIdx = current.endpointPresets.indexOfFirst { it.id == sanitized.id }
    val updatedEndpoints =
      if (existingIdx >= 0) {
        current.endpointPresets.toMutableList().apply { set(existingIdx, sanitized) }
      } else {
        current.endpointPresets + sanitized
      }

    var updatedConfigs = current.configurationPresets
    var nextActiveConfigId = current.activeConfigurationId
    var activationOutcome: ActivateConfigResult? = null

    val activeCfg = current.activeConfiguration
    if (activeCfg != null && activeCfg.primaryEndpointId == sanitized.id && !sanitized.isUsable) {
      nextActiveConfigId = null
      activationOutcome =
        if (sanitized.isMissingRequiredApiKey) {
          ActivateConfigResult.MissingApiKey(activeCfg, sanitized)
        } else {
          ActivateConfigResult.InvalidEndpoint(activeCfg)
        }
    } else if (autoActivateIfNoActiveConfig && sanitized.isUsable) {
      // Find existing config whose primary is this endpoint, or create one if none active
      val matchingCfg =
        updatedConfigs.firstOrNull { it.primaryEndpointId == sanitized.id }
          ?: if (current.activeConfigurationId == null) {
            val defaultAppId =
              current.appSettingsPresets.firstOrNull()?.id ?: AppSettingsPreset.DEFAULT_APP_PRESET_ID
            ConfigurationPreset(
              customName = "",
              primaryEndpointId = sanitized.id,
              appSettingsPresetId = defaultAppId,
              allowEndpointFailover = true,
            ).also { updatedConfigs = updatedConfigs + it }
          } else {
            null
          }
      if (matchingCfg != null && current.activeConfigurationId == null) {
        nextActiveConfigId = matchingCfg.id
        activationOutcome =
          ActivateConfigResult.Activated(
            matchingCfg,
            matchingCfg.resolveDisplayName(updatedEndpoints, current.appSettingsPresets),
          )
      }
    }

    persistHierarchy(
      endpoints = updatedEndpoints,
      configs = updatedConfigs,
      activeConfigId = nextActiveConfigId,
    )
    return activationOutcome
  }

  fun deleteEndpointPreset(endpointId: String) {
    val current = _settings.value
    val updatedEndpoints = current.endpointPresets.filterNot { it.id == endpointId }
    val updatedConfigs =
      current.configurationPresets.map { cfg ->
        var primary = cfg.primaryEndpointId
        var secondary = cfg.secondaryEndpointId
        var tertiary = cfg.tertiaryEndpointId
        if (tertiary == endpointId) tertiary = null
        if (secondary == endpointId) {
          secondary = tertiary
          tertiary = null
        }
        if (primary == endpointId) {
          primary = secondary
          secondary = tertiary
          tertiary = null
        }
        cfg.copy(
          primaryEndpointId = primary,
          secondaryEndpointId = secondary,
          tertiaryEndpointId = tertiary,
        )
      }

    var nextActive = current.activeConfigurationId
    if (nextActive != null) {
      val activeAfter = updatedConfigs.firstOrNull { it.id == nextActive }
      val primaryEp = activeAfter?.primaryEndpointId?.let { id -> updatedEndpoints.firstOrNull { it.id == id } }
      if (primaryEp == null || !primaryEp.isUsable) {
        nextActive = null
      }
    }

    persistHierarchy(
      endpoints = updatedEndpoints,
      configs = updatedConfigs,
      activeConfigId = nextActive,
    )
  }

  fun saveAppPreset(preset: AppSettingsPreset): AppSettingsPreset {
    val sanitized = preset.copy(customName = preset.customName.trim())
    val current = _settings.value
    val idx = current.appSettingsPresets.indexOfFirst { it.id == sanitized.id }
    val updatedApps =
      if (idx >= 0) {
        current.appSettingsPresets.toMutableList().apply { set(idx, sanitized) }
      } else {
        current.appSettingsPresets + sanitized
      }
    persistHierarchy(appPresets = updatedApps)
    return sanitized
  }

  fun deleteAppPreset(appPresetId: String) {
    val current = _settings.value
    val remaining = current.appSettingsPresets.filterNot { it.id == appPresetId }
    val safeApps = remaining.ifEmpty { listOf(AppSettingsPreset.defaultPreset()) }
    val fallbackAppId = safeApps.first().id
    val updatedConfigs =
      current.configurationPresets.map { cfg ->
        if (cfg.appSettingsPresetId == appPresetId) cfg.copy(appSettingsPresetId = fallbackAppId)
        else cfg
      }
    persistHierarchy(appPresets = safeApps, configs = updatedConfigs)
  }

  fun saveConfigurationPreset(
    config: ConfigurationPreset,
    activateIfEligible: Boolean = true,
  ): ActivateConfigResult? {
    val current = _settings.value
    val resolvedAppId =
      config.appSettingsPresetId?.takeIf { id -> current.appSettingsPresets.any { it.id == id } }
        ?: current.appSettingsPresets.firstOrNull()?.id

    // Normalize distinct endpoint slots
    val pId = config.primaryEndpointId?.takeIf { id -> current.endpointPresets.any { it.id == id } }
    val sId =
      config.secondaryEndpointId
        ?.takeIf { it != pId && current.endpointPresets.any { ep -> ep.id == it } }
    val tId =
      config.tertiaryEndpointId
        ?.takeIf { it != pId && it != sId && current.endpointPresets.any { ep -> ep.id == it } }

    val sanitized =
      config.copy(
        customName = config.customName.trim(),
        primaryEndpointId = pId,
        secondaryEndpointId = sId,
        tertiaryEndpointId = tId,
        appSettingsPresetId = resolvedAppId,
      )

    val idx = current.configurationPresets.indexOfFirst { it.id == sanitized.id }
    val updatedConfigs =
      if (idx >= 0) {
        current.configurationPresets.toMutableList().apply { set(idx, sanitized) }
      } else {
        current.configurationPresets + sanitized
      }

    val primaryEp = sanitized.primaryEndpointId?.let { current.endpointById(it) }
    val wasActive = current.activeConfigurationId == sanitized.id
    val nextActiveId =
      when {
        primaryEp == null || !primaryEp.isUsable -> if (wasActive) null else current.activeConfigurationId
        activateIfEligible || wasActive -> sanitized.id
        else -> current.activeConfigurationId
      }

    persistHierarchy(configs = updatedConfigs, activeConfigId = nextActiveId)

    return when {
      (primaryEp == null || primaryEp.endpointInput.isBlank()) && (activateIfEligible || wasActive) ->
        ActivateConfigResult.InvalidEndpoint(sanitized)
      primaryEp != null && primaryEp.isMissingRequiredApiKey && (activateIfEligible || wasActive) ->
        ActivateConfigResult.MissingApiKey(sanitized, primaryEp)
      nextActiveId == sanitized.id ->
        ActivateConfigResult.Activated(
          sanitized,
          sanitized.resolveDisplayName(current.endpointPresets, current.appSettingsPresets),
        )
      else -> null
    }
  }

  fun activateConfigurationPreset(configId: String): ActivateConfigResult {
    val current = _settings.value
    val target =
      current.configurationPresets.firstOrNull { it.id == configId }
        ?: return ActivateConfigResult.InvalidEndpoint(ConfigurationPreset(id = configId))
    val primaryEp = target.primaryEndpointId?.let { current.endpointById(it) }
    if (primaryEp == null || primaryEp.endpointInput.isBlank()) {
      return ActivateConfigResult.InvalidEndpoint(target)
    }
    if (primaryEp.isMissingRequiredApiKey) {
      return ActivateConfigResult.MissingApiKey(target, primaryEp)
    }
    persistHierarchy(activeConfigId = target.id)
    return ActivateConfigResult.Activated(
      target,
      target.resolveDisplayName(current.endpointPresets, current.appSettingsPresets),
    )
  }

  fun deleteConfigurationPreset(configId: String) {
    val current = _settings.value
    val updated = current.configurationPresets.filterNot { it.id == configId }
    val nextActive = if (current.activeConfigurationId == configId) null else current.activeConfigurationId
    persistHierarchy(configs = updated, activeConfigId = nextActive)
  }

  fun saveDetectedEndpointForPreset(
    presetId: String,
    cacheKey: String,
    fullUrl: String,
    family: ServerFamily,
  ) {
    val current = _settings.value
    val updated =
      current.endpointPresets.map { ep ->
        if (ep.id == presetId && ep.cacheKey == cacheKey) {
          ep.copy(
            detectedEndpointUrl = fullUrl,
            detectedFamily = family,
            detectedForInput = cacheKey,
          )
        } else {
          ep
        }
      }
    persistHierarchy(endpoints = updated)
  }

  fun clearDetectedEndpointForPreset(presetId: String) {
    val current = _settings.value
    val updated =
      current.endpointPresets.map { ep ->
        if (ep.id == presetId) {
          ep.copy(detectedEndpointUrl = "", detectedFamily = null, detectedForInput = "")
        } else {
          ep
        }
      }
    persistHierarchy(endpoints = updated)
  }

  fun setTargetAppOnActivePreset(packageName: String, label: String) {
    val activeApp = _settings.value.activeAppPreset ?: return
    saveAppPreset(activeApp.copy(targetPackage = packageName, targetLabel = label))
  }

  fun setMicButtonCapture(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_MIC_BUTTON_CAPTURE, enabled).apply()
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

  fun setLastAssistArgsLog(summary: String) {
    prefs.edit().putString(KEY_LAST_ASSIST_ARGS, summary).apply()
    _settings.value = readFromPrefs()
  }

  fun loadEndpointHealthRecords(): Map<String, EndpointHealthRecord> {
    val raw = prefs.getString(KEY_ENDPOINT_HEALTH_JSON, null)
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
      val json = JSONObject(raw)
      buildMap {
        for (key in json.keys()) {
          val obj = json.optJSONObject(key) ?: continue
          put(key, EndpointHealthRecord.fromJson(obj))
        }
      }
    } catch (_: Exception) {
      emptyMap()
    }
  }

  fun saveEndpointHealthRecords(records: Map<String, EndpointHealthRecord>) {
    val json = JSONObject()
    for ((id, rec) in records) {
      json.put(id, rec.toJson())
    }
    prefs.edit().putString(KEY_ENDPOINT_HEALTH_JSON, json.toString()).apply()
  }

  companion object {
    private const val PREFS_NAME = "tv_voice_search_prefs"
    private const val KEY_ENDPOINTS_JSON = "endpoint_presets_v2_json"
    private const val KEY_APP_PRESETS_JSON = "app_presets_v2_json"
    private const val KEY_CONFIGS_JSON = "config_presets_v2_json"
    private const val KEY_ACTIVE_CONFIG_ID = "active_config_preset_id"
    private const val KEY_ENDPOINT_HEALTH_JSON = "endpoint_health_records_json"

    // Legacy v1 / v2 keys retained for seamless migration
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
    @Volatile private var boundAppContext: Context? = null

    fun get(context: Context): SettingsRepository {
      val appCtx = context.applicationContext
      val current = instance
      if (current != null && boundAppContext === appCtx) {
        return current
      }
      return synchronized(this) {
        val existing = instance
        if (existing != null && boundAppContext === appCtx) {
          existing
        } else {
          SettingsRepository(appCtx).also {
            instance = it
            boundAppContext = appCtx
          }
        }
      }
    }
  }
}
