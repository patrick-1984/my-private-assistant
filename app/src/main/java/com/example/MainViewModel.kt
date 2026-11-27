package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AssistAudioBridge
import com.example.audio.RecordingOutcome
import com.example.audio.VoiceRecorder
import com.example.data.ActivateConfigResult
import com.example.data.AppHandoffStatus
import com.example.data.AppSettings
import com.example.data.AppSettingsPreset
import com.example.data.ConfigurationPreset
import com.example.data.EndpointHealthRecord
import com.example.data.EndpointHealthStatus
import com.example.data.EndpointPreset
import com.example.data.EndpointSlot
import com.example.data.LanguageOption
import com.example.data.MicSource
import com.example.data.SettingsRepository
import com.example.handoff.AppHandoff
import com.example.handoff.HandoffMethod
import com.example.handoff.HandoffResult
import com.example.handoff.InstalledTvApp
import com.example.network.EndpointClient
import com.example.network.EndpointHealthManager
import com.example.network.ProbeResult
import com.example.network.TranscribeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoicePhase {
  IDLE,
  PERMISSION_NEEDED,
  LISTENING,
  TRANSCRIBING,
  OPENING_TARGET,
  ERROR,
}

sealed class TestStatus {
  data object Idle : TestStatus()

  data object Testing : TestStatus()

  data class Ok(val message: String) : TestStatus()

  data class Warning(val message: String) : TestStatus()

  data class Error(val message: String) : TestStatus()
}

sealed class SettingsPage {
  data object Home : SettingsPage()

  data class EditConfiguration(
    val draft: ConfigurationPreset,
    val isNew: Boolean,
  ) : SettingsPage()

  data class EditEndpoint(
    val draft: EndpointPreset,
    val isNew: Boolean,
    val returnToConfigSlot: EndpointSlot? = null,
  ) : SettingsPage()

  data class EditAppPreset(
    val draft: AppSettingsPreset,
    val isNew: Boolean,
    val assignToConfig: Boolean = false,
  ) : SettingsPage()
}

