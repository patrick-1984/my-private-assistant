package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainUiState
import com.example.R
import com.example.TestStatus
import com.example.data.AppHandoffStatus
import com.example.data.LanguageOption
import com.example.data.MicSource
import com.example.handoff.HandoffMethod
import com.example.handoff.InstalledTvApp
import com.example.ui.components.TvFocusableCard
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.theme.TvAmber
import com.example.ui.theme.TvBackground
import com.example.ui.theme.TvCoral
import com.example.ui.theme.TvCyan
import com.example.ui.theme.TvEmerald
import com.example.ui.theme.TvOutline
import com.example.ui.theme.TvSurface
import com.example.ui.theme.TvSurfaceElevated
import com.example.ui.theme.TvSurfaceFocus
import com.example.ui.theme.TvTextPrimary
import com.example.ui.theme.TvTextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  state: MainUiState,
  onBack: () -> Unit,
  onEndpointChange: (String) -> Unit,
  onToggleIpOverride: (Boolean) -> Unit,
  onOverrideIpChange: (String) -> Unit,
  onToggleIgnoreSsl: (Boolean) -> Unit,
  onToggleApiKey: (Boolean) -> Unit,
  onTestEndpoint: () -> Unit,
  onToggleCustomModel: (Boolean) -> Unit,
  onRefreshModels: () -> Unit,
  onToggleLiveTranscription: (Boolean) -> Unit,
  onToggleSearchInCurrentApp: (Boolean) -> Unit,
  onSelectTargetApp: (InstalledTvApp) -> Unit,
  onTestAppSearch: (InstalledTvApp) -> Unit,
  onConfirmAppTest: (Boolean) -> Unit,
  onDismissPendingVerification: () -> Unit,
  onToggleNeverUseApp: (InstalledTvApp) -> Unit,
  onSelectLanguage: (LanguageOption) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onModelNameChange: (String) -> Unit,
  onSelectMicSource: (MicSource) -> Unit,
  onToggleMicCapture: (Boolean) -> Unit,
  onRefreshAssistantStatus: () -> Unit,
  onCopyAdbCommand: (String, String) -> Unit,
) {
  val testPassed = state.testStatus is TestStatus.Ok || state.settings.hasValidCachedEndpoint

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(TvBackground),
    contentAlignment = Alignment.TopCenter,
  ) {
    val isCompact = maxWidth < 640.dp
    val hPad = if (isCompact) 16.dp else 36.dp
    val vPad = if (isCompact) 16.dp else 28.dp
    val cardPad = if (isCompact) 18.dp else 24.dp

    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp).testTag("settings_list"),
      contentPadding = PaddingValues(horizontal = hPad, vertical = vPad),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      // Top Bar
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          TvSecondaryButton(
            onClick = onBack,
            modifier = Modifier.testTag("settings_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back",
              tint = TvCyan,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Back", style = MaterialTheme.typography.titleMedium, maxLines = 1)
          }

          Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp),
            color = TvTextPrimary,
            maxLines = 1,
          )
        }
      }

      // 1. Transcription endpoint (inputs top-to-bottom -> Test at bottom -> Post-test options)
      item {
        SettingsSectionCard(title = "1. Transcription Endpoint", cardPadding = cardPad) {
          EndpointTextField(
            value = state.settings.endpointInput,
            onValueChange = onEndpointChange,
            onDone = onTestEndpoint,
            modifier = Modifier.fillMaxWidth().testTag("endpoint_input"),
          )

          // Checkbox 1: IP override for FQDN / split-DNS / Tailscale setups
          TvCheckboxRow(
            checked = state.settings.useIpOverride,
            onCheckedChange = onToggleIpOverride,
            title = "Override server IP address",
            subtitle =
              "Connect directly to this IP (LAN / Tailscale) while keeping the hostname and Host/SNI headers",
            modifier = Modifier.testTag("use_ip_override_checkbox"),
          )

          AnimatedVisibility(visible = state.settings.useIpOverride) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "Server IP address (IP only, no port or path)",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )
              OutlinedTextField(
                value = state.settings.overrideIp,
                onValueChange = onOverrideIpChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvTextPrimary),
                placeholder = {
                  Text(
                    text = "e.g. 10.3.3.20 or 100.64.1.5",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onTestEndpoint() }),
                shape = RoundedCornerShape(14.dp),
                colors =
                  OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TvSurfaceFocus,
                    unfocusedContainerColor = TvSurfaceElevated,
                    focusedBorderColor = TvCyan,
                    unfocusedBorderColor = TvOutline,
                    cursorColor = TvCyan,
                  ),
                modifier = Modifier.fillMaxWidth().testTag("override_ip_input"),
              )
            }
          }

          // Checkbox 2: Ignore SSL certificate checks
          TvCheckboxRow(
            checked = state.settings.ignoreSsl,
            onCheckedChange = onToggleIgnoreSsl,
            title = "Ignore SSL certificate checks",
            subtitle =
              "Allow self-signed or private-CA HTTPS certificates on local/Tailscale servers",
            modifier = Modifier.testTag("use_ignore_ssl_checkbox"),
          )

          // Checkbox 3: Provide API key (OpenAI Authorization: Bearer)
          TvCheckboxRow(
            checked = state.settings.useApiKey,
            onCheckedChange = onToggleApiKey,
            title = "Provide API key",
            subtitle = "Include Authorization: Bearer token per OpenAI API specification",
            modifier = Modifier.testTag("use_api_key_checkbox"),
          )

          AnimatedVisibility(visible = state.settings.useApiKey) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "API key (sent as Authorization: Bearer)",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )
              OutlinedTextField(
                value = state.settings.apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvTextPrimary),
                placeholder = {
                  Text(
                    text = "sk-… or server bearer token",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onTestEndpoint() }),
                shape = RoundedCornerShape(14.dp),
                colors =
                  OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TvSurfaceFocus,
                    unfocusedContainerColor = TvSurfaceElevated,
                    focusedBorderColor = TvCyan,
                    unfocusedBorderColor = TvOutline,
                    cursorColor = TvCyan,
                  ),
                modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
              )
            }
          }

          // Test Connection action bar at the END of the endpoint fields (button + green check status)
          val cachedLabel =
            if (state.settings.hasValidCachedEndpoint && state.testStatus == TestStatus.Idle) {
              val viaIp =
                if (state.settings.useIpOverride && state.settings.overrideIp.isNotBlank()) {
                  " (via ${state.settings.overrideIp.trim()})"
                } else {
                  ""
                }
              "Connected: ${state.settings.detectedEndpointUrl}$viaIp"
            } else {
              null
            }

          if (isCompact) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              TestEndpointButton(
                isTesting = state.testStatus == TestStatus.Testing,
                onClick = onTestEndpoint,
                modifier = Modifier.fillMaxWidth().testTag("test_endpoint_button"),
              )
              EndpointStatusBadge(
                testStatus = state.testStatus,
                cachedOkMessage = cachedLabel,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          } else {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              TestEndpointButton(
                isTesting = state.testStatus == TestStatus.Testing,
                onClick = onTestEndpoint,
                modifier = Modifier.testTag("test_endpoint_button"),
              )
              EndpointStatusBadge(
                testStatus = state.testStatus,
                cachedOkMessage = cachedLabel,
                modifier = Modifier.weight(1f),
              )
            }
          }

          // Post-Test Options: Custom Model / OpenAI /v1/models Picker & Live Chunked Transcription
          AnimatedVisibility(visible = testPassed) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              TvCheckboxRow(
                checked = state.settings.useCustomModel,
                onCheckedChange = onToggleCustomModel,
                title = "Pick custom model / endpoint",
                subtitle =
                  if (state.availableModels.isNotEmpty()) {
                    "Discovered ${state.availableModels.size} OpenAI model(s) on server"
                  } else {
                    "Query OpenAI /v1/models or specify a custom transcription model"
                  },
                modifier = Modifier.testTag("use_custom_model_checkbox"),
              )

              AnimatedVisibility(visible = state.settings.useCustomModel) {
                Column(
                  modifier = Modifier.fillMaxWidth(),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text =
                        when {
                          state.isFetchingModels -> "Querying /v1/models…"
                          state.availableModels.isNotEmpty() -> "Server OpenAI models:"
                          else -> "Model ID (sent to transcription endpoint):"
                        },
                      style = MaterialTheme.typography.bodyMedium,
                      color = TvTextSecondary,
                      modifier = Modifier.weight(1f),
                    )
                    TvSecondaryButton(
                      onClick = onRefreshModels,
                      enabled = !state.isFetchingModels,
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                      modifier = Modifier.testTag("refresh_models_button"),
                    ) {
                      Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Query OpenAI models",
                        tint = TvCyan,
                        modifier = Modifier.size(18.dp),
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Query", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                  }

                  if (state.availableModels.isNotEmpty()) {
                    FlowRow(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      state.availableModels.forEach { modelId ->
                        val selected = state.settings.modelName == modelId
                        TvSecondaryButton(
                          onClick = { onModelNameChange(modelId) },
                          isSelected = selected,
                          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                          modifier = Modifier.testTag("model_chip_$modelId"),
                        ) {
                          if (selected) {
                            Icon(
                              imageVector = Icons.Rounded.Check,
                              contentDescription = null,
                              tint = TvCyan,
                              modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                          }
                          Text(
                            text = modelId,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                          )
                        }
                      }
                    }
                  }

                  OutlinedTextField(
                    value = state.settings.modelName,
                    onValueChange = onModelNameChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvTextPrimary),
                    placeholder = {
                      Text(
                        text = "whisper-1",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvTextSecondary.copy(alpha = 0.7f),
                      )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors =
                      OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TvSurfaceFocus,
                        unfocusedContainerColor = TvSurfaceElevated,
                        focusedBorderColor = TvCyan,
                        unfocusedBorderColor = TvOutline,
                        cursorColor = TvCyan,
                      ),
                    modifier = Modifier.fillMaxWidth().testTag("model_input"),
                  )
                }
              }

              TvCheckboxRow(
                checked = state.settings.liveTranscription,
                onCheckedChange = onToggleLiveTranscription,
                title = "Live transcription (chunked streaming)",
                subtitle =
                  "Send progressive audio chunks while speaking for live words in the search bar",
                modifier = Modifier.testTag("live_transcription_checkbox"),
              )
            }
          }
        }
      }

      // 2. Target & current-app search
      item {
        SettingsSectionCard(title = "2. Target & Current-App Search", cardPadding = cardPad) {
          TvSwitchRow(
            checked = state.settings.searchInCurrentApp,
            onCheckedChange = onToggleSearchInCurrentApp,
            title = "Search in the current app",
            subtitle =
              "Send voice queries to the active app when it supports search; otherwise fall back to the default app below",
            modifier = Modifier.testTag("search_current_app_switch"),
          )

          AnimatedVisibility(visible = state.pendingVerificationApp != null) {
            state.pendingVerificationApp?.let { pendingApp ->
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = TvSurfaceFocus,
                border = BorderStroke(1.5.dp, TvCyan),
                modifier = Modifier.fillMaxWidth().testTag("pending_verification_banner"),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(14.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Text(
                    text = "Did search results appear in ${pendingApp.label}?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvTextPrimary,
                  )
                  FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    TvPrimaryButton(
                      onClick = { onConfirmAppTest(true) },
                      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                      modifier = Modifier.testTag("confirm_app_works_button"),
                    ) {
                      Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Yes, worked", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    TvSecondaryButton(
                      onClick = { onConfirmAppTest(false) },
                      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                      modifier = Modifier.testTag("confirm_app_broken_button"),
                    ) {
                      Text("No, not working", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    TvSecondaryButton(
                      onClick = onDismissPendingVerification,
                      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                      modifier = Modifier.testTag("dismiss_app_verify_button"),
                    ) {
                      Text("Cancel", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                  }
                }
              }
            }
          }

          Text(
            text = "Default target app (used on Home or when current app does not support search):",
            style = MaterialTheme.typography.bodyMedium,
            color = TvTextSecondary,
          )

          if (state.isLoadingApps && state.installedApps.isEmpty()) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(
                color = TvCyan,
                strokeWidth = 3.dp,
                modifier = Modifier.size(24.dp),
              )
              Text(
                text = "Scanning installed TV apps…",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )
            }
          } else if (state.installedApps.isEmpty()) {
            Text(
              text = "No installed TV apps found.",
              style = MaterialTheme.typography.bodyMedium,
              color = TvAmber,
            )
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              state.installedApps.forEach { app ->
                TargetAppRow(
                  app = app,
                  isSelected = app.packageName == state.settings.targetPackage,
                  handoffStatus = state.settings.handoffStatusFor(app.packageName),
                  isCompact = isCompact,
                  onClick = { onSelectTargetApp(app) },
                  onTestSearch = { onTestAppSearch(app) },
                  onToggleNeverUse = { onToggleNeverUseApp(app) },
                )
              }
            }
          }
        }
      }

      // 3. Language
      item {
        SettingsSectionCard(title = "3. Language", cardPadding = cardPad) {
          Text(
            text = "Sent to the server only when not set to Auto.",
            style = MaterialTheme.typography.bodyMedium,
            color = TvTextSecondary,
          )
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            LanguageOption.entries.forEach { option ->
              val selected = state.settings.language == option
              TvSecondaryButton(
                onClick = { onSelectLanguage(option) },
                isSelected = selected,
                modifier = Modifier.testTag("lang_${option.name.lowercase()}"),
              ) {
                if (selected) {
                  Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = TvCyan,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                  text = option.label,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                )
              }
            }
          }
        }
      }

      // 4. Remote Mic Button & Audio Source
      item {
        SettingsSectionCard(title = "4. Remote Mic & Audio", cardPadding = cardPad) {
          TvSwitchRow(
            checked = state.settings.micButtonCapture,
            onCheckedChange = onToggleMicCapture,
            title = "Mic button capture",
            subtitle =
              if (state.settings.micButtonCapture) {
                "On — catches the remote mic key (KEYCODE_SEARCH) inside SmartTube and other apps"
              } else {
                "Off — lets foreground apps handle the mic button normally"
              },
            modifier = Modifier.testTag("mic_capture_switch"),
          )

          Text(
            text =
              "MicKeyService reads only Search/Assist key presses and the foreground app's package name — never screen text or UI content.",
            style = MaterialTheme.typography.bodyMedium,
            color = TvTextSecondary,
            modifier = Modifier.testTag("mic_privacy_note"),
          )

          Text(
            text = "Microphone Source",
            style = MaterialTheme.typography.bodyMedium,
            color = TvTextSecondary,
          )
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            MicSource.entries.forEach { source ->
              val selected = state.settings.micSource == source
              TvSecondaryButton(
                onClick = { onSelectMicSource(source) },
                isSelected = selected,
              ) {
                Text(
                  text = source.label,
                  style = MaterialTheme.typography.labelLarge,
                  maxLines = 1,
                )
              }
            }
          }
        }
      }

      // 5. Assistant & Accessibility Status
      item {
        val status = state.assistantStatus
        SettingsSectionCard(title = "5. Assistant & Service Status", cardPadding = cardPad) {
          if (status != null) {
            RoleStatusRow(
              label = "Default Assistant",
              active = status.isFullAssistantActive,
              detail =
                if (status.isFullAssistantActive) "Active (this app)"
                else "Currently: ${status.currentAssistant}",
            )
            RoleStatusRow(
              label = "Speech Recognizer",
              active = status.isRecognizerActive,
              detail =
                if (status.isRecognizerActive) "Active (this app)"
                else "Currently: ${status.currentRecognizer}",
            )
            RoleStatusRow(
              label = "Mic Key Accessibility Service",
              active = status.isMicKeyServiceEnabled,
              detail =
                if (status.isMicKeyServiceEnabled) "Enabled (catches mic key inside apps)"
                else "Disabled — enable in TV Accessibility settings or via ADB below",
            )

            if (state.settings.lastAssistArgsLog.isNotBlank()) {
              Text(
                text = "Last Assist onShow log: ${state.settings.lastAssistArgsLog}",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )
            }

            AdbCommandBox(
              title = "ADB Setup Commands",
              commands = status.setupCommands,
              onCopy = { onCopyAdbCommand("setup ADB commands", status.setupCommands) },
            )

            AdbCommandBox(
              title = "ADB Undo Commands (restore Google TV)",
              commands = status.undoCommands,
              onCopy = { onCopyAdbCommand("undo ADB commands", status.undoCommands) },
            )

            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              TvSecondaryButton(onClick = onRefreshAssistantStatus) {
                Text("Refresh Status", style = MaterialTheme.typography.labelLarge, maxLines = 1)
              }
              state.copiedBanner?.let { banner ->
                Text(
                  text = banner,
                  style = MaterialTheme.typography.bodyMedium,
                  color = TvEmerald,
                )
              }
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun EndpointStatusBadge(
  testStatus: TestStatus,
  cachedOkMessage: String?,
  modifier: Modifier = Modifier,
) {
  when {
    testStatus == TestStatus.Testing -> {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvCyan.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, TvCyan.copy(alpha = 0.45f)),
        modifier = modifier,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(
            color = TvCyan,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = "Testing endpoint & querying models…",
            style = MaterialTheme.typography.bodyMedium,
            color = TvCyan,
          )
        }
      }
    }
    testStatus is TestStatus.Ok -> {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvEmerald.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, TvEmerald.copy(alpha = 0.6f)),
        modifier = modifier,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Connected",
            tint = TvEmerald,
            modifier = Modifier.size(24.dp),
          )
          Text(
            text = testStatus.message,
            style = MaterialTheme.typography.bodyMedium,
            color = TvEmerald,
            modifier = Modifier.testTag("test_result_text"),
          )
        }
      }
    }
    testStatus is TestStatus.Warning -> {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvAmber.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, TvAmber.copy(alpha = 0.6f)),
        modifier = modifier,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = "Warning",
            tint = TvAmber,
            modifier = Modifier.size(24.dp),
          )
          Text(
            text = testStatus.message,
            style = MaterialTheme.typography.bodyMedium,
            color = TvAmber,
            modifier = Modifier.testTag("test_result_text"),
          )
        }
      }
    }
    testStatus is TestStatus.Error -> {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvCoral.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, TvCoral.copy(alpha = 0.6f)),
        modifier = modifier,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = "Error",
            tint = TvCoral,
            modifier = Modifier.size(24.dp),
          )
          Text(
            text = testStatus.message,
            style = MaterialTheme.typography.bodyMedium,
            color = TvCoral,
            modifier = Modifier.testTag("test_result_text"),
          )
        }
      }
    }
    cachedOkMessage != null -> {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvEmerald.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, TvEmerald.copy(alpha = 0.45f)),
        modifier = modifier,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Active",
            tint = TvEmerald,
            modifier = Modifier.size(22.dp),
          )
          Text(
            text = cachedOkMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = TvEmerald,
            modifier = Modifier.testTag("test_result_text"),
          )
        }
      }
    }
  }
}

