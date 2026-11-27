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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainUiState
import com.example.SettingsPage
import com.example.data.AppSettingsPreset
import com.example.data.ConfigurationPreset
import com.example.data.EndpointHealthStatus
import com.example.data.EndpointPreset
import com.example.data.EndpointSlot
import com.example.ui.components.TvSecondaryButton
import com.example.ui.theme.TvAmber
import com.example.ui.theme.TvBackground
import com.example.ui.theme.TvCoral
import com.example.ui.theme.TvCyan
import com.example.ui.theme.TvEmerald
import com.example.ui.theme.TvOutline
import com.example.ui.theme.TvSurface
import com.example.ui.theme.TvSurfaceElevated
import com.example.ui.theme.TvTextPrimary
import com.example.ui.theme.TvTextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  state: MainUiState,
  actions: SettingsActions,
) {
  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(TvBackground),
    contentAlignment = Alignment.TopCenter,
  ) {
    val isCompact = maxWidth < 640.dp
    val hPad = if (isCompact) 16.dp else 36.dp
    val vPad = if (isCompact) 16.dp else 28.dp
    val cardPad = if (isCompact) 18.dp else 24.dp

    val pageTitle =
      when (state.settingsPage) {
        SettingsPage.Home -> "Settings"
        is SettingsPage.EditConfiguration -> "Configuration Preset"
        is SettingsPage.EditEndpoint -> "Transcription Endpoint"
        is SettingsPage.EditAppPreset -> "App Settings Preset"
      }

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
            onClick = actions.onBack,
            modifier = Modifier.testTag("settings_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back",
              tint = TvCyan,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (state.settingsPage == SettingsPage.Home) "Back" else "Settings",
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
            )
          }

          Text(
            text = pageTitle,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp),
            color = TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // Status / Validation Banner
      item {
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
      }

      when (val page = state.settingsPage) {
        is SettingsPage.EditConfiguration -> {
          item {
            ConfigurationPresetEditor(
              draft = page.draft,
              isNew = page.isNew,
              state = state,
              actions = actions,
              cardPad = cardPad,
            )
          }
        }

        is SettingsPage.EditEndpoint -> {
          item {
            EndpointPresetEditor(
              draft = page.draft,
              isNew = page.isNew,
              state = state,
              actions = actions,
              isCompact = isCompact,
              cardPad = cardPad,
            )
          }
        }

        is SettingsPage.EditAppPreset -> {
          item {
            AppPresetEditor(
              draft = page.draft,
              isNew = page.isNew,
              state = state,
              actions = actions,
              isCompact = isCompact,
              cardPad = cardPad,
            )
          }
        }

        SettingsPage.Home -> {
          // 1. Master Configuration Presets (Primary / Secondary / Tertiary + App Preset)
          item {
            SettingsSectionCard(
              title = "1. Configuration Presets",
              cardPadding = cardPad,
              modifier = Modifier.testTag("section_configurations"),
            ) {
              Text(
                text =
                  "Combine Primary, Secondary, and Tertiary endpoints with an App Settings preset. Activate any combination or open it to configure automatic failover.",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )

              if (state.settings.configurationPresets.isEmpty()) {
                Text(
                  text = "No configuration presets yet. Create one below.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = TvAmber,
                )
              } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  state.settings.configurationPresets.forEach { cfg ->
                    ConfigurationPresetRow(
                      config = cfg,
                      isActive = cfg.id == state.settings.activeConfigurationId,
                      state = state,
                      onActivate = { actions.onActivateConfig(cfg) },
                      onEdit = { actions.onEditConfig(cfg) },
                      onDelete = { actions.onDeleteConfig(cfg) },
                    )
                  }
                }
              }

              TvSecondaryButton(
                onClick = actions.onStartNewConfig,
                modifier = Modifier.testTag("add_config_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Add,
                  contentDescription = null,
                  tint = TvCyan,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Configuration Preset", style = MaterialTheme.typography.titleMedium, maxLines = 1)
              }
            }
          }

          // 2. Reusable Transcription Endpoints Library
          item {
            SettingsSectionCard(
              title = "2. Transcription Endpoints",
              cardPadding = cardPad,
              modifier = Modifier.testTag("section_endpoints"),
            ) {
              Text(
                text =
                  "Manage speech servers & API keys independently, then assign them as Primary, Secondary, or Tertiary in any Configuration Preset.",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )

              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.settings.endpointPresets.forEach { endpoint ->
                  val activeSlot = state.settings.activeConfiguration?.slotForEndpoint(endpoint.id)
                  EndpointPresetRow(
                    endpoint = endpoint,
                    activeSlot = activeSlot,
                    isChecking = endpoint.id in state.checkingEndpointIds,
                    healthRecord = state.endpointHealthRecords[endpoint.id],
                    onCheckHealth = { actions.onCheckEndpointHealth(endpoint) },
                    onEdit = { actions.onEditEndpoint(endpoint) },
                    onDelete = { actions.onDeleteEndpoint(endpoint) },
                  )
                }
              }

              TvSecondaryButton(
                onClick = { actions.onStartNewEndpoint(null) },
                modifier = Modifier.testTag("add_endpoint_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Add,
                  contentDescription = null,
                  tint = TvCyan,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Transcription Endpoint", style = MaterialTheme.typography.titleMedium, maxLines = 1)
              }
            }
          }

          // 3. Reusable App Settings Presets Library
          item {
            SettingsSectionCard(
              title = "3. Application Settings Presets",
              cardPadding = cardPad,
              modifier = Modifier.testTag("section_app_presets"),
            ) {
              Text(
                text = "Reusable presets for target app, current-app search, language, and mic source.",
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
              )

              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val activeAppId = state.settings.activeConfiguration?.appSettingsPresetId
                state.settings.appSettingsPresets.forEach { appPreset ->
                  AppPresetRow(
                    preset = appPreset,
                    isInActiveConfig = appPreset.id == activeAppId,
                    canDelete = state.settings.appSettingsPresets.size > 1,
                    onEdit = { actions.onEditAppPreset(appPreset) },
                    onDelete = { actions.onDeleteAppPreset(appPreset) },
                  )
                }
              }

              TvSecondaryButton(
                onClick = { actions.onStartNewAppPreset(false) },
                modifier = Modifier.testTag("add_app_preset_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Add,
                  contentDescription = null,
                  tint = TvCyan,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add App Settings Preset", style = MaterialTheme.typography.titleMedium, maxLines = 1)
              }
            }
          }

          // 4. Global: Remote Mic Button & Accessibility
          item {
            SettingsSectionCard(title = "Remote Mic Key (Global)", cardPadding = cardPad) {
              TvSwitchRow(
                checked = state.settings.micButtonCapture,
                onCheckedChange = actions.onToggleMicCapture,
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

          // 5. Global: Assistant & Accessibility Status
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
                  onCopy = { actions.onCopyAdbCommand("setup ADB commands", status.setupCommands) },
                )

                AdbCommandBox(
                  title = "ADB Undo Commands (restore Google TV)",
                  commands = status.undoCommands,
                  onCopy = { actions.onCopyAdbCommand("undo ADB commands", status.undoCommands) },
                )

                FlowRow(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  TvSecondaryButton(onClick = actions.onRefreshAssistantStatus) {
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
        }
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigurationPresetRow(
  config: ConfigurationPreset,
  isActive: Boolean,
  state: MainUiState,
  onActivate: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val endpoints = state.settings.endpointPresets
  val appPresets = state.settings.appSettingsPresets
  val primaryEp = state.settings.endpointById(config.primaryEndpointId)
  val secondaryEp = state.settings.endpointById(config.secondaryEndpointId)
  val tertiaryEp = state.settings.endpointById(config.tertiaryEndpointId)
  val appPreset = state.settings.appPresetById(config.appSettingsPresetId)
  val displayName = config.resolveDisplayName(endpoints, appPresets)

  val borderColor = if (isActive) TvEmerald else TvOutline
  val bgColor = if (isActive) TvEmerald.copy(alpha = 0.09f) else TvSurfaceElevated

  val endpointChain = buildList {
    primaryEp?.let { add("1° ${it.displayName}") }
    if (config.allowEndpointFailover) {
      secondaryEp?.let { add("2° ${it.displayName}") }
      tertiaryEp?.let { add("3° ${it.displayName}") }
    }
  }.joinToString(" → ").ifBlank { "No primary endpoint selected" }

  val appLine = "App: ${appPreset?.displayName ?: "Default TV"}"

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = bgColor,
    border = BorderStroke(if (isActive) 2.dp else 1.dp, borderColor),
    modifier = Modifier.fillMaxWidth().testTag("config_row_${config.id}"),
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
            text = displayName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
          when {
            isActive ->
              SmallStatusChip(
                text = "Active",
                bg = TvEmerald.copy(alpha = 0.16f),
                border = TvEmerald.copy(alpha = 0.65f),
                fg = TvEmerald,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
            primaryEp?.isMissingRequiredApiKey == true ->
              SmallStatusChip(
                text = "API key needed",
                bg = TvCoral.copy(alpha = 0.16f),
                border = TvCoral.copy(alpha = 0.65f),
                fg = TvCoral,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
            else ->
              SmallStatusChip(
                text = "Inactive",
                bg = TvSurface,
                border = TvOutline,
                fg = TvTextSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
          }
          if (config.allowEndpointFailover && (secondaryEp != null || tertiaryEp != null)) {
            SmallStatusChip(
              text = "Failover On",
              bg = TvCyan.copy(alpha = 0.12f),
              border = TvCyan.copy(alpha = 0.45f),
              fg = TvCyan,
              modifier = Modifier.align(Alignment.CenterVertically),
            )
          }
        }
        Text(
          text = endpointChain,
          style = MaterialTheme.typography.bodyMedium,
          color = TvTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = appLine,
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
          contentDescription = if (isActive) "Active: $displayName" else "Activate $displayName",
          tint = if (isActive) TvEmerald else TvCyan,
          isHighlighted = isActive,
          onClick = onActivate,
          modifier = Modifier.testTag("activate_config_${config.id}"),
        )
        TvIconActionButton(
          icon = Icons.Rounded.Edit,
          contentDescription = "Edit $displayName",
          tint = TvTextPrimary,
          onClick = onEdit,
          modifier = Modifier.testTag("edit_config_${config.id}"),
        )
        TvIconActionButton(
          icon = Icons.Rounded.Delete,
          contentDescription = "Delete $displayName",
          tint = TvCoral,
          onClick = onDelete,
          modifier = Modifier.testTag("delete_config_${config.id}"),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndpointPresetRow(
  endpoint: EndpointPreset,
  activeSlot: EndpointSlot?,
  isChecking: Boolean,
  healthRecord: com.example.data.EndpointHealthRecord?,
  onCheckHealth: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val validHealth = healthRecord?.takeIf { it.fingerprint == endpoint.healthFingerprint }
  val modelLabel = if (endpoint.useCustomModel) endpoint.modelName else "whisper-1"
  val modeLabel = if (endpoint.liveTranscription) "Live" else "Batch"
  val protoLabel = if (endpoint.isGrpcEndpoint) "gRPC • " else ""
  val summary = "$protoLabel$modelLabel • $modeLabel • ${endpoint.endpointInput.ifBlank { endpoint.overrideIp }}"

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (activeSlot != null) TvCyan.copy(alpha = 0.06f) else TvSurfaceElevated,
    border = BorderStroke(1.dp, if (activeSlot != null) TvCyan.copy(alpha = 0.45f) else TvOutline),
    modifier = Modifier.fillMaxWidth().testTag("endpoint_row_${endpoint.id}"),
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
            text = endpoint.displayName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
          if (activeSlot != null) {
            SmallStatusChip(
              text = "${activeSlot.shortLabel} ${activeSlot.label}",
              bg = TvCyan.copy(alpha = 0.16f),
              border = TvCyan.copy(alpha = 0.55f),
              fg = TvCyan,
              modifier = Modifier.align(Alignment.CenterVertically),
            )
          }
          when {
            endpoint.isMissingRequiredApiKey ->
              SmallStatusChip(
                text = "API key needed",
                bg = TvCoral.copy(alpha = 0.16f),
                border = TvCoral.copy(alpha = 0.65f),
                fg = TvCoral,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
            isChecking ->
              SmallStatusChip(
                text = "Checking…",
                bg = TvCyan.copy(alpha = 0.12f),
                border = TvCyan.copy(alpha = 0.5f),
                fg = TvCyan,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
            validHealth?.status == EndpointHealthStatus.HEALTHY ->
              SmallStatusChip(
                text = "Healthy",
                bg = TvEmerald.copy(alpha = 0.14f),
                border = TvEmerald.copy(alpha = 0.55f),
                fg = TvEmerald,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
            validHealth?.status == EndpointHealthStatus.DEGRADED ->
              SmallStatusChip(
                text = "Unavailable",
                bg = TvAmber.copy(alpha = 0.16f),
                border = TvAmber.copy(alpha = 0.65f),
                fg = TvAmber,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
          }
        }
        Text(
          text = summary,
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
          icon = Icons.Rounded.Refresh,
          contentDescription = "Check health of ${endpoint.displayName}",
          tint = TvCyan,
          onClick = onCheckHealth,
          modifier = Modifier.testTag("check_endpoint_${endpoint.id}"),
        )
        TvIconActionButton(
          icon = Icons.Rounded.Edit,
          contentDescription = "Edit endpoint ${endpoint.displayName}",
          tint = TvTextPrimary,
          onClick = onEdit,
          modifier = Modifier.testTag("edit_endpoint_${endpoint.id}"),
        )
        TvIconActionButton(
          icon = Icons.Rounded.Delete,
          contentDescription = "Delete endpoint ${endpoint.displayName}",
          tint = TvCoral,
          onClick = onDelete,
          modifier = Modifier.testTag("delete_endpoint_${endpoint.id}"),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppPresetRow(
  preset: AppSettingsPreset,
  isInActiveConfig: Boolean,
  canDelete: Boolean,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (isInActiveConfig) TvCyan.copy(alpha = 0.06f) else TvSurfaceElevated,
    border = BorderStroke(1.dp, if (isInActiveConfig) TvCyan.copy(alpha = 0.45f) else TvOutline),
    modifier = Modifier.fillMaxWidth().testTag("app_preset_row_${preset.id}"),
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
            text = preset.displayName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
          if (isInActiveConfig) {
            SmallStatusChip(
              text = "In active preset",
              bg = TvEmerald.copy(alpha = 0.14f),
              border = TvEmerald.copy(alpha = 0.55f),
              fg = TvEmerald,
              modifier = Modifier.align(Alignment.CenterVertically),
            )
          }
        }
        Text(
          text = preset.summaryLine,
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
          icon = Icons.Rounded.Edit,
          contentDescription = "Edit app preset ${preset.displayName}",
          tint = TvTextPrimary,
          onClick = onEdit,
          modifier = Modifier.testTag("edit_app_preset_${preset.id}"),
        )
        if (canDelete) {
          TvIconActionButton(
            icon = Icons.Rounded.Delete,
            contentDescription = "Delete app preset ${preset.displayName}",
            tint = TvCoral,
            onClick = onDelete,
            modifier = Modifier.testTag("delete_app_preset_${preset.id}"),
          )
        }
      }
    }
  }
}
