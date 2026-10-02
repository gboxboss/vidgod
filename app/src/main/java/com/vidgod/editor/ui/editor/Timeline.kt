package com.vidgod.editor.ui.editor

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.engine.catalog.Effects
import com.vidgod.editor.engine.catalog.Filters
import com.vidgod.editor.media.Thumbnails
import com.vidgod.editor.media.Waveforms
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.sourceToTimelineUs
import com.vidgod.editor.model.timelineToSourceUs
import com.vidgod.editor.ui.common.formatTime
import com.vidgod.editor.ui.theme.VG
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Scroll/zoom state shared by all timeline rows. */
class TimelineState {
    /** Pixels per second. */
    var pxPerSec by mutableFloatStateOf(90f)
    /** True while the user is touching the timeline. */
    var userScrolling by mutableStateOf(false)

    fun usToPx(us: Long) = us / 1_000_000f * pxPerSec
    fun pxToUs(px: Float) = (px / pxPerSec * 1_000_000f).toLong()
}

private val ROW_MAIN = 56.dp
private val ROW_SMALL = 30.dp
private val ROW_OVERLAY = 40.dp
private val ROW_AUDIO = 36.dp
private val ROW_GAP = 4.dp
private val HANDLE_W = 14.dp

@Composable
fun Timeline(
    vm: EditorViewModel,
    project: Project,
    positionUs: Long,
    selection: Selection?,
    state: TimelineState,
    onAddMedia: () -> Unit,
    onTransitionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val duration = project.durationUs
    val currentPosition by rememberUpdatedState(positionUs)
    val currentDuration by rememberUpdatedState(duration)
    val fling = remember { Animatable(0f) }

    BoxWithConstraints(modifier.fillMaxWidth().background(VG.Bg)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val half = widthPx / 2f
        // x position on screen of a timeline time
        fun xOf(us: Long): Float = half + state.usToPx(us) - state.usToPx(currentPosition)

        // Background gestures: scroll, fling and pinch-zoom.
        val gestureModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                scope.launch { fling.stop() }
                state.userScrolling = true
                vm.preview.pause()
                vm.preview.setScrubbing(true)
                val tracker = VelocityTracker()
                var pos = currentPosition
                var moved = false
                tracker.addPosition(down.uptimeMillis, down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) state.pxPerSec = (state.pxPerSec * zoom).coerceIn(8f, 600f)
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        moved = true
                    } else {
                        val ch = pressed.first()
                        if (ch.isConsumed) continue
                        val dx = ch.positionChange().x
                        if (dx != 0f) {
                            moved = true
                            pos = (pos - state.pxToUs(dx)).coerceIn(0, currentDuration)
                            vm.preview.seekTo(pos)
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            ch.consume()
                        }
                    }
                }
                vm.preview.setScrubbing(false)
                val v = tracker.calculateVelocity().x
                if (moved && abs(v) > 300f) {
                    scope.launch {
                        fling.snapTo(0f)
                        var last = 0f
                        fling.animateDecay(-v, exponentialDecay(frictionMultiplier = 1.6f)) {
                            val delta = value - last
                            last = value
                            pos = (pos + state.pxToUs(delta)).coerceIn(0, currentDuration)
                            vm.preview.seekTo(pos)
                        }
                        state.userScrolling = false
                    }
                } else {
                    state.userScrolling = false
                }
            }
        }

        Column(Modifier.fillMaxWidth().then(gestureModifier)) {
            Ruler(state, widthPx, currentPosition, duration)
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    // Overlay (picture-in-picture) layers, top-most first.
                    project.overlays.map { it.layer }.distinct().sortedDescending().forEach { layer ->
                        Lane(ROW_OVERLAY) {
                            project.overlays.filter { it.layer == layer }.forEach { c ->
                                TimedItem(
                                    vm, state, Selection.Overlay(c.id), selection, c.startUs, c.durationUs, ::xOf, widthPx,
                                    VG.TrackOverlay, ROW_OVERLAY, trimmableSource = c,
                                ) { w -> FilmStrip(c, w, ROW_OVERLAY, state) }
                            }
                        }
                    }
                    // Texts
                    project.texts.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.texts.filter { it.lane == lane }.forEach { t ->
                                TimedItem(vm, state, Selection.Text(t.id), selection, t.startUs, t.durationUs, ::xOf, widthPx, if (t.isCaption) Color(0xFFD9822B) else VG.TrackText, ROW_SMALL) {
                                    ItemLabel(if (t.isCaption) "CC  ${t.text}" else "T  ${t.text}")
                                }
                            }
                        }
                    }
                    // Stickers
                    project.stickers.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.stickers.filter { it.lane == lane }.forEach { s ->
                                TimedItem(vm, state, Selection.Sticker(s.id), selection, s.startUs, s.durationUs, ::xOf, widthPx, VG.TrackSticker, ROW_SMALL) {
                                    ItemLabel(if (s.kind == StickerKind.EMOJI) s.content else "Sticker")
                                }
                            }
                        }
                    }
                    // Effects
                    project.effects.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.effects.filter { it.lane == lane }.forEach { e ->
                                TimedItem(vm, state, Selection.Effect(e.id), selection, e.startUs, e.durationUs, ::xOf, widthPx, VG.TrackEffect, ROW_SMALL) {
                                    ItemLabel("✦ " + (Effects.get(e.fx.id)?.name ?: e.fx.id))
                                }
                            }
                        }
                    }
                    // Filters / adjustments
                    project.filters.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.filters.filter { it.lane == lane }.forEach { f ->
                                TimedItem(vm, state, Selection.Filter(f.id), selection, f.startUs, f.durationUs, ::xOf, widthPx, VG.TrackFilter, ROW_SMALL) {
                                    ItemLabel("◐ " + (Filters.get(f.filter?.id)?.name ?: "Adjust"))
                                }
                            }
                        }
                    }
                    // Main track
                    MainTrack(vm, project, selection, state, ::xOf, widthPx, onAddMedia, onTransitionClick)
                    // Audio
                    project.audios.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_AUDIO) {
                            project.audios.filter { it.lane == lane }.forEach { a ->
                                TimedItem(vm, state, Selection.Audio(a.id), selection, a.startUs, a.durationUs, ::xOf, widthPx, VG.TrackAudio, ROW_AUDIO, audio = a) { w ->
                                    Waveform(a.source.uri, a.trimStartUs, a.speed, w, state)
                                    ItemLabel("♪ " + a.name, Modifier.align(Alignment.TopStart))
                                }
                            }
                        }
                    }
                }
            }
        }
        // Playhead
        Box(
            Modifier.offset { IntOffset((half - with(density) { 1.dp.toPx() }).roundToInt(), 0) }
                .width(2.dp).fillMaxHeight().background(Color.White),
        )
        Text(
            formatTime(positionUs, true), fontSize = 10.sp, color = Color.Black,
            modifier = Modifier.offset { IntOffset((half + with(density) { 4.dp.toPx() }).roundToInt(), 0) }
                .background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
        )
    }
}

