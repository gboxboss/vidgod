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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.timelineToSourceUs
import com.vidgod.editor.ui.common.formatTime
import com.vidgod.editor.ui.theme.VG
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Scroll/zoom state shared by all timeline rows. */
class TimelineState {
    private var zoom by mutableFloatStateOf(90f)

    /**
     * Highest zoom allowed for the current project: Compose cannot lay out views wider than
     * about 260k pixels, so very long clips lower it.
     */
    var maxPxPerSec = 600f

    /** Pixels per second. */
    var pxPerSec: Float
        get() = min(zoom, maxPxPerSec)
        set(value) {
            zoom = value.coerceIn(8f, max(8f, maxPxPerSec))
        }

    fun usToPx(us: Long) = us / 1_000_000f * pxPerSec
    fun pxToUs(px: Float) = (px / pxPerSec * 1_000_000f).toLong()
}

/** Widest item (in pixels) the timeline lays out; see [TimelineState.maxPxPerSec]. */
private const val MAX_ITEM_PX = 150_000f

/** coerceIn that tolerates an empty range (e.g. sources whose length could not be read). */
private fun Long.clampSafe(lo: Long, hi: Long): Long = if (hi < lo) lo else coerceIn(lo, hi)

private val ROW_MAIN = 56.dp
private val ROW_SMALL = 30.dp
private val ROW_OVERLAY = 40.dp
private val ROW_AUDIO = 36.dp
private val ROW_GAP = 4.dp
private val HANDLE_W = 14.dp

/** Converts timeline times to screen x. Reads the playhead lazily (layout/draw phase only). */
private class Geometry(val state: TimelineState, val position: State<Long>, val half: Float) {
    fun x(us: Long): Float = half + state.usToPx(us) - state.usToPx(position.value)
}

