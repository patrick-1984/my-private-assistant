package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
  state: MainUiState,
  hasMicPermission: Boolean,
  onMicClick: () -> Unit,
  onRequestPermission: () -> Unit,
  onSearchTextChange: (String) -> Unit,
  onSubmitSearch: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val micFocusRequester = remember { FocusRequester() }
  val settingsFocusRequester = remember { FocusRequester() }

  LaunchedEffect(state.phase, state.isSettingsOpen) {
    if (!state.isSettingsOpen) {
      if (state.phase == VoicePhase.ERROR && state.settings.endpointInput.isBlank()) {
        runCatching { settingsFocusRequester.requestFocus() }
      } else if (state.phase == VoicePhase.LISTENING || state.phase == VoicePhase.IDLE) {
        runCatching { micFocusRequester.requestFocus() }
      }
    }
  }

  BoxWithConstraints(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.radialGradient(
            colors = listOf(Color(0xFF152A4A), TvBackground),
            radius = 1100f,
          )
        ),
    contentAlignment = Alignment.Center,
  ) {
    val isCompact = maxWidth < 640.dp
    val outerPad = if (isCompact) 16.dp else 36.dp
    val cardPad = if (isCompact) 20.dp else 32.dp
    val micSize = if (isCompact) 84.dp else 76.dp

    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(outerPad),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        shape = RoundedCornerShape(24.dp),
        color = TvSurface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, TvOutline),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
      ) {
        Column(
          modifier = Modifier.padding(cardPad),
          verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          // Header: wraps cleanly without crushing on narrow screens
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                painter = painterResource(id = R.drawable.ic_tv),
                contentDescription = null,
                tint = TvCyan,
                modifier = Modifier.size(26.dp),
              )
              Text(
                text = "TV Voice Search",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                color = TvTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }

            Surface(
              shape = RoundedCornerShape(999.dp),
              color = TvSurfaceElevated,
              border = BorderStroke(1.dp, TvOutline),
            ) {
              Text(
                text = "${state.settings.targetLabel} · ${state.settings.language.label}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                color = TvTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
              )
            }
          }

          // Hero Search + Mic layout:
          // Wide TV -> [ Mic ] [ Search Box ] side-by-side
          // Compact -> Centered [ Mic ] above full-width [ Search Box ]
          if (isCompact) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
              MicOrbButton(
                phase = state.phase,
                audioLevel = state.audioLevel,
                size = micSize,
                onClick = {
                  if (!hasMicPermission) onRequestPermission() else onMicClick()
                },
                modifier = Modifier.focusRequester(micFocusRequester).testTag("mic_button"),
              )
              SearchQueryField(
                value = state.searchText,
                onValueChange = onSearchTextChange,
                onSubmit = onSubmitSearch,
                modifier = Modifier.fillMaxWidth().testTag("search_input"),
              )
            }
          } else {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
              MicOrbButton(
                phase = state.phase,
                audioLevel = state.audioLevel,
                size = micSize,
                onClick = {
                  if (!hasMicPermission) onRequestPermission() else onMicClick()
                },
                modifier = Modifier.focusRequester(micFocusRequester).testTag("mic_button"),
              )
              SearchQueryField(
                value = state.searchText,
                onValueChange = onSearchTextChange,
                onSubmit = onSubmitSearch,
                modifier = Modifier.weight(1f).testTag("search_input"),
              )
            }
          }

          // Status banner — full width on compact, aligned under input on wide TV
          StatusBanner(
            phase = state.phase,
            message = state.statusMessage,
            audioLevel = state.audioLevel,
            hasMicPermission = hasMicPermission,
            onRequestPermission = onRequestPermission,
            modifier =
              if (isCompact) Modifier.fillMaxWidth()
              else Modifier.fillMaxWidth().padding(start = 96.dp),
          )

          // Action buttons — never collide or wrap awkwardly
          if (isCompact) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              TvPrimaryButton(
                onClick = onSubmitSearch,
                enabled = state.searchText.isNotBlank() && state.phase != VoicePhase.TRANSCRIBING,
                modifier = Modifier.fillMaxWidth().testTag("search_target_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Search,
                  contentDescription = null,
                  modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "Search in ${state.settings.targetLabel}",
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }

              TvSecondaryButton(
                onClick = onOpenSettings,
                modifier =
                  Modifier.fillMaxWidth()
                    .focusRequester(settingsFocusRequester)
                    .testTag("settings_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Settings,
                  contentDescription = null,
                  tint = TvCyan,
                  modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "Settings",
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                )
              }
            }
          } else {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              TvPrimaryButton(
                onClick = onSubmitSearch,
                enabled = state.searchText.isNotBlank() && state.phase != VoicePhase.TRANSCRIBING,
                modifier = Modifier.testTag("search_target_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Search,
                  contentDescription = null,
                  modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "Search in ${state.settings.targetLabel}",
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }

              TvSecondaryButton(
                onClick = onOpenSettings,
                modifier =
                  Modifier.focusRequester(settingsFocusRequester).testTag("settings_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Settings,
                  contentDescription = null,
                  tint = TvCyan,
                  modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "Settings",
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
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
        fontSize = 21.sp,
        color = TvTextPrimary,
      ),
    placeholder = {
      Text(
        text = "Speak or type search…",
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
        color = TvTextSecondary.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    shape = RoundedCornerShape(16.dp),
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedContainerColor = TvSurfaceFocus,
        unfocusedContainerColor = TvSurfaceElevated,
        focusedBorderColor = TvCyan,
        unfocusedBorderColor = if (fieldFocused) TvCyan else TvOutline,
        cursorColor = TvCyan,
      ),
    modifier = modifier,
  )
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
        if (isListening) (idlePulse + audioLevel * 0.16f) else if (isFocused) 1.05f else 1f,
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
      else -> TvCyan.copy(alpha = 0.7f)
    }

  Surface(
    onClick = onClick,
    enabled = !isBusy,
    interactionSource = interactionSource,
    shape = CircleShape,
    color = orbColor,
    border = BorderStroke(if (isFocused) 3.dp else 2.dp, borderColor),
    modifier = modifier.size(size).scale(levelScale),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      if (isBusy) {
        CircularProgressIndicator(
          color = TvCyan,
          strokeWidth = 3.5.dp,
          modifier = Modifier.size(34.dp),
        )
      } else {
        Icon(
          painter = painterResource(id = R.drawable.ic_mic),
          contentDescription = if (isListening) "Stop listening" else "Start voice search",
          tint = if (isListening || isFocused) TvBackground else TvTextPrimary,
          modifier = Modifier.size(36.dp),
        )
      }
    }
  }
}

@Composable
private fun StatusBanner(
  phase: VoicePhase,
  message: String,
  audioLevel: Float,
  hasMicPermission: Boolean,
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val statusColor =
    when (phase) {
      VoicePhase.LISTENING -> TvCoral
      VoicePhase.TRANSCRIBING, VoicePhase.OPENING_TARGET -> TvCyan
      VoicePhase.ERROR, VoicePhase.PERMISSION_NEEDED -> TvAmber
      VoicePhase.IDLE -> TvEmerald
    }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = statusColor.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.30f)),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        when (phase) {
          VoicePhase.LISTENING -> TvListeningBars(audioLevel = audioLevel)
          VoicePhase.ERROR, VoicePhase.PERMISSION_NEEDED ->
            Icon(
              imageVector = Icons.Rounded.Warning,
              contentDescription = null,
              tint = statusColor,
              modifier = Modifier.size(22.dp),
            )
          else -> Unit
        }
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
          color = statusColor,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f).testTag("status_text"),
        )
      }

      if (!hasMicPermission) {
        TvSecondaryButton(
          onClick = onRequestPermission,
          modifier = Modifier.testTag("grant_permission_button"),
        ) {
          Text("Grant Mic Permission", style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}