@Composable
private fun Lane(height: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height + ROW_GAP)) { content() }
}

@Composable
private fun ItemLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Ruler(state: TimelineState, widthPx: Float, positionUs: Long, durationUs: Long) {
    val density = LocalDensity.current
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8E8E96.toInt()
            textSize = with(density) { 9.sp.toPx() }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val half = widthPx / 2f
        val stepSec = when {
            state.pxPerSec > 300 -> 0.25f
            state.pxPerSec > 120 -> 0.5f
            state.pxPerSec > 50 -> 1f
            state.pxPerSec > 20 -> 2f
            else -> 5f
        }
        val startSec = ((positionUs / 1e6f) - half / state.pxPerSec).coerceAtLeast(0f)
        val endSec = (positionUs / 1e6f + half / state.pxPerSec).coerceAtMost(durationUs / 1e6f + 1)
        var s = (startSec / stepSec).toInt() * stepSec
        while (s <= endSec) {
            val x = half + (s - positionUs / 1e6f) * state.pxPerSec
            val major = abs(s - s.roundToInt()) < 0.001f && (s.roundToInt() % max(1, stepSec.roundToInt())) == 0
            drawLine(Color(0xFF55555C), Offset(x, size.height * if (major) 0.45f else 0.7f), Offset(x, size.height), 1f)
            if (major) drawContext.canvas.nativeCanvas.drawText(formatTime((s * 1e6f).toLong()), x + 3f, size.height * 0.45f, labelPaint)
            s += stepSec
        }
    }
}

