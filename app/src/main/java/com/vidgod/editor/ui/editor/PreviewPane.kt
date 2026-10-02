package com.vidgod.editor.ui.editor

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Panel
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.engine.Keyframes
import com.vidgod.editor.engine.catalog.AnimState
import com.vidgod.editor.engine.catalog.Animations
import com.vidgod.editor.engine.text.OverlayLanes
import com.vidgod.editor.engine.text.StickerRenderer
import com.vidgod.editor.engine.text.TextRenderer
import com.vidgod.editor.model.Keyframe
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.VisualClip
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry of the selected item in canvas-normalised units. */
private data class Box2(
    val cx: Float, val cy: Float, // centre, 0..1 of canvas
    val w: Float, val h: Float,   // size, fraction of canvas width/height
    val rotation: Float,
    val transform: Transform,
    val local: Long,
    val hasKeyframes: Boolean,
)

@Composable
fun PreviewPane(
    vm: EditorViewModel,
    project: Project,
    selection: Selection?,
    position: androidx.compose.runtime.State<Long>,
    canvasSize: Pair<Int, Int>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.background(Color(0xFF0B0B0C)), contentAlignment = Alignment.Center) {
        val aspect = project.canvasAspect
        val boxAspect = maxWidth.value / maxHeight.value
        val (w, h) = if (aspect > boxAspect) maxWidth to maxWidth / aspect else maxHeight * aspect to maxHeight
        Box(Modifier.size(w, h).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also { vm.preview.attach(it) } },
                onRelease = { vm.preview.detach(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (project.clips.isEmpty()) return@Box
            if (selection != null || project.texts.isNotEmpty() || project.stickers.isNotEmpty() || project.overlays.isNotEmpty()) {
                SelectionLayer(vm, project, selection, position.value, canvasSize)
            }
        }
    }
}

