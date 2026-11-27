package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainUiState
import com.example.R
import com.example.VoicePhase
import com.example.data.EndpointSlot
import com.example.handoff.InstalledTvApp
import com.example.ui.components.TvListeningBars
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
fun SearchScreen(
  state: MainUiState,
  hasMicPermission: Boolean,
  onMicClick: () -> Unit,
  onRequestPermission: () -> Unit,
  onSearchTextChange: (String) -> Unit,
  onSubmitSearch: () -> Unit,
  onSelectFallbackApp: (InstalledTvApp) -> Unit,
  onOpenSettings: () -> Unit,
  onDismiss: () -> Unit,
) {
  val micFocusRequester = remember { FocusRequester() }
  val settingsFocusRequester = remember { FocusRequester() }
  val needsSetup = state.settings.orderedActiveEndpoints().isEmpty()

  LaunchedEffect(state.phase, state.isSettingsOpen, needsSetup) {
    if (!state.isSettingsOpen) {
      if (needsSetup) {
        runCatching { settingsFocusRequester.requestFocus() }
      } else if (state.phase == VoicePhase.LISTENING || state.phase == VoicePhase.IDLE) {
        runCatching { micFocusRequester.requestFocus() }
      }
    }
  }

  val barBorderColor by
    animateColorAsState(
      targetValue =
        when (state.phase) {
          VoicePhase.LISTENING -> TvCoral.copy(alpha = 0.85f)
          VoicePhase.TRANSCRIBING, VoicePhase.OPENING_TARGET -> TvCyan.copy(alpha = 0.85f)
          VoicePhase.ERROR, VoicePhase.PERMISSION_NEEDED -> TvAmber.copy(alpha = 0.75f)
          VoicePhase.IDLE -> TvOutline
        },
      animationSpec = tween(200),
      label = "barBorder",
    )

  val showErrorOrSetup =
    !hasMicPermission ||
      needsSetup ||
      state.phase == VoicePhase.ERROR ||
      state.phase == VoicePhase.PERMISSION_NEEDED
  val showSubmitButton = state.searchText.isNotBlank() && state.phase != VoicePhase.LISTENING
  val showActivePresetPill =
    !needsSetup &&
      (state.activeConfigDisplayName.isNotBlank() || state.activeEndpointDisplayName.isNotBlank())

  // Lean floating pill search bar anchored cleanly near top-center
  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } },
    contentAlignment = Alignment.TopCenter,
  ) {
    val isCompact = maxWidth < 640.dp
    val topInset = if (isCompact) 14.dp else 32.dp
    val sideInset = if (isCompact) 14.dp else 44.dp
    val micSize = if (isCompact) 50.dp else 54.dp

    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(start = sideInset, end = sideInset, top = topInset, bottom = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      AnimatedVisibility(visible = showActivePresetPill) {
        ActivePresetPill(
          configName = state.activeConfigDisplayName,
          endpointName = state.activeEndpointDisplayName,
          slot = state.activeEndpointSlot,
          showSlotTag = state.hasBackupEndpoints || state.isBackupEndpointActive,
          isTranscribing =
            state.phase == VoicePhase.TRANSCRIBING || state.phase == VoicePhase.LISTENING,
          isCheckingHealth = state.isCheckingEndpointHealth,
          modifier = Modifier.widthIn(max = 780.dp).testTag("active_set_pill"),
        )
      }

      Surface(
        shape = RoundedCornerShape(36.dp),
        color = TvSurface.copy(alpha = 0.96f),
        border = BorderStroke(1.5.dp, barBorderColor),
        shadowElevation = 18.dp,
        tonalElevation = 8.dp,
        modifier =
          Modifier.fillMaxWidth()
            .widthIn(max = 780.dp)
            .pointerInput(Unit) { detectTapGestures {} },
      ) {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = if (isCompact) 10.dp else 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp),
        ) {
          MicOrbButton(
            phase = state.phase,
            audioLevel = state.audioLevel,
            size = micSize,
            onClick = { if (!hasMicPermission) onRequestPermission() else onMicClick() },
            modifier = Modifier.focusRequester(micFocusRequester).testTag("mic_button"),
          )

          LeanSearchField(
            value = state.searchText,
            phase = state.phase,
            audioLevel = state.audioLevel,
            targetLabel = state.effectiveDisplayTargetLabel,
            endpointLabel = state.activeEndpointDisplayName,
            onValueChange = onSearchTextChange,
            onSubmit = onSubmitSearch,
            modifier = Modifier.weight(1f).testTag("search_input"),
          )

          DiscreetSettingsButton(
            onClick = onOpenSettings,
            highlight = needsSetup,
            modifier = Modifier.focusRequester(settingsFocusRequester).testTag("settings_button"),
          )
        }
      }

      // Contextual strip below the pill ONLY for errors, setup, missing permission, or manual submit
      AnimatedVisibility(visible = showErrorOrSetup || showSubmitButton) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = TvSurface.copy(alpha = 0.94f),
          border =
            BorderStroke(
              1.dp,
              if (showErrorOrSetup) TvAmber.copy(alpha = 0.5f) else TvOutline,
            ),
          modifier =
            Modifier.fillMaxWidth()
              .widthIn(max = 780.dp)
              .pointerInput(Unit) { detectTapGestures {} },
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (showErrorOrSetup) {
                Icon(
                  imageVector = Icons.Rounded.Warning,
                  contentDescription = null,
                  tint = TvAmber,
                  modifier = Modifier.size(20.dp),
                )
                Text(
                  text =
                    when {
                      !hasMicPermission -> "Microphone permission required"
                      needsSetup -> "Open Settings to set your transcription server"
                      state.statusMessage.isNotBlank() -> state.statusMessage
                      else -> "Check server settings"
                    },
                  style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                  color = TvAmber,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f).testTag("status_text"),
                )
              } else {
                Spacer(modifier = Modifier.weight(1f))
              }

              if (!hasMicPermission) {
                TvSecondaryButton(
                  onClick = onRequestPermission,
                  contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                  modifier = Modifier.testTag("grant_permission_button"),
                ) {
                  Text("Grant Mic", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
              }

              if (showSubmitButton) {
                TvPrimaryButton(
                  onClick = onSubmitSearch,
                  enabled = state.phase != VoicePhase.TRANSCRIBING,
                  contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                  modifier = Modifier.testTag("search_target_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Search in ${state.effectiveDisplayTargetLabel}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }

            if (state.showFallbackAppPicker && state.fallbackPickerApps.isNotEmpty()) {
              Column(
                modifier = Modifier.fillMaxWidth().testTag("fallback_picker_container"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = "Send query to another app:",
                  style = MaterialTheme.typography.bodyMedium,
                  color = TvTextSecondary,
                )
                FlowRow(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  state.fallbackPickerApps.forEach { fallbackApp ->
                    TvSecondaryButton(
                      onClick = { onSelectFallbackApp(fallbackApp) },
                      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                      modifier = Modifier.testTag("fallback_app_${fallbackApp.packageName}"),
                    ) {
                      Text(
                        text = fallbackApp.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ActivePresetPill(
  configName: String,
  endpointName: String,
  slot: EndpointSlot,
  showSlotTag: Boolean,
  isTranscribing: Boolean,
  isCheckingHealth: Boolean,
  modifier: Modifier = Modifier,
) {
  val isBackup = slot != EndpointSlot.PRIMARY
  val accentColor =
    when {
      isBackup -> TvAmber
      isTranscribing -> TvCyan
      else -> TvEmerald
    }

  Surface(
    shape = RoundedCornerShape(999.dp),
    color = TvSurface.copy(alpha = 0.90f),
    border = BorderStroke(1.dp, accentColor.copy(alpha = if (isBackup || isTranscribing) 0.7f else 0.35f)),
    tonalElevation = 4.dp,
    modifier = modifier.pointerInput(Unit) { detectTapGestures {} },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier =
          Modifier.size(8.dp)
            .background(color = accentColor, shape = CircleShape)
      )
      val titleText =
        when {
          configName.isNotBlank() && endpointName.isNotBlank() && configName != endpointName ->
            "$configName • $endpointName"
          endpointName.isNotBlank() -> endpointName
          else -> configName
        }
      Text(
        text = titleText,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = TvTextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("active_set_label"),
      )
      if (showSlotTag) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = accentColor.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        ) {
          Text(
            text = if (isBackup) "${slot.label} (failover)" else slot.label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = accentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).testTag("active_slot_badge"),
          )
        }
      }
      if (isCheckingHealth) {
        CircularProgressIndicator(
          color = TvCyan,
          strokeWidth = 2.dp,
          modifier = Modifier.size(12.dp).testTag("health_check_spinner"),
        )
      }
    }
  }
}

@Composable
private fun LeanSearchField(
  value: String,
  phase: VoicePhase,
  audioLevel: Float,
  targetLabel: String,
  endpointLabel: String,
  onValueChange: (String) -> Unit,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fieldInteraction = remember { MutableInteractionSource() }
  val fieldFocused by fieldInteraction.collectIsFocusedAsState()

  val placeholderText =
    when (phase) {
      VoicePhase.LISTENING -> "Listening…"
      VoicePhase.TRANSCRIBING ->
        if (endpointLabel.isNotBlank()) "Transcribing ($endpointLabel)…" else "Transcribing…"
      VoicePhase.OPENING_TARGET -> "Opening $targetLabel…"
      else -> "Search $targetLabel…"
    }

  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    interactionSource = fieldInteraction,
    singleLine = true,
    textStyle =
      MaterialTheme.typography.titleLarge.copy(
        fontSize = 20.sp,
        color = TvTextPrimary,
      ),
    placeholder = {
      Text(
        text = placeholderText,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
        color = if (phase == VoicePhase.LISTENING) TvCoral else TvTextSecondary.copy(alpha = 0.72f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    trailingIcon =
      if (phase == VoicePhase.LISTENING) {
        {
          Box(modifier = Modifier.padding(end = 10.dp)) {
            TvListeningBars(audioLevel = audioLevel)
          }
        }
      } else {
        null
      },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    shape = RoundedCornerShape(999.dp),
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedContainerColor = TvSurfaceFocus.copy(alpha = 0.7f),
        unfocusedContainerColor = Color.Transparent,
        focusedBorderColor = TvCyan,
        unfocusedBorderColor = if (fieldFocused) TvCyan else Color.Transparent,
        cursorColor = TvCyan,
      ),
    modifier = modifier,
  )
}

@Composable
private fun DiscreetSettingsButton(
  onClick: () -> Unit,
  highlight: Boolean,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, tween(140), label = "gearScale")

  val borderColor =
    when {
      isFocused -> Color.White
      highlight -> TvAmber
      else -> Color.Transparent
    }

  val bgColor =
    when {
      isFocused -> TvCyan
      highlight -> TvAmber.copy(alpha = 0.18f)
      else -> TvSurfaceElevated.copy(alpha = 0.55f)
    }

  val iconTint =
    when {
      isFocused -> TvBackground
      highlight -> TvAmber
      else -> TvTextSecondary
    }

  Surface(
    onClick = onClick,
    interactionSource = interactionSource,
    shape = CircleShape,
    color = bgColor,
    border = if (borderColor != Color.Transparent) BorderStroke(2.dp, borderColor) else null,
    modifier = modifier.size(48.dp).scale(scale),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "Settings",
        tint = iconTint,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

@Composable
private fun MicOrbButton(
  phase: VoicePhase,
  audioLevel: Float,
  size: Dp,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val isListening = phase == VoicePhase.LISTENING
  val isBusy = phase == VoicePhase.TRANSCRIBING || phase == VoicePhase.OPENING_TARGET

  val pulseTransition = rememberInfiniteTransition(label = "micPulse")
  val idlePulse by
    pulseTransition.animateFloat(
      initialValue = 1.0f,
      targetValue = 1.07f,
      animationSpec = infiniteRepeatable(animation = tween(680), repeatMode = RepeatMode.Reverse),
      label = "pulseVal",
    )

  val levelScale by
    animateFloatAsState(
      targetValue =
        if (isListening) (idlePulse + audioLevel * 0.14f) else if (isFocused) 1.06f else 1f,
      animationSpec = tween(90),
      label = "micScale",
    )

  val orbColor by
    animateColorAsState(
      targetValue =
        when {
          isListening -> TvCoral
          isBusy -> TvCyan.copy(alpha = 0.35f)
          isFocused -> TvCyan
          else -> TvSurfaceElevated
        },
      animationSpec = tween(180),
      label = "orbColor",
    )

  val borderColor =
    when {
      isFocused -> Color.White
      isListening -> TvCoral.copy(alpha = 0.9f)
      else -> TvCyan.copy(alpha = 0.75f)
    }

  Surface(
    onClick = onClick,
    enabled = !isBusy,
    interactionSource = interactionSource,
    shape = CircleShape,
    color = orbColor,
    border = BorderStroke(if (isFocused) 2.5.dp else 1.5.dp, borderColor),
    modifier = modifier.size(size).scale(levelScale),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      if (isBusy) {
        CircularProgressIndicator(
          color = TvCyan,
          strokeWidth = 3.dp,
          modifier = Modifier.size(24.dp),
        )
      } else {
        Icon(
          painter = painterResource(id = R.drawable.ic_mic),
          contentDescription = if (isListening) "Stop listening" else "Start voice search",
          tint = if (isListening || isFocused) TvBackground else TvTextPrimary,
          modifier = Modifier.size(26.dp),
        )
      }
    }
  }
}