/** A generic item on a secondary lane: tap to select, long-press-drag to move, handles to trim. */
@Composable
private fun TimedItem(
    vm: EditorViewModel,
    state: TimelineState,
    sel: Selection,
    current: Selection?,
    startUs: Long,
    durationUs: Long,
    xOf: (Long) -> Float,
    widthPx: Float,
    color: Color,
    height: androidx.compose.ui.unit.Dp,
    trimmableSource: VisualClip? = null,
    audio: com.vidgod.editor.model.AudioClip? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(Float) -> Unit,
) {
    val density = LocalDensity.current
    val x = xOf(startUs)
    val w = state.usToPx(durationUs).coerceAtLeast(with(density) { 4.dp.toPx() })
    if (x > widthPx || x + w < 0) return
    val selected = current == sel
    val curStart by rememberUpdatedState(startUs)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), 0) }
            .width(with(density) { w.toDp() })
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (selected) 1f else 0.82f))
            .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
            .pointerInput(sel) {
                detectTapGestures(onTap = { vm.select(if (vm.selection.value == sel) null else sel) })
            }
            .pointerInput(sel) {
                var startAt = 0L
                var acc = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { vm.select(sel); vm.beginGesture(); startAt = curStart; acc = 0f },
                    onDragEnd = { vm.update(record = false) { ProjectOps.normalizeLanes(it) }; vm.endGesture() },
                    onDragCancel = { vm.endGesture() },
                ) { change, drag ->
                    change.consume()
                    acc += drag.x
                    val target = (startAt + state.pxToUs(acc)).coerceAtLeast(0)
                    vm.update(record = false) { ProjectOps.moveTo(it, sel, target) }
                }
            },
    ) {
        content(w)
    }
    if (selected) {
        // Trim handles
        TrimHandle(xLeft = x - with(density) { HANDLE_W.toPx() }, height = height, left = true) { dxPx, phase ->
            when (phase) {
                0 -> vm.beginGesture()
                2 -> vm.endGesture()
                else -> {
                    val dUs = state.pxToUs(dxPx)
                    vm.update(record = false) { p -> trimLeft(p, sel, dUs) }
                }
            }
        }
        TrimHandle(xLeft = x + w, height = height, left = false) { dxPx, phase ->
            when (phase) {
                0 -> vm.beginGesture()
                2 -> vm.endGesture()
                else -> {
                    val dUs = state.pxToUs(dxPx)
                    vm.update(record = false) { p -> trimRight(p, sel, dUs) }
                }
            }
        }
    }
}

/** Trim handle; [onDrag] receives (dx, phase) with phase 0=start 1=drag 2=end. */
@Composable
private fun TrimHandle(xLeft: Float, height: androidx.compose.ui.unit.Dp, left: Boolean, onDrag: (Float, Int) -> Unit) {
    Box(
        Modifier
            .offset { IntOffset(xLeft.roundToInt(), 0) }
            .width(HANDLE_W)
            .height(height)
            .clip(RoundedCornerShape(topStart = if (left) 6.dp else 0.dp, bottomStart = if (left) 6.dp else 0.dp, topEnd = if (left) 0.dp else 6.dp, bottomEnd = if (left) 0.dp else 6.dp))
            .background(Color.White)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDrag(0f, 0) },
                    onDragEnd = { onDrag(0f, 2) },
                    onDragCancel = { onDrag(0f, 2) },
                ) { change, drag ->
                    change.consume()
                    onDrag(drag.x, 1)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(height / 3).background(Color.Black.copy(alpha = 0.6f)))
    }
}

