package com.example.ui.components

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.ui.theme.TvBackground
import com.example.ui.theme.TvCoral
import com.example.ui.theme.TvCyan
import com.example.ui.theme.TvOutline
import com.example.ui.theme.TvSurfaceElevated
import com.example.ui.theme.TvSurfaceFocus
import com.example.ui.theme.TvTextPrimary
import kotlin.math.sin

@Composable
fun TvPrimaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  containerColor: Color = TvCyan,
  contentColor: Color = TvBackground,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
  content: @Composable RowScope.() -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (isFocused) 1.03f else 1f, tween(140), label = "btnScale")
  val borderColor by
    animateColorAsState(
      if (isFocused) Color.White else Color.Transparent,
      tween(140),
      label = "btnBorder",
    )

  Button(
    onClick = onClick,
    enabled = enabled,
    interactionSource = interactionSource,
    shape = RoundedCornerShape(14.dp),
    border = if (isFocused) BorderStroke(2.5.dp, borderColor) else null,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = TvSurfaceElevated,
        disabledContentColor = TvTextPrimary.copy(alpha = 0.42f),
      ),
    contentPadding = contentPadding,
    modifier = modifier.defaultMinSize(minHeight = 50.dp).scale(scale),
    content = content,
  )
}

@Composable
fun TvSecondaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  isSelected: Boolean = false,
  contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
  content: @Composable RowScope.() -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (isFocused) 1.03f else 1f, tween(140), label = "secScale")

  val borderColor =
    when {
      isFocused -> TvCyan
      isSelected -> TvCyan.copy(alpha = 0.8f)
      else -> TvOutline
    }
  val bgColor =
    when {
      isFocused -> TvSurfaceFocus
      isSelected -> TvSurfaceFocus.copy(alpha = 0.8f)
      else -> TvSurfaceElevated
    }

  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    interactionSource = interactionSource,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(if (isFocused || isSelected) 2.5.dp else 1.dp, borderColor),
    colors =
      ButtonDefaults.outlinedButtonColors(
        containerColor = bgColor,
        contentColor = TvTextPrimary,
      ),
    contentPadding = contentPadding,
    modifier = modifier.defaultMinSize(minHeight = 50.dp).scale(scale),
    content = content,
  )
}

@Composable
fun TvFocusableCard(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isSelected: Boolean = false,
  enabled: Boolean = true,
  shape: Shape = RoundedCornerShape(16.dp),
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (isFocused) 1.01f else 1f, tween(140), label = "cardScale")

  val borderColor =
    when {
      isFocused -> TvCyan
      isSelected -> TvCyan.copy(alpha = 0.75f)
      else -> TvOutline
    }
  val containerColor =
    when {
      isFocused -> TvSurfaceFocus
      isSelected -> TvSurfaceFocus.copy(alpha = 0.65f)
      else -> TvSurfaceElevated
    }

  Surface(
    onClick = onClick,
    enabled = enabled,
    interactionSource = interactionSource,
    shape = shape,
    color = containerColor,
    border = BorderStroke(if (isFocused) 2.5.dp else if (isSelected) 2.dp else 1.dp, borderColor),
    modifier = modifier.defaultMinSize(minHeight = 52.dp).scale(scale),
    content = content,
  )
}

@Composable
fun TvListeningBars(audioLevel: Float, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "bars")
  val phase by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 6.283f,
      animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
      label = "wavePhase",
    )

  Row(
    modifier = modifier.height(22.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (i in 0 until 5) {
      val wave = ((sin(phase + i * 1.1f) + 1f) * 0.5f)
      val factor = (0.25f + wave * 0.35f + audioLevel * 0.55f).coerceIn(0.2f, 1f)
      Box(
        modifier =
          Modifier.width(4.dp)
            .height((6f + 16f * factor).dp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (i % 2 == 0) TvCoral else TvCyan)
      )
    }
  }
}
