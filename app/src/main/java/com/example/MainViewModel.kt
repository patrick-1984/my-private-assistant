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
import com.example.data.AppHandoffStatus
import com.example.data.AppSettings
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
            statusMessage = "No transcription endpoint set. Open Settings to configure.",
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
          statusMessage = "No transcription endpoint set. Open Settings to configure.",
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
    if (
      settingsRepo.settings.value.hasValidCachedEndpoint &&
        _uiState.value.availableModels.isEmpty()
    ) {
      refreshAvailableModels()
    }
    _uiState.update {
      it.copy(
        isSettingsOpen = true,
        phase = VoicePhase.IDLE,
        copiedBanner = null,
      )
    }
  }

  fun closeSettings() {
    _uiState.update {
      it.copy(
        isSettingsOpen = false,
        pendingVerificationApp = null,
        copiedBanner = null,
        statusMessage = "",
      )
    }
  }

  fun updateEndpointInput(input: String) {
    settingsRepo.updateEndpointInput(input)
    _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
  }

  fun setUseIpOverride(enabled: Boolean) {
    settingsRepo.setUseIpOverride(enabled)
    _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
  }

  fun updateOverrideIp(ip: String) {
    settingsRepo.updateOverrideIp(ip)
    _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
  }

  fun setIgnoreSsl(enabled: Boolean) {
    settingsRepo.setIgnoreSsl(enabled)
    _uiState.update { it.copy(testStatus = TestStatus.Idle, availableModels = emptyList()) }
  }

  fun setUseApiKey(enabled: Boolean) {
    settingsRepo.setUseApiKey(enabled)
    _uiState.update { it.copy(testStatus = TestStatus.Idle) }
  }

  fun setUseCustomModel(enabled: Boolean) {
    settingsRepo.setUseCustomModel(enabled)
    if (enabled && _uiState.value.availableModels.isEmpty()) {
      refreshAvailableModels()
    }
  }

  fun setLiveTranscription(enabled: Boolean) {
    settingsRepo.setLiveTranscription(enabled)
  }

  fun setMicButtonCapture(enabled: Boolean) {
    settingsRepo.setMicButtonCapture(enabled)
  }

  fun setSearchInCurrentApp(enabled: Boolean) {
    settingsRepo.setSearchInCurrentApp(enabled)
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
    val current = settingsRepo.settings.value
    if (current.endpointInput.isBlank()) return
    viewModelScope.launch {
      _uiState.update { it.copy(isFetchingModels = true) }
      val models =
        endpointClient.fetchModels(
          detectedUrl = current.detectedEndpointUrl,
          rawInput = current.endpointInput,
          apiKey = current.effectiveApiKey,
          useIpOverride = current.useIpOverride,
          overrideIp = current.overrideIp,
          ignoreSsl = current.ignoreSsl,
        )
      _uiState.update { it.copy(availableModels = models, isFetchingModels = false) }
    }
  }

  fun testEndpoint() {
    val current = settingsRepo.settings.value
    if (current.endpointInput.isBlank()) {
      _uiState.update {
        it.copy(testStatus = TestStatus.Error("Enter an endpoint host or URL first."))
      }
      return
    }
    if (current.useApiKey && current.apiKey.isBlank()) {
      _uiState.update {
        it.copy(testStatus = TestStatus.Error("Enter an API key or uncheck 'Provide API key'."))
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
          settingsRepo.saveDetectedEndpoint(current.cacheKey, probe.url, probe.family)
          _uiState.update {
            it.copy(
              testStatus = TestStatus.Ok(probe.message),
              availableModels = probe.availableModels,
            )
          }
        }
        is ProbeResult.NeedsApiKey -> {
          settingsRepo.saveDetectedEndpoint(current.cacheKey, probe.url, probe.family)
          _uiState.update { it.copy(testStatus = TestStatus.Warning(probe.message)) }
        }
        is ProbeResult.Failure -> {
          _uiState.update { it.copy(testStatus = TestStatus.Error(probe.message)) }
        }
      }
    }
  }

  fun selectTargetApp(app: InstalledTvApp) {
    settingsRepo.setTargetApp(app.packageName, app.label)
  }

  fun setLanguage(language: LanguageOption) {
    settingsRepo.setLanguage(language)
  }

  fun setApiKey(apiKey: String) {
    settingsRepo.setApiKey(apiKey)
  }

  fun setModelName(modelName: String) {
    settingsRepo.setModelName(modelName)
  }

  fun setMicSource(micSource: MicSource) {
    settingsRepo.setMicSource(micSource)
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