private fun trimLeft(p: Project, sel: Selection, dUs: Long): Project {
    return when (sel) {
        is Selection.Overlay -> ProjectOps.updateVisual(p, sel.id) { c ->
            val newStart = (c.startUs + dUs).coerceIn(0, c.endUs - ProjectOps.MIN_DURATION_US)
            val realD = newStart - c.startUs
            val srcStart = if (c.isImage) 0 else (c.trimStartUs + (realD * c.speed).toLong()).coerceIn(0, c.trimEndUs - ProjectOps.MIN_DURATION_US)
            if (c.isImage) c.copy(startUs = newStart, trimEndUs = (c.trimEndUs - realD).coerceAtLeast(ProjectOps.MIN_DURATION_US))
            else c.copy(startUs = c.startUs + ((srcStart - c.trimStartUs) / c.speed).toLong(), trimStartUs = srcStart)
        }
        is Selection.Audio -> ProjectOps.updateAudio(p, sel.id) { a ->
            val srcStart = (a.trimStartUs + (dUs * a.speed).toLong()).coerceIn(0, a.trimEndUs - ProjectOps.MIN_DURATION_US)
            val realD = ((srcStart - a.trimStartUs) / a.speed).toLong()
            a.copy(trimStartUs = srcStart, startUs = (a.startUs + realD).coerceAtLeast(0))
        }
        is Selection.Text -> ProjectOps.updateText(p, sel.id) { t ->
            val ns = (t.startUs + dUs).coerceIn(0, t.endUs - ProjectOps.MIN_DURATION_US)
            t.copy(startUs = ns, durationUs = t.endUs - ns)
        }
        is Selection.Sticker -> ProjectOps.updateSticker(p, sel.id) { t ->
            val ns = (t.startUs + dUs).coerceIn(0, t.endUs - ProjectOps.MIN_DURATION_US)
            t.copy(startUs = ns, durationUs = t.endUs - ns)
        }
        is Selection.Effect -> ProjectOps.updateEffect(p, sel.id) { t ->
            val ns = (t.startUs + dUs).coerceIn(0, t.endUs - ProjectOps.MIN_DURATION_US)
            t.copy(startUs = ns, durationUs = t.endUs - ns)
        }
        is Selection.Filter -> ProjectOps.updateFilter(p, sel.id) { t ->
            val ns = (t.startUs + dUs).coerceIn(0, t.endUs - ProjectOps.MIN_DURATION_US)
            t.copy(startUs = ns, durationUs = t.endUs - ns)
        }
        is Selection.Main -> p
    }
}

private fun trimRight(p: Project, sel: Selection, dUs: Long): Project = when (sel) {
    is Selection.Overlay -> ProjectOps.updateVisual(p, sel.id) { c ->
        if (c.isImage) c.copy(trimEndUs = (c.trimEndUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US))
        else c.copy(trimEndUs = (c.trimEndUs + (dUs * c.speed).toLong()).coerceIn(c.trimStartUs + ProjectOps.MIN_DURATION_US, c.source.durationUs))
    }
    is Selection.Audio -> ProjectOps.updateAudio(p, sel.id) { a ->
        a.copy(trimEndUs = (a.trimEndUs + (dUs * a.speed).toLong()).coerceIn(a.trimStartUs + ProjectOps.MIN_DURATION_US, a.source.durationUs))
    }
    is Selection.Text -> ProjectOps.updateText(p, sel.id) { it.copy(durationUs = (it.durationUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US)) }
    is Selection.Sticker -> ProjectOps.updateSticker(p, sel.id) { it.copy(durationUs = (it.durationUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US)) }
    is Selection.Effect -> ProjectOps.updateEffect(p, sel.id) { it.copy(durationUs = (it.durationUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US)) }
    is Selection.Filter -> ProjectOps.updateFilter(p, sel.id) { it.copy(durationUs = (it.durationUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US)) }
    is Selection.Main -> p
}

