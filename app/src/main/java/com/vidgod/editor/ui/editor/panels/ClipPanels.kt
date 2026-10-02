package com.vidgod.editor.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.engine.effects.label
import com.vidgod.editor.model.ChromaKey
import com.vidgod.editor.model.CropRect
import com.vidgod.editor.model.Mask
import com.vidgod.editor.model.MaskShape
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.SpeedCurves
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.VoiceFx
import com.vidgod.editor.ui.common.ChoiceTile
import com.vidgod.editor.ui.common.LabeledSlider
import com.vidgod.editor.ui.common.PanelHeader
import com.vidgod.editor.ui.common.PillRow
import com.vidgod.editor.ui.common.ToolButton
import com.vidgod.editor.ui.theme.VG
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun visualOf(p: Project, s: Selection?): VisualClip? =
    if (s is Selection.Main || s is Selection.Overlay) (p.clips + p.overlays).firstOrNull { it.id == s.id } else null

@Composable
internal fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = VG.TextDim, fontSize = 13.sp)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = VG.Accent))
    }
}

// ------------------------------------------------------------------ speed

private fun speedToSlider(s: Float) = ((log10(s.toDouble()) + 1) / 3).toFloat().coerceIn(0f, 1f)
private fun sliderToSpeed(v: Float) = 10.0.pow(v * 3.0 - 1.0).toFloat().let { (it * 10).roundToInt() / 10f }.coerceIn(0.1f, 100f)