@Composable
fun Timeline(
    vm: EditorViewModel,
    project: Project,
    position: State<Long>,
    selection: Selection?,
    state: TimelineState,
    onAddMedia: () -> Unit,
    onTransitionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val duration = project.durationUs
    val currentDuration by rememberUpdatedState(duration)
    val fling = remember { Animatable(0f) }

    BoxWithConstraints(modifier.fillMaxWidth().background(VG.Bg).testTag("timeline")) {
        val widthPx = with(density) { maxWidth.toPx() }
        val half = widthPx / 2f
        val geo = remember(state, position, half) { Geometry(state, position, half) }
        // Visible time window, quantised so that composition only changes every half screen.
        val window by remember(state, position, half) {
            derivedStateOf {
                val halfUs = state.pxToUs(half).coerceAtLeast(1)
                val q = position.value / halfUs
                ((q - 3) * halfUs)..((q + 4) * halfUs)
            }
        }

        // Longest item decides how far the timeline may zoom in (see TimelineState.maxPxPerSec).
        val longestUs = remember(project) {
            maxOf(
                project.clips.maxOfOrNull { it.durationUs } ?: 0L,
                project.overlays.maxOfOrNull { it.durationUs } ?: 0L,
                project.audios.maxOfOrNull { it.durationUs } ?: 0L,
                project.texts.maxOfOrNull { it.durationUs } ?: 0L,
                project.stickers.maxOfOrNull { it.durationUs } ?: 0L,
                project.effects.maxOfOrNull { it.durationUs } ?: 0L,
                project.filters.maxOfOrNull { it.durationUs } ?: 0L,
                1_000_000L,
            )
        }
        state.maxPxPerSec = (MAX_ITEM_PX / (longestUs / 1e6f)).coerceIn(8f, 600f)

        val gestureModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                scope.launch { fling.stop() }
                val slop = viewConfiguration.touchSlop
                // 0 = undecided, 1 = scrubbing, 2 = pinch zoom. Until the finger has moved
                // horizontally past the touch slop, children (taps, trim handles, long-press moves,
                // the lanes' vertical scroll) get the gesture.
                var mode = 0
                var accX = 0f
                var accY = 0f
                var pos = position.value
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2 && mode != 1) {
                        if (mode == 0 && event.changes.any { it.isConsumed }) break
                        mode = 2
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) state.pxPerSec = state.pxPerSec * zoom
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        continue
                    }
                    if (mode == 2) continue
                    val ch = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                    if (mode == 0) {
                        if (ch.isConsumed) break
                        val d = ch.positionChange()
                        accX += d.x
                        accY += d.y
                        if (abs(accY) > slop && abs(accY) > abs(accX)) break
                        if (abs(accX) <= slop) continue
                        mode = 1
                        vm.preview.pause()
                        vm.preview.setScrubbing(true)
                    }
                    if (ch.isConsumed) continue
                    val dx = ch.positionChange().x
                    if (dx != 0f) {
                        pos = (pos - state.pxToUs(dx)).coerceIn(0, currentDuration)
                        vm.preview.seekTo(pos)
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        ch.consume()
                    }
                }
                if (mode == 1) {
                    vm.preview.setScrubbing(false)
                    val v = tracker.calculateVelocity().x
                    if (abs(v) > 300f) {
                        scope.launch {
                            fling.snapTo(0f)
                            var last = 0f
                            fling.animateDecay(-v, exponentialDecay(frictionMultiplier = 1.6f)) {
                                val delta = value - last
                                last = value
                                pos = (pos + state.pxToUs(delta)).coerceIn(0, currentDuration)
                                vm.preview.seekTo(pos)
                            }
                        }
                    }
                }
            }
        }

        // Keep the main track in view when lanes (texts, stickers, overlays…) stack above it.
        val lanesScroll = rememberScrollState()
        val lanesAbove = (ROW_OVERLAY + ROW_GAP) * project.overlays.map { it.layer }.distinct().size +
            (ROW_SMALL + ROW_GAP) * (project.texts.map { it.lane }.distinct().size + project.stickers.map { it.lane }.distinct().size +
                project.effects.map { it.lane }.distinct().size + project.filters.map { it.lane }.distinct().size)
        LaunchedEffect(lanesAbove) {
            val target = with(density) { (lanesAbove - ROW_SMALL).toPx() }.roundToInt()
            if (target <= 0) return@LaunchedEffect
            snapshotFlow { lanesScroll.maxValue }.first { it > 0 }
            lanesScroll.animateScrollTo(target.coerceAtMost(lanesScroll.maxValue))
        }

        // The whole timeline area (also below the tracks) scrubs, like CapCut.
        Column(Modifier.fillMaxSize().then(gestureModifier)) {
            Ruler(state, geo, duration)
            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(lanesScroll)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    project.overlays.map { it.layer }.distinct().sortedDescending().forEach { layer ->
                        Lane(ROW_OVERLAY) {
                            project.overlays.filter { it.layer == layer && it.startUs <= window.last && it.endUs >= window.first }.forEach { c ->
                                key(c.id) {
                                    TimedItem(vm, state, geo, Selection.Overlay(c.id), selection, c.startUs, c.durationUs, VG.TrackOverlay, ROW_OVERLAY) {
                                        FilmStrip(c, c.startUs, ROW_OVERLAY, state, window)
                                    }
                                }
                            }
                        }
                    }
                    project.texts.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.texts.filter { it.lane == lane && it.startUs <= window.last && it.endUs >= window.first }.forEach { t ->
                                key(t.id) {
                                    TimedItem(vm, state, geo, Selection.Text(t.id), selection, t.startUs, t.durationUs,
                                        if (t.isCaption) Color(0xFFD9822B) else VG.TrackText, ROW_SMALL) {
                                        ItemLabel(if (t.isCaption) "CC  ${t.text}" else "T  ${t.text}")
                                    }
                                }
                            }
                        }
                    }
                    project.stickers.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.stickers.filter { it.lane == lane && it.startUs <= window.last && it.endUs >= window.first }.forEach { s ->
                                key(s.id) {
                                    TimedItem(vm, state, geo, Selection.Sticker(s.id), selection, s.startUs, s.durationUs, VG.TrackSticker, ROW_SMALL) {
                                        ItemLabel(if (s.kind == StickerKind.EMOJI) s.content else "Sticker")
                                    }
                                }
                            }
                        }
                    }
                    project.effects.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.effects.filter { it.lane == lane && it.startUs <= window.last && it.endUs >= window.first }.forEach { e ->
                                key(e.id) {
                                    TimedItem(vm, state, geo, Selection.Effect(e.id), selection, e.startUs, e.durationUs, VG.TrackEffect, ROW_SMALL) {
                                        ItemLabel("✦ " + (Effects.get(e.fx.id)?.name ?: e.fx.id))
                                    }
                                }
                            }
                        }
                    }
                    project.filters.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_SMALL) {
                            project.filters.filter { it.lane == lane && it.startUs <= window.last && it.endUs >= window.first }.forEach { f ->
                                key(f.id) {
                                    TimedItem(vm, state, geo, Selection.Filter(f.id), selection, f.startUs, f.durationUs, VG.TrackFilter, ROW_SMALL) {
                                        ItemLabel("◐ " + (Filters.get(f.filter?.id)?.name ?: "Adjust"))
                                    }
                                }
                            }
                        }
                    }
                    MainTrack(vm, project, selection, state, geo, window, onAddMedia, onTransitionClick)
                    project.audios.map { it.lane }.distinct().sorted().forEach { lane ->
                        Lane(ROW_AUDIO) {
                            project.audios.filter { it.lane == lane && it.startUs <= window.last && it.endUs >= window.first }.forEach { a ->
                                key(a.id) {
                                    TimedItem(vm, state, geo, Selection.Audio(a.id), selection, a.startUs, a.durationUs, VG.TrackAudio, ROW_AUDIO) {
                                        Waveform(a, state)
                                        ItemLabel("♪ " + a.name, Modifier.align(Alignment.TopStart))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Playhead
        Box(
            Modifier.offset { IntOffset((half - 1.dp.toPx()).roundToInt(), 0) }
                .width(2.dp).fillMaxHeight().background(Color.White),
        )
        PlayheadLabel(position, half)
    }
}

@Composable
private fun PlayheadLabel(position: State<Long>, half: Float) {
    val label by remember(position) { derivedStateOf { formatTime(position.value, true) } }
    Text(
        label, fontSize = 10.sp, color = Color.Black,
        modifier = Modifier.offset { IntOffset((half + 4.dp.toPx()).roundToInt(), 0) }
            .background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
    )
}

@Composable
private fun Lane(height: Dp, content: @Composable BoxScope.() -> Unit) {
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
private fun Ruler(state: TimelineState, geo: Geometry, durationUs: Long) {
    val density = LocalDensity.current
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8E8E96.toInt()
            textSize = with(density) { 9.sp.toPx() }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val positionUs = geo.position.value
        val half = geo.half
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
private fun BoxScope.TimedItem(
    vm: EditorViewModel,
    state: TimelineState,
    geo: Geometry,
    sel: Selection,
    current: Selection?,
    startUs: Long,
    durationUs: Long,
    color: Color,
    height: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val w = state.usToPx(durationUs).coerceAtLeast(with(density) { 4.dp.toPx() })
    val selected = current == sel
    val curStart by rememberUpdatedState(startUs)
    Box(
        Modifier
            .offset { IntOffset(geo.x(curStart).roundToInt(), 0) }
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
    ) { content() }
    if (selected) {
        TrimHandle(geo, { geo.x(curStart) - HANDLE_W.toPx() }, height, left = true) { dxPx, phase ->
            when (phase) {
                0 -> vm.beginGesture()
                2 -> vm.endGesture()
                else -> vm.update(record = false) { p -> trimLeft(p, sel, state.pxToUs(dxPx)) }
            }
        }
        TrimHandle(geo, { geo.x(curStart) + w }, height, left = false) { dxPx, phase ->
            when (phase) {
                0 -> vm.beginGesture()
                2 -> vm.endGesture()
                else -> vm.update(record = false) { p -> trimRight(p, sel, state.pxToUs(dxPx)) }
            }
        }
    }
}

/** Trim handle; [onDrag] receives (dx, phase) with phase 0=start 1=drag 2=end. */
@Composable
private fun TrimHandle(
    @Suppress("UNUSED_PARAMETER") geo: Geometry,
    xLeft: androidx.compose.ui.unit.Density.() -> Float,
    height: Dp,
    left: Boolean,
    onDrag: (Float, Int) -> Unit,
) {
    // The touch area is twice as wide as the visible handle, extending away from the clip.
    Box(
        Modifier
            .offset { IntOffset((xLeft() - if (left) HANDLE_W.toPx() else 0f).roundToInt(), 0) }
            .width(HANDLE_W * 2)
            .height(height)
            .testTag(if (left) "trim_start" else "trim_end")
            // Above the neighbouring clips (the right handle overlaps the next clip), so the
            // handle, not the next clip, receives the drag.
            .zIndex(2f)
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
        contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .width(HANDLE_W)
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = if (left) 6.dp else 0.dp, bottomStart = if (left) 6.dp else 0.dp,
                        topEnd = if (left) 0.dp else 6.dp, bottomEnd = if (left) 0.dp else 6.dp,
                    ),
                )
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(2.dp).height(height / 3).background(Color.Black.copy(alpha = 0.6f)))
        }
    }
}

private fun trimLeft(p: Project, sel: Selection, dUs: Long): Project = when (sel) {
    is Selection.Overlay -> ProjectOps.updateVisual(p, sel.id) { c ->
        val newStart = (c.startUs + dUs).clampSafe(0, c.endUs - ProjectOps.MIN_DURATION_US)
        val realD = newStart - c.startUs
        if (c.isImage) c.copy(startUs = newStart, trimEndUs = (c.trimEndUs - realD).coerceAtLeast(ProjectOps.MIN_DURATION_US))
        else {
            val srcStart = (c.trimStartUs + (realD * c.speed).toLong()).clampSafe(0, c.trimEndUs - ProjectOps.MIN_DURATION_US)
            c.copy(startUs = c.startUs + ((srcStart - c.trimStartUs) / c.speed).toLong(), trimStartUs = srcStart)
        }
    }
    is Selection.Audio -> ProjectOps.updateAudio(p, sel.id) { a ->
        val srcStart = (a.trimStartUs + (dUs * a.speed).toLong()).clampSafe(0, a.trimEndUs - ProjectOps.MIN_DURATION_US)
        val realD = ((srcStart - a.trimStartUs) / a.speed).toLong()
        a.copy(trimStartUs = srcStart, startUs = (a.startUs + realD).coerceAtLeast(0))
    }
    is Selection.Text -> ProjectOps.updateText(p, sel.id) { t ->
        val ns = (t.startUs + dUs).clampSafe(0, t.endUs - ProjectOps.MIN_DURATION_US)
        t.copy(startUs = ns, durationUs = t.endUs - ns)
    }
    is Selection.Sticker -> ProjectOps.updateSticker(p, sel.id) { t ->
        val ns = (t.startUs + dUs).clampSafe(0, t.endUs - ProjectOps.MIN_DURATION_US)
        t.copy(startUs = ns, durationUs = t.endUs - ns)
    }
    is Selection.Effect -> ProjectOps.updateEffect(p, sel.id) { t ->
        val ns = (t.startUs + dUs).clampSafe(0, t.endUs - ProjectOps.MIN_DURATION_US)
        t.copy(startUs = ns, durationUs = t.endUs - ns)
    }
    is Selection.Filter -> ProjectOps.updateFilter(p, sel.id) { t ->
        val ns = (t.startUs + dUs).clampSafe(0, t.endUs - ProjectOps.MIN_DURATION_US)
        t.copy(startUs = ns, durationUs = t.endUs - ns)
    }
    is Selection.Main -> p
}

private fun trimRight(p: Project, sel: Selection, dUs: Long): Project = when (sel) {
    is Selection.Overlay -> ProjectOps.updateVisual(p, sel.id) { c ->
        if (c.isImage) c.copy(trimEndUs = (c.trimEndUs + dUs).coerceAtLeast(ProjectOps.MIN_DURATION_US))
        else c.copy(trimEndUs = (c.trimEndUs + (dUs * c.speed).toLong()).clampSafe(c.trimStartUs + ProjectOps.MIN_DURATION_US, c.source.durationUs))
    }
    is Selection.Audio -> ProjectOps.updateAudio(p, sel.id) { a ->
        a.copy(trimEndUs = (a.trimEndUs + (dUs * a.speed).toLong()).clampSafe(a.trimStartUs + ProjectOps.MIN_DURATION_US, a.source.durationUs))
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
    geo: Geometry,
    window: LongRange,
    onAddMedia: () -> Unit,
    onTransitionClick: (String) -> Unit,
) {
    val density = LocalDensity.current
    val starts = project.clipStarts()
    Box(Modifier.fillMaxWidth().height(ROW_MAIN + 8.dp).padding(vertical = 4.dp).testTag("main_track")) {
        Box(
            Modifier.offset { IntOffset((geo.x(0) - 64.dp.toPx()).roundToInt(), 0) }
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
        project.clips.forEachIndexed { i, clip -> key(clip.id) {
            val start = starts[i]
            val end = start + clip.durationUs
            if (start <= window.last && end >= window.first) {
                val w = state.usToPx(clip.durationUs)
                val sel = Selection.Main(clip.id)
                val selected = selection == sel
                Box(
                    Modifier
                        .offset { IntOffset(geo.x(start).roundToInt(), 0) }
                        .width(with(density) { w.toDp() })
                        .height(ROW_MAIN)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VG.TrackMain)
                        .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                        .pointerInput(clip.id) {
                            detectTapGestures(onTap = { vm.select(if (vm.selection.value == sel) null else sel) })
                        },
                ) {
                    FilmStrip(clip, start, ROW_MAIN, state, window)
                    val badges = buildList {
                        if (clip.speed != 1f || clip.speedCurve.isNotEmpty()) add(if (clip.speedCurve.isNotEmpty()) "Curve" else "${"%.1f".format(clip.speed)}x")
                        if (clip.isReversed) add("Reversed")
                        if (clip.muted) add("Muted")
                        if (clip.filter != null) add("Filter")
                        if (clip.removeBackground) add("No BG")
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
                if (selected) MainTrimHandles(vm, clip, start, w, geo, state)
            }
            if (i < project.clips.size - 1 && end >= window.first && end <= window.last) {
                val has = clip.transitionOut != null
                Box(
                    Modifier.offset { IntOffset((geo.x(end) - 11.dp.toPx()).roundToInt(), 16.dp.toPx().roundToInt()) }
                        .size(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (has) VG.Accent else Color.White)
                        .clickable { onTransitionClick(clip.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.SwapHoriz, "Transition", tint = Color.Black, modifier = Modifier.size(15.dp))
                }
            }
        } }
        val mainEnd = project.mainDurationUs
        Box(
            Modifier.offset { IntOffset((geo.x(mainEnd) + 12.dp.toPx()).roundToInt(), 8.dp.toPx().roundToInt()) }
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(onClick = onAddMedia),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, "Add media", tint = Color.Black) }
    }
}

@Composable
private fun MainTrimHandles(vm: EditorViewModel, clip: VisualClip, start: Long, w: Float, geo: Geometry, state: TimelineState) {
    var origin by remember { mutableStateOf<VisualClip?>(null) }
    var acc by remember { mutableFloatStateOf(0f) }
    TrimHandle(geo, { geo.x(start) - HANDLE_W.toPx() }, ROW_MAIN, true) { dx, phase ->
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
                        val newSrc = if (dUs >= 0) o.timelineToSourceUs(dUs) else o.trimStartUs + (dUs * o.speed).toLong()
                        ProjectOps.clampKeyframes(ProjectOps.trimVisual(o, newSrc, o.trimEndUs))
                    }
                }
            }
        }
    }
    TrimHandle(geo, { geo.x(start) + w }, ROW_MAIN, false) { dx, phase ->
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

/** Thumbnails of a visual clip; only those inside the visible [window] are composed. */
@Composable
private fun BoxScope.FilmStrip(clip: VisualClip, clipStartUs: Long, height: Dp, state: TimelineState, window: LongRange) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val thumbW = with(density) { (height * 0.8f).toPx() }
    val widthPx = state.usToPx(clip.durationUs)
    val count = (widthPx / thumbW).toInt().coerceAtLeast(1) + 1
    val thumbUs = state.pxToUs(thumbW).coerceAtLeast(1)
    val first = ((window.first - clipStartUs) / thumbUs).toInt().coerceIn(0, count - 1)
    val last = ((window.last - clipStartUs) / thumbUs).toInt().coerceIn(0, count - 1)
    for (i in first..last) key(i) {
        val offsetUs = (thumbUs * i + thumbUs / 2).coerceAtMost(clip.durationUs)
        val srcUs = if (clip.isImage) 0 else clip.timelineToSourceUs(offsetUs)
        Thumb(
            context, clip.playbackUri, srcUs, clip.isImage,
            Modifier.offset { IntOffset((i * thumbW).roundToInt(), 0) }.width(with(density) { thumbW.toDp() }).fillMaxHeight(),
        )
    }
}

@Composable
private fun Thumb(context: Context, uri: String, timeUs: Long, isImage: Boolean, modifier: Modifier) {
    val bucket = if (isImage) 0 else timeUs / 250_000 * 250_000
    var bmp by remember(uri, bucket) { mutableStateOf(Thumbnails.cached(uri, bucket, 160)) }
    LaunchedEffect(uri, bucket) {
        if (bmp == null) bmp = Thumbnails.get(context, uri, bucket, 160, isImage)
    }
    Box(modifier.background(VG.Surface3)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Waveform(a: AudioClip, state: TimelineState) {
    val context = LocalContext.current
    val peaks by produceState<FloatArray?>(null, a.source.uri) { value = Waveforms.peaks(context, a.source.uri) }
    val p = peaks
    Canvas(Modifier.fillMaxSize()) {
        if (p != null) {
            val bars = (size.width / 4f).toInt().coerceAtLeast(1)
            for (b in 0 until bars) {
                val x = b * 4f
                val srcUs = a.trimStartUs + (state.pxToUs(x) * a.speed).toLong()
                val idx = (srcUs / 1_000_000.0 * Waveforms.RATE).toInt()
                val v = if (idx in p.indices) p[idx] else 0f
                val h = size.height * 0.85f * v.coerceAtLeast(0.04f)
                drawLine(Color(0xCCFFFFFF), Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), 2f)
            }
        }
        // Beat markers
        for (beat in a.beatsUs) {
            val x = state.usToPx(((beat - a.trimStartUs) / a.speed).toLong())
            if (x in 0f..size.width) drawCircle(Color(0xFFFFD60A), 3.5f, Offset(x, size.height - 5f))
        }
    }
}
