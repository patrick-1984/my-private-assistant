package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
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
import com.example.MainUiState
import com.example.TestStatus
import com.example.data.AppHandoffStatus
import com.example.data.AppSettingsPreset
import com.example.data.ConfigurationPreset
import com.example.data.EndpointHealthStatus
import com.example.data.EndpointPreset
import com.example.data.EndpointSlot
import com.example.data.LanguageOption
import com.example.data.MicSource
import com.example.data.NvidiaFunctionPreset
import com.example.handoff.InstalledTvApp
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.theme.TvAmber
import com.example.ui.theme.TvCoral
import com.example.ui.theme.TvCyan
import com.example.ui.theme.TvEmerald
import com.example.ui.theme.TvOutline
import com.example.ui.theme.TvSurfaceFocus
import com.example.ui.theme.TvTextPrimary
import com.example.ui.theme.TvTextSecondary

data class SettingsActions(
  val onBack: () -> Unit = {},
  val onStartNewConfig: () -> Unit = {},
  val onEditConfig: (ConfigurationPreset) -> Unit = {},
  val onActivateConfig: (ConfigurationPreset) -> Unit = {},
  val onDeleteConfig: (ConfigurationPreset) -> Unit = {},
  val onConfigNameChange: (String) -> Unit = {},
  val onConfigFailoverToggle: (Boolean) -> Unit = {},
  val onSelectConfigSlot: (EndpointSlot, String?) -> Unit = { _, _ -> },
  val onSelectConfigAppPreset: (String) -> Unit = {},
  val onSaveConfigDone: () -> Unit = {},
  val onCancelSubPage: () -> Unit = {},
  val onStartNewEndpoint: (EndpointSlot?) -> Unit = {},
  val onEditEndpoint: (EndpointPreset) -> Unit = {},
  val onDeleteEndpoint: (EndpointPreset) -> Unit = {},
  val onCheckEndpointHealth: (EndpointPreset) -> Unit = {},
  val onEndpointNameChange: (String) -> Unit = {},
  val onEndpointUrlChange: (String) -> Unit = {},
  val onFunctionIdChange: (String) -> Unit = {},
  val onApplyNvidiaCloudPreset: (NvidiaFunctionPreset?) -> Unit = {},
  val onToggleIpOverride: (Boolean) -> Unit = {},
  val onOverrideIpChange: (String) -> Unit = {},
  val onToggleIgnoreSsl: (Boolean) -> Unit = {},
  val onToggleApiKeyNotNeeded: (Boolean) -> Unit = {},
  val onApiKeyChange: (String) -> Unit = {},
  val onTestEndpoint: () -> Unit = {},
  val onToggleCustomModel: (Boolean) -> Unit = {},
  val onRefreshModels: () -> Unit = {},
  val onModelNameChange: (String) -> Unit = {},
  val onToggleLiveTranscription: (Boolean) -> Unit = {},
  val onSaveEndpointDone: () -> Unit = {},
  val onStartNewAppPreset: (Boolean) -> Unit = {},
  val onEditAppPreset: (AppSettingsPreset) -> Unit = {},
  val onDeleteAppPreset: (AppSettingsPreset) -> Unit = {},
  val onAppPresetNameChange: (String) -> Unit = {},
  val onToggleSearchInCurrentApp: (Boolean) -> Unit = {},
  val onSelectTargetApp: (InstalledTvApp) -> Unit = {},
  val onTestAppSearch: (InstalledTvApp) -> Unit = {},
  val onConfirmAppTest: (Boolean) -> Unit = {},
  val onDismissPendingVerification: () -> Unit = {},
  val onToggleNeverUseApp: (InstalledTvApp) -> Unit = {},
  val onSelectLanguage: (LanguageOption) -> Unit = {},
  val onSelectMicSource: (MicSource) -> Unit = {},
  val onSaveAppPresetDone: () -> Unit = {},
  val onToggleMicCapture: (Boolean) -> Unit = {},
  val onRefreshAssistantStatus: () -> Unit = {},
  val onCopyAdbCommand: (String, String) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConfigurationPresetEditor(
  draft: ConfigurationPreset,
  isNew: Boolean,
  state: MainUiState,
  actions: SettingsActions,
  cardPad: Dp,
) {
  val endpoints = state.settings.endpointPresets
  val appPresets = state.settings.appSettingsPresets
  val displayName = draft.resolveDisplayName(endpoints, appPresets)

  SettingsSectionCard(
    title = if (isNew) "New Configuration Preset" else "Edit Preset: $displayName",
    cardPadding = cardPad,
    modifier = Modifier.testTag("config_editor_card"),
  ) {
    Text(
      text = "Configuration preset name (optional — auto-derived from Primary & App if blank)",
      style = MaterialTheme.typography.bodyMedium,
      color = TvTextSecondary,
    )
    TvOutlinedInput(
      value = draft.customName,
      onValueChange = actions.onConfigNameChange,
      placeholderText = "Auto: $displayName",
      modifier = Modifier.fillMaxWidth().testTag("config_name_input"),
    )

    TvSwitchRow(
      checked = draft.allowEndpointFailover,
      onCheckedChange = actions.onConfigFailoverToggle,
      title = "Check backup endpoints if primary is unavailable",
      subtitle =
        "Automatically fails over Primary → Secondary → Tertiary, caches health for 1h (reset by live transcriptions), and checks Primary recovery in the background",
      modifier = Modifier.testTag("failover_switch"),
    )

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    // Primary Endpoint Picker
    SlotEndpointSelector(
      slot = EndpointSlot.PRIMARY,
      selectedId = draft.primaryEndpointId,
      allowNone = false,
      excludedIds = emptySet(),
      endpoints = endpoints,
      state = state,
      onSelect = { actions.onSelectConfigSlot(EndpointSlot.PRIMARY, it) },
      onAddNew = { actions.onStartNewEndpoint(EndpointSlot.PRIMARY) },
    )

    AnimatedVisibility(visible = draft.allowEndpointFailover) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        SlotEndpointSelector(
          slot = EndpointSlot.SECONDARY,
          selectedId = draft.secondaryEndpointId,
          allowNone = true,
          excludedIds = setOfNotNull(draft.primaryEndpointId),
          endpoints = endpoints,
          state = state,
          onSelect = { actions.onSelectConfigSlot(EndpointSlot.SECONDARY, it) },
          onAddNew = { actions.onStartNewEndpoint(EndpointSlot.SECONDARY) },
        )

        SlotEndpointSelector(
          slot = EndpointSlot.TERTIARY,
          selectedId = draft.tertiaryEndpointId,
          allowNone = true,
          excludedIds = setOfNotNull(draft.primaryEndpointId, draft.secondaryEndpointId),
          endpoints = endpoints,
          state = state,
          onSelect = { actions.onSelectConfigSlot(EndpointSlot.TERTIARY, it) },
          onAddNew = { actions.onStartNewEndpoint(EndpointSlot.TERTIARY) },
        )
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    // App Settings Preset Picker
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Application Settings Preset",
        style = MaterialTheme.typography.titleMedium,
        color = TvCyan,
        modifier = Modifier.weight(1f),
      )
      TvSecondaryButton(
        onClick = { actions.onStartNewAppPreset(true) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.testTag("new_app_for_config_button"),
      ) {
        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New App Set", style = MaterialTheme.typography.labelLarge, maxLines = 1)
      }
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      appPresets.forEach { appPreset ->
        val selected = draft.appSettingsPresetId == appPreset.id
        TvSecondaryButton(
          onClick = { actions.onSelectConfigAppPreset(appPreset.id) },
          isSelected = selected,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          modifier = Modifier.testTag("pick_app_preset_${appPreset.id}"),
        ) {
          if (selected) {
            Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
          }
          Text(
            text = appPreset.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    EditorActionButtonsRow(
      doneTag = "config_done_button",
      cancelTag = "config_cancel_button",
      onDone = actions.onSaveConfigDone,
      onCancel = actions.onCancelSubPage,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEndpointSelector(
  slot: EndpointSlot,
  selectedId: String?,
  allowNone: Boolean,
  excludedIds: Set<String>,
  endpoints: List<EndpointPreset>,
  state: MainUiState,
  onSelect: (String?) -> Unit,
  onAddNew: () -> Unit,
) {
  val filtered = endpoints.filterNot { it.id in excludedIds }
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "${slot.shortLabel} ${slot.label} Endpoint",
        style = MaterialTheme.typography.titleMedium,
        color = TvCyan,
        modifier = Modifier.weight(1f),
      )
      TvSecondaryButton(
        onClick = onAddNew,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.testTag("add_endpoint_for_${slot.name.lowercase()}"),
      ) {
        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New Endpoint", style = MaterialTheme.typography.labelLarge, maxLines = 1)
      }
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (allowNone) {
        val noneSelected = selectedId == null
        TvSecondaryButton(
          onClick = { onSelect(null) },
          isSelected = noneSelected,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          modifier = Modifier.testTag("pick_${slot.name.lowercase()}_none"),
        ) {
          Text("None", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
      }

      filtered.forEach { ep ->
        val selected = selectedId == ep.id
        val rec = state.endpointHealthRecords[ep.id]?.takeIf { it.fingerprint == ep.healthFingerprint }
        val healthSuffix =
          when {
            ep.isMissingRequiredApiKey -> " (key needed)"
            rec?.status == EndpointHealthStatus.DEGRADED -> " (unavailable)"
            rec?.status == EndpointHealthStatus.HEALTHY -> " (healthy)"
            else -> ""
          }
        TvSecondaryButton(
          onClick = { onSelect(ep.id) },
          isSelected = selected,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          modifier = Modifier.testTag("pick_${slot.name.lowercase()}_${ep.id}"),
        ) {
          if (selected) {
            Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
          }
          Text(
            text = "${ep.displayName}$healthSuffix",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ep.isMissingRequiredApiKey) TvCoral else TvTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EndpointPresetEditor(
  draft: EndpointPreset,
  isNew: Boolean,
  state: MainUiState,
  actions: SettingsActions,
  isCompact: Boolean,
  cardPad: Dp,
) {
  val testPassed = state.testStatus is TestStatus.Ok || draft.hasValidCachedEndpoint
  val title =
    if (isNew) "New Endpoint — What is your transcription endpoint?"
    else "Edit Endpoint: ${draft.displayName}"

  SettingsSectionCard(
    title = title,
    cardPadding = cardPad,
    modifier = Modifier.testTag("endpoint_editor_card"),
  ) {
    Text(
      text = "Endpoint name (optional — auto-uses host or IP if blank)",
      style = MaterialTheme.typography.bodyMedium,
      color = TvTextSecondary,
    )
    TvOutlinedInput(
      value = draft.customName,
      onValueChange = actions.onEndpointNameChange,
      placeholderText = "Auto: ${draft.displayName}",
      modifier = Modifier.fillMaxWidth().testTag("endpoint_name_input"),
    )

    Text(
      text = "Transcription endpoint (HTTP/HTTPS OpenAI URL, or gRPC host:port e.g. grpc.nvcf.nvidia.com:443)",
      style = MaterialTheme.typography.bodyMedium,
      color = TvTextSecondary,
    )
    TvOutlinedInput(
      value = draft.endpointInput,
      onValueChange = actions.onEndpointUrlChange,
      placeholderText = "10.3.3.20:8000, https://…, or grpc.nvcf.nvidia.com:443",
      onDone = actions.onTestEndpoint,
      modifier = Modifier.fillMaxWidth().testTag("endpoint_input"),
    )

    if (!draft.isGrpcEndpoint && draft.endpointInput.isBlank()) {
      TvSecondaryButton(
        onClick = { actions.onApplyNvidiaCloudPreset(null) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.testTag("fill_nvidia_grpc_button"),
      ) {
        Text(
          text = "Use NVIDIA Cloud gRPC (grpc.nvcf.nvidia.com:443)",
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
        )
      }
    }

    AnimatedVisibility(visible = draft.isGrpcEndpoint) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "NVIDIA Cloud Function ID (gRPC `function-id` UUID)",
          style = MaterialTheme.typography.bodyMedium,
          color = TvCyan,
        )
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          EndpointPreset.NVIDIA_FUNCTION_PRESETS.forEach { preset ->
            val selected = draft.functionId.trim().equals(preset.functionId, ignoreCase = true)
            TvSecondaryButton(
              onClick = { actions.onApplyNvidiaCloudPreset(preset) },
              isSelected = selected,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              modifier = Modifier.testTag("nvidia_fn_chip_${preset.id}"),
            ) {
              if (selected) {
                Icon(
                  imageVector = Icons.Rounded.Check,
                  contentDescription = null,
                  tint = TvCyan,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
              }
              Text(
                text = "${preset.label} (${preset.subtitle})",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
        TvOutlinedInput(
          value = draft.functionId,
          onValueChange = actions.onFunctionIdChange,
          placeholderText = "UUID e.g. ${EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID} (blank for local Riva)",
          onDone = actions.onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("function_id_input"),
        )
      }
    }

    TvCheckboxRow(
      checked = draft.useIpOverride,
      onCheckedChange = actions.onToggleIpOverride,
      title = "Override server IP address",
      subtitle = "Connect directly to this IP (LAN / Tailscale) while keeping Host and TLS SNI headers",
      modifier = Modifier.testTag("use_ip_override_checkbox"),
    )

    AnimatedVisibility(visible = draft.useIpOverride) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Server IP address (IP only, no port or path)",
          style = MaterialTheme.typography.bodyMedium,
          color = TvTextSecondary,
        )
        TvOutlinedInput(
          value = draft.overrideIp,
          onValueChange = actions.onOverrideIpChange,
          placeholderText = "e.g. 10.3.3.20 or 100.64.1.5",
          onDone = actions.onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("override_ip_input"),
        )
      }
    }

    TvCheckboxRow(
      checked = draft.ignoreSsl,
      onCheckedChange = actions.onToggleIgnoreSsl,
      title = "Ignore SSL certificate checks",
      subtitle = "Allow self-signed or private-CA HTTPS certificates on local/Tailscale servers",
      modifier = Modifier.testTag("use_ignore_ssl_checkbox"),
    )

    AnimatedVisibility(visible = !draft.apiKeyNotNeeded) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "API key (sent as Authorization: Bearer)",
          style = MaterialTheme.typography.bodyMedium,
          color = if (draft.apiKey.isBlank()) TvAmber else TvTextSecondary,
        )
        TvOutlinedInput(
          value = draft.apiKey,
          onValueChange = actions.onApiKeyChange,
          placeholderText = "Missing API key (nvapi-… or sk-…)",
          visualTransformation = PasswordVisualTransformation(),
          onDone = actions.onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
        )
      }
    }

    TvCheckboxRow(
      checked = draft.apiKeyNotNeeded,
      onCheckedChange = actions.onToggleApiKeyNotNeeded,
      title = "API key not needed",
      subtitle = "Allow activating and calling this endpoint without an API key",
      modifier = Modifier.testTag("api_key_not_needed_checkbox"),
    )

    val cachedLabel =
      if (draft.hasValidCachedEndpoint && state.testStatus == TestStatus.Idle) {
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
          isTesting = state.testStatus == TestStatus.Testing,
          onClick = actions.onTestEndpoint,
          modifier = Modifier.fillMaxWidth().testTag("test_endpoint_button"),
        )
        EndpointStatusBadge(testStatus = state.testStatus, cachedOkMessage = cachedLabel, modifier = Modifier.fillMaxWidth())
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TestEndpointButton(
          isTesting = state.testStatus == TestStatus.Testing,
          onClick = actions.onTestEndpoint,
          modifier = Modifier.testTag("test_endpoint_button"),
        )
        EndpointStatusBadge(testStatus = state.testStatus, cachedOkMessage = cachedLabel, modifier = Modifier.weight(1f))
      }
    }

    AnimatedVisibility(visible = testPassed || draft.useCustomModel || draft.liveTranscription) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvCheckboxRow(
          checked = draft.useCustomModel,
          onCheckedChange = actions.onToggleCustomModel,
          title = "Pick custom model / endpoint",
          subtitle =
            if (state.availableModels.isNotEmpty()) {
              "Discovered ${state.availableModels.size} speech/OpenAI model(s) (newest parakeet listed first)"
            } else {
              "Query OpenAI /v1/models or specify a model ID (default 'parakeet' picks highest version)"
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
                    state.isFetchingModels -> "Querying /v1/models…"
                    state.availableModels.isNotEmpty() -> "Server speech models:"
                    else -> "Model ID (sent to transcription endpoint):"
                  },
                style = MaterialTheme.typography.bodyMedium,
                color = TvTextSecondary,
                modifier = Modifier.weight(1f),
              )
              TvSecondaryButton(
                onClick = actions.onRefreshModels,
                enabled = !state.isFetchingModels,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("refresh_models_button"),
              ) {
                Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Query OpenAI models", tint = TvCyan, modifier = Modifier.size(18.dp))
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
                  val selected = draft.modelName == modelId
                  TvSecondaryButton(
                    onClick = { actions.onModelNameChange(modelId) },
                    isSelected = selected,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("model_chip_$modelId"),
                  ) {
                    if (selected) {
                      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
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
              onValueChange = actions.onModelNameChange,
              placeholderText = "whisper-1",
              modifier = Modifier.fillMaxWidth().testTag("model_input"),
            )
          }
        }

        TvCheckboxRow(
          checked = draft.liveTranscription,
          onCheckedChange = actions.onToggleLiveTranscription,
          title = "Live transcription (show dictation as you speak)",
          subtitle = "Streams SSE deltas or progressive audio chunks into the search bar live",
          modifier = Modifier.testTag("live_transcription_checkbox"),
        )
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    EditorActionButtonsRow(
      doneTag = "endpoint_done_button",
      cancelTag = "endpoint_cancel_button",
      onDone = actions.onSaveEndpointDone,
      onCancel = actions.onCancelSubPage,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppPresetEditor(
  draft: AppSettingsPreset,
  isNew: Boolean,
  state: MainUiState,
  actions: SettingsActions,
  isCompact: Boolean,
  cardPad: Dp,
) {
  val title =
    if (isNew) "New Application Settings Preset"
    else "Edit App Settings: ${draft.displayName}"

  SettingsSectionCard(
    title = title,
    cardPadding = cardPad,
    modifier = Modifier.testTag("app_preset_editor_card"),
  ) {
    Text(
      text = "Preset name (optional — auto-uses app & language if blank)",
      style = MaterialTheme.typography.bodyMedium,
      color = TvTextSecondary,
    )
    TvOutlinedInput(
      value = draft.customName,
      onValueChange = actions.onAppPresetNameChange,
      placeholderText = "Auto: ${draft.displayName}",
      modifier = Modifier.fillMaxWidth().testTag("app_preset_name_input"),
    )

    TvSwitchRow(
      checked = draft.searchInCurrentApp,
      onCheckedChange = actions.onToggleSearchInCurrentApp,
      title = "Search in the current app",
      subtitle = "Send voice queries to the active app when it supports search; otherwise fall back to the default app below",
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
                onClick = { actions.onConfirmAppTest(true) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.testTag("confirm_app_works_button"),
              ) {
                Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Yes, worked", style = MaterialTheme.typography.labelLarge, maxLines = 1)
              }
              TvSecondaryButton(
                onClick = { actions.onConfirmAppTest(false) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.testTag("confirm_app_broken_button"),
              ) {
                Text("No, not working", style = MaterialTheme.typography.labelLarge, maxLines = 1)
              }
              TvSecondaryButton(
                onClick = actions.onDismissPendingVerification,
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
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = TvCyan, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
        Text("Scanning installed TV apps…", style = MaterialTheme.typography.bodyMedium, color = TvTextSecondary)
      }
    } else if (state.installedApps.isEmpty()) {
      Text("No installed TV apps found.", style = MaterialTheme.typography.bodyMedium, color = TvAmber)
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.installedApps.forEach { app ->
          TargetAppRow(
            app = app,
            isSelected = app.packageName == draft.targetPackage,
            handoffStatus = state.settings.appHandoffStatuses[app.packageName] ?: AppHandoffStatus.UNKNOWN,
            isCompact = isCompact,
            onClick = { actions.onSelectTargetApp(app) },
            onTestSearch = { actions.onTestAppSearch(app) },
            onToggleNeverUse = { actions.onToggleNeverUseApp(app) },
          )
        }
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

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
          onClick = { actions.onSelectLanguage(option) },
          isSelected = selected,
          modifier = Modifier.testTag("lang_${option.name.lowercase()}"),
        ) {
          if (selected) {
            Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = TvCyan, modifier = Modifier.size(20.dp))
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
          onClick = { actions.onSelectMicSource(source) },
          isSelected = selected,
        ) {
          Text(text = source.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
      }
    }

    HorizontalDivider(color = TvOutline, thickness = 1.dp)

    EditorActionButtonsRow(
      doneTag = "app_preset_done_button",
      cancelTag = "app_preset_cancel_button",
      onDone = actions.onSaveAppPresetDone,
      onCancel = actions.onCancelSubPage,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorActionButtonsRow(
  doneTag: String,
  cancelTag: String,
  onDone: () -> Unit,
  onCancel: () -> Unit,
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TvPrimaryButton(onClick = onDone, modifier = Modifier.testTag(doneTag)) {
      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Done",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
      )
    }

    TvSecondaryButton(onClick = onCancel, modifier = Modifier.testTag(cancelTag)) {
      Text("Cancel", style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
  }
}