@Composable
fun SpeedPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s)
    val audio = if (s is Selection.Audio) p.audios.firstOrNull { it.id == s.id } else null
    if (clip == null && audio == null) return Hint("Select a clip")
    if (clip?.isImage == true) {
        Column { PanelHeader("Duration", close); ImageDuration(vm, clip) }
        return
    }
    var tab by remember { mutableStateOf("Normal") }
    val current = clip?.speed ?: audio!!.speed
    var slider by remember(s?.id) { mutableFloatStateOf(speedToSlider(current)) }
    Column {
        PanelHeader("Speed", close)
        if (clip != null) PillRow(listOf("Normal", "Curve"), tab, { tab = it })
        if (tab == "Normal" || clip == null) {
            val shown = sliderToSpeed(slider)
            Text(
                "${shown}x", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = VG.Text,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            LabeledSlider("Speed", slider, { slider = it }, valueText = { "${sliderToSpeed(it)}x" }, onEnd = {
                val sp = sliderToSpeed(slider)
                if (clip != null) vm.editVisual(clip.id) { ProjectOps.clampKeyframes(it.copy(speed = sp, speedCurve = emptyList(), speedCurveName = null)) }
                else vm.editAudio(audio!!.id) { it.copy(speed = sp) }
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0.5f, 1f, 1.5f, 2f, 3f, 5f).forEach { v ->
                    Text(
                        "${v}x", fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(VG.Surface2).clickable {
                            slider = speedToSlider(v)
                            if (clip != null) vm.editVisual(clip.id) { ProjectOps.clampKeyframes(it.copy(speed = v, speedCurve = emptyList(), speedCurveName = null)) }
                            else vm.editAudio(audio!!.id) { it.copy(speed = v) }
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            val keep = clip?.keepPitch ?: audio!!.keepPitch
            SwitchRow("Keep original pitch", keep) { v ->
                if (clip != null) vm.editVisual(clip.id) { it.copy(keepPitch = v) } else vm.editAudio(audio!!.id) { it.copy(keepPitch = v) }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                ChoiceTile("None", clip.speedCurve.isEmpty(), { vm.editVisual(clip.id) { it.copy(speedCurve = emptyList(), speedCurveName = null) } }) {
                    Text("✕", color = VG.TextDim)
                }
                SpeedCurves.presets.forEach { preset ->
                    ChoiceTile(preset.name, clip.speedCurveName == preset.name, {
                        vm.editVisual(clip.id) { ProjectOps.clampKeyframes(it.copy(speedCurve = preset.points, speedCurveName = preset.name, speed = 1f)) }
                    }) { CurveGraph(preset.points) }
                }
            }
            if (clip.speedCurve.isNotEmpty()) {
                Text(
                    "Duration: ${"%.1f".format(clip.durationUs / 1e6)}s (source ${"%.1f".format(clip.sourceRangeUs / 1e6)}s)",
                    color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CurveGraph(points: List<com.vidgod.editor.model.SpeedPoint>) {
    Canvas(Modifier.size(48.dp, 36.dp)) {
        val maxS = 6f
        var prev: Offset? = null
        for (i in 0..24) {
            val pos = i / 24f
            val v = SpeedCurves.valueAt(points, pos)
            val y = size.height * (1f - (log10(v.toDouble()) + 1).toFloat() / (log10(maxS.toDouble()) + 1).toFloat())
            val pt = Offset(size.width * pos, y.coerceIn(0f, size.height))
            prev?.let { drawLine(VG.Accent, it, pt, 3f) }
            prev = pt
        }
    }
}

@Composable
private fun ImageDuration(vm: EditorViewModel, clip: VisualClip) {
    var secs by remember(clip.id) { mutableFloatStateOf(clip.durationUs / 1e6f) }
    LabeledSlider("Duration", secs, { secs = it }, range = 0.5f..20f, valueText = { "%.1fs".format(it) }, onEnd = {
        vm.editVisual(clip.id) { ProjectOps.clampKeyframes(it.copy(trimStartUs = 0, trimEndUs = (secs * 1e6).toLong())) }
    })
}

// ------------------------------------------------------------------ volume

@Composable
fun VolumePanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s)
    val audio = if (s is Selection.Audio) p.audios.firstOrNull { it.id == s.id } else null
    if (clip == null && audio == null) return Hint("Select a clip or audio")
    val volume = clip?.volume ?: audio!!.volume
    val fadeIn = clip?.fadeInUs ?: audio!!.fadeInUs
    val fadeOut = clip?.fadeOutUs ?: audio!!.fadeOutUs
    var v by remember(s?.id) { mutableFloatStateOf(volume) }
    var fi by remember(s?.id) { mutableFloatStateOf(fadeIn / 1e6f) }
    var fo by remember(s?.id) { mutableFloatStateOf(fadeOut / 1e6f) }
    // Only moving the volume slider itself un-mutes a muted clip (fades must not).
    fun commit(live: Boolean, unmute: Boolean = false) {
        if (clip != null) vm.editVisual(clip.id, record = !live) { it.copy(volume = v, fadeInUs = (fi * 1e6).toLong(), fadeOutUs = (fo * 1e6).toLong(), muted = if (unmute && v > 0f) false else it.muted) }
        else vm.editAudio(audio!!.id, record = !live) { it.copy(volume = v, fadeInUs = (fi * 1e6).toLong(), fadeOutUs = (fo * 1e6).toLong()) }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Volume", close, onApplyAll = if (clip != null && s is Selection.Main) ({ vm.editAllMain { it.copy(volume = v) } }) else null)
        LabeledSlider("Volume", v, { v = it; if (clip != null) commit(true, unmute = true) }, range = 0f..2f, valueText = { "${(it * 100).roundToInt()}%" },
            onStart = { vm.beginGesture() }, onEnd = { commit(false, unmute = true); vm.endGesture() })
        LabeledSlider("Fade in", fi, { fi = it; if (clip != null) commit(true) }, range = 0f..5f, valueText = { "%.1fs".format(it) },
            onStart = { vm.beginGesture() }, onEnd = { commit(false); vm.endGesture() })
        LabeledSlider("Fade out", fo, { fo = it; if (clip != null) commit(true) }, range = 0f..5f, valueText = { "%.1fs".format(it) },
            onStart = { vm.beginGesture() }, onEnd = { commit(false); vm.endGesture() })
        if (clip != null) {
            SwitchRow("Mute this clip", clip.muted) { m -> vm.editVisual(clip.id) { it.copy(muted = m) } }
        }
    }
}

// ------------------------------------------------------------------ opacity

@Composable
fun OpacityPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s)
    val sticker = if (s is Selection.Sticker) p.stickers.firstOrNull { it.id == s.id } else null
    if (clip == null && sticker == null) return Hint("Select a clip or sticker")
    var o by remember(s?.id) { mutableFloatStateOf(clip?.opacity ?: sticker!!.opacity) }
    Column {
        PanelHeader("Opacity", close)
        LabeledSlider("Opacity", o, { v ->
            o = v
            if (clip != null) vm.editVisual(clip.id, record = false) { it.copy(opacity = v) }
            else vm.editSticker(sticker!!.id, record = false) { it.copy(opacity = v) }
        }, valueText = { "${(it * 100).roundToInt()}%" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
    }
}

// ------------------------------------------------------------------ transform / crop

@Composable
fun TransformPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s) ?: return Hint("Select a clip")
    var tab by remember { mutableStateOf("Transform") }
    val (cw, ch) = p.canvasSize(1080)
    val srcW = max(1, clip.source.width).toFloat()
    val srcH = max(1, clip.source.height).toFloat()
    fun fillScale(c: VisualClip): Float {
        val w = srcW * (c.crop.right - c.crop.left)
        val h = srcH * (c.crop.bottom - c.crop.top)
        return max(cw / w, ch / h) / min(cw / w, ch / h)
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Transform", close)
        PillRow(listOf("Transform", "Crop"), tab, { tab = it })
        if (tab == "Transform") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                ToolButton(Icons.AutoMirrored.Filled.RotateRight, "Rotate", {
                    vm.editVisual(clip.id) { it.copy(transform = it.transform.copy(rotation = ((it.transform.rotation + 90f) % 360f))) }
                })
                ToolButton(Icons.Default.Flip, "Mirror", { vm.editVisual(clip.id) { it.copy(transform = it.transform.copy(flipH = !it.transform.flipH)) } })
                ToolButton(Icons.Default.Flip, "Flip", { vm.editVisual(clip.id) { it.copy(transform = it.transform.copy(flipV = !it.transform.flipV)) } })
                ToolButton(Icons.Default.FitScreen, "Fit", { vm.editVisual(clip.id) { it.copy(transform = it.transform.copy(scale = 1f, x = 0f, y = 0f)) } })
                ToolButton(Icons.Default.Fullscreen, "Fill", { vm.editVisual(clip.id) { it.copy(transform = it.transform.copy(scale = fillScale(it), x = 0f, y = 0f)) } })
                ToolButton(Icons.Default.CenterFocusStrong, "Center", { vm.editVisual(clip.id) { it.copy(transform = it.transform.copy(x = 0f, y = 0f)) } })
                ToolButton(Icons.Default.RestartAlt, "Reset", { vm.editVisual(clip.id) { it.copy(transform = Transform(), crop = CropRect()) } })
            }
            LabeledSlider("Scale", clip.transform.scale, { v -> vm.editVisual(clip.id, false) { it.copy(transform = it.transform.copy(scale = v)) } },
                range = 0.1f..5f, valueText = { "${(it * 100).roundToInt()}%" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Rotation", clip.transform.rotation, { v -> vm.editVisual(clip.id, false) { it.copy(transform = it.transform.copy(rotation = v)) } },
                range = -180f..180f, valueText = { "${it.roundToInt()}°" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Position X", clip.transform.x, { v -> vm.editVisual(clip.id, false) { it.copy(transform = it.transform.copy(x = v)) } },
                range = -1f..1f, valueText = { "${(it * 100).roundToInt()}" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Position Y", clip.transform.y, { v -> vm.editVisual(clip.id, false) { it.copy(transform = it.transform.copy(y = v)) } },
                range = -1f..1f, valueText = { "${(it * 100).roundToInt()}" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        } else {
            val presets = listOf("Free" to 0f, "9:16" to 9f / 16f, "16:9" to 16f / 9f, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "4:3" to 4f / 3f)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                presets.forEach { (name, ratio) ->
                    ChoiceTile(name, false, {
                        vm.editVisual(clip.id) { c ->
                            if (ratio == 0f) c.copy(crop = CropRect()) else {
                                val srcAspect = srcW / srcH
                                c.copy(
                                    crop = if (ratio < srcAspect) {
                                        val wFrac = ratio / srcAspect
                                        CropRect(0.5f - wFrac / 2, 0f, 0.5f + wFrac / 2, 1f)
                                    } else {
                                        val hFrac = srcAspect / ratio
                                        CropRect(0f, 0.5f - hFrac / 2, 1f, 0.5f + hFrac / 2)
                                    },
                                )
                            }
                        }
                    }) { androidx.compose.material3.Icon(Icons.Default.Crop, null, tint = VG.Text) }
                }
            }
            val c = clip.crop
            LabeledSlider("Left", c.left, { v -> vm.editVisual(clip.id, false) { it.copy(crop = it.crop.copy(left = v.coerceAtMost(it.crop.right - 0.05f))) } },
                range = 0f..0.95f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Right", c.right, { v -> vm.editVisual(clip.id, false) { it.copy(crop = it.crop.copy(right = v.coerceAtLeast(it.crop.left + 0.05f))) } },
                range = 0.05f..1f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Top", c.top, { v -> vm.editVisual(clip.id, false) { it.copy(crop = it.crop.copy(top = v.coerceAtMost(it.crop.bottom - 0.05f))) } },
                range = 0f..0.95f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Bottom", c.bottom, { v -> vm.editVisual(clip.id, false) { it.copy(crop = it.crop.copy(bottom = v.coerceAtLeast(it.crop.top + 0.05f))) } },
                range = 0.05f..1f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        }
    }
}

// ------------------------------------------------------------------ chroma key

private val keyColors = listOf(0xFF00FF00, 0xFF00B140, 0xFF0047BB, 0xFF0000FF, 0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFFFF00FF)

@Composable
fun ChromaPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s) ?: return Hint("Select a clip")
    val ck = clip.chromaKey
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Chroma key", close)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pick the background colour to remove", color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "🎯 Pick from video", color = Color.Black, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(VG.Accent).clickable { vm.startEyedropper(clip.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(VG.Surface2).border(2.dp, if (ck == null) VG.Accent else Color.Transparent, CircleShape)
                    .clickable { vm.editVisual(clip.id) { it.copy(chromaKey = null) } },
                contentAlignment = Alignment.Center,
            ) { Text("Off", fontSize = 11.sp) }
            keyColors.forEach { col ->
                val c = col.toInt()
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                        .border(2.dp, if (ck?.color == c) VG.Accent else VG.Surface3, CircleShape)
                        .clickable { vm.editVisual(clip.id) { it.copy(chromaKey = (it.chromaKey ?: ChromaKey()).copy(color = c)) } },
                )
            }
        }
        if (ck != null) {
            LabeledSlider("Intensity", ck.intensity, { v -> vm.editVisual(clip.id, false) { it.copy(chromaKey = it.chromaKey?.copy(intensity = v)) } },
                onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Edge", ck.shadow, { v -> vm.editVisual(clip.id, false) { it.copy(chromaKey = it.chromaKey?.copy(shadow = v)) } },
                onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        }
    }
}

// ------------------------------------------------------------------ mask

@Composable
fun MaskPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s) ?: return Hint("Select a clip")
    val m = clip.mask
    fun edit(live: Boolean = false, f: (Mask) -> Mask) = vm.editVisual(clip.id, record = !live) { c -> c.copy(mask = c.mask?.let(f)) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Mask", close)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ChoiceTile("None", m == null, { vm.editVisual(clip.id) { it.copy(mask = null) } }) { Text("✕", color = VG.TextDim) }
            MaskShape.entries.forEach { shape ->
                ChoiceTile(shape.label, m?.shape == shape, {
                    vm.editVisual(clip.id) { it.copy(mask = (it.mask ?: Mask(shape)).copy(shape = shape)) }
                }) { MaskIcon(shape) }
            }
        }
        if (m != null) {
            SwitchRow("Invert", m.invert) { v -> edit { it.copy(invert = v) } }
            LabeledSlider("Size", m.height, { v -> edit(true) { it.copy(width = v, height = v) } }, range = 0.05f..2f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Width", m.width, { v -> edit(true) { it.copy(width = v) } }, range = 0.05f..2f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Feather", m.feather, { v -> edit(true) { it.copy(feather = v) } }, range = 0f..0.5f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Rotation", m.rotation, { v -> edit(true) { it.copy(rotation = v) } }, range = -180f..180f, valueText = { "${it.roundToInt()}°" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Move X", m.x, { v -> edit(true) { it.copy(x = v) } }, range = -0.5f..0.5f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Move Y", m.y, { v -> edit(true) { it.copy(y = v) } }, range = -0.5f..0.5f, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            if (m.shape == MaskShape.RECTANGLE) {
                LabeledSlider("Round", m.roundness, { v -> edit(true) { it.copy(roundness = v) } }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            }
        }
    }
}

@Composable
private fun MaskIcon(shape: MaskShape) {
    Canvas(Modifier.size(30.dp)) {
        val c = Color.White
        when (shape) {
            MaskShape.LINEAR -> drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f)
            MaskShape.MIRROR -> {
                drawLine(c, Offset(0f, size.height * 0.3f), Offset(size.width, size.height * 0.3f), 4f)
                drawLine(c, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.7f), 4f)
            }
            MaskShape.CIRCLE -> drawCircle(c, size.minDimension / 2.2f, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            MaskShape.RECTANGLE -> drawRect(c, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            MaskShape.HEART -> drawCircle(c, size.minDimension / 4, Offset(size.width * 0.33f, size.height * 0.4f))
                .also { drawCircle(c, size.minDimension / 4, Offset(size.width * 0.67f, size.height * 0.4f)) }
            MaskShape.STAR -> drawCircle(c, size.minDimension / 6)
        }
    }
}

// ------------------------------------------------------------------ voice effects

@Composable
fun VoiceFxPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s)
    val audio = if (s is Selection.Audio) p.audios.firstOrNull { it.id == s.id } else null
    if (clip == null && audio == null) return Hint("Select a clip or audio")
    val current = clip?.voiceFx ?: audio!!.voiceFx
    val emoji = mapOf(
        VoiceFx.NONE to "🚫", VoiceFx.CHIPMUNK to "🐿️", VoiceFx.HELIUM to "🎈", VoiceFx.DEEP to "🎙️", VoiceFx.MONSTER to "👹",
        VoiceFx.GIANT to "🗿", VoiceFx.ROBOT to "🤖", VoiceFx.ECHO to "🏞️", VoiceFx.CAVE to "🕳️", VoiceFx.TELEPHONE to "☎️",
        VoiceFx.MEGAPHONE to "📢", VoiceFx.RADIO to "📻",
    )
    Column {
        PanelHeader("Voice effects", close)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            VoiceFx.entries.forEach { fx ->
                ChoiceTile(fx.name.lowercase().replaceFirstChar { it.uppercase() }, fx == current, {
                    if (clip != null) vm.editVisual(clip.id) { it.copy(voiceFx = fx) } else vm.editAudio(audio!!.id) { it.copy(voiceFx = fx) }
                }) { Text(emoji[fx] ?: "🎵", fontSize = 24.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------ overlay layer order

@Composable
fun LayerPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = if (s is Selection.Overlay) p.overlays.firstOrNull { it.id == s.id } else null
    if (clip == null) return Hint("Select an overlay")
    Column {
        PanelHeader("Layer", close)
        Text("Layer ${clip.layer + 1}", modifier = Modifier.padding(16.dp), color = VG.TextDim)
        Row(Modifier.padding(horizontal = 8.dp)) {
            ToolButton(Icons.Default.ArrowUpward, "Bring forward", {
                vm.update { pr -> ProjectOps.normalizeLanes(ProjectOps.updateVisual(pr, clip.id) { it.copy(layer = it.layer + 1) }) }
            })
            ToolButton(Icons.Default.ArrowDownward, "Send back", {
                vm.update { pr -> ProjectOps.normalizeLanes(ProjectOps.updateVisual(pr, clip.id) { it.copy(layer = (it.layer - 1).coerceAtLeast(0)) }) }
            }, enabled = clip.layer > 0)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize())
        @Suppress("UNUSED_VARIABLE") val w = Modifier.width(1.dp)
    }
}
