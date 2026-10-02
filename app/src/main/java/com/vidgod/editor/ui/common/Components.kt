package com.vidgod.editor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.ui.theme.VG
import kotlin.math.roundToInt

/** Icon + label button used in the bottom toolbars. */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    badge: String? = null,
) {
    Column(
        modifier = modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                icon, contentDescription = label,
                tint = when {
                    !enabled -> VG.TextDim.copy(alpha = 0.4f)
                    selected -> VG.Accent
                    else -> VG.Text
                },
                modifier = Modifier.size(24.dp),
            )
            if (badge != null) {
                Text(
                    badge, fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).background(VG.Accent, CircleShape).padding(horizontal = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = 11.sp, color = if (selected) VG.Accent else if (enabled) VG.Text else VG.TextDim.copy(alpha = 0.4f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** Header of a bottom panel with a title and done button. */
@Composable
fun PanelHeader(title: String, onClose: () -> Unit, onApplyAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = VG.TextDim) }
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (onApplyAll != null) {
            Text(
                "Apply to all", color = VG.Accent, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onApplyAll).padding(8.dp),
            )
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Check, "Done", tint = VG.Accent) }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: (Float) -> String = { "${(it * 100).roundToInt()}" },
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    steps: Int = 0,
) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = VG.TextDim, modifier = Modifier.width(84.dp), maxLines = 1)
        var started = false
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                if (!started) { started = true; onStart() }
                onValueChange(it)
            },
            onValueChangeFinished = { started = false; onEnd() },
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = VG.Accent, inactiveTrackColor = VG.Surface3),
        )
        Text(valueText(value), fontSize = 12.sp, color = VG.Text, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

/** A selectable rounded tile with an optional colour swatch. */
@Composable
fun ChoiceTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.width(68.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(swatch ?: VG.Surface2)
                .border(2.dp, if (selected) VG.Accent else Color.Transparent, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { content?.invoke() }
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = 11.sp, color = if (selected) VG.Accent else VG.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) VG.Text else VG.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (selected) Color.Black else VG.Text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
fun PillRow(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { Pill(it, it == selected, { onSelect(it) }) }
    }
}

fun formatTime(us: Long, withFraction: Boolean = false): String {
    val totalMs = us / 1000
    val m = totalMs / 60_000
    val s = (totalMs / 1000) % 60
    return if (withFraction) {
        val f = (totalMs % 1000) / 100
        "%02d:%02d.%d".format(m, s, f)
    } else {
        "%02d:%02d".format(m, s)
    }
}

fun Int.toComposeColor() = Color(this)