@Composable
private fun TvSwitchRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
) {
  TvFocusableCard(
    onClick = { onCheckedChange(!checked) },
    isSelected = checked,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = TvTextPrimary,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = TvTextSecondary,
        )
      }
      Switch(
        checked = checked,
        onCheckedChange = null,
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = TvBackground,
            checkedTrackColor = TvCyan,
            uncheckedThumbColor = TvTextSecondary,
            uncheckedTrackColor = TvSurface,
          ),
      )
    }
  }
}

@Composable
private fun TvCheckboxRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
) {
  TvFocusableCard(
    onClick = { onCheckedChange(!checked) },
    isSelected = checked,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Checkbox(
        checked = checked,
        onCheckedChange = null,
        colors =
          CheckboxDefaults.colors(
            checkedColor = TvCyan,
            uncheckedColor = TvTextSecondary,
            checkmarkColor = TvBackground,
          ),
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = TvTextPrimary,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = TvTextSecondary,
        )
      }
    }
  }
}

@Composable
private fun EndpointTextField(
  value: String,
  onValueChange: (String) -> Unit,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvTextPrimary),
    placeholder = {
      Text(
        text = "10.3.3.20:8000 or https://…",
        style = MaterialTheme.typography.bodyMedium,
        color = TvTextSecondary.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    shape = RoundedCornerShape(14.dp),
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedContainerColor = TvSurfaceFocus,
        unfocusedContainerColor = TvSurfaceElevated,
        focusedBorderColor = TvCyan,
        unfocusedBorderColor = TvOutline,
        cursorColor = TvCyan,
      ),
    modifier = modifier,
  )
}

