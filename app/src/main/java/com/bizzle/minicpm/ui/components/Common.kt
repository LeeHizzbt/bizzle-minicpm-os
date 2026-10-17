package com.bizzle.minicpm.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.minicpm.R
import com.bizzle.minicpm.ui.theme.Danger
import com.bizzle.minicpm.ui.theme.LocalPalette

fun copyText(ctx: Context, text: String, label: String = "Copied") {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bizzle", text))
    Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

/**
 * Every screen uses this scaffold, so every page gets the themed back button, the v4.20 header and the Bizzy bubble.
 */
@Composable
fun BizzleScaffold(
    title: String,
    onBack: (() -> Unit)?,
    onBizzy: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) ThemedBackButton(onBack) else Spacer(Modifier.width(4.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Bizzle's Mini CPM v4.7 OS · v4.20", color = pal.text.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1)
                }
                actions()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, pal.accent, Color.Transparent))))
            Column(Modifier.weight(1f).fillMaxWidth()) { content() }
            bottomBar()
        }
        if (onBizzy != null) {
            Box(Modifier.align(Alignment.BottomEnd).systemBarsPadding().imePadding().padding(end = 14.dp, bottom = 92.dp)) { BizzyBubble(onBizzy) }
        }
    }
}

@Composable
fun ThemedBackButton(onBack: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(pal.surface).border(1.5.dp, pal.accent, CircleShape)
            .clickable(onClick = onBack).semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = pal.accent, modifier = Modifier.size(22.dp)) }
}

@Composable
fun BizzyBubble(onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(pal.surface).border(2.dp, pal.accent, CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = "Open Bizzy helper" },
        contentAlignment = Alignment.Center
    ) { Image(painterResource(R.drawable.bizzy_avatar), null, Modifier.size(48.dp).clip(CircleShape)) }
}

@Composable
fun NeonButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Danger else pal.accent, contentColor = if (danger) Color.Black else pal.onAccent,
            disabledContainerColor = pal.accent.copy(alpha = 0.22f), disabledContentColor = pal.text.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun NeonOutlineButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val pal = LocalPalette.current
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        border = BorderStroke(1.dp, pal.accent.copy(alpha = if (enabled) 1f else 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.accent), shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.surface)
            .border(1.dp, pal.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        if (title != null) { Text(title, color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp); Spacer(Modifier.height(8.dp)) }
        content()
    }
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, alpha: Float = 1f, size: Int = 14) {
    Text(text, color = LocalPalette.current.text.copy(alpha = alpha), fontSize = size.sp, modifier = modifier, lineHeight = (size + 6).sp)
}

@Composable
fun Pill(text: String, color: Color = LocalPalette.current.accent) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.text.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = pal.text.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = pal.onAccent, checkedTrackColor = pal.accent),
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, format: (Float) -> String, help: String? = null, onChange: (Float) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(label, color = pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text(format(value), color = pal.accent, fontWeight = FontWeight.Bold) }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent, inactiveTrackColor = pal.accent.copy(alpha = 0.2f)),
            modifier = Modifier.semantics { contentDescription = label })
        if (help != null) Text(help, color = pal.text.copy(alpha = 0.55f), fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
fun NeonField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 6,
              keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
              keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default) {
    val pal = LocalPalette.current
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines, maxLines = maxLines, modifier = modifier,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = pal.accent, unfocusedBorderColor = pal.accent.copy(alpha = 0.45f), focusedLabelColor = pal.accent,
            cursorColor = pal.accent, focusedTextColor = pal.text, unfocusedTextColor = pal.text, unfocusedLabelColor = pal.text.copy(alpha = 0.7f)
        )
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Delete", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text(title, color = pal.accent, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = pal.text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Danger, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = pal.accent) } }
    )
}
