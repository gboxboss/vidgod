package com.vidgod.editor.engine.catalog

import com.vidgod.editor.model.AnimRef
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Changes applied on top of a layer's transform by animations, all neutral by default. */
class AnimState {
    var dx = 0f          // fraction of canvas width
    var dy = 0f          // fraction of canvas height
    var scale = 1f
    var scaleX = 1f
    var scaleY = 1f
    var rotation = 0f    // degrees clockwise
    var alpha = 1f
    var blur = 0f        // 0..1
    /** Wipe reveal: 0 = fully hidden, 1 = fully visible. */
    var wipe = 1f
    /** Wipe direction: 0 left->right, 1 right->left, 2 top->bottom, 3 bottom->top. */
    var wipeDir = 0
    /** Fraction of characters visible (typewriter, text only). */
    var reveal = 1f
    var brightness = 0f  // -1..1 flash

    fun reset() {
        dx = 0f; dy = 0f; scale = 1f; scaleX = 1f; scaleY = 1f; rotation = 0f; alpha = 1f
        blur = 0f; wipe = 1f; wipeDir = 0; reveal = 1f; brightness = 0f
    }
}

enum class AnimKind { IN, OUT, COMBO }

data class AnimDef(val id: String, val name: String, val kind: AnimKind, val textOnly: Boolean = false)

object Animations {
    val all = listOf(
        AnimDef("fade_in", "Fade in", AnimKind.IN),
        AnimDef("zoom_in", "Zoom in", AnimKind.IN),
        AnimDef("zoom_out_in", "Zoom out", AnimKind.IN),
        AnimDef("slide_left_in", "Slide left", AnimKind.IN),
        AnimDef("slide_right_in", "Slide right", AnimKind.IN),
        AnimDef("slide_up_in", "Slide up", AnimKind.IN),
        AnimDef("slide_down_in", "Slide down", AnimKind.IN),
        AnimDef("rise_in", "Rise", AnimKind.IN),
        AnimDef("drop_in", "Drop", AnimKind.IN),
        AnimDef("pop_in", "Pop", AnimKind.IN),
        AnimDef("bounce_in", "Bounce", AnimKind.IN),
        AnimDef("spin_in", "Spin", AnimKind.IN),
        AnimDef("swing_in", "Swing", AnimKind.IN),
        AnimDef("flip_in", "Flip", AnimKind.IN),
        AnimDef("blur_in", "Blur in", AnimKind.IN),
        AnimDef("wipe_right_in", "Wipe right", AnimKind.IN),
        AnimDef("wipe_left_in", "Wipe left", AnimKind.IN),
        AnimDef("wipe_down_in", "Wipe down", AnimKind.IN),
        AnimDef("shake_in", "Shake", AnimKind.IN),
        AnimDef("flash_in", "Flash", AnimKind.IN),
        AnimDef("typewriter", "Typewriter", AnimKind.IN, textOnly = true),

        AnimDef("fade_out", "Fade out", AnimKind.OUT),
        AnimDef("zoom_out", "Zoom out", AnimKind.OUT),
        AnimDef("zoom_in_out", "Zoom in", AnimKind.OUT),
        AnimDef("slide_left_out", "Slide left", AnimKind.OUT),
        AnimDef("slide_right_out", "Slide right", AnimKind.OUT),
        AnimDef("slide_up_out", "Slide up", AnimKind.OUT),
        AnimDef("slide_down_out", "Slide down", AnimKind.OUT),
        AnimDef("sink_out", "Sink", AnimKind.OUT),
        AnimDef("pop_out", "Pop", AnimKind.OUT),
        AnimDef("spin_out", "Spin", AnimKind.OUT),
        AnimDef("flip_out", "Flip", AnimKind.OUT),
        AnimDef("blur_out", "Blur out", AnimKind.OUT),
        AnimDef("wipe_right_out", "Wipe right", AnimKind.OUT),
        AnimDef("wipe_left_out", "Wipe left", AnimKind.OUT),
        AnimDef("shake_out", "Shake", AnimKind.OUT),
        AnimDef("flash_out", "Flash", AnimKind.OUT),

        AnimDef("shake", "Shake", AnimKind.COMBO),
        AnimDef("pulse", "Pulse", AnimKind.COMBO),
        AnimDef("heartbeat", "Heartbeat", AnimKind.COMBO),
        AnimDef("wobble", "Wobble", AnimKind.COMBO),
        AnimDef("swing", "Swing", AnimKind.COMBO),
        AnimDef("spin", "Spin", AnimKind.COMBO),
        AnimDef("float", "Float", AnimKind.COMBO),
        AnimDef("jelly", "Jelly", AnimKind.COMBO),
        AnimDef("zoom_cycle", "Zoom cycle", AnimKind.COMBO),
        AnimDef("pendulum", "Pendulum", AnimKind.COMBO),
        AnimDef("blink", "Blink", AnimKind.COMBO),
        AnimDef("ken_burns", "Ken Burns", AnimKind.COMBO),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String?) = id?.let { byId[it] }

