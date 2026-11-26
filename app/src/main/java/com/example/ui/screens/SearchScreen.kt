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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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

@Composable
fun SearchScreen(
  state: MainUiState,
  hasMicPermission: Boolean,
  onMicClick: () -> Unit,
  onRequestPermission: () -> Unit,
  onSearchTextChange: (String) -> Unit,
  onSubmitSearch: () -> Unit,
  onOpenSettings: () -> Unit,
  onDismiss: () -> Unit,
) {
  val micFocusRequester = remember { FocusRequester() }
  val settingsFocusRequester = remember { FocusRequester() }
  val needsSetup = state.settings.endpointInput.isBlank()

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
          VoicePhase.ERROR, VoicePhase.PERMISSION_NEEDED -> TvAmber.copy(alpha = 0.7f)
          VoicePhase.IDLE -> TvOutline
        },
      animationSpec = tween(220),
      label = "barBorder",
    )

  // Floating hover search overlay anchored cleanly in the upper area of the screen
  BoxWithConstraints(
    modifier =
      Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { onDismiss() }
      },
    contentAlignment = Alignment.TopCenter,
  ) {
    val isCompact = maxWidth < 640.dp
    val topInset = if (isCompact) 20.dp else 44.dp
    val sideInset = if (isCompact) 14.dp else 40.dp
    val barPadH = if (isCompact) 14.dp else 20.dp
    val barPadV = if (isCompact) 14.dp else 16.dp
    val micSize = if (isCompact) 58.dp else 64.dp

    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(start = sideInset, end = sideInset, top = topInset, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = TvSurface.copy(alpha = 0.96f),
        border = BorderStroke(1.5.dp, barBorderColor),
        shadowElevation = 18.dp,
        tonalElevation = 8.dp,
        modifier =
          Modifier.fillMaxWidth()
            .widthIn(max = 880.dp)
            .pointerInput(Unit) {
              detectTapGestures {}
            },
      ) {
        Column(
          modifier = Modifier.padding(horizontal = barPadH, vertical = barPadV),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // Hover bar row: [ Mic Orb ] [ Search Input ] [ Discreet Settings Gear ]
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 14.dp),
          ) {
            MicOrbButton(
              phase = state.phase,
              audioLevel = state.audioLevel,
              size = micSize,
              onClick = { if (!hasMicPermission) onRequestPermission() else onMicClick() },
              modifier = Modifier.focusRequester(micFocusRequester).testTag("mic_button"),
            )

            SearchQueryField(
              value = state.searchText,
              targetLabel = state.settings.targetLabel,
              onValueChange = onSearchTextChange,
              onSubmit = onSubmitSearch,
              modifier = Modifier.weight(1f).testTag("search_input"),
            )

            DiscreetSettingsButton(
              onClick = onOpenSettings,
              highlight = needsSetup,
              modifier =
                Modifier.focusRequester(settingsFocusRequester).testTag("settings_button"),
            )
          }

          // Compact assistant status strip & optional actions
          val statusColor =
            when (state.phase) {
              VoicePhase.LISTENING -> TvCoral
              VoicePhase.TRANSCRIBING, VoicePhase.OPENING_TARGET -> TvCyan
              VoicePhase.ERROR, VoicePhase.PERMISSION_NEEDED -> TvAmber
              VoicePhase.IDLE -> TvEmerald
            }

          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f),
            ) {
              when (state.phase) {
                VoicePhase.LISTENING -> TvListeningBars(audioLevel = state.audioLevel)
                VoicePhase.TRANSCRIBING, VoicePhase.OPENING_TARGET ->
                  CircularProgressIndicator(
                    color = TvCyan,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(18.dp),
                  )
                VoicePhase.ERROR, VoicePhase.PERMISSION_NEEDED ->
                  Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                  )
                VoicePhase.IDLE -> Unit
              }

              Text(
                text =
                  if (needsSetup) "Set your server in Settings to enable voice search"
                  else state.statusMessage,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                color = if (needsSetup) TvAmber else statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("status_text"),
              )
            }

            if (!hasMicPermission) {
              TvSecondaryButton(
                onClick = onRequestPermission,
                modifier = Modifier.testTag("grant_permission_button"),
              ) {
                Text("Grant Mic", style = MaterialTheme.typography.labelLarge, maxLines = 1)
              }
            } else if (state.searchText.isBlank() && !needsSetup && !isCompact) {
              Text(
                text = "${state.settings.targetLabel} · ${state.settings.language.label}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                color = TvTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          AnimatedVisibility(visible = state.searchText.isNotBlank()) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End,
            ) {
              TvPrimaryButton(
                onClick = onSubmitSearch,
                enabled = state.searchText.isNotBlank() && state.phase != VoicePhase.TRANSCRIBING,
                modifier = Modifier.testTag("search_target_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Search,
                  contentDescription = null,
                  modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Search in ${state.settings.targetLabel}",
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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

@Composable
private fun SearchQueryField(
  value: String,
  targetLabel: String,
  onValueChange: (String) -> Unit,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fieldInteraction = remember { MutableInteractionSource() }
  val fieldFocused by fieldInteraction.collectIsFocusedAsState()

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
        text = "Speak or search $targetLabel…",
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
        color = TvTextSecondary.copy(alpha = 0.72f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    shape = RoundedCornerShape(999.dp),
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedContainerColor = TvSurfaceFocus,
        unfocusedContainerColor = TvSurfaceElevated.copy(alpha = 0.85f),
        focusedBorderColor = TvCyan,
        unfocusedBorderColor = if (fieldFocused) TvCyan else TvOutline.copy(alpha = 0.65f),
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
      else -> TvOutline.copy(alpha = 0.6f)
    }

  val bgColor =
    when {
      isFocused -> TvCyan
      highlight -> TvAmber.copy(alpha = 0.18f)
      else -> TvSurfaceElevated.copy(alpha = 0.7f)
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
    border = BorderStroke(if (isFocused || highlight) 2.dp else 1.dp, borderColor),
    modifier = modifier.size(50.dp).scale(scale),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "Settings",
        tint = iconTint,
        modifier = Modifier.size(24.dp),
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
      targetValue = 1.08f,
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
          modifier = Modifier.size(28.dp),
        )
      } else {
        Icon(
          painter = painterResource(id = R.drawable.ic_mic),
          contentDescription = if (isListening) "Stop listening" else "Start voice search",
          tint = if (isListening || isFocused) TvBackground else TvTextPrimary,
          modifier = Modifier.size(30.dp),
        )
      }
    }
  }
}