@Composable
private fun MainTrack(
    vm: EditorViewModel,
    project: Project,
    selection: Selection?,
    state: TimelineState,
    xOf: (Long) -> Float,
    widthPx: Float,
    onAddMedia: () -> Unit,
    onTransitionClick: (String) -> Unit,
) {
    val density = LocalDensity.current
    val starts = project.clipStarts()
    Box(Modifier.fillMaxWidth().height(ROW_MAIN + 8.dp).padding(vertical = 4.dp)) {
        // Mute toggle left of the track start
        val startX = xOf(0)
        Box(
            Modifier.offset { IntOffset((startX - with(density) { 64.dp.toPx() }).roundToInt(), 0) }
                .size(52.dp, ROW_MAIN)
                .clip(RoundedCornerShape(8.dp))
                .background(VG.Surface2)
                .clickable { vm.update { it.copy(mainMuted = !it.mainMuted) } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (project.mainMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    "Mute track", tint = VG.Text, modifier = Modifier.size(18.dp),
                )
                Text(if (project.mainMuted) "Unmute" else "Mute", fontSize = 9.sp, color = VG.TextDim)
            }
        }
        project.clips.forEachIndexed { i, clip ->
            val x = xOf(starts[i])
            val w = state.usToPx(clip.durationUs)
            if (x <= widthPx && x + w >= 0) {
                val sel = Selection.Main(clip.id)
                val selected = selection == sel
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), 0) }
                        .width(with(density) { w.toDp() })
                        .height(ROW_MAIN)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VG.TrackMain)
                        .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                        .pointerInput(clip.id) {
                            detectTapGestures(onTap = { vm.select(if (vm.selection.value == sel) null else sel) })
                        },
                ) {
                    FilmStrip(clip, w, ROW_MAIN, state)
                    val badges = buildList {
                        if (clip.speed != 1f || clip.speedCurve.isNotEmpty()) add(if (clip.speedCurve.isNotEmpty()) "Curve" else "${"%.1f".format(clip.speed)}x")
                        if (clip.isReversed) add("Reversed")
                        if (clip.muted) add("Muted")
                        if (clip.filter != null) add("Filter")
                        if (clip.keyframes.isNotEmpty()) add("◆")
                    }
                    if (badges.isNotEmpty()) {
                        Text(
                            badges.joinToString(" · "), fontSize = 9.sp, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                                .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                        )
                    }
                    Text(
                        formatTime(clip.durationUs, true), fontSize = 9.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(3.dp)
                            .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                    )
                }
                if (selected) MainTrimHandles(vm, clip, x, w, state)
            }
            // Transition button between clips
            if (i < project.clips.size - 1) {
                val bx = xOf(starts[i] + clip.durationUs)
                if (bx > -40 && bx < widthPx + 40) {
                    val has = clip.transitionOut != null
                    Box(
                        Modifier.offset { IntOffset((bx - with(density) { 11.dp.toPx() }).roundToInt(), with(density) { 16.dp.toPx() }.roundToInt()) }
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (has) VG.Accent else Color.White)
                            .clickable { onTransitionClick(clip.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.SwapHoriz, "Transition", tint = Color.Black, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        // Add button after the last clip
        val endX = xOf(project.mainDurationUs)
        Box(
            Modifier.offset { IntOffset((endX + with(density) { 12.dp.toPx() }).roundToInt(), with(density) { 8.dp.toPx() }.roundToInt()) }
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(onClick = onAddMedia),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, "Add media", tint = Color.Black) }
    }
}

@Composable
private fun MainTrimHandles(vm: EditorViewModel, clip: VisualClip, x: Float, w: Float, state: TimelineState) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf<VisualClip?>(null) }
    var acc by remember { mutableFloatStateOf(0f) }
    TrimHandle(x - with(density) { HANDLE_W.toPx() }, ROW_MAIN, true) { dx, phase ->
        when (phase) {
            0 -> { vm.beginGesture(); origin = vm.project.value.clips.firstOrNull { it.id == clip.id }; acc = 0f }
            2 -> { vm.endGesture(); origin = null }
            else -> {
                val o = origin ?: return@TrimHandle
                acc += dx
                val dUs = state.pxToUs(acc)
                vm.editVisual(clip.id, record = false) {
                    if (o.isImage) o.copy(trimEndUs = (o.trimEndUs - dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US))
                    else {
                        val newSrc = o.timelineToSourceUs(dUs.coerceAtLeast(0)).let { if (dUs < 0) o.trimStartUs + (dUs * o.speed).toLong() else it }
                        ProjectOps.clampKeyframes(ProjectOps.trimVisual(o, newSrc, o.trimEndUs))
                    }
                }
            }
        }
    }
    TrimHandle(x + w, ROW_MAIN, false) { dx, phase ->
        when (phase) {
            0 -> { vm.beginGesture(); origin = vm.project.value.clips.firstOrNull { it.id == clip.id }; acc = 0f }
            2 -> { vm.endGesture(); origin = null }
            else -> {
                val o = origin ?: return@TrimHandle
                acc += dx
                val dUs = state.pxToUs(acc)
                vm.editVisual(clip.id, record = false) {
                    if (o.isImage) o.copy(trimEndUs = (o.trimEndUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US))
                    else {
                        val endOffset = (o.durationUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US)
                        val newEnd = if (endOffset <= o.durationUs) o.timelineToSourceUs(endOffset) else o.trimEndUs + ((endOffset - o.durationUs) * o.speed).toLong()
                        ProjectOps.clampKeyframes(ProjectOps.trimVisual(o, o.trimStartUs, newEnd))
                    }
                }
            }
        }
    }
}

