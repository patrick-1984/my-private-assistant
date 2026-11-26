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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
  onToggleApiKey: (Boolean) -> Unit,
  onTestEndpoint: () -> Unit,
  onSelectTargetApp: (InstalledTvApp) -> Unit,
  onSelectLanguage: (LanguageOption) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onModelNameChange: (String) -> Unit,
  onSelectMicSource: (MicSource) -> Unit,
  onRefreshAssistantStatus: () -> Unit,
  onCopyAdbCommand: (String, String) -> Unit,
) {
  var advancedExpanded by remember { mutableStateOf(false) }

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(TvBackground),
    contentAlignment = Alignment.TopCenter,
  ) {
    val isCompact = maxWidth < 640.dp
    val hPad = if (isCompact) 16.dp else 36.dp
    val vPad = if (isCompact) 16.dp else 28.dp
    val cardPad = if (isCompact) 18.dp else 24.dp

    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp),
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

      // 1. Transcription endpoint
      item {
        SettingsSectionCard(title = "1. Transcription Endpoint", cardPadding = cardPad) {
          if (isCompact) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              EndpointTextField(
                value = state.settings.endpointInput,
                onValueChange = onEndpointChange,
                onDone = onTestEndpoint,
                modifier = Modifier.fillMaxWidth().testTag("endpoint_input"),
              )
              TestEndpointButton(
                isTesting = state.testStatus == TestStatus.Testing,
                onClick = onTestEndpoint,
                modifier = Modifier.fillMaxWidth().testTag("test_endpoint_button"),
              )
            }
          } else {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              EndpointTextField(
                value = state.settings.endpointInput,
                onValueChange = onEndpointChange,
                onDone = onTestEndpoint,
                modifier = Modifier.weight(1f).testTag("endpoint_input"),
              )
              TestEndpointButton(
                isTesting = state.testStatus == TestStatus.Testing,
                onClick = onTestEndpoint,
                modifier = Modifier.testTag("test_endpoint_button"),
              )
            }
          }

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

          // Checkbox 2: Provide API key (OpenAI Authorization: Bearer)
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

          if (state.settings.hasValidCachedEndpoint && state.testStatus == TestStatus.Idle) {
            val viaIp =
              if (state.settings.useIpOverride && state.settings.overrideIp.isNotBlank()) {
                " (via ${state.settings.overrideIp.trim()})"
              } else {
                ""
              }
            Text(
              text =
                "Active: ${state.settings.detectedFamily?.label} at ${state.settings.detectedEndpointUrl}$viaIp",
              style = MaterialTheme.typography.bodyMedium,
              color = TvEmerald,
            )
          }

          when (val status = state.testStatus) {
            TestStatus.Idle -> Unit
            TestStatus.Testing ->
              Text(
                text = "Probing OpenAI, whisper.cpp, and whisper-asr routes…",
                style = MaterialTheme.typography.bodyMedium,
                color = TvCyan,
              )
            is TestStatus.Ok ->
              Text(
                text = status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TvEmerald,
                modifier = Modifier.testTag("test_result_text"),
              )
            is TestStatus.Warning ->
              Text(
                text = status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TvAmber,
                modifier = Modifier.testTag("test_result_text"),
              )
            is TestStatus.Error ->
              Text(
                text = status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TvCoral,
                modifier = Modifier.testTag("test_result_text"),
              )
          }
        }
      }

      // 2. Target app
      item {
        SettingsSectionCard(title = "2. Target App", cardPadding = cardPad) {
          Text(
            text = "Voice queries launch directly into the selected app's search:",
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              state.installedApps.forEach { app ->
                TargetAppRow(
                  app = app,
                  isSelected = app.packageName == state.settings.targetPackage,
                  isCompact = isCompact,
                  onClick = { onSelectTargetApp(app) },
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

      // 4. Advanced (collapsed by default)
      item {
        SettingsSectionCard(title = "4. Advanced", cardPadding = cardPad) {
          TvFocusableCard(
            onClick = { advancedExpanded = !advancedExpanded },
            modifier = Modifier.fillMaxWidth().testTag("advanced_toggle"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text =
                  if (advancedExpanded) "Hide model & microphone source"
                  else "Show model & microphone source",
                style = MaterialTheme.typography.bodyLarge,
                color = TvTextPrimary,
                modifier = Modifier.weight(1f),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Icon(
                painter =
                  painterResource(
                    id = if (advancedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                  ),
                contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                tint = TvCyan,
                modifier = Modifier.size(26.dp),
              )
            }
          }

          AnimatedVisibility(visible = advancedExpanded) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Text(
                text = "Model Name (default: whisper-1)",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )
              OutlinedTextField(
                value = state.settings.modelName,
                onValueChange = onModelNameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvTextPrimary),
                shape = RoundedCornerShape(14.dp),
                colors =
                  OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TvSurfaceFocus,
                    unfocusedContainerColor = TvSurfaceElevated,
                    focusedBorderColor = TvCyan,
                    unfocusedBorderColor = TvOutline,
                  ),
                modifier = Modifier.fillMaxWidth().testTag("model_input"),
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
        }
      }

      // 5. Assistant status
      item {
        val status = state.assistantStatus
        SettingsSectionCard(title = "5. Assistant & Recognizer Status", cardPadding = cardPad) {
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
        "Test",
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

@Composable
private fun TargetAppRow(
  app: InstalledTvApp,
  isSelected: Boolean,
  isCompact: Boolean,
  onClick: () -> Unit,
) {
  val supported = app.handoffMethod != HandoffMethod.NOT_SUPPORTED
  TvFocusableCard(
    onClick = onClick,
    enabled = supported,
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
          HandoffBadge(method = app.handoffMethod, supported = supported)
        }
      }

      if (!isCompact) {
        HandoffBadge(method = app.handoffMethod, supported = supported)
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
private fun RoleStatusRow(label: String, active: Boolean, detail: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = if (active) Icons.Rounded.Check else Icons.Rounded.Warning,
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
