package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.TestStatus
import com.example.data.AppHandoffStatus
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

@Composable
internal fun SettingsSectionCard(
  title: String,
  cardPadding: Dp,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = TvSurface,
    border = BorderStroke(1.dp, TvOutline),
    modifier = modifier.fillMaxWidth(),
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
internal fun TvIconActionButton(
  icon: ImageVector,
  contentDescription: String,
  tint: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isHighlighted: Boolean = false,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()

  val bg =
    when {
      isFocused -> TvSurfaceFocus
      isHighlighted -> tint.copy(alpha = 0.16f)
      else -> TvSurfaceElevated
    }
  val border =
    when {
      isFocused -> TvCyan
      isHighlighted -> tint.copy(alpha = 0.7f)
      else -> TvOutline
    }

  Surface(
    onClick = onClick,
    interactionSource = interactionSource,
    shape = CircleShape,
    color = bg,
    border = BorderStroke(if (isFocused) 2.5.dp else 1.dp, border),
    modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).size(48.dp),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (isFocused) TvCyan else tint,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

@Composable
internal fun TvOutlinedInput(
  value: String,
  onValueChange: (String) -> Unit,
  placeholderText: String,
  onDone: (() -> Unit)? = null,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    visualTransformation = visualTransformation,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvTextPrimary),
    placeholder = {
      Text(
        text = placeholderText,
        style = MaterialTheme.typography.bodyMedium,
        color = TvTextSecondary.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
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
internal fun TvSwitchRow(
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
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = TvTextPrimary)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TvTextSecondary)
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
internal fun TvCheckboxRow(
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
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = TvTextPrimary)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TvTextSecondary)
      }
    }
  }
}

@Composable
internal fun TestEndpointButton(
  isTesting: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TvPrimaryButton(onClick = onClick, enabled = !isTesting, modifier = modifier) {
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
internal fun EndpointStatusBadge(
  testStatus: TestStatus,
  cachedOkMessage: String?,
  modifier: Modifier = Modifier,
) {
  when {
    testStatus == TestStatus.Testing -> {
      StatusSurface(
        bg = TvCyan.copy(alpha = 0.12f),
        border = TvCyan.copy(alpha = 0.45f),
        fg = TvCyan,
        text = "Testing endpoint & querying models…",
        isLoading = true,
        modifier = modifier,
      )
    }
    testStatus is TestStatus.Ok -> {
      StatusSurface(
        bg = TvEmerald.copy(alpha = 0.14f),
        border = TvEmerald.copy(alpha = 0.6f),
        fg = TvEmerald,
        text = testStatus.message,
        icon = Icons.Rounded.CheckCircle,
        iconDesc = "Connected",
        textTag = "test_result_text",
        modifier = modifier,
      )
    }
    testStatus is TestStatus.Warning -> {
      StatusSurface(
        bg = TvAmber.copy(alpha = 0.14f),
        border = TvAmber.copy(alpha = 0.6f),
        fg = TvAmber,
        text = testStatus.message,
        icon = Icons.Rounded.Warning,
        iconDesc = "Warning",
        textTag = "test_result_text",
        modifier = modifier,
      )
    }
    testStatus is TestStatus.Error -> {
      StatusSurface(
        bg = TvCoral.copy(alpha = 0.14f),
        border = TvCoral.copy(alpha = 0.6f),
        fg = TvCoral,
        text = testStatus.message,
        icon = Icons.Rounded.Warning,
        iconDesc = "Error",
        textTag = "test_result_text",
        modifier = modifier,
      )
    }
    cachedOkMessage != null -> {
      StatusSurface(
        bg = TvEmerald.copy(alpha = 0.10f),
        border = TvEmerald.copy(alpha = 0.45f),
        fg = TvEmerald,
        text = cachedOkMessage,
        icon = Icons.Rounded.CheckCircle,
        iconDesc = "Active",
        textTag = "test_result_text",
        modifier = modifier,
      )
    }
  }
}

@Composable
private fun StatusSurface(
  bg: Color,
  border: Color,
  fg: Color,
  text: String,
  icon: ImageVector? = null,
  iconDesc: String? = null,
  isLoading: Boolean = false,
  textTag: String? = null,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = bg,
    border = BorderStroke(1.dp, border),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (isLoading) {
        CircularProgressIndicator(color = fg, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
      } else if (icon != null) {
        Icon(imageVector = icon, contentDescription = iconDesc, tint = fg, modifier = Modifier.size(22.dp))
      }
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        modifier = if (textTag != null) Modifier.testTag(textTag) else Modifier,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TargetAppRow(
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
internal fun HandoffBadge(method: HandoffMethod, supported: Boolean) {
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
internal fun VerificationBadge(status: AppHandoffStatus) {
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
internal fun RoleStatusRow(label: String, active: Boolean, detail: String) {
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
      Text(text = label, style = MaterialTheme.typography.titleMedium, color = TvTextPrimary)
      Text(
        text = detail,
        style = MaterialTheme.typography.bodyMedium,
        color = if (active) TvEmerald else TvTextSecondary,
      )
    }
  }
}

@Composable
internal fun AdbCommandBox(
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