/** Thumbnails of a visual clip spread over its width on the timeline. */
@Composable
private fun FilmStrip(clip: VisualClip, widthPx: Float, height: androidx.compose.ui.unit.Dp, state: TimelineState) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val thumbW = with(density) { (height * 0.8f).toPx() }
    val count = (widthPx / thumbW).toInt().coerceIn(1, 400)
    Row(Modifier.fillMaxSize()) {
        for (i in 0 until count) {
            val offsetUs = state.pxToUs((i + 0.5f) * thumbW).coerceAtMost(clip.durationUs)
            val srcUs = if (clip.isImage) 0 else clip.timelineToSourceUs(offsetUs)
            Thumb(context, clip.playbackUri, srcUs, clip.isImage, Modifier.width(with(density) { thumbW.toDp() }).fillMaxHeight())
        }
    }
}

@Composable
private fun Thumb(context: Context, uri: String, timeUs: Long, isImage: Boolean, modifier: Modifier) {
    val bucket = if (isImage) 0 else timeUs / 250_000 * 250_000
    val bmp by produceState(Thumbnails.cached(uri, bucket, 160), uri, bucket) {
        if (value == null) value = Thumbnails.get(context, uri, bucket, 160, isImage)
    }
    Box(modifier.background(VG.Surface3)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Waveform(uri: String, trimStartUs: Long, speed: Float, widthPx: Float, state: TimelineState) {
    val context = LocalContext.current
    val peaks by produceState<FloatArray?>(null, uri) { value = Waveforms.peaks(context, uri) }
    val p = peaks ?: return
    Canvas(Modifier.fillMaxSize()) {
        val bars = (widthPx / 4f).toInt().coerceAtLeast(1)
        for (b in 0 until bars) {
            val x = b * 4f
            val srcUs = trimStartUs + (state.pxToUs(x) * speed).toLong()
            val idx = (srcUs / 1_000_000.0 * Waveforms.RATE).toInt()
            val v = if (idx in p.indices) p[idx] else 0f
            val h = size.height * 0.85f * v.coerceAtLeast(0.04f)
            drawLine(Color(0xCCFFFFFF), Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), 2f)
        }
    }
}

/** Keeps playback and the timeline in sync: pinch zoom default on first composition. */
@Composable
fun rememberTimelineState(): TimelineState {
    val s = remember { TimelineState() }
    LaunchedEffect(Unit) { }
    return s
}
