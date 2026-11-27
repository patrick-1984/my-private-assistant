package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainUiState
import com.example.TestStatus
import com.example.data.EndpointProfile
import com.example.data.LanguageOption
import com.example.data.MicSource
import com.example.handoff.InstalledTvApp
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
  onStartNewProfile: () -> Unit,
  onEditProfile: (EndpointProfile) -> Unit,
  onActivateProfile: (EndpointProfile) -> Unit,
  onDeleteProfile: (EndpointProfile) -> Unit,
  onSaveProfileDone: () -> Unit,
  onCancelProfileDraft: () -> Unit,
  onProfileNameChange: (String) -> Unit,
  onEndpointChange: (String) -> Unit,
  onToggleIpOverride: (Boolean) -> Unit,
  onOverrideIpChange: (String) -> Unit,
  onToggleIgnoreSsl: (Boolean) -> Unit,
  onToggleApiKeyNotNeeded: (Boolean) -> Unit,
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
  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(TvBackground),
    contentAlignment = Alignment.TopCenter,
  ) {
    val isCompact = maxWidth < 640.dp
    val hPad = if (isCompact) 16.dp else 36.dp
    val vPad = if (isCompact) 16.dp else 28.dp
    val cardPad = if (isCompact) 18.dp else 24.dp

    val activeProfile = state.settings.activeProfile
    val draft = state.profileDraft

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

      // 1. Saved Configuration Sets (Master List)
      item {
        SettingsSectionCard(title = "1. Configuration Sets", cardPadding = cardPad) {
          Text(
            text =
              "Each set stores an endpoint, API key, target app, language, and mic setting. Activate, edit, or delete sets anytime:",
            style = MaterialTheme.typography.bodyMedium,
            color = TvTextSecondary,
          )

          AnimatedVisibility(visible = state.profileBanner != null) {
            state.profileBanner?.let { msg ->
              val color = if (state.isProfileBannerError) TvCoral else TvEmerald
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = color.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(
                    imageVector =
                      if (state.isProfileBannerError) Icons.Rounded.Warning
                      else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(22.dp),
                  )
                  Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.testTag("profile_banner_text"),
                  )
                }
              }
            }
          }

          if (state.settings.profiles.isEmpty()) {
            Text(
              text = "No saved configurations yet. Create one below.",
              style = MaterialTheme.typography.bodyMedium,
              color = TvAmber,
            )
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              state.settings.profiles.forEach { profile ->
                val isActive = profile.id == state.settings.activeProfileId
                ProfileListRow(
                  profile = profile,
                  isActive = isActive,
                  isBeingEdited = draft?.id == profile.id,
                  onActivate = { onActivateProfile(profile) },
                  onEdit = { onEditProfile(profile) },
                  onDelete = { onDeleteProfile(profile) },
                )
              }
            }
          }

          TvSecondaryButton(
            onClick = onStartNewProfile,
            modifier = Modifier.testTag("add_profile_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Add,
              contentDescription = null,
              tint = TvCyan,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Endpoint Configuration", style = MaterialTheme.typography.titleMedium, maxLines = 1)
          }
        }
      }

      // 2. Configuration Set Editor (auto-open on first run when activeProfile == null, or on Add / Edit)
      if (draft != null) {
        item {
          ConfigurationEditorCard(
            draft = draft,
            isNew = state.isDraftNew,
            testStatus = state.testStatus,
            availableModels = state.availableModels,
            isFetchingModels = state.isFetchingModels,
            installedApps = state.installedApps,
            isLoadingApps = state.isLoadingApps,
            handoffStatuses = state.settings.appHandoffStatuses,
            pendingVerificationApp = state.pendingVerificationApp,
            isCompact = isCompact,
            cardPad = cardPad,
            onProfileNameChange = onProfileNameChange,
            onEndpointChange = onEndpointChange,
            onToggleIpOverride = onToggleIpOverride,
            onOverrideIpChange = onOverrideIpChange,
            onToggleIgnoreSsl = onToggleIgnoreSsl,
            onToggleApiKeyNotNeeded = onToggleApiKeyNotNeeded,
            onApiKeyChange = onApiKeyChange,
            onTestEndpoint = onTestEndpoint,
            onToggleCustomModel = onToggleCustomModel,
            onRefreshModels = onRefreshModels,
            onModelNameChange = onModelNameChange,
            onToggleLiveTranscription = onToggleLiveTranscription,
            onToggleSearchInCurrentApp = onToggleSearchInCurrentApp,
            onSelectTargetApp = onSelectTargetApp,
            onTestAppSearch = onTestAppSearch,
            onConfirmAppTest = onConfirmAppTest,
            onDismissPendingVerification = onDismissPendingVerification,
            onToggleNeverUseApp = onToggleNeverUseApp,
            onSelectLanguage = onSelectLanguage,
            onSelectMicSource = onSelectMicSource,
            onSaveDone = onSaveProfileDone,
            onCancel = onCancelProfileDraft,
          )
        }
      } else if (activeProfile != null) {
        // Active Configuration quick controls when editor is closed
        item {
          TargetAppSectionCard(
            title = "2. Active Set — Target & Current-App Search (${activeProfile.displayName})",
            searchInCurrentApp = activeProfile.searchInCurrentApp,
            installedApps = state.installedApps,
            isLoadingApps = state.isLoadingApps,
            selectedTargetPackage = activeProfile.targetPackage,
            handoffStatuses = state.settings.appHandoffStatuses,
            pendingVerificationApp = state.pendingVerificationApp,
            isCompact = isCompact,
            cardPad = cardPad,
            onToggleSearchInCurrentApp = onToggleSearchInCurrentApp,
            onSelectTargetApp = onSelectTargetApp,
            onTestAppSearch = onTestAppSearch,
            onConfirmAppTest = onConfirmAppTest,
            onDismissPendingVerification = onDismissPendingVerification,
            onToggleNeverUseApp = onToggleNeverUseApp,
          )
        }
      }

      // Global: Remote Mic Button & Accessibility
      item {
        SettingsSectionCard(title = "Remote Mic Key (Global)", cardPadding = cardPad) {
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
        }
      }

      // Global: Assistant & Accessibility Status
      item {
        val status = state.assistantStatus
        SettingsSectionCard(title = "Assistant & Service Status", cardPadding = cardPad) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileListRow(
  profile: EndpointProfile,
  isActive: Boolean,
  isBeingEdited: Boolean,
  onActivate: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val borderColor =
    when {
      isActive -> TvEmerald
      isBeingEdited -> TvCyan
      else -> TvOutline
    }
  val bgColor =
    when {
      isActive -> TvEmerald.copy(alpha = 0.09f)
      isBeingEdited -> TvSurfaceFocus
      else -> TvSurfaceElevated
    }

  val modelLabel = if (profile.useCustomModel) profile.modelName else "whisper-1"
  val liveLabel = if (profile.liveTranscription) "Live" else "Batch"
  val summaryLine = "$modelLabel • ${profile.targetLabel} • $liveLabel"

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = bgColor,
    border = BorderStroke(if (isActive || isBeingEdited) 2.dp else 1.dp, borderColor),
    modifier = Modifier.fillMaxWidth().testTag("profile_row_${profile.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = profile.displayName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
          val badgeBg: androidx.compose.ui.graphics.Color
          val badgeBorder: androidx.compose.ui.graphics.Color
          val badgeFg: androidx.compose.ui.graphics.Color
          val badgeText: String
          when {
            isActive -> {
              badgeBg = TvEmerald.copy(alpha = 0.16f)
              badgeBorder = TvEmerald.copy(alpha = 0.65f)
              badgeFg = TvEmerald
              badgeText = "Active"
            }
            profile.isMissingRequiredApiKey -> {
              badgeBg = TvCoral.copy(alpha = 0.16f)
              badgeBorder = TvCoral.copy(alpha = 0.65f)
              badgeFg = TvCoral
              badgeText = "API key needed"
            }
            else -> {
              badgeBg = TvSurface
              badgeBorder = TvOutline
              badgeFg = TvTextSecondary
              badgeText = "Inactive"
            }
          }
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = badgeBg,
            border = BorderStroke(1.dp, badgeBorder),
            modifier = Modifier.align(Alignment.CenterVertically),
          ) {
            Text(
              text = badgeText,
              style = MaterialTheme.typography.labelMedium,
              color = badgeFg,
              maxLines = 1,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }
        Text(
          text = summaryLine,
          style = MaterialTheme.typography.bodyMedium,
          color = TvTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TvIconActionButton(
          icon = if (isActive) Icons.Rounded.CheckCircle else Icons.Rounded.PlayArrow,
          contentDescription =
            if (isActive) "Active: ${profile.displayName}" else "Activate ${profile.displayName}",
          tint = if (isActive) TvEmerald else TvCyan,
          isHighlighted = isActive,
          onClick = onActivate,
          modifier = Modifier.testTag("activate_profile_${profile.id}"),
        )
        TvIconActionButton(
          icon = Icons.Rounded.Edit,
          contentDescription = "Edit ${profile.displayName}",
          tint = TvTextPrimary,
          isHighlighted = isBeingEdited,
          onClick = onEdit,
          modifier = Modifier.testTag("edit_profile_${profile.id}"),
        )
        TvIconActionButton(
          icon = Icons.Rounded.Delete,
          contentDescription = "Delete ${profile.displayName}",
          tint = TvCoral,
          onClick = onDelete,
          modifier = Modifier.testTag("delete_profile_${profile.id}"),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigurationEditorCard(
  draft: EndpointProfile,
  isNew: Boolean,
  testStatus: TestStatus,
  availableModels: List<String>,
  isFetchingModels: Boolean,
  installedApps: List<InstalledTvApp>,
  isLoadingApps: Boolean,
  handoffStatuses: Map<String, com.example.data.AppHandoffStatus>,
  pendingVerificationApp: InstalledTvApp?,
  isCompact: Boolean,
  cardPad: Dp,
  onProfileNameChange: (String) -> Unit,
  onEndpointChange: (String) -> Unit,
  onToggleIpOverride: (Boolean) -> Unit,
  onOverrideIpChange: (String) -> Unit,
  onToggleIgnoreSsl: (Boolean) -> Unit,
  onToggleApiKeyNotNeeded: (Boolean) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onTestEndpoint: () -> Unit,
  onToggleCustomModel: (Boolean) -> Unit,
  onRefreshModels: () -> Unit,
  onModelNameChange: (String) -> Unit,
  onToggleLiveTranscription: (Boolean) -> Unit,
  onToggleSearchInCurrentApp: (Boolean) -> Unit,
  onSelectTargetApp: (InstalledTvApp) -> Unit,
  onTestAppSearch: (InstalledTvApp) -> Unit,
  onConfirmAppTest: (Boolean) -> Unit,
  onDismissPendingVerification: () -> Unit,
  onToggleNeverUseApp: (InstalledTvApp) -> Unit,
  onSelectLanguage: (LanguageOption) -> Unit,
  onSelectMicSource: (MicSource) -> Unit,
  onSaveDone: () -> Unit,
  onCancel: () -> Unit,
) {
  val testPassed = testStatus is TestStatus.Ok || draft.hasValidCachedEndpoint
  val title =
    if (isNew) "2. New Configuration — What is your transcription endpoint?"
    else "2. Edit Configuration: ${draft.displayName}"

  SettingsSectionCard(
    title = title,
    cardPadding = cardPad,
    modifier = Modifier.testTag("profile_editor_card"),
  ) {
    Text(
      text = "Configuration name (optional — auto-uses host or IP if blank)",
      style = MaterialTheme.typography.bodyMedium,
      color = TvTextSecondary,
    )
    TvOutlinedInput(
      value = draft.customName,
      onValueChange = onProfileNameChange,
      placeholderText = "Auto: ${draft.displayName}",
      modifier = Modifier.fillMaxWidth().testTag("profile_name_input"),
    )

    Text(
      text = "Transcription endpoint (host, URL, or full path)",
      style = MaterialTheme.typography.bodyMedium,
      color = TvTextSecondary,
    )
    TvOutlinedInput(
      value = draft.endpointInput,
      onValueChange = onEndpointChange,
      placeholderText = "10.3.3.20:8000 or https://…",
      onDone = onTestEndpoint,
      modifier = Modifier.fillMaxWidth().testTag("endpoint_input"),
    )

    TvCheckboxRow(
      checked = draft.useIpOverride,
      onCheckedChange = onToggleIpOverride,
      title = "Override server IP address",
      subtitle =
        "Connect directly to this IP (LAN / Tailscale) while keeping the hostname and Host/SNI headers",
      modifier = Modifier.testTag("use_ip_override_checkbox"),
    )

    AnimatedVisibility(visible = draft.useIpOverride) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Server IP address (IP only, no port or path)",
          style = MaterialTheme.typography.bodyMedium,
          color = TvTextSecondary,
        )
        TvOutlinedInput(
          value = draft.overrideIp,
          onValueChange = onOverrideIpChange,
          placeholderText = "e.g. 10.3.3.20 or 100.64.1.5",
          onDone = onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("override_ip_input"),
        )
      }
    }

    TvCheckboxRow(
      checked = draft.ignoreSsl,
      onCheckedChange = onToggleIgnoreSsl,
      title = "Ignore SSL certificate checks",
      subtitle = "Allow self-signed or private-CA HTTPS certificates on local/Tailscale servers",
      modifier = Modifier.testTag("use_ignore_ssl_checkbox"),
    )

    // API key input + "API key not needed" checkbox
    AnimatedVisibility(visible = !draft.apiKeyNotNeeded) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "API key (sent as Authorization: Bearer)",
          style = MaterialTheme.typography.bodyMedium,
          color = if (draft.apiKey.isBlank()) TvAmber else TvTextSecondary,
        )
        TvOutlinedInput(
          value = draft.apiKey,
          onValueChange = onApiKeyChange,
          placeholderText = "Missing API key (nvapi-… or sk-…)",
          visualTransformation = PasswordVisualTransformation(),
          onDone = onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
        )
      }
    }

    TvCheckboxRow(
      checked = draft.apiKeyNotNeeded,
      onCheckedChange = onToggleApiKeyNotNeeded,
      title = "API key not needed",
      subtitle = "Allow activating and calling this endpoint without an API key",
      modifier = Modifier.testTag("api_key_not_needed_checkbox"),
    )

    // Test Connection bar
    val cachedLabel =
      if (draft.hasValidCachedEndpoint && testStatus == TestStatus.Idle) {
        val viaIp =
          if (draft.useIpOverride && draft.overrideIp.isNotBlank()) " (via ${draft.overrideIp.trim()})"
          else ""
        "Ready: ${draft.detectedEndpointUrl}$viaIp"
      } else {
        null
      }

    if (isCompact) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TestEndpointButton(
          isTesting = testStatus == TestStatus.Testing,
          onClick = onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("test_endpoint_button"),
        )
        EndpointStatusBadge(testStatus = testStatus, cachedOkMessage = cachedLabel, modifier = Modifier.fillMaxWidth())
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TestEndpointButton(
          isTesting = testStatus == TestStatus.Testing,
          onClick = onTestEndpoint,
          modifier = Modifier.testTag("test_endpoint_button"),
        )
        EndpointStatusBadge(testStatus = testStatus, cachedOkMessage = cachedLabel, modifier = Modifier.weight(1f))
      }
    }

    // Model & live streaming options
    AnimatedVisibility(visible = testPassed || draft.useCustomModel || draft.liveTranscription) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvCheckboxRow(
          checked = draft.useCustomModel,
          onCheckedChange = onToggleCustomModel,
          title = "Pick custom model / endpoint",
          subtitle =
            if (availableModels.isNotEmpty()) {
              "Discovered ${availableModels.size} speech/OpenAI model(s)"
            } else {
              "Query OpenAI /v1/models or specify a model ID (e.g. nvidia/parakeet-tdt-0.6b)"
            },
          modifier = Modifier.testTag("use_custom_model_checkbox"),
        )

        AnimatedVisibility(visible = draft.useCustomModel) {
          Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text =
                  when {
                    isFetchingModels -> "Querying /v1/models…"
                    availableModels.isNotEmpty() -> "Server speech models:"
                    else -> "Model ID (sent to transcription endpoint):"
                  },
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
                modifier = Modifier.weight(1f),
              )
              TvSecondaryButton(
                onClick = onRefreshModels,
                enabled = !isFetchingModels,
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

            if (availableModels.isNotEmpty()) {
              FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                availableModels.forEach { modelId ->
                  val selected = draft.modelName == modelId
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

            TvOutlinedInput(
              value = draft.modelName,
              onValueChange = onModelNameChange,
              placeholderText = "whisper-1",
              modifier = Modifier.fillMaxWidth().testTag("model_input"),
            )
          }
        }

        TvCheckboxRow(
          checked = draft.liveTranscription,
          onCheckedChange = onToggleLiveTranscription,
          title = "Live transcription (show dictation as you speak)",
          subtitle = "Streams SSE deltas or progressive audio chunks into the search bar live",
          modifier = Modifier.testTag("live_transcription_checkbox"),
        )
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    // Default Target App & Current-App Search belonging to this Configuration Set
    Text(
      text = "Target App & Search Routing (for this configuration)",
      style = MaterialTheme.typography.titleMedium,
      color = TvCyan,
    )
    TargetAppControlsBlock(
      searchInCurrentApp = draft.searchInCurrentApp,
      installedApps = installedApps,
      isLoadingApps = isLoadingApps,
      selectedTargetPackage = draft.targetPackage,
      handoffStatuses = handoffStatuses,
      pendingVerificationApp = pendingVerificationApp,
      isCompact = isCompact,
      onToggleSearchInCurrentApp = onToggleSearchInCurrentApp,
      onSelectTargetApp = onSelectTargetApp,
      onTestAppSearch = onTestAppSearch,
      onConfirmAppTest = onConfirmAppTest,
      onDismissPendingVerification = onDismissPendingVerification,
      onToggleNeverUseApp = onToggleNeverUseApp,
    )

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    // Language & Mic Source belonging to this Configuration Set
    Text(
      text = "Language (sent only when not Auto)",
      style = MaterialTheme.typography.titleMedium,
      color = TvCyan,
    )
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      LanguageOption.entries.forEach { option ->
        val selected = draft.language == option
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
          Text(text = option.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
      }
    }

    Text(
      text = "Microphone Source",
      style = MaterialTheme.typography.titleMedium,
      color = TvCyan,
    )
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      MicSource.entries.forEach { source ->
        val selected = draft.micSource == source
        TvSecondaryButton(
          onClick = { onSelectMicSource(source) },
          isSelected = selected,
        ) {
          Text(text = source.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    // Done & Cancel row (Done works with or without running Test Connection!)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      TvPrimaryButton(
        onClick = onSaveDone,
        modifier = Modifier.testTag("profile_done_button"),
      ) {
        Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Done",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
        )
      }

      TvSecondaryButton(
        onClick = onCancel,
        modifier = Modifier.testTag("profile_cancel_button"),
      ) {
        Text("Cancel", style = MaterialTheme.typography.titleMedium, maxLines = 1)
      }
    }
  }
}