data class MainUiState(
  val phase: VoicePhase = VoicePhase.IDLE,
  val searchText: String = "",
  val statusMessage: String = "",
  val audioLevel: Float = 0f,
  val isSettingsOpen: Boolean = false,
  val settingsPage: SettingsPage = SettingsPage.Home,
  val suspendedConfigDraft: ConfigurationPreset? = null,
  val suspendedConfigIsNew: Boolean = false,
  val settings: AppSettings = AppSettings(),
  val endpointHealthRecords: Map<String, EndpointHealthRecord> = emptyMap(),
  val checkingEndpointIds: Set<String> = emptySet(),
  val activeUsedSlot: EndpointSlot? = null,
  val activeUsedEndpointId: String? = null,
  val profileBanner: String? = null,
  val isProfileBannerError: Boolean = false,
  val activeSourcePackage: String? = null,
  val showFallbackAppPicker: Boolean = false,
  val pendingVerificationApp: InstalledTvApp? = null,
  val testStatus: TestStatus = TestStatus.Idle,
  val availableModels: List<String> = emptyList(),
  val isFetchingModels: Boolean = false,
  val installedApps: List<InstalledTvApp> = emptyList(),
  val isLoadingApps: Boolean = false,
  val assistantStatus: AssistantRoleStatus? = null,
  val copiedBanner: String? = null,
  val shouldCloseApp: Boolean = false,
) {
  val activeConfigDisplayName: String
    get() =
      settings.activeConfiguration?.resolveDisplayName(
        settings.endpointPresets,
        settings.appSettingsPresets,
      ) ?: ""

  val activeEndpointSlot: EndpointSlot
    get() {
      activeUsedSlot?.let { return it }
      val cfg = settings.activeConfiguration ?: return EndpointSlot.PRIMARY
      val usable = cfg.orderedUsableSlots(settings.endpointPresets)
      if (!cfg.allowEndpointFailover || usable.size <= 1) {
        return usable.firstOrNull()?.first ?: EndpointSlot.PRIMARY
      }
      val chosen =
        usable.firstOrNull { (_, ep) ->
          val rec = endpointHealthRecords[ep.id]
          rec == null ||
            rec.fingerprint != ep.healthFingerprint ||
            rec.status != EndpointHealthStatus.DEGRADED
        } ?: usable.first()
      return chosen.first
    }

  val activeEndpointDisplayName: String
    get() {
      activeUsedEndpointId?.let { id ->
        settings.endpointById(id)?.let { return it.displayName }
      }
      val cfg = settings.activeConfiguration ?: return ""
      val slot = activeEndpointSlot
      val ep =
        when (slot) {
          EndpointSlot.PRIMARY -> settings.endpointById(cfg.primaryEndpointId)
          EndpointSlot.SECONDARY -> settings.endpointById(cfg.secondaryEndpointId)
          EndpointSlot.TERTIARY -> settings.endpointById(cfg.tertiaryEndpointId)
        }
      return ep?.displayName ?: settings.activePrimaryEndpoint?.displayName.orEmpty()
    }

  val hasBackupEndpoints: Boolean
    get() {
      val cfg = settings.activeConfiguration ?: return false
      return cfg.allowEndpointFailover && cfg.orderedUsableSlots(settings.endpointPresets).size > 1
    }

  val isBackupEndpointActive: Boolean
    get() = activeEndpointSlot != EndpointSlot.PRIMARY

  val isCheckingEndpointHealth: Boolean
    get() {
      val cfg = settings.activeConfiguration ?: return false
      val configuredIds =
        listOfNotNull(cfg.primaryEndpointId, cfg.secondaryEndpointId, cfg.tertiaryEndpointId)
      return configuredIds.any { it in checkingEndpointIds }
    }

  val effectiveDisplayTargetLabel: String
    get() {
      val src = activeSourcePackage
      if (settings.searchInCurrentApp && !src.isNullOrBlank()) {
        val status = settings.handoffStatusFor(src)
        val matched = installedApps.firstOrNull { it.packageName == src }
        val methodOk =
          matched?.handoffMethod?.isDirectSearchMethod() == true ||
            status == AppHandoffStatus.CONFIRMED
        if (status != AppHandoffStatus.NOT_WORKING && methodOk && matched != null) {
          return matched.label
        }
      }
      return settings.targetLabel
    }

  val fallbackPickerApps: List<InstalledTvApp>
    get() =
      installedApps
        .filter {
          it.handoffMethod != HandoffMethod.NOT_SUPPORTED &&
            settings.handoffStatusFor(it.packageName) != AppHandoffStatus.NOT_WORKING
        }
        .take(6)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val appContext = application.applicationContext
  private val settingsRepo = SettingsRepository.get(appContext)
  private val healthManager = EndpointHealthManager.get(appContext)
  private val endpointClient = EndpointClient()
  private val recorder = VoiceRecorder(appContext)

  private val _uiState =
    MutableStateFlow(
      MainUiState(
        settings = settingsRepo.settings.value,
        endpointHealthRecords = healthManager.records.value,
        checkingEndpointIds = healthManager.checkingEndpointIds.value,
        assistantStatus = AssistantStatusChecker.inspect(appContext),
      )
    )
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  private var listenJob: Job? = null
  private var liveChunkJob: Job? = null
  private var preCheckJob: Job? = null
  private var hasStartedOnce = false

  init {
    viewModelScope.launch {
      settingsRepo.settings.collect { latest ->
        _uiState.update { it.copy(settings = latest) }
      }
    }
    viewModelScope.launch {
      healthManager.records.collect { records ->
        _uiState.update { it.copy(endpointHealthRecords = records) }
      }
    }
    viewModelScope.launch {
      healthManager.checkingEndpointIds.collect { checking ->
        _uiState.update { it.copy(checkingEndpointIds = checking) }
      }
    }
    loadInstalledApps()
  }

  fun onPermissionChecked(
    granted: Boolean,
    forceStart: Boolean = false,
    sourcePackage: String? = null,
  ) {
    if (!granted) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.PERMISSION_NEEDED,
          activeSourcePackage = sourcePackage ?: it.activeSourcePackage,
          statusMessage = "Microphone permission is required for voice search.",
        )
      }
      return
    }

    if ((forceStart || !hasStartedOnce) && !_uiState.value.isSettingsOpen) {
      hasStartedOnce = true
      if (sourcePackage != null) {
        _uiState.update { it.copy(activeSourcePackage = sourcePackage) }
      }
      if (settingsRepo.settings.value.orderedActiveEndpoints().isEmpty()) {
        _uiState.update {
          it.copy(
            phase = VoicePhase.ERROR,
            statusMessage = "No active configuration preset. Open Settings to configure.",
          )
        }
      } else {
        startListening()
      }
    }
  }

  fun onAssistLaunch(sourcePackage: String? = null) {
    hasStartedOnce = true
    _uiState.update {
      it.copy(
        isSettingsOpen = false,
        activeSourcePackage = sourcePackage,
        showFallbackAppPicker = false,
      )
    }
    if (settingsRepo.settings.value.orderedActiveEndpoints().isEmpty()) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.ERROR,
          statusMessage = "No active configuration preset. Open Settings to configure.",
        )
      }
      return
    }
    startListening()
  }

  fun onMicButtonPressed(hasPermission: Boolean) {
    if (!hasPermission) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.PERMISSION_NEEDED,
          statusMessage = "Grant microphone permission to record.",
        )
      }
      return
    }
    when (_uiState.value.phase) {
      VoicePhase.LISTENING -> recorder.requestStop()
      VoicePhase.TRANSCRIBING, VoicePhase.OPENING_TARGET -> Unit
      else -> startListening()
    }
  }

  fun startListening() {
    if (_uiState.value.phase == VoicePhase.LISTENING || _uiState.value.phase == VoicePhase.TRANSCRIBING) {
      return
    }
    listenJob?.cancel()
    liveChunkJob?.cancel()
    val remotePfd = AssistAudioBridge.consumeStream()

    val currentSettings = settingsRepo.settings.value
    val activeConfig = currentSettings.activeConfiguration
    val activeAppPreset = currentSettings.activeAppPreset

    // Kick off non-blocking health pre-check (1h TTL) or degraded primary recovery right as mic opens
    preCheckJob =
      if (activeConfig != null) {
        healthManager.onRecordingStarted(
          config = activeConfig,
          endpoints = currentSettings.endpointPresets,
          appPreset = activeAppPreset,
        )
      } else {
        null
      }

    val initialSelection =
      healthManager.selectLeadingEndpoint(activeConfig, currentSettings.endpointPresets)

    listenJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            phase = VoicePhase.LISTENING,
            searchText = "",
            statusMessage = "Listening…",
            activeUsedSlot = initialSelection?.slot,
            activeUsedEndpointId = initialSelection?.endpoint?.id,
            showFallbackAppPicker = false,
            audioLevel = 0f,
          )
        }

        val levelCollector =
          launch {
            recorder.level.collect { lvl ->
              _uiState.update { state -> state.copy(audioLevel = lvl) }
            }
          }

        val onChunkCallback: ((ByteArray) -> Unit) = { wavSnapshot ->
          val latestSettings = settingsRepo.settings.value
          val latestConfig = latestSettings.activeConfiguration
          val selection =
            healthManager.selectLeadingEndpoint(latestConfig, latestSettings.endpointPresets)
          if (
            selection != null &&
              selection.endpoint.liveTranscription &&
              liveChunkJob?.isActive != true &&
              _uiState.value.phase == VoicePhase.LISTENING
          ) {
            val targetEndpoint = selection.endpoint
            val targetSlot = selection.slot
            liveChunkJob =
              viewModelScope.launch {
                _uiState.update {
                  it.copy(activeUsedSlot = targetSlot, activeUsedEndpointId = targetEndpoint.id)
                }
                val probeSettings = targetEndpoint.toProbeSettings(latestSettings.activeAppPreset)
                val partial =
                  endpointClient.transcribe(
                    wavBytes = wavSnapshot,
                    settings = probeSettings,
                    onEndpointDetected = { url, family ->
                      settingsRepo.saveDetectedEndpointForPreset(
                        targetEndpoint.id,
                        targetEndpoint.cacheKey,
                        url,
                        family,
                      )
                    },
                    onEndpointInvalidated = {},
                    onPartialText = { deltaText ->
                      if (_uiState.value.phase == VoicePhase.LISTENING && deltaText.isNotBlank()) {
                        _uiState.update { it.copy(searchText = deltaText) }
                      }
                    },
                  )
                when (partial) {
                  is TranscribeResult.Success -> {
                    // Resets the 1-hour health timer immediately
                    healthManager.recordSuccess(targetEndpoint)
                    if (
                      partial.text.isNotBlank() &&
                        _uiState.value.phase == VoicePhase.LISTENING
                    ) {
                      _uiState.update { it.copy(searchText = partial.text.trim()) }
                    }
                  }
                  is TranscribeResult.Failure -> {
                    if (latestConfig?.allowEndpointFailover == true) {
                      healthManager.recordFailure(targetEndpoint, partial.message)
                      val nextSel =
                        healthManager.selectLeadingEndpoint(
                          latestConfig,
                          latestSettings.endpointPresets,
                        )
                      if (nextSel != null) {
                        _uiState.update {
                          it.copy(
                            activeUsedSlot = nextSel.slot,
                            activeUsedEndpointId = nextSel.endpoint.id,
                          )
                        }
                      }
                    }
                  }
                }
              }
          }
        }

        val recOutcome =
          try {
            recorder.record(
              micSource = currentSettings.micSource,
              remoteStreamPfd = remotePfd,
              onAudioChunk = onChunkCallback,
            )
          } finally {
            levelCollector.cancel()
            liveChunkJob?.cancel()
          }

        when (recOutcome) {
          is RecordingOutcome.Error -> {
            _uiState.update {
              it.copy(
                phase = VoicePhase.ERROR,
                statusMessage = recOutcome.message,
                audioLevel = 0f,
              )
            }
          }
          is RecordingOutcome.Success -> {
            transcribeAndHandoff(recOutcome.wavBytes)
          }
        }
      }
  }

  private suspend fun transcribeAndHandoff(wavBytes: ByteArray) {
    // If primary itself was being pre-checked (stale >1h), wait cleanly for that short probe so
    // an offline primary doesn't waste an extra round-trip. Backup paths never block on primary!
    val pendingPreCheck = preCheckJob
    if (pendingPreCheck != null && pendingPreCheck.isActive) {
      pendingPreCheck.join()
    }

    val currentSettings = settingsRepo.settings.value
    val activeConfig = currentSettings.activeConfiguration
    val activeAppPreset = currentSettings.activeAppPreset
    val plan = healthManager.orderedFailoverPlan(activeConfig, currentSettings.endpointPresets)

    if (plan.isEmpty()) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.ERROR,
          statusMessage = "No usable endpoint configured. Open Settings.",
          audioLevel = 0f,
        )
      }
      return
    }

    var lastFailure: TranscribeResult.Failure? = null
    var successfulResult: TranscribeResult.Success? = null
    var successfulSlot: EndpointSlot = plan.first().first

    for ((index, slotPair) in plan.withIndex()) {
      val (slot, endpoint) = slotPair
      val isFallbackAttempt = index > 0
      _uiState.update {
        it.copy(
          phase = VoicePhase.TRANSCRIBING,
          activeUsedSlot = slot,
          activeUsedEndpointId = endpoint.id,
          statusMessage =
            if (isFallbackAttempt) "Switching to ${slot.label}: ${endpoint.displayName}…"
            else "Transcribing via ${endpoint.displayName}…",
          audioLevel = 0f,
        )
      }

      val probeSettings = endpoint.toProbeSettings(activeAppPreset)
      val result =
        endpointClient.transcribe(
          wavBytes = wavBytes,
          settings = probeSettings,
          onEndpointDetected = { url, family ->
            settingsRepo.saveDetectedEndpointForPreset(
              endpoint.id,
              endpoint.cacheKey,
              url,
              family,
            )
          },
          onEndpointInvalidated = {
            settingsRepo.clearDetectedEndpointForPreset(endpoint.id)
          },
          onPartialText = { liveDelta ->
            if (_uiState.value.phase == VoicePhase.TRANSCRIBING && liveDelta.isNotBlank()) {
              _uiState.update { it.copy(searchText = liveDelta) }
            }
          },
        )

      when (result) {
        is TranscribeResult.Success -> {
          healthManager.recordSuccess(endpoint)
          successfulResult = result
          successfulSlot = slot
          // If a backup (Secondary/Tertiary) handled the request, ensure Primary is checked
          // in the background so it can recover automatically once back online.
          healthManager.schedulePrimaryRecoveryIfUsingBackup(
            config = activeConfig,
            endpoints = currentSettings.endpointPresets,
            appPreset = activeAppPreset,
            usedSlot = slot,
          )
          break
        }
        is TranscribeResult.Failure -> {
          healthManager.recordFailure(endpoint, result.message)
          lastFailure = result
        }
      }
    }

    val finalSuccess = successfulResult
    if (finalSuccess == null) {
      val prefix =
        if (plan.size > 1) "All ${plan.size} endpoints failed. Last error: "
        else ""
      _uiState.update {
        it.copy(
          phase = VoicePhase.ERROR,
          statusMessage = "$prefix${lastFailure?.message ?: "Transcription failed."}",
        )
      }
      return
    }

    val text = finalSuccess.text.trim()
    if (text.isEmpty()) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.ERROR,
          activeUsedSlot = successfulSlot,
          statusMessage = "No speech detected. Try again or type below.",
        )
      }
    } else {
      val targetDisplay = _uiState.value.effectiveDisplayTargetLabel
      _uiState.update {
        it.copy(
          searchText = text,
          phase = VoicePhase.OPENING_TARGET,
          activeUsedSlot = successfulSlot,
          statusMessage = "Opening $targetDisplay…",
        )
      }
      launchTarget(text)
    }
  }

  fun updateSearchText(text: String) {
    _uiState.update { it.copy(searchText = text, showFallbackAppPicker = false) }
  }

  fun submitManualSearch() {
    val query = _uiState.value.searchText.trim()
    if (query.isEmpty()) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.ERROR,
          statusMessage = "Type a search query or press the mic button.",
        )
      }
      return
    }
    launchTarget(query)
  }

  private fun launchTarget(query: String) {
    val settings = settingsRepo.settings.value
    val sourcePackage = _uiState.value.activeSourcePackage
    val outcome =
      AppHandoff.launchWithFallback(
        context = appContext,
        sourcePackage = sourcePackage,
        searchInCurrentApp = settings.searchInCurrentApp,
        defaultPackage = settings.targetPackage,
        defaultLabel = settings.targetLabel,
        appStatuses = settings.appHandoffStatuses,
        rawQuery = query,
      )
    when (outcome) {
      is HandoffResult.Launched -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.IDLE,
            statusMessage = "Sent to ${outcome.appLabel}",
            showFallbackAppPicker = false,
            shouldCloseApp = true,
          )
        }
      }
      is HandoffResult.Failed -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.ERROR,
            statusMessage = outcome.message,
            showFallbackAppPicker = true,
          )
        }
      }
    }
  }

  fun launchFallbackApp(app: InstalledTvApp) {
    val query = _uiState.value.searchText.trim()
    if (query.isEmpty()) return
    when (val outcome = AppHandoff.launchSearch(appContext, app.packageName, query, app.label)) {
      is HandoffResult.Launched -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.IDLE,
            statusMessage = "Sent to ${outcome.appLabel}",
            showFallbackAppPicker = false,
            shouldCloseApp = true,
          )
        }
      }
      is HandoffResult.Failed -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.ERROR,
            statusMessage = outcome.message,
            showFallbackAppPicker = true,
          )
        }
      }
    }
  }

  fun onAppClosedHandled() {
    _uiState.update { it.copy(shouldCloseApp = false) }
  }

  // --- Settings Navigation & Lifecycle ---

  fun openSettings() {
    if (_uiState.value.phase == VoicePhase.LISTENING) {
      recorder.requestStop()
      listenJob?.cancel()
      liveChunkJob?.cancel()
    }
    refreshAssistantStatus()
    loadInstalledApps()

    val current = settingsRepo.settings.value
    val needsFirstTimeEndpoint =
      current.activeConfiguration == null && current.endpointPresets.none { it.isUsable }
    val initialPage: SettingsPage =
      if (needsFirstTimeEndpoint) {
        SettingsPage.EditEndpoint(draft = EndpointPreset(), isNew = true)
      } else {
        SettingsPage.Home
      }

    _uiState.update {
      it.copy(
        isSettingsOpen = true,
        phase = VoicePhase.IDLE,
        settingsPage = initialPage,
        suspendedConfigDraft = null,
        suspendedConfigIsNew = false,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = null,
        isProfileBannerError = false,
        copiedBanner = null,
      )
    }
  }

  fun navigateSettingsBack() {
    val page = _uiState.value.settingsPage
    val suspended = _uiState.value.suspendedConfigDraft
    when (page) {
      SettingsPage.Home -> closeSettings()
      is SettingsPage.EditEndpoint, is SettingsPage.EditAppPreset -> {
        if (suspended != null) {
          _uiState.update {
            it.copy(
              settingsPage =
                SettingsPage.EditConfiguration(
                  draft = suspended,
                  isNew = it.suspendedConfigIsNew,
                ),
              suspendedConfigDraft = null,
              suspendedConfigIsNew = false,
              testStatus = TestStatus.Idle,
              availableModels = emptyList(),
              pendingVerificationApp = null,
            )
          }
        } else {
          _uiState.update {
            it.copy(
              settingsPage = SettingsPage.Home,
              testStatus = TestStatus.Idle,
              availableModels = emptyList(),
              pendingVerificationApp = null,
            )
          }
        }
      }
      is SettingsPage.EditConfiguration -> {
        _uiState.update {
          it.copy(
            settingsPage = SettingsPage.Home,
            suspendedConfigDraft = null,
            suspendedConfigIsNew = false,
            pendingVerificationApp = null,
          )
        }
      }
    }
  }

  fun closeSettings() {
    _uiState.update {
      it.copy(
        isSettingsOpen = false,
        settingsPage = SettingsPage.Home,
        suspendedConfigDraft = null,
        suspendedConfigIsNew = false,
        pendingVerificationApp = null,
        profileBanner = null,
        isProfileBannerError = false,
        copiedBanner = null,
        statusMessage = "",
      )
    }
  }

  // --- Configuration Preset CRUD ---

  fun startNewConfigurationDraft() {
    val current = settingsRepo.settings.value
    val firstUsable =
      current.endpointPresets.firstOrNull { it.isUsable } ?: current.endpointPresets.firstOrNull()
    val firstApp = current.appSettingsPresets.firstOrNull()
    val draft =
      ConfigurationPreset(
        primaryEndpointId = firstUsable?.id,
        appSettingsPresetId = firstApp?.id,
        allowEndpointFailover = true,
      )
    _uiState.update {
      it.copy(
        settingsPage = SettingsPage.EditConfiguration(draft = draft, isNew = true),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun editConfiguration(config: ConfigurationPreset) {
    _uiState.update {
      it.copy(
        settingsPage = SettingsPage.EditConfiguration(draft = config, isNew = false),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun updateConfigDraftName(name: String) {
    updateConfigDraft { it.copy(customName = name) }
  }

  fun setConfigDraftFailover(allow: Boolean) {
    updateConfigDraft { it.copy(allowEndpointFailover = allow) }
  }

  fun selectConfigDraftSlot(slot: EndpointSlot, endpointId: String?) {
    updateConfigDraft { cfg ->
      when (slot) {
        EndpointSlot.PRIMARY -> {
          val newSecondary = if (cfg.secondaryEndpointId == endpointId) null else cfg.secondaryEndpointId
          val newTertiary = if (cfg.tertiaryEndpointId == endpointId) null else cfg.tertiaryEndpointId
          cfg.copy(
            primaryEndpointId = endpointId,
            secondaryEndpointId = newSecondary,
            tertiaryEndpointId = newTertiary,
          )
        }
        EndpointSlot.SECONDARY -> {
          val cleanId = if (endpointId == cfg.primaryEndpointId) null else endpointId
          val newTertiary = if (cleanId != null && cfg.tertiaryEndpointId == cleanId) null else cfg.tertiaryEndpointId
          cfg.copy(secondaryEndpointId = cleanId, tertiaryEndpointId = newTertiary)
        }
        EndpointSlot.TERTIARY -> {
          val cleanId =
            if (endpointId == cfg.primaryEndpointId || endpointId == cfg.secondaryEndpointId) null
            else endpointId
          cfg.copy(tertiaryEndpointId = cleanId)
        }
      }
    }
  }

  fun selectConfigDraftAppPreset(appPresetId: String) {
    updateConfigDraft { it.copy(appSettingsPresetId = appPresetId) }
  }

  private inline fun updateConfigDraft(transform: (ConfigurationPreset) -> ConfigurationPreset) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditConfiguration ?: return@update state
      state.copy(settingsPage = page.copy(draft = transform(page.draft)))
    }
  }

  fun saveConfigurationDraftDone() {
    val page = _uiState.value.settingsPage as? SettingsPage.EditConfiguration ?: return
    val draft = page.draft
    if (draft.primaryEndpointId.isNullOrBlank()) {
      _uiState.update {
        it.copy(
          profileBanner = "Select a Primary transcription endpoint first.",
          isProfileBannerError = true,
        )
      }
      return
    }

    val current = settingsRepo.settings.value
    val name = draft.resolveDisplayName(current.endpointPresets, current.appSettingsPresets)
    val outcome = settingsRepo.saveConfigurationPreset(draft, activateIfEligible = true)
    applyConfigActivationBanner(outcome, prefix = "Saved", fallbackName = name)
  }

  fun activateConfiguration(config: ConfigurationPreset) {
    val outcome = settingsRepo.activateConfigurationPreset(config.id)
    applyConfigActivationBanner(outcome, prefix = "Active", fallbackName = null)
  }

  fun deleteConfiguration(config: ConfigurationPreset) {
    val current = settingsRepo.settings.value
    val name = config.resolveDisplayName(current.endpointPresets, current.appSettingsPresets)
    settingsRepo.deleteConfigurationPreset(config.id)
    _uiState.update { state ->
      val nextPage =
        if ((state.settingsPage as? SettingsPage.EditConfiguration)?.draft?.id == config.id) {
          SettingsPage.Home
        } else {
          state.settingsPage
        }
      state.copy(
        settingsPage = nextPage,
        profileBanner = "Deleted configuration \"$name\"",
        isProfileBannerError = false,
      )
    }
  }

  private fun applyConfigActivationBanner(
    outcome: ActivateConfigResult?,
    prefix: String,
    fallbackName: String?,
  ) {
    when (outcome) {
      is ActivateConfigResult.Activated -> {
        _uiState.update {
          it.copy(
            settingsPage = SettingsPage.Home,
            suspendedConfigDraft = null,
            suspendedConfigIsNew = false,
            profileBanner =
              if (prefix == "Active") "Active preset: ${outcome.displayName}"
              else "Saved & activated \"${outcome.displayName}\"",
            isProfileBannerError = false,
          )
        }
      }
      is ActivateConfigResult.MissingApiKey -> {
        _uiState.update {
          it.copy(
            settingsPage = SettingsPage.Home,
            suspendedConfigDraft = null,
            suspendedConfigIsNew = false,
            profileBanner =
              "API key needed for \"${outcome.endpoint.displayName}\". Edit that endpoint to add an API key or check 'API key not needed'.",
            isProfileBannerError = true,
          )
        }
      }
      is ActivateConfigResult.InvalidEndpoint -> {
        val label = fallbackName ?: "preset"
        _uiState.update {
          it.copy(
            settingsPage = SettingsPage.Home,
            suspendedConfigDraft = null,
            suspendedConfigIsNew = false,
            profileBanner = "Primary endpoint URL is missing for \"$label\".",
            isProfileBannerError = true,
          )
        }
      }
      null -> {
        _uiState.update {
          it.copy(
            settingsPage = SettingsPage.Home,
            suspendedConfigDraft = null,
            suspendedConfigIsNew = false,
            profileBanner = "Saved \"${fallbackName ?: "configuration"}\"",
            isProfileBannerError = false,
          )
        }
      }
    }
  }

  // --- Endpoint Preset CRUD ---

  fun startNewEndpointDraft(assignToConfigSlot: EndpointSlot? = null) {
    val currentPage = _uiState.value.settingsPage
    val suspendedCfg = (currentPage as? SettingsPage.EditConfiguration)?.draft
    val suspendedIsNew = (currentPage as? SettingsPage.EditConfiguration)?.isNew ?: false
    _uiState.update {
      it.copy(
        settingsPage =
          SettingsPage.EditEndpoint(
            draft = EndpointPreset(),
            isNew = true,
            returnToConfigSlot = assignToConfigSlot,
          ),
        suspendedConfigDraft = suspendedCfg ?: it.suspendedConfigDraft,
        suspendedConfigIsNew = if (suspendedCfg != null) suspendedIsNew else it.suspendedConfigIsNew,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun editEndpoint(endpoint: EndpointPreset) {
    _uiState.update {
      it.copy(
        settingsPage = SettingsPage.EditEndpoint(draft = endpoint, isNew = false),
        suspendedConfigDraft = null,
        suspendedConfigIsNew = false,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
    if (endpoint.hasValidCachedEndpoint) {
      refreshAvailableModels()
    }
  }

  fun deleteEndpoint(endpoint: EndpointPreset) {
    settingsRepo.deleteEndpointPreset(endpoint.id)
    _uiState.update {
      it.copy(
        profileBanner = "Deleted endpoint \"${endpoint.displayName}\"",
        isProfileBannerError = false,
      )
    }
  }

  fun checkEndpointHealthNow(endpoint: EndpointPreset) {
    viewModelScope.launch {
      val appPreset = settingsRepo.settings.value.activeAppPreset
      healthManager.checkEndpointNow(endpoint, appPreset) { key, url, family ->
        settingsRepo.saveDetectedEndpointForPreset(endpoint.id, key, url, family)
      }
    }
  }

  fun saveEndpointDraftDone() {
    val page = _uiState.value.settingsPage as? SettingsPage.EditEndpoint ?: return
    val draft = page.draft
    if (draft.endpointInput.isBlank() && !(draft.useIpOverride && draft.overrideIp.isNotBlank())) {
      _uiState.update {
        it.copy(testStatus = TestStatus.Error("Enter a transcription endpoint host or URL first."))
      }
      return
    }

    val suspendedCfg = _uiState.value.suspendedConfigDraft
    val autoActivate = suspendedCfg == null && page.returnToConfigSlot == null
    val outcome = settingsRepo.saveEndpointPreset(draft, autoActivateIfNoActiveConfig = autoActivate)

    if (suspendedCfg != null) {
      val slot = page.returnToConfigSlot ?: EndpointSlot.PRIMARY
      val updatedCfg =
        when (slot) {
          EndpointSlot.PRIMARY -> suspendedCfg.copy(primaryEndpointId = draft.id)
          EndpointSlot.SECONDARY -> suspendedCfg.copy(secondaryEndpointId = draft.id)
          EndpointSlot.TERTIARY -> suspendedCfg.copy(tertiaryEndpointId = draft.id)
        }
      _uiState.update {
        it.copy(
          settingsPage =
            SettingsPage.EditConfiguration(
              draft = updatedCfg,
              isNew = it.suspendedConfigIsNew,
            ),
          suspendedConfigDraft = null,
          suspendedConfigIsNew = false,
          testStatus = TestStatus.Idle,
          availableModels = emptyList(),
          profileBanner = "Saved endpoint \"${draft.displayName}\"",
          isProfileBannerError = false,
        )
      }
      return
    }

    val bannerText =
      when (outcome) {
        is ActivateConfigResult.Activated ->
          "Saved endpoint \"${draft.displayName}\" & activated \"${outcome.displayName}\""
        is ActivateConfigResult.MissingApiKey ->
          "Saved \"${draft.displayName}\" as inactive — API key needed to activate."
        else -> "Saved endpoint \"${draft.displayName}\""
      }

    _uiState.update {
      it.copy(
        settingsPage = SettingsPage.Home,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = bannerText,
        isProfileBannerError = outcome is ActivateConfigResult.MissingApiKey,
      )
    }
  }

  private inline fun updateEndpointDraft(transform: (EndpointPreset) -> EndpointPreset) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditEndpoint ?: return@update state
      state.copy(settingsPage = page.copy(draft = transform(page.draft)))
    }
  }

  fun updateEndpointName(name: String) {
    updateEndpointDraft { it.copy(customName = name) }
  }

  fun updateEndpointInput(input: String) {
    val trimmed = input.trimStart()
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditEndpoint ?: return@update state
      val draft = page.draft
      val nextCacheKey =
        "${trimmed.trim()}|${draft.useIpOverride}|${draft.overrideIp.trim()}|${draft.ignoreSsl}"
      val keepDetected = draft.detectedForInput == nextCacheKey
      state.copy(
        settingsPage =
          page.copy(
            draft =
              draft.copy(
                endpointInput = trimmed,
                detectedEndpointUrl = if (keepDetected) draft.detectedEndpointUrl else "",
                detectedFamily = if (keepDetected) draft.detectedFamily else null,
                detectedForInput = if (keepDetected) draft.detectedForInput else "",
              )
          ),
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
      )
    }
  }

  fun setUseIpOverride(enabled: Boolean) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditEndpoint ?: return@update state
      state.copy(
        settingsPage =
          page.copy(
            draft =
              page.draft.copy(
                useIpOverride = enabled,
                detectedEndpointUrl = "",
                detectedFamily = null,
                detectedForInput = "",
              )
          ),
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
      )
    }
  }

  fun updateOverrideIp(ip: String) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditEndpoint ?: return@update state
      state.copy(
        settingsPage =
          page.copy(
            draft =
              page.draft.copy(
                overrideIp = ip,
                detectedEndpointUrl = "",
                detectedFamily = null,
                detectedForInput = "",
              )
          ),
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
      )
    }
  }

  fun setIgnoreSsl(enabled: Boolean) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditEndpoint ?: return@update state
      state.copy(
        settingsPage =
          page.copy(
            draft =
              page.draft.copy(
                ignoreSsl = enabled,
                detectedEndpointUrl = "",
                detectedFamily = null,
                detectedForInput = "",
              )
          ),
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
      )
    }
  }

  fun setApiKeyNotNeeded(notNeeded: Boolean) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditEndpoint ?: return@update state
      state.copy(
        settingsPage = page.copy(draft = page.draft.copy(apiKeyNotNeeded = notNeeded)),
        testStatus = TestStatus.Idle,
      )
    }
  }

  fun setApiKey(apiKey: String) {
    updateEndpointDraft { it.copy(apiKey = apiKey.trim()) }
  }

  fun setUseCustomModel(enabled: Boolean) {
    updateEndpointDraft { it.copy(useCustomModel = enabled) }
    if (enabled && _uiState.value.availableModels.isEmpty()) {
      refreshAvailableModels()
    }
  }

  fun setModelName(modelName: String) {
    updateEndpointDraft { it.copy(modelName = modelName) }
  }

  fun setLiveTranscription(enabled: Boolean) {
    updateEndpointDraft { it.copy(liveTranscription = enabled) }
  }

  // --- App Settings Preset CRUD ---

  fun startNewAppPresetDraft(assignToConfig: Boolean = false) {
    val currentPage = _uiState.value.settingsPage
    val suspendedCfg = (currentPage as? SettingsPage.EditConfiguration)?.draft
    val suspendedIsNew = (currentPage as? SettingsPage.EditConfiguration)?.isNew ?: false
    val current = settingsRepo.settings.value
    _uiState.update {
      it.copy(
        settingsPage =
          SettingsPage.EditAppPreset(
            draft =
              AppSettingsPreset(
                targetPackage = current.targetPackage,
                targetLabel = current.targetLabel,
                searchInCurrentApp = current.searchInCurrentApp,
                language = current.language,
                micSource = current.micSource,
              ),
            isNew = true,
            assignToConfig = assignToConfig,
          ),
        suspendedConfigDraft = suspendedCfg ?: it.suspendedConfigDraft,
        suspendedConfigIsNew = if (suspendedCfg != null) suspendedIsNew else it.suspendedConfigIsNew,
        pendingVerificationApp = null,
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun editAppPreset(preset: AppSettingsPreset) {
    _uiState.update {
      it.copy(
        settingsPage = SettingsPage.EditAppPreset(draft = preset, isNew = false),
        suspendedConfigDraft = null,
        suspendedConfigIsNew = false,
        pendingVerificationApp = null,
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun deleteAppPreset(preset: AppSettingsPreset) {
    settingsRepo.deleteAppPreset(preset.id)
    _uiState.update {
      it.copy(
        profileBanner = "Deleted app preset \"${preset.displayName}\"",
        isProfileBannerError = false,
      )
    }
  }

  fun saveAppPresetDraftDone() {
    val page = _uiState.value.settingsPage as? SettingsPage.EditAppPreset ?: return
    val saved = settingsRepo.saveAppPreset(page.draft)
    val suspendedCfg = _uiState.value.suspendedConfigDraft
    if (suspendedCfg != null) {
      val updatedCfg =
        if (page.assignToConfig) suspendedCfg.copy(appSettingsPresetId = saved.id)
        else suspendedCfg
      _uiState.update {
        it.copy(
          settingsPage =
            SettingsPage.EditConfiguration(
              draft = updatedCfg,
              isNew = it.suspendedConfigIsNew,
            ),
          suspendedConfigDraft = null,
          suspendedConfigIsNew = false,
          pendingVerificationApp = null,
          profileBanner = "Saved app preset \"${saved.displayName}\"",
          isProfileBannerError = false,
        )
      }
      return
    }

    _uiState.update {
      it.copy(
        settingsPage = SettingsPage.Home,
        pendingVerificationApp = null,
        profileBanner = "Saved app preset \"${saved.displayName}\"",
        isProfileBannerError = false,
      )
    }
  }

  private inline fun updateAppPresetDraft(transform: (AppSettingsPreset) -> AppSettingsPreset) {
    _uiState.update { state ->
      val page = state.settingsPage as? SettingsPage.EditAppPreset ?: return@update state
      state.copy(settingsPage = page.copy(draft = transform(page.draft)))
    }
  }

  fun updateAppPresetName(name: String) {
    updateAppPresetDraft { it.copy(customName = name) }
  }

  fun setSearchInCurrentApp(enabled: Boolean) {
    updateAppPresetDraft { it.copy(searchInCurrentApp = enabled) }
  }

  fun selectTargetApp(app: InstalledTvApp) {
    updateAppPresetDraft { it.copy(targetPackage = app.packageName, targetLabel = app.label) }
  }

  fun setLanguage(language: LanguageOption) {
    updateAppPresetDraft { it.copy(language = language) }
  }

  fun setMicSource(micSource: MicSource) {
    updateAppPresetDraft { it.copy(micSource = micSource) }
  }

  fun setMicButtonCapture(enabled: Boolean) {
    settingsRepo.setMicButtonCapture(enabled)
  }

  fun testAppSearch(app: InstalledTvApp) {
    val result = AppHandoff.launchSearch(appContext, app.packageName, "test search", app.label)
    when (result) {
      is HandoffResult.Launched -> {
        _uiState.update { it.copy(pendingVerificationApp = app) }
      }
      is HandoffResult.Failed -> {
        settingsRepo.setAppHandoffStatus(app.packageName, AppHandoffStatus.NOT_WORKING)
        _uiState.update {
          it.copy(
            pendingVerificationApp = null,
            copiedBanner = "${app.label}: marked Not working",
          )
        }
      }
    }
  }

  fun confirmAppTestResult(worked: Boolean) {
    val app = _uiState.value.pendingVerificationApp ?: return
    val status = if (worked) AppHandoffStatus.CONFIRMED else AppHandoffStatus.NOT_WORKING
    settingsRepo.setAppHandoffStatus(app.packageName, status)
    _uiState.update {
      it.copy(
        pendingVerificationApp = null,
        copiedBanner = "${app.label}: ${status.label}",
      )
    }
  }

  fun dismissPendingVerification() {
    _uiState.update { it.copy(pendingVerificationApp = null) }
  }

  fun toggleNeverUseApp(app: InstalledTvApp) {
    val current = settingsRepo.settings.value.handoffStatusFor(app.packageName)
    val next =
      if (current == AppHandoffStatus.NOT_WORKING) AppHandoffStatus.UNKNOWN
      else AppHandoffStatus.NOT_WORKING
    settingsRepo.setAppHandoffStatus(app.packageName, next)
    if (_uiState.value.pendingVerificationApp?.packageName == app.packageName) {
      _uiState.update { it.copy(pendingVerificationApp = null) }
    }
  }

  fun refreshAvailableModels() {
    val page = _uiState.value.settingsPage as? SettingsPage.EditEndpoint ?: return
    val probeTarget = page.draft.toProbeSettings()
    if (probeTarget.endpointInput.isBlank()) return
    viewModelScope.launch {
      _uiState.update { it.copy(isFetchingModels = true) }
      val models =
        endpointClient.fetchModels(
          detectedUrl = probeTarget.detectedEndpointUrl,
          rawInput = probeTarget.endpointInput,
          apiKey = probeTarget.effectiveApiKey,
          useIpOverride = probeTarget.useIpOverride,
          overrideIp = probeTarget.overrideIp,
          ignoreSsl = probeTarget.ignoreSsl,
        )
      _uiState.update { it.copy(availableModels = models, isFetchingModels = false) }
    }
  }

  fun testEndpoint() {
    val page = _uiState.value.settingsPage as? SettingsPage.EditEndpoint ?: return
    val draft = page.draft
    val current = draft.toProbeSettings()
    if (current.endpointInput.isBlank()) {
      _uiState.update {
        it.copy(testStatus = TestStatus.Error("Enter an endpoint host or URL first."))
      }
      return
    }
    if (current.useApiKey && current.apiKey.isBlank()) {
      _uiState.update {
        it.copy(
          testStatus =
            TestStatus.Error("API key needed — enter an API key or check 'API key not needed'.")
        )
      }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(testStatus = TestStatus.Testing) }
      val probe =
        endpointClient.detectEndpoint(
          rawInput = current.endpointInput,
          apiKey = current.effectiveApiKey,
          modelName = current.effectiveModelName,
          language = current.language,
          useIpOverride = current.useIpOverride,
          overrideIp = current.overrideIp,
          ignoreSsl = current.ignoreSsl,
        )
      when (probe) {
        is ProbeResult.Success -> {
          val updatedDraft =
            draft.copy(
              detectedEndpointUrl = probe.url,
              detectedFamily = probe.family,
              detectedForInput = draft.cacheKey,
            )
          healthManager.recordSuccess(updatedDraft)
          _uiState.update { state ->
            val currPage = state.settingsPage as? SettingsPage.EditEndpoint
            val nextPage =
              if (currPage != null && currPage.draft.id == draft.id) {
                currPage.copy(
                  draft =
                    currPage.draft.copy(
                      detectedEndpointUrl = probe.url,
                      detectedFamily = probe.family,
                      detectedForInput = currPage.draft.cacheKey,
                    )
                )
              } else {
                state.settingsPage
              }
            state.copy(
              settingsPage = nextPage,
              testStatus = TestStatus.Ok(probe.message),
              availableModels = probe.availableModels,
            )
          }
        }
        is ProbeResult.NeedsApiKey -> {
          _uiState.update { state ->
            val currPage = state.settingsPage as? SettingsPage.EditEndpoint
            val nextPage =
              if (currPage != null && currPage.draft.id == draft.id) {
                currPage.copy(
                  draft =
                    currPage.draft.copy(
                      detectedEndpointUrl = probe.url,
                      detectedFamily = probe.family,
                      detectedForInput = currPage.draft.cacheKey,
                    )
                )
              } else {
                state.settingsPage
              }
            state.copy(
              settingsPage = nextPage,
              testStatus = TestStatus.Warning(probe.message),
            )
          }
        }
        is ProbeResult.Failure -> {
          healthManager.recordFailure(draft, probe.message)
          _uiState.update { it.copy(testStatus = TestStatus.Error(probe.message)) }
        }
      }
    }
  }

  fun refreshAssistantStatus() {
    _uiState.update { it.copy(assistantStatus = AssistantStatusChecker.inspect(appContext)) }
  }

  fun copyToClipboard(label: String, text: String) {
    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    _uiState.update { it.copy(copiedBanner = "Copied $label to clipboard") }
  }

  fun loadInstalledApps() {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isLoadingApps = true) }
      val apps = AppHandoff.listInstalledTvApps(appContext)
      val currentTarget = settingsRepo.settings.value.targetPackage
      val currentInstalled = apps.any { it.packageName == currentTarget }
      if (!currentInstalled && apps.isNotEmpty()) {
        val preferred =
          apps.firstOrNull { it.packageName == AppHandoff.PKG_SMARTTUBE }
            ?: apps.firstOrNull { it.packageName == AppHandoff.PKG_NEWPIPE }
        if (preferred != null) {
          settingsRepo.setTargetAppOnActivePreset(preferred.packageName, preferred.label)
        }
      }
      _uiState.update { it.copy(installedApps = apps, isLoadingApps = false) }
    }
  }

  override fun onCleared() {
    super.onCleared()
    recorder.requestStop()
    listenJob?.cancel()
    liveChunkJob?.cancel()
  }
}
