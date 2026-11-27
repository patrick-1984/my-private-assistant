package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsActions
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.TvVoiceSearchTheme

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val launchedFromAssist = isAssistIntent(intent)
    val launchSourcePackage =
      if (launchedFromAssist) intent?.getStringExtra(EXTRA_SOURCE_PACKAGE) else null

    setContent {
      TvVoiceSearchTheme {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        var hasMicPermission by remember { mutableStateOf(checkMicPermission()) }

        val permissionLauncher =
          rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            viewModel.onPermissionChecked(
              granted = granted,
              forceStart = granted,
              sourcePackage = launchSourcePackage,
            )
          }

        LaunchedEffect(Unit) {
          hasMicPermission = checkMicPermission()
          if (hasMicPermission) {
            if (launchedFromAssist) {
              viewModel.onAssistLaunch(launchSourcePackage)
            } else {
              viewModel.onPermissionChecked(
                granted = true,
                sourcePackage = launchSourcePackage,
              )
            }
          } else {
            viewModel.onPermissionChecked(granted = false, sourcePackage = launchSourcePackage)
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
        }

        LaunchedEffect(state.shouldCloseApp) {
          if (state.shouldCloseApp) {
            viewModel.onAppClosedHandled()
            finish()
          }
        }

        BackHandler {
          if (state.isSettingsOpen) {
            viewModel.navigateSettingsBack()
          } else {
            finish()
          }
        }

        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
          if (state.isSettingsOpen) {
            SettingsScreen(
              state = state,
              actions =
                SettingsActions(
                  onBack = { viewModel.navigateSettingsBack() },
                  onStartNewConfig = { viewModel.startNewConfigurationDraft() },
                  onEditConfig = { viewModel.editConfiguration(it) },
                  onActivateConfig = { viewModel.activateConfiguration(it) },
                  onDeleteConfig = { viewModel.deleteConfiguration(it) },
                  onConfigNameChange = { viewModel.updateConfigDraftName(it) },
                  onConfigFailoverToggle = { viewModel.setConfigDraftFailover(it) },
                  onSelectConfigSlot = { slot, id -> viewModel.selectConfigDraftSlot(slot, id) },
                  onSelectConfigAppPreset = { viewModel.selectConfigDraftAppPreset(it) },
                  onSaveConfigDone = { viewModel.saveConfigurationDraftDone() },
                  onCancelSubPage = { viewModel.navigateSettingsBack() },
                  onStartNewEndpoint = { slot -> viewModel.startNewEndpointDraft(slot) },
                  onEditEndpoint = { viewModel.editEndpoint(it) },
                  onDeleteEndpoint = { viewModel.deleteEndpoint(it) },
                  onCheckEndpointHealth = { viewModel.checkEndpointHealthNow(it) },
                  onEndpointNameChange = { viewModel.updateEndpointName(it) },
                  onEndpointUrlChange = { viewModel.updateEndpointInput(it) },
                  onFunctionIdChange = { viewModel.setFunctionId(it) },
                  onApplyNvidiaCloudPreset = { viewModel.applyNvidiaCloudPreset(it) },
                  onToggleIpOverride = { viewModel.setUseIpOverride(it) },
                  onOverrideIpChange = { viewModel.updateOverrideIp(it) },
                  onToggleIgnoreSsl = { viewModel.setIgnoreSsl(it) },
                  onToggleApiKeyNotNeeded = { viewModel.setApiKeyNotNeeded(it) },
                  onApiKeyChange = { viewModel.setApiKey(it) },
                  onTestEndpoint = { viewModel.testEndpoint() },
                  onToggleCustomModel = { viewModel.setUseCustomModel(it) },
                  onRefreshModels = { viewModel.refreshAvailableModels() },
                  onModelNameChange = { viewModel.setModelName(it) },
                  onToggleLiveTranscription = { viewModel.setLiveTranscription(it) },
                  onSaveEndpointDone = { viewModel.saveEndpointDraftDone() },
                  onStartNewAppPreset = { assign -> viewModel.startNewAppPresetDraft(assign) },
                  onEditAppPreset = { viewModel.editAppPreset(it) },
                  onDeleteAppPreset = { viewModel.deleteAppPreset(it) },
                  onAppPresetNameChange = { viewModel.updateAppPresetName(it) },
                  onToggleSearchInCurrentApp = { viewModel.setSearchInCurrentApp(it) },
                  onSelectTargetApp = { viewModel.selectTargetApp(it) },
                  onTestAppSearch = { viewModel.testAppSearch(it) },
                  onConfirmAppTest = { viewModel.confirmAppTestResult(it) },
                  onDismissPendingVerification = { viewModel.dismissPendingVerification() },
                  onToggleNeverUseApp = { viewModel.toggleNeverUseApp(it) },
                  onSelectLanguage = { viewModel.setLanguage(it) },
                  onSelectMicSource = { viewModel.setMicSource(it) },
                  onSaveAppPresetDone = { viewModel.saveAppPresetDraftDone() },
                  onToggleMicCapture = { viewModel.setMicButtonCapture(it) },
                  onRefreshAssistantStatus = { viewModel.refreshAssistantStatus() },
                  onCopyAdbCommand = { label, cmd -> viewModel.copyToClipboard(label, cmd) },
                ),
            )
          } else {
            SearchScreen(
              state = state,
              hasMicPermission = hasMicPermission,
              onMicClick = { viewModel.onMicButtonPressed(hasMicPermission) },
              onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
              onSearchTextChange = { viewModel.updateSearchText(it) },
              onSubmitSearch = { viewModel.submitManualSearch() },
              onSelectFallbackApp = { viewModel.launchFallbackApp(it) },
              onOpenSettings = { viewModel.openSettings() },
              onDismiss = { finish() },
            )
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (isAssistIntent(intent) && checkMicPermission()) {
      viewModel.onAssistLaunch(intent.getStringExtra(EXTRA_SOURCE_PACKAGE))
    }
  }

  private fun isAssistIntent(intent: Intent?): Boolean {
    if (intent == null) return false
    return intent.action == Intent.ACTION_ASSIST ||
      intent.getBooleanExtra(EXTRA_FROM_ASSIST, false) ||
      intent.getBooleanExtra(EXTRA_START_LISTENING, false)
  }

  private fun checkMicPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  }

  companion object {
    const val EXTRA_FROM_ASSIST = "extra_from_assist"
    const val EXTRA_HAS_REMOTE_STREAM = "extra_has_remote_stream"
    const val EXTRA_START_LISTENING = "start_listening"
    const val EXTRA_SOURCE_PACKAGE = "source_package"
  }
}
