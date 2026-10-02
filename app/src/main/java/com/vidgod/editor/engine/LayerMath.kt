package com.vidgod.editor.engine

import com.vidgod.editor.engine.catalog.AnimState
import com.vidgod.editor.engine.catalog.Animations
import com.vidgod.editor.model.VisualClip
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Geometry shared by the renderer, the gesture layer and the eyedropper. */
object LayerMath {
    /** Centre (canvas fraction), size (fraction of canvas w/h) and rotation of a clip's layer. */
    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Float)

    fun clipBox(c: VisualClip, localUs: Long, cw: Int, ch: Int): Box {
        val kv = Keyframes.evaluate(c.keyframes, localUs, c.transform, c.opacity)
        val anim = AnimState()
        Animations.apply(anim, localUs, c.durationUs, c.animIn, c.animOut, c.animCombo)
        val sw = max(1, c.source.width).toFloat() * (c.crop.right - c.crop.left)
        val sh = max(1, c.source.height).toFloat() * (c.crop.bottom - c.crop.top)
        val fit = min(cw / sw, ch / sh)
        val s = kv.transform.scale * anim.scale
        return Box(
            cx = 0.5f + kv.transform.x + anim.dx,
            cy = 0.5f + kv.transform.y + anim.dy,
            w = sw * fit * s * anim.scaleX / cw,
            h = sh * fit * s * anim.scaleY / ch,
            rotation = kv.transform.rotation + anim.rotation,
        )
    }

    /**
     * Maps a point on the canvas (fractions, y down) to a point of the clip's source frame
     * (fractions, y down), or null if the point is outside the layer.
     */
    fun canvasToSource(c: VisualClip, localUs: Long, nx: Float, ny: Float, cw: Int, ch: Int): Pair<Float, Float>? {
        val b = clipBox(c, localUs, cw, ch)
        val dx = (nx - b.cx) * cw
        val dy = (ny - b.cy) * ch
        val a = Math.toRadians(-b.rotation.toDouble())
        val rx = (dx * cos(a) - dy * sin(a)).toFloat()
        val ry = (dx * sin(a) + dy * cos(a)).toFloat()
        var lu = rx / (b.w * cw) + 0.5f
        var lv = ry / (b.h * ch) + 0.5f
        if (lu !in 0f..1f || lv !in 0f..1f) return null
        val kv = Keyframes.evaluate(c.keyframes, localUs, c.transform, c.opacity)
        if (kv.transform.flipH) lu = 1f - lu
        if (kv.transform.flipV) lv = 1f - lv
        return (c.crop.left + lu * (c.crop.right - c.crop.left)) to (c.crop.top + lv * (c.crop.bottom - c.crop.top))
    }
}