    fun ofKind(kind: AnimKind, forText: Boolean) = all.filter { it.kind == kind && (forText || !it.textOnly) }

    private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
    private fun easeInOut(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
    private fun backOut(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }
    private fun bounceOut(t0: Float): Float {
        var t = t0
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> { t -= 1.5f / d1; n1 * t * t + 0.75f }
            t < 2.5f / d1 -> { t -= 2.25f / d1; n1 * t * t + 0.9375f }
            else -> { t -= 2.625f / d1; n1 * t * t + 0.984375f }
        }
    }
    private fun damped(t: Float) = (exp(-5f * t) * cos(4f * PI.toFloat() * t))

    /**
     * Applies the animations of an item to [s].
     * @param localUs time since the item start on the timeline
     * @param durationUs item duration on the timeline
     */
    fun apply(
        s: AnimState,
        localUs: Long,
        durationUs: Long,
        animIn: AnimRef?,
        animOut: AnimRef?,
        combo: AnimRef?,
    ) {
        s.reset()
        if (combo != null) {
            val period = combo.durationUs.coerceAtLeast(100_000)
            val ph = ((localUs % period).toFloat() / period)
            applyCombo(s, combo.id, ph, localUs.toFloat() / durationUs.coerceAtLeast(1))
        }
        if (animIn != null) {
            val d = animIn.durationUs.coerceIn(50_000, durationUs.coerceAtLeast(50_000))
            if (localUs < d) applyIn(s, animIn.id, (localUs.toFloat() / d).coerceIn(0f, 1f))
        }
        if (animOut != null) {
            val d = animOut.durationUs.coerceIn(50_000, durationUs.coerceAtLeast(50_000))
            val remaining = durationUs - localUs
            if (remaining < d) applyOut(s, animOut.id, (1f - remaining.toFloat() / d).coerceIn(0f, 1f))
        }
    }

    private fun applyIn(s: AnimState, id: String, p: Float) {
        val e = easeOut(p)
        when (id) {
            "fade_in" -> s.alpha *= p
            "zoom_in" -> { s.scale *= 0.3f + 0.7f * e; s.alpha *= (p * 3f).coerceAtMost(1f) }
            "zoom_out_in" -> { s.scale *= 1.8f - 0.8f * e; s.alpha *= (p * 3f).coerceAtMost(1f) }
            "slide_left_in" -> s.dx += (1f - e)
            "slide_right_in" -> s.dx -= (1f - e)
            "slide_up_in" -> s.dy += (1f - e)
            "slide_down_in" -> s.dy -= (1f - e)
            "rise_in" -> { s.dy += 0.15f * (1f - e); s.alpha *= p }
            "drop_in" -> { s.dy -= 1.1f * (1f - bounceOut(p)) }
            "pop_in" -> { s.scale *= backOut(p).coerceAtLeast(0f); s.alpha *= (p * 4f).coerceAtMost(1f) }
            "bounce_in" -> { s.scale *= bounceOut(p).coerceAtLeast(0.01f) }
            "spin_in" -> { s.rotation += -360f * (1f - e); s.scale *= 0.2f + 0.8f * e; s.alpha *= p }
            "swing_in" -> { s.rotation += 35f * damped(p) }
            "flip_in" -> { s.scaleX *= e.coerceAtLeast(0.01f) }
            "blur_in" -> { s.blur = maxOf(s.blur, 1f - e); s.alpha *= (p * 2f).coerceAtMost(1f) }
            "wipe_right_in" -> { s.wipe = e; s.wipeDir = 0 }
            "wipe_left_in" -> { s.wipe = e; s.wipeDir = 1 }
            "wipe_down_in" -> { s.wipe = e; s.wipeDir = 2 }
            "shake_in" -> { s.dx += 0.04f * sin(p * 40f) * (1f - p); s.dy += 0.03f * cos(p * 37f) * (1f - p) }
            "flash_in" -> { s.brightness = maxOf(s.brightness, 1f - p) }
            "typewriter" -> s.reveal = p
        }
    }

