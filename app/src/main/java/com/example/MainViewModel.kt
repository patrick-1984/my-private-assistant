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
import com.example.data.AppSettings
import com.example.data.LanguageOption
import com.example.data.MicSource
import com.example.data.SettingsRepository
import com.example.handoff.AppHandoff
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
  val statusMessage: String = "Press mic or OK to speak",
  val audioLevel: Float = 0f,
  val isSettingsOpen: Boolean = false,
  val settings: AppSettings = AppSettings(),
  val testStatus: TestStatus = TestStatus.Idle,
  val installedApps: List<InstalledTvApp> = emptyList(),
  val isLoadingApps: Boolean = false,
  val assistantStatus: AssistantRoleStatus? = null,
  val copiedBanner: String? = null,
  val shouldCloseApp: Boolean = false,
)

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
  private var hasStartedOnce = false

  init {
    viewModelScope.launch {
      settingsRepo.settings.collect { latest ->
        _uiState.update { it.copy(settings = latest) }
      }
    }
    loadInstalledApps()
  }

  fun onPermissionChecked(granted: Boolean, forceStart: Boolean = false) {
    if (!granted) {
      _uiState.update {
        it.copy(
          phase = VoicePhase.PERMISSION_NEEDED,
          statusMessage = "Microphone permission is required for voice search.",
        )
      }
      return
    }

    if ((forceStart || !hasStartedOnce) && !_uiState.value.isSettingsOpen) {
      hasStartedOnce = true
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

  fun onAssistLaunch() {
    hasStartedOnce = true
    if (_uiState.value.isSettingsOpen) {
      _uiState.update { it.copy(isSettingsOpen = false) }
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
    val remotePfd = AssistAudioBridge.consumeStream()

    listenJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            phase = VoicePhase.LISTENING,
            statusMessage = "Listening… Speak now (OK to finish)",
            audioLevel = 0f,
          )
        }

        val levelCollector =
          launch {
            recorder.level.collect { lvl ->
              _uiState.update { state -> state.copy(audioLevel = lvl) }
            }
          }

        val recOutcome =
          try {
            recorder.record(
              micSource = settingsRepo.settings.value.micSource,
              remoteStreamPfd = remotePfd,
            )
          } finally {
            levelCollector.cancel()
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
          _uiState.update {
            it.copy(
              searchText = text,
              phase = VoicePhase.OPENING_TARGET,
              statusMessage = "Opening ${currentSettings.targetLabel}…",
            )
          }
          launchTarget(text)
        }
      }
    }
  }

  fun updateSearchText(text: String) {
    _uiState.update { it.copy(searchText = text) }
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
    when (val outcome = AppHandoff.launchSearch(appContext, settings.targetPackage, query)) {
      is HandoffResult.Launched -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.IDLE,
            statusMessage = "Sent to ${settings.targetLabel}",
            shouldCloseApp = true,
          )
        }
      }
      is HandoffResult.Failed -> {
        _uiState.update {
          it.copy(
            phase = VoicePhase.ERROR,
            statusMessage = outcome.message,
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
    }
    refreshAssistantStatus()
    loadInstalledApps()
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
        copiedBanner = null,
        statusMessage =
          if (it.searchText.isNotBlank()) "Ready to search" else "Press mic or OK to speak",
      )
    }
  }

  fun updateEndpointInput(input: String) {
    settingsRepo.updateEndpointInput(input)
    _uiState.update { it.copy(testStatus = TestStatus.Idle) }
  }

  fun setUseIpOverride(enabled: Boolean) {
    settingsRepo.setUseIpOverride(enabled)
    _uiState.update { it.copy(testStatus = TestStatus.Idle) }
  }

  fun updateOverrideIp(ip: String) {
    settingsRepo.updateOverrideIp(ip)
    _uiState.update { it.copy(testStatus = TestStatus.Idle) }
  }

  fun setIgnoreSsl(enabled: Boolean) {
    settingsRepo.setIgnoreSsl(enabled)
    _uiState.update { it.copy(testStatus = TestStatus.Idle) }
  }

  fun setUseApiKey(enabled: Boolean) {
    settingsRepo.setUseApiKey(enabled)
    _uiState.update { it.copy(testStatus = TestStatus.Idle) }
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
          modelName = current.modelName,
          language = current.language,
          useIpOverride = current.useIpOverride,
          overrideIp = current.overrideIp,
          ignoreSsl = current.ignoreSsl,
        )
      when (probe) {
        is ProbeResult.Success -> {
          settingsRepo.saveDetectedEndpoint(current.cacheKey, probe.url, probe.family)
          _uiState.update { it.copy(testStatus = TestStatus.Ok(probe.message)) }
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
  }
}
