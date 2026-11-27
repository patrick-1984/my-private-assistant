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
import com.example.data.ActivateProfileResult
import com.example.data.AppHandoffStatus
import com.example.data.AppSettings
import com.example.data.EndpointProfile
import com.example.data.LanguageOption
import com.example.data.MicSource
import com.example.data.SettingsRepository
import com.example.handoff.AppHandoff
import com.example.handoff.HandoffMethod
import com.example.handoff.HandoffResult
import com.example.handoff.InstalledTvApp
import com.example.network.EndpointClient
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

data class MainUiState(
  val phase: VoicePhase = VoicePhase.IDLE,
  val searchText: String = "",
  val statusMessage: String = "",
  val audioLevel: Float = 0f,
  val isSettingsOpen: Boolean = false,
  val settings: AppSettings = AppSettings(),
  val profileDraft: EndpointProfile? = null,
  val isDraftNew: Boolean = false,
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
  private val endpointClient = EndpointClient()
  private val recorder = VoiceRecorder(appContext)

  private val _uiState =
    MutableStateFlow(
      MainUiState(
        settings = settingsRepo.settings.value,
        assistantStatus = AssistantStatusChecker.inspect(appContext),
      )
    )
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  private var listenJob: Job? = null
  private var liveChunkJob: Job? = null
  private var hasStartedOnce = false

  init {
    viewModelScope.launch {
      settingsRepo.settings.collect { latest ->
        _uiState.update { it.copy(settings = latest) }
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
      if (settingsRepo.settings.value.endpointInput.isBlank()) {
        _uiState.update {
          it.copy(
            phase = VoicePhase.ERROR,
            statusMessage = "No active transcription endpoint. Open Settings to configure.",
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
    if (settingsRepo.settings.value.endpointInput.isBlank()) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.ERROR,
          statusMessage = "No active transcription endpoint. Open Settings to configure.",
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

    listenJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            phase = VoicePhase.LISTENING,
            searchText = "",
            statusMessage = "Listening…",
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

        val liveEnabled = settingsRepo.settings.value.liveTranscription
        val onChunkCallback: ((ByteArray) -> Unit)? =
          if (liveEnabled) {
            { wavSnapshot ->
              if (liveChunkJob?.isActive != true && _uiState.value.phase == VoicePhase.LISTENING) {
                val snapshotSettings = settingsRepo.settings.value
                liveChunkJob =
                  viewModelScope.launch {
                    val partial =
                      endpointClient.transcribe(
                        wavBytes = wavSnapshot,
                        settings = snapshotSettings,
                        onEndpointDetected = { url, family ->
                          settingsRepo.saveDetectedEndpoint(
                            snapshotSettings.cacheKey,
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
                    if (
                      partial is TranscribeResult.Success &&
                        partial.text.isNotBlank() &&
                        _uiState.value.phase == VoicePhase.LISTENING
                    ) {
                      _uiState.update { it.copy(searchText = partial.text.trim()) }
                    }
                  }
              }
            }
          } else {
            null
          }

        val recOutcome =
          try {
            recorder.record(
              micSource = settingsRepo.settings.value.micSource,
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
    _uiState.update {
      it.copy(
        phase = VoicePhase.TRANSCRIBING,
        statusMessage = "Transcribing…",
        audioLevel = 0f,
      )
    }

    val currentSettings = settingsRepo.settings.value
    val result =
      endpointClient.transcribe(
        wavBytes = wavBytes,
        settings = currentSettings,
        onEndpointDetected = { url, family ->
          settingsRepo.saveDetectedEndpoint(currentSettings.cacheKey, url, family)
        },
        onEndpointInvalidated = { settingsRepo.clearDetectedEndpoint() },
        onPartialText = { liveDelta ->
          if (_uiState.value.phase == VoicePhase.TRANSCRIBING && liveDelta.isNotBlank()) {
            _uiState.update { it.copy(searchText = liveDelta) }
          }
        },
      )

    when (result) {
      is TranscribeResult.Failure -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.ERROR,
            statusMessage = result.message,
          )
        }
      }
      is TranscribeResult.Success -> {
        val text = result.text.trim()
        if (text.isEmpty()) {
          _uiState.update {
            it.copy(
              phase = VoicePhase.ERROR,
              statusMessage = "No speech detected. Try again or type below.",
            )
          }
        } else {
          val targetDisplay = _uiState.value.effectiveDisplayTargetLabel
          _uiState.update {
            it.copy(
              searchText = text,
              phase = VoicePhase.OPENING_TARGET,
              statusMessage = "Opening $targetDisplay…",
            )
          }
          launchTarget(text)
        }
      }
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

  fun openSettings() {
    if (_uiState.value.phase == VoicePhase.LISTENING) {
      recorder.requestStop()
      listenJob?.cancel()
      liveChunkJob?.cancel()
    }
    refreshAssistantStatus()
    loadInstalledApps()

    val current = settingsRepo.settings.value
    val openInitialNewDraft = current.activeProfile == null
    val initialDraft =
      if (openInitialNewDraft) {
        EndpointProfile(
          targetPackage = current.targetPackage,
          targetLabel = current.targetLabel,
          searchInCurrentApp = current.searchInCurrentApp,
          language = current.language,
          micSource = current.micSource,
        )
      } else {
        null
      }

    if (current.hasValidCachedEndpoint && _uiState.value.availableModels.isEmpty()) {
      refreshAvailableModels()
    }

    _uiState.update {
      it.copy(
        isSettingsOpen = true,
        phase = VoicePhase.IDLE,
        profileDraft = initialDraft,
        isDraftNew = openInitialNewDraft,
        testStatus = TestStatus.Idle,
        profileBanner = null,
        isProfileBannerError = false,
        copiedBanner = null,
      )
    }
  }

  fun closeSettings() {
    _uiState.update {
      it.copy(
        isSettingsOpen = false,
        profileDraft = null,
        isDraftNew = false,
        pendingVerificationApp = null,
        profileBanner = null,
        isProfileBannerError = false,
        copiedBanner = null,
        statusMessage = "",
      )
    }
  }

  fun startNewProfileDraft() {
    val current = settingsRepo.settings.value
    _uiState.update {
      it.copy(
        profileDraft =
          EndpointProfile(
            targetPackage = current.targetPackage,
            targetLabel = current.targetLabel,
            searchInCurrentApp = current.searchInCurrentApp,
            language = current.language,
            micSource = current.micSource,
          ),
        isDraftNew = true,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun editProfile(profile: EndpointProfile) {
    _uiState.update {
      it.copy(
        profileDraft = profile,
        isDraftNew = false,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
    if (profile.hasValidCachedEndpoint) {
      refreshAvailableModels()
    }
  }

  fun cancelProfileDraft() {
    _uiState.update {
      it.copy(
        profileDraft = null,
        isDraftNew = false,
        testStatus = TestStatus.Idle,
        availableModels = emptyList(),
        profileBanner = null,
        isProfileBannerError = false,
      )
    }
  }

  fun saveProfileDraftAndDone() {
    val draft = _uiState.value.profileDraft ?: return
    if (draft.endpointInput.isBlank() && !(draft.useIpOverride && draft.overrideIp.isNotBlank())) {
      _uiState.update {
        it.copy(testStatus = TestStatus.Error("Enter a transcription endpoint host or URL first."))
      }
      return
    }

    val outcome = settingsRepo.saveProfile(draft, activateIfEligible = true)
    val savedName = draft.displayName
    when (outcome) {
      is ActivateProfileResult.MissingApiKey -> {
        _uiState.update {
          it.copy(
            profileDraft = null,
            isDraftNew = false,
            testStatus = TestStatus.Idle,
            profileBanner =
              "Saved \"$savedName\" as inactive — API key needed to activate.",
            isProfileBannerError = true,
          )
        }
      }
      is ActivateProfileResult.InvalidEndpoint -> {
        _uiState.update {
          it.copy(
            profileDraft = null,
            isDraftNew = false,
            testStatus = TestStatus.Idle,
            profileBanner = "Saved \"$savedName\" as inactive — endpoint URL is required.",
            isProfileBannerError = true,
          )
        }
      }
      is ActivateProfileResult.Activated -> {
        _uiState.update {
          it.copy(
            profileDraft = null,
            isDraftNew = false,
            testStatus = TestStatus.Idle,
            profileBanner = "Saved & activated \"$savedName\"",
            isProfileBannerError = false,
          )
        }
      }
      null -> {
        _uiState.update {
          it.copy(
            profileDraft = null,
            isDraftNew = false,
            testStatus = TestStatus.Idle,
            profileBanner = "Saved \"$savedName\"",
            isProfileBannerError = false,
          )
        }
      }
    }
  }

  fun activateProfile(profile: EndpointProfile) {
    when (val res = settingsRepo.activateProfile(profile.id)) {
      is ActivateProfileResult.Activated -> {
        _uiState.update {
          it.copy(
            profileBanner = "Active endpoint: ${res.profile.displayName}",
            isProfileBannerError = false,
          )
        }
      }
      is ActivateProfileResult.MissingApiKey -> {
        _uiState.update {
          it.copy(
            profileBanner =
              "API key needed for \"${res.profile.displayName}\". Edit it to add your API key or check 'API key not needed'.",
            isProfileBannerError = true,
          )
        }
      }
      is ActivateProfileResult.InvalidEndpoint -> {
        _uiState.update {
          it.copy(
            profileBanner = "Endpoint URL is missing for \"${res.profile.displayName}\".",
            isProfileBannerError = true,
          )
        }
      }
    }
  }

  fun deleteProfile(profile: EndpointProfile) {
    settingsRepo.deleteProfile(profile.id)
    _uiState.update { state ->
      val clearedDraft = if (state.profileDraft?.id == profile.id) null else state.profileDraft
      state.copy(
        profileDraft = clearedDraft,
        isDraftNew = if (clearedDraft == null) false else state.isDraftNew,
        profileBanner = "Deleted \"${profile.displayName}\"",
        isProfileBannerError = false,
      )
    }
  }

  fun updateProfileName(name: String) {
    _uiState.update { state ->
      val draft = state.profileDraft ?: return@update state
      state.copy(profileDraft = draft.copy(customName = name))
    }
  }

  fun updateEndpointInput(input: String) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      val trimmed = input.trimStart()
      val nextCacheKey =
        "${trimmed.trim()}|${draft.useIpOverride}|${draft.overrideIp.trim()}|${draft.ignoreSsl}"
      val keepDetected = draft.detectedForInput == nextCacheKey
      _uiState.update {
        it.copy(
          profileDraft =
            draft.copy(
              endpointInput = trimmed,
              detectedEndpointUrl = if (keepDetected) draft.detectedEndpointUrl else "",
              detectedFamily = if (keepDetected) draft.detectedFamily else null,
              detectedForInput = if (keepDetected) draft.detectedForInput else "",
            ),
          testStatus = TestStatus.Idle,
          availableModels = emptyList(),
        )
      }
    } else {
      settingsRepo.updateEndpointInput(input)
      _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
    }
  }

  fun setUseIpOverride(enabled: Boolean) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update {
        it.copy(
          profileDraft =
            draft.copy(
              useIpOverride = enabled,
              detectedEndpointUrl = "",
              detectedFamily = null,
              detectedForInput = "",
            ),
          testStatus = TestStatus.Idle,
          availableModels = emptyList(),
        )
      }
    } else {
      settingsRepo.setUseIpOverride(enabled)
      _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
    }
  }

  fun updateOverrideIp(ip: String) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update {
        it.copy(
          profileDraft =
            draft.copy(
              overrideIp = ip,
              detectedEndpointUrl = "",
              detectedFamily = null,
              detectedForInput = "",
            ),
          testStatus = TestStatus.Idle,
          availableModels = emptyList(),
        )
      }
    } else {
      settingsRepo.updateOverrideIp(ip)
      _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
    }
  }

  fun setIgnoreSsl(enabled: Boolean) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update {
        it.copy(
          profileDraft =
            draft.copy(
              ignoreSsl = enabled,
              detectedEndpointUrl = "",
              detectedFamily = null,
              detectedForInput = "",
            ),
          testStatus = TestStatus.Idle,
          availableModels = emptyList(),
        )
      }
    } else {
      settingsRepo.setIgnoreSsl(enabled)
      _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
    }
  }

  fun setApiKeyNotNeeded(notNeeded: Boolean) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update {
        it.copy(
          profileDraft = draft.copy(apiKeyNotNeeded = notNeeded),
          testStatus = TestStatus.Idle,
        )
      }
    } else {
      settingsRepo.setUseApiKey(!notNeeded)
      _uiState.update { it.copy(testStatus = TestStatus.Idle) }
    }
  }

  fun setApiKey(apiKey: String) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update {
        it.copy(profileDraft = draft.copy(apiKey = apiKey.trim()))
      }
    } else {
      settingsRepo.setApiKey(apiKey)
    }
  }

  fun setUseCustomModel(enabled: Boolean) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update { it.copy(profileDraft = draft.copy(useCustomModel = enabled)) }
      if (enabled && _uiState.value.availableModels.isEmpty()) {
        refreshAvailableModels()
      }
    } else {
      settingsRepo.setUseCustomModel(enabled)
      if (enabled && _uiState.value.availableModels.isEmpty()) {
        refreshAvailableModels()
      }
    }
  }

  fun setModelName(modelName: String) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update { it.copy(profileDraft = draft.copy(modelName = modelName)) }
    } else {
      settingsRepo.setModelName(modelName)
    }
  }

  fun setLiveTranscription(enabled: Boolean) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update { it.copy(profileDraft = draft.copy(liveTranscription = enabled)) }
    } else {
      settingsRepo.setLiveTranscription(enabled)
    }
  }

  fun setMicButtonCapture(enabled: Boolean) {
    settingsRepo.setMicButtonCapture(enabled)
  }

  fun setSearchInCurrentApp(enabled: Boolean) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update { it.copy(profileDraft = draft.copy(searchInCurrentApp = enabled)) }
    } else {
      settingsRepo.setSearchInCurrentApp(enabled)
    }
  }

  fun selectTargetApp(app: InstalledTvApp) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update {
        it.copy(
          profileDraft = draft.copy(targetPackage = app.packageName, targetLabel = app.label)
        )
      }
    } else {
      settingsRepo.setTargetApp(app.packageName, app.label)
    }
  }

  fun setLanguage(language: LanguageOption) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update { it.copy(profileDraft = draft.copy(language = language)) }
    } else {
      settingsRepo.setLanguage(language)
    }
  }

  fun setMicSource(micSource: MicSource) {
    val draft = _uiState.value.profileDraft
    if (draft != null) {
      _uiState.update { it.copy(profileDraft = draft.copy(micSource = micSource)) }
    } else {
      settingsRepo.setMicSource(micSource)
    }
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
    val probeTarget = _uiState.value.profileDraft?.toProbeSettings() ?: settingsRepo.settings.value
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
    val draft = _uiState.value.profileDraft
    val current = draft?.toProbeSettings() ?: settingsRepo.settings.value
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
          if (draft != null) {
            _uiState.update { state ->
              val currentDraft = state.profileDraft
              val updatedDraft =
                if (currentDraft?.id == draft.id) {
                  currentDraft.copy(
                    detectedEndpointUrl = probe.url,
                    detectedFamily = probe.family,
                    detectedForInput = currentDraft.cacheKey,
                  )
                } else {
                  currentDraft
                }
              state.copy(
                profileDraft = updatedDraft,
                testStatus = TestStatus.Ok(probe.message),
                availableModels = probe.availableModels,
              )
            }
          } else {
            settingsRepo.saveDetectedEndpoint(current.cacheKey, probe.url, probe.family)
            _uiState.update {
              it.copy(
                testStatus = TestStatus.Ok(probe.message),
                availableModels = probe.availableModels,
              )
            }
          }
        }
        is ProbeResult.NeedsApiKey -> {
          if (draft != null) {
            _uiState.update { state ->
              val currentDraft = state.profileDraft
              val updatedDraft =
                if (currentDraft?.id == draft.id) {
                  currentDraft.copy(
                    detectedEndpointUrl = probe.url,
                    detectedFamily = probe.family,
                    detectedForInput = currentDraft.cacheKey,
                  )
                } else {
                  currentDraft
                }
              state.copy(
                profileDraft = updatedDraft,
                testStatus = TestStatus.Warning(probe.message),
              )
            }
          } else {
            settingsRepo.saveDetectedEndpoint(current.cacheKey, probe.url, probe.family)
            _uiState.update { it.copy(testStatus = TestStatus.Warning(probe.message)) }
          }
        }
        is ProbeResult.Failure -> {
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
          settingsRepo.setTargetApp(preferred.packageName, preferred.label)
          _uiState.update { state ->
            val draft = state.profileDraft
            if (draft != null && draft.targetPackage == currentTarget) {
              state.copy(
                profileDraft =
                  draft.copy(targetPackage = preferred.packageName, targetLabel = preferred.label)
              )
            } else {
              state
            }
          }
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