@Composable
private fun TargetAppSectionCard(
  title: String,
  searchInCurrentApp: Boolean,
  installedApps: List<InstalledTvApp>,
  isLoadingApps: Boolean,
  selectedTargetPackage: String,
  handoffStatuses: Map<String, com.example.data.AppHandoffStatus>,
  pendingVerificationApp: InstalledTvApp?,
  isCompact: Boolean,
  cardPad: Dp,
  onToggleSearchInCurrentApp: (Boolean) -> Unit,
  onSelectTargetApp: (InstalledTvApp) -> Unit,
  onTestAppSearch: (InstalledTvApp) -> Unit,
  onConfirmAppTest: (Boolean) -> Unit,
  onDismissPendingVerification: () -> Unit,
  onToggleNeverUseApp: (InstalledTvApp) -> Unit,
) {
  SettingsSectionCard(title = title, cardPadding = cardPad) {
    TargetAppControlsBlock(
      searchInCurrentApp = searchInCurrentApp,
      installedApps = installedApps,
      isLoadingApps = isLoadingApps,
      selectedTargetPackage = selectedTargetPackage,
      handoffStatuses = handoffStatuses,
      pendingVerificationApp = pendingVerificationApp,
      isCompact = isCompact,
      onToggleSearchInCurrentApp = onToggleSearchInCurrentApp,
      onSelectTargetApp = onSelectTargetApp,
      onTestAppSearch = onTestAppSearch,
      onConfirmAppTest = onConfirmAppTest,
      onDismissPendingVerification = onDismissPendingVerification,
      onToggleNeverUseApp = onToggleNeverUseApp,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetAppControlsBlock(
  searchInCurrentApp: Boolean,
  installedApps: List<InstalledTvApp>,
  isLoadingApps: Boolean,
  selectedTargetPackage: String,
  handoffStatuses: Map<String, com.example.data.AppHandoffStatus>,
  pendingVerificationApp: InstalledTvApp?,
  isCompact: Boolean,
  onToggleSearchInCurrentApp: (Boolean) -> Unit,
  onSelectTargetApp: (InstalledTvApp) -> Unit,
  onTestAppSearch: (InstalledTvApp) -> Unit,
  onConfirmAppTest: (Boolean) -> Unit,
  onDismissPendingVerification: () -> Unit,
  onToggleNeverUseApp: (InstalledTvApp) -> Unit,
) {
  TvSwitchRow(
    checked = searchInCurrentApp,
    onCheckedChange = onToggleSearchInCurrentApp,
    title = "Search in the current app",
    subtitle =
      "Send voice queries to the active app when it supports search; otherwise fall back to the default app below",
    modifier = Modifier.testTag("search_current_app_switch"),
  )

  AnimatedVisibility(visible = pendingVerificationApp != null) {
    pendingVerificationApp?.let { pendingApp ->
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
              Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
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

  if (isLoadingApps && installedApps.isEmpty()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      CircularProgressIndicator(color = TvCyan, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
      Text("Scanning installed TV apps…", style = MaterialTheme.typography.bodyMedium, color = TvTextSecondary)
    }
  } else if (installedApps.isEmpty()) {
    Text("No installed TV apps found.", style = MaterialTheme.typography.bodyMedium, color = TvAmber)
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      installedApps.forEach { app ->
        TargetAppRow(
          app = app,
          isSelected = app.packageName == selectedTargetPackage,
          handoffStatus = handoffStatuses[app.packageName] ?: com.example.data.AppHandoffStatus.UNKNOWN,
          isCompact = isCompact,
          onClick = { onSelectTargetApp(app) },
          onTestSearch = { onTestAppSearch(app) },
          onToggleNeverUse = { onToggleNeverUseApp(app) },
        )
      }
    }
  }
}
