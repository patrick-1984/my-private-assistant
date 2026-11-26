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
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.TvVoiceSearchTheme

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val launchedFromAssist = isAssistIntent(intent)

    setContent {
      TvVoiceSearchTheme {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        var hasMicPermission by remember { mutableStateOf(checkMicPermission()) }

        val permissionLauncher =
          rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            viewModel.onPermissionChecked(granted, forceStart = granted)
          }

        LaunchedEffect(Unit) {
          hasMicPermission = checkMicPermission()
          if (hasMicPermission) {
            if (launchedFromAssist) {
              viewModel.onAssistLaunch()
            } else {
              viewModel.onPermissionChecked(granted = true)
            }
          } else {
            viewModel.onPermissionChecked(granted = false)
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
            viewModel.closeSettings()
          } else {
            finish()
          }
        }

        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
          if (state.isSettingsOpen) {
            SettingsScreen(
              state = state,
              onBack = { viewModel.closeSettings() },
              onEndpointChange = { viewModel.updateEndpointInput(it) },
              onToggleIpOverride = { viewModel.setUseIpOverride(it) },
              onOverrideIpChange = { viewModel.updateOverrideIp(it) },
              onToggleApiKey = { viewModel.setUseApiKey(it) },
              onTestEndpoint = { viewModel.testEndpoint() },
              onSelectTargetApp = { viewModel.selectTargetApp(it) },
              onSelectLanguage = { viewModel.setLanguage(it) },
              onApiKeyChange = { viewModel.setApiKey(it) },
              onModelNameChange = { viewModel.setModelName(it) },
              onSelectMicSource = { viewModel.setMicSource(it) },
              onRefreshAssistantStatus = { viewModel.refreshAssistantStatus() },
              onCopyAdbCommand = { label, cmd -> viewModel.copyToClipboard(label, cmd) },
            )
          } else {
            SearchScreen(
              state = state,
              hasMicPermission = hasMicPermission,
              onMicClick = { viewModel.onMicButtonPressed(hasMicPermission) },
              onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
              onSearchTextChange = { viewModel.updateSearchText(it) },
              onSubmitSearch = { viewModel.submitManualSearch() },
              onOpenSettings = { viewModel.openSettings() },
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
      viewModel.onAssistLaunch()
    }
  }

  private fun isAssistIntent(intent: Intent?): Boolean {
    if (intent == null) return false
    return intent.action == Intent.ACTION_ASSIST ||
      intent.getBooleanExtra(EXTRA_FROM_ASSIST, false)
  }

  private fun checkMicPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  }

  companion object {
    const val EXTRA_FROM_ASSIST = "extra_from_assist"
    const val EXTRA_HAS_REMOTE_STREAM = "extra_has_remote_stream"
  }
}