@Composable
private fun SelectionLayer(vm: EditorViewModel, project: Project, selection: Selection?, positionUs: Long, canvasSize: Pair<Int, Int>) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val (cw, ch) = canvasSize
    val box = selectionBox(context, project, selection, positionUs, cw, ch)
    val currentBox by rememberUpdatedState(box)
    val currentSel by rememberUpdatedState(selection)
    val currentProject by rememberUpdatedState(project)
    val currentPos by rememberUpdatedState(positionUs)

    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val sel = currentSel
                var b = currentBox
                // Tap-to-select texts and stickers.
                if (b == null || !inside(b, down.position.x / size.width, down.position.y / size.height, size.width.toFloat(), size.height.toFloat())) {
                    val hit = hitTest(context, currentProject, currentPos, down.position.x / size.width, down.position.y / size.height, cw, ch)
                    if (hit != null && hit != sel) {
                        vm.select(hit)
                        return@awaitEachGesture
                    }
                    if (b == null) return@awaitEachGesture
                }
                vm.preview.pause()
                vm.beginGesture()
                var moved = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    val rot = event.calculateRotation()
                    if (pan == Offset.Zero && zoom == 1f && rot == 0f) continue
                    moved = true
                    event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                    val t = b!!.transform
                    val nt = t.copy(
                        x = t.x + pan.x / size.width,
                        y = t.y + pan.y / size.height,
                        scale = (t.scale * zoom).coerceIn(0.05f, 20f),
                        rotation = t.rotation + rot,
                    )
                    applyTransform(vm, sel!!, nt, b.local, b.hasKeyframes)
                    b = b.copy(transform = nt)
                }
                vm.endGesture()
                if (!moved && sel != null) {
                    // A tap on the selected text opens the editor.
                    if (sel is Selection.Text) vm.openPanel(Panel.TEXT_EDIT)
                }
            }
        },
    ) {
        val b = box ?: return@BoxWithConstraints
        val pw = with(density) { maxWidth.toPx() }
        val ph = with(density) { maxHeight.toPx() }
        val bw = max(b.w * pw, with(density) { 24.dp.toPx() })
        val bh = max(b.h * ph, with(density) { 24.dp.toPx() })
        val left = b.cx * pw - bw / 2
        val top = b.cy * ph - bh / 2
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                .graphicsLayer { rotationZ = b.rotation }
                .border(1.5.dp, Color.White),
        ) {
            if (selection !is Selection.Main) {
                CornerButton(Icons.Default.Close, Alignment.TopStart) { vm.deleteSelected() }
            }
            if (selection is Selection.Text) {
                CornerButton(Icons.Default.Edit, Alignment.TopEnd) { vm.openPanel(Panel.TEXT_EDIT) }
            }
        }
        // Rotate & scale handle at the rotated bottom-right corner.
        val theta = Math.toRadians(b.rotation.toDouble())
        val ccx = b.cx * pw
        val ccy = b.cy * ph
        val hx = (ccx + Math.cos(theta) * bw / 2 - Math.sin(theta) * bh / 2).toFloat()
        val hy = (ccy + Math.sin(theta) * bw / 2 + Math.cos(theta) * bh / 2).toFloat()
        val handleR = with(density) { 12.dp.toPx() }
        Box(
            Modifier
                .offset { IntOffset((hx - handleR).roundToInt(), (hy - handleR).roundToInt()) }
                .size(24.dp).clip(CircleShape).background(Color.White)
                .pointerInput(selection) {
                    var start: Transform? = null
                    var pointer = Offset.Zero
                    var center = Offset.Zero
                    var startAngle = 0.0
                    var startDist = 1f
                    var local = 0L
                    var kf = false
                    detectDragGestures(
                        onDragStart = {
                            val cb = currentBox ?: return@detectDragGestures
                            vm.preview.pause()
                            vm.beginGesture()
                            start = cb.transform; local = cb.local; kf = cb.hasKeyframes
                            val th = Math.toRadians(cb.rotation.toDouble())
                            val w2 = max(cb.w * pw, 1f) / 2; val h2 = max(cb.h * ph, 1f) / 2
                            center = Offset(cb.cx * pw, cb.cy * ph)
                            pointer = Offset(
                                (center.x + Math.cos(th) * w2 - Math.sin(th) * h2).toFloat(),
                                (center.y + Math.sin(th) * w2 + Math.cos(th) * h2).toFloat(),
                            )
                            startAngle = Math.toDegrees(atan2((pointer.y - center.y).toDouble(), (pointer.x - center.x).toDouble()))
                            startDist = hypot(pointer.x - center.x, pointer.y - center.y).coerceAtLeast(1f)
                        },
                        onDragEnd = { vm.endGesture() },
                        onDragCancel = { vm.endGesture() },
                    ) { change, drag ->
                        change.consume()
                        val s0 = start ?: return@detectDragGestures
                        pointer += drag
                        val ang = Math.toDegrees(atan2((pointer.y - center.y).toDouble(), (pointer.x - center.x).toDouble()))
                        val dist = hypot(pointer.x - center.x, pointer.y - center.y)
                        val nt = s0.copy(
                            scale = (s0.scale * dist / startDist).coerceIn(0.05f, 20f),
                            rotation = s0.rotation + (ang - startAngle).toFloat(),
                        )
                        applyTransform(vm, currentSel ?: return@detectDragGestures, nt, local, kf)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.OpenInFull, "Scale and rotate", tint = Color.Black, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    align: Alignment,
    onClick: () -> Unit,
) {
    Box(
        Modifier.align(align).offset(if (align == Alignment.TopStart) (-10).dp else 10.dp, (-10).dp)
            .size(24.dp).clip(CircleShape).background(Color.White)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    d.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.Black, modifier = Modifier.size(14.dp)) }
}

private fun inside(b: Box2, nx: Float, ny: Float, pw: Float, ph: Float): Boolean {
    // Rotate the point into the box frame (in pixels to respect aspect ratio).
    val dx = (nx - b.cx) * pw
    val dy = (ny - b.cy) * ph
    val a = Math.toRadians(-b.rotation.toDouble())
    val rx = dx * Math.cos(a) - dy * Math.sin(a)
    val ry = dx * Math.sin(a) + dy * Math.cos(a)
    val hw = max(b.w * pw / 2, 40f) + 20f
    val hh = max(b.h * ph / 2, 40f) + 20f
    return kotlin.math.abs(rx) <= hw && kotlin.math.abs(ry) <= hh
}

private fun applyTransform(vm: EditorViewModel, sel: Selection, t: Transform, local: Long, hasKeyframes: Boolean) {
    when (sel) {
        is Selection.Main, is Selection.Overlay -> vm.editVisual(sel.id, record = false) { c ->
            if (hasKeyframes) {
                val v = Keyframes.evaluate(c.keyframes, local, c.transform, c.opacity, c.volume)
                c.copy(keyframes = Keyframes.upsert(c.keyframes, Keyframe(local, t, v.opacity, v.volume)))
            } else c.copy(transform = t)
        }
        is Selection.Text -> vm.editText(sel.id, record = false) { c ->
            if (hasKeyframes) {
                val v = Keyframes.evaluate(c.keyframes, local, c.transform, 1f)
                c.copy(keyframes = Keyframes.upsert(c.keyframes, Keyframe(local, t, v.opacity)))
            } else c.copy(transform = t)
        }
        is Selection.Sticker -> vm.editSticker(sel.id, record = false) { c ->
            if (hasKeyframes) {
                val v = Keyframes.evaluate(c.keyframes, local, c.transform, 1f)
                c.copy(keyframes = Keyframes.upsert(c.keyframes, Keyframe(local, t, v.opacity)))
            } else c.copy(transform = t)
        }
        else -> {}
    }
}

/** Box of the selected item at [positionUs], or null if it is not visible. */
private fun selectionBox(
    context: android.content.Context,
    p: Project,
    sel: Selection?,
    positionUs: Long,
    cw: Int,
    ch: Int,
): Box2? {
    sel ?: return null
    val range = ProjectOps.range(p, sel) ?: return null
    if (positionUs < range.first || positionUs > range.last) return null
    val local = positionUs - range.first
    return when (sel) {
        is Selection.Text -> {
            val t = p.texts.firstOrNull { it.id == sel.id } ?: return null
            val size = textSize(context, t, cw)
            val pl = OverlayLanes.placement(local, t.durationUs, t, null)
            val kv = Keyframes.evaluate(t.keyframes, local, t.transform, 1f)
            Box2(0.5f + pl.x, 0.5f + pl.y, size.first * pl.scale / cw, size.second * pl.scale / ch, pl.rotation, kv.transform, local, t.keyframes.isNotEmpty())
        }
        is Selection.Sticker -> {
            val s = p.stickers.firstOrNull { it.id == sel.id } ?: return null
            val size = stickerSize(context, s, cw)
            val pl = OverlayLanes.placement(local, s.durationUs, null, s)
            val kv = Keyframes.evaluate(s.keyframes, local, s.transform, 1f)
            Box2(0.5f + pl.x, 0.5f + pl.y, size.first * pl.scale / cw, size.second * pl.scale / ch, pl.rotation, kv.transform, local, s.keyframes.isNotEmpty())
        }
        is Selection.Main, is Selection.Overlay -> {
            val c = (p.clips + p.overlays).firstOrNull { it.id == sel.id } ?: return null
            clipBox(c, local, cw, ch)
        }
        else -> null
    }
}

private fun clipBox(c: VisualClip, local: Long, cw: Int, ch: Int): Box2 {
    val kv = Keyframes.evaluate(c.keyframes, local, c.transform, c.opacity)
    val anim = AnimState()
    Animations.apply(anim, local, c.durationUs, c.animIn, c.animOut, c.animCombo)
    val sw = max(1, c.source.width).toFloat() * (c.crop.right - c.crop.left)
    val sh = max(1, c.source.height).toFloat() * (c.crop.bottom - c.crop.top)
    val fit = min(cw / sw, ch / sh)
    val s = kv.transform.scale * anim.scale
    return Box2(
        cx = 0.5f + kv.transform.x + anim.dx,
        cy = 0.5f + kv.transform.y + anim.dy,
        w = sw * fit * s * anim.scaleX / cw,
        h = sh * fit * s * anim.scaleY / ch,
        rotation = kv.transform.rotation + anim.rotation,
        transform = kv.transform,
        local = local,
        hasKeyframes = c.keyframes.isNotEmpty(),
    )
}

private val sizeCache = android.util.LruCache<Int, Pair<Int, Int>>(64)

private fun textSize(context: android.content.Context, t: com.vidgod.editor.model.TextClip, cw: Int): Pair<Int, Int> {
    val key = arrayOf(t.text, t.style, cw).contentHashCode()
    sizeCache.get(key)?.let { return it }
    val b = TextRenderer.render(context, t, cw)
    val r = b.width to b.height
    b.recycle()
    sizeCache.put(key, r)
    return r
}

private fun stickerSize(context: android.content.Context, s: com.vidgod.editor.model.StickerClip, cw: Int): Pair<Int, Int> {
    val key = arrayOf<Any>(s.kind, s.content, s.size, cw).contentHashCode()
    sizeCache.get(key)?.let { return it }
    val b = StickerRenderer.render(context, s, cw)
    val r = b.width to b.height
    b.recycle()
    sizeCache.put(key, r)
    return r
}

/** Finds the top-most text or sticker under a normalised point. */
private fun hitTest(context: android.content.Context, p: Project, t: Long, nx: Float, ny: Float, cw: Int, ch: Int): Selection? {
    val stickers = p.stickers.filter { t >= it.startUs && t < it.endUs }.reversed()
    for (s in stickers) {
        val b = selectionBox(context, p, Selection.Sticker(s.id), t, cw, ch) ?: continue
        if (inside(b, nx, ny, cw.toFloat(), ch.toFloat())) return Selection.Sticker(s.id)
    }
    val texts = p.texts.filter { t >= it.startUs && t < it.endUs }.reversed()
    for (x in texts) {
        val b = selectionBox(context, p, Selection.Text(x.id), t, cw, ch) ?: continue
        if (inside(b, nx, ny, cw.toFloat(), ch.toFloat())) return Selection.Text(x.id)
    }
    val overlays = p.overlays.filter { t >= it.startUs && t < it.endUs }.sortedByDescending { it.layer }
    for (o in overlays) {
        val b = selectionBox(context, p, Selection.Overlay(o.id), t, cw, ch) ?: continue
        if (inside(b, nx, ny, cw.toFloat(), ch.toFloat())) return Selection.Overlay(o.id)
    }
    return null
}