@Composable
private fun TestEndpointButton(
  isTesting: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TvPrimaryButton(
    onClick = onClick,
    enabled = !isTesting,
    modifier = modifier,
  ) {
    if (isTesting) {
      CircularProgressIndicator(
        color = TvBackground,
        strokeWidth = 3.dp,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text("Testing…", style = MaterialTheme.typography.titleMedium, maxLines = 1)
    } else {
      Text(
        "Test Connection",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun SettingsSectionCard(
  title: String,
  cardPadding: Dp,
  content: @Composable () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = TvSurface,
    border = BorderStroke(1.dp, TvOutline),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(cardPadding),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = TvCyan,
      )
      content()
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetAppRow(
  app: InstalledTvApp,
  isSelected: Boolean,
  handoffStatus: AppHandoffStatus,
  isCompact: Boolean,
  onClick: () -> Unit,
  onTestSearch: () -> Unit,
  onToggleNeverUse: () -> Unit,
) {
  val supported = app.handoffMethod != HandoffMethod.NOT_SUPPORTED
  val isBlocked = handoffStatus == AppHandoffStatus.NOT_WORKING

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    TvFocusableCard(
      onClick = onClick,
      enabled = supported && !isBlocked,
      isSelected = isSelected,
      modifier = Modifier.fillMaxWidth().testTag("app_item_${app.packageName}"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        val bmp = app.iconBitmap
        if (bmp != null) {
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier.size(40.dp),
          )
        } else {
          Icon(
            painter = painterResource(id = R.drawable.ic_tv),
            contentDescription = app.label,
            tint = TvCyan,
            modifier = Modifier.size(40.dp),
          )
        }

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            color = TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = app.packageName,
            style = MaterialTheme.typography.bodyMedium,
            color = TvTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (isCompact) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              HandoffBadge(method = app.handoffMethod, supported = supported)
              VerificationBadge(status = handoffStatus)
            }
          }
        }

        if (!isCompact) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            HandoffBadge(method = app.handoffMethod, supported = supported)
            VerificationBadge(status = handoffStatus)
          }
        }

        if (isSelected) {
          Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = "Selected",
            tint = TvEmerald,
            modifier = Modifier.size(26.dp),
          )
        }
      }
    }

    if (supported || isBlocked) {
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TvSecondaryButton(
          onClick = onTestSearch,
          enabled = supported && !isBlocked,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
          modifier = Modifier.testTag("test_app_${app.packageName}"),
        ) {
          Text("Test search", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }

        TvSecondaryButton(
          onClick = onToggleNeverUse,
          isSelected = isBlocked,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
          modifier = Modifier.testTag("block_app_${app.packageName}"),
        ) {
          Text(
            text = if (isBlocked) "Allow app" else "Never use this app",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun HandoffBadge(method: HandoffMethod, supported: Boolean) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = if (supported) TvSurface else TvCoral.copy(alpha = 0.2f),
    border = BorderStroke(1.dp, if (supported) TvOutline else TvCoral),
  ) {
    Text(
      text = method.label,
      style = MaterialTheme.typography.labelMedium,
      color = if (supported) TvCyan else TvCoral,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
  }
}

@Composable
private fun VerificationBadge(status: AppHandoffStatus) {
  val (bg, border, fg) =
    when (status) {
      AppHandoffStatus.CONFIRMED ->
        Triple(TvEmerald.copy(alpha = 0.16f), TvEmerald.copy(alpha = 0.6f), TvEmerald)
      AppHandoffStatus.NOT_WORKING ->
        Triple(TvCoral.copy(alpha = 0.16f), TvCoral.copy(alpha = 0.6f), TvCoral)
      AppHandoffStatus.UNKNOWN -> Triple(TvSurface, TvOutline, TvTextSecondary)
    }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = bg,
    border = BorderStroke(1.dp, border),
  ) {
    Text(
      text = status.label,
      style = MaterialTheme.typography.labelMedium,
      color = fg,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
  }
}

@Composable
private fun RoleStatusRow(label: String, active: Boolean, detail: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
      contentDescription = null,
      tint = if (active) TvEmerald else TvAmber,
      modifier = Modifier.size(24.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = TvTextPrimary,
      )
      Text(
        text = detail,
        style = MaterialTheme.typography.bodyMedium,
        color = if (active) TvEmerald else TvTextSecondary,
      )
    }
  }
}

@Composable
private fun AdbCommandBox(
  title: String,
  commands: String,
  onCopy: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = TvBackground,
    border = BorderStroke(1.dp, TvOutline),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          color = TvTextSecondary,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        TvSecondaryButton(
          onClick = onCopy,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
          Icon(
            painter = painterResource(id = R.drawable.ic_copy),
            contentDescription = "Copy",
            tint = TvCyan,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Copy", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
      }

      Text(
        text = commands,
        style =
          MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            lineHeight = 26.sp,
          ),
        color = TvTextPrimary,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      )
    }
  }
}