    private fun applyOut(s: AnimState, id: String, p: Float) {
        val e = easeInOut(p)
        when (id) {
            "fade_out" -> s.alpha *= 1f - p
            "zoom_out" -> { s.scale *= 1f - 0.7f * e; s.alpha *= 1f - p }
            "zoom_in_out" -> { s.scale *= 1f + 0.8f * e; s.alpha *= 1f - p }
            "slide_left_out" -> s.dx -= e
            "slide_right_out" -> s.dx += e
            "slide_up_out" -> s.dy -= e
            "slide_down_out" -> s.dy += e
            "sink_out" -> { s.dy += 0.15f * e; s.alpha *= 1f - p }
            "pop_out" -> { s.scale *= (1f + 0.2f * sin(p * PI.toFloat())) * (1f - e).coerceAtLeast(0.01f) }
            "spin_out" -> { s.rotation += 360f * e; s.scale *= 1f - 0.8f * e; s.alpha *= 1f - p }
            "flip_out" -> { s.scaleX *= (1f - e).coerceAtLeast(0.01f) }
            "blur_out" -> { s.blur = maxOf(s.blur, e); s.alpha *= (2f - 2f * p).coerceAtMost(1f) }
            "wipe_right_out" -> { s.wipe = 1f - e; s.wipeDir = 1 }
            "wipe_left_out" -> { s.wipe = 1f - e; s.wipeDir = 0 }
            "shake_out" -> { s.dx += 0.04f * sin(p * 40f) * p; s.dy += 0.03f * cos(p * 37f) * p }
            "flash_out" -> { s.brightness = maxOf(s.brightness, p) }
        }
    }

    private fun applyCombo(s: AnimState, id: String, ph: Float, overall: Float) {
        val tau = 2f * PI.toFloat()
        when (id) {
            "shake" -> { s.dx += 0.015f * sin(ph * tau * 6f); s.dy += 0.012f * cos(ph * tau * 7f); s.rotation += 2f * sin(ph * tau * 5f) }
            "pulse" -> s.scale *= 1f + 0.06f * sin(ph * tau)
            "heartbeat" -> {
                val beat = abs(sin(ph * tau)).pow(8)
                s.scale *= 1f + 0.12f * beat
            }
            "wobble" -> { s.rotation += 6f * sin(ph * tau); s.dx += 0.02f * sin(ph * tau * 2f) }
            "swing" -> s.rotation += 12f * sin(ph * tau)
            "spin" -> s.rotation += 360f * ph
            "float" -> s.dy += 0.025f * sin(ph * tau)
            "jelly" -> { s.scaleX *= 1f + 0.08f * sin(ph * tau * 2f); s.scaleY *= 1f - 0.08f * sin(ph * tau * 2f) }
            "zoom_cycle" -> s.scale *= 1f + 0.15f * (0.5f - 0.5f * cos(ph * tau))
            "pendulum" -> s.rotation += 20f * sin(ph * tau)
            "blink" -> s.alpha *= if (ph < 0.5f) 1f else 0.25f
            "ken_burns" -> { s.scale *= 1f + 0.18f * overall; s.dx += 0.03f * overall; s.dy -= 0.02f * overall }
        }
    }
}
