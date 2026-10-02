package com.vidgod.editor.engine

import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.Easing
import com.vidgod.editor.model.Keyframe
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.VisualClip
import kotlin.math.pow

/**
 * Thread-safe holder of the project being rendered. GL effects read item parameters from it at
 * draw time, so property tweaks (filters, positions, ...) show up without rebuilding the
 * composition.
 */
class LiveProject(initial: Project) {
    @Volatile
    var project: Project = initial
        set(value) {
            field = value
            index = Index(value)
        }

    @Volatile
    private var index = Index(initial)

    fun visual(id: String): VisualClip? = index.visual[id]
    fun text(id: String): TextClip? = index.text[id]
    fun sticker(id: String): StickerClip? = index.sticker[id]
    fun audio(id: String): AudioClip? = index.audio[id]

    private class Index(p: Project) {
        val visual: Map<String, VisualClip> = (p.clips + p.overlays).associateBy { it.id }
        val text: Map<String, TextClip> = p.texts.associateBy { it.id }
        val sticker: Map<String, StickerClip> = p.stickers.associateBy { it.id }
        val audio: Map<String, AudioClip> = p.audios.associateBy { it.id }
    }
}

/** Result of evaluating keyframes. */
data class KeyframeValue(val transform: Transform, val opacity: Float, val volume: Float)

object Keyframes {
    private fun ease(e: Easing, t: Float): Float = when (e) {
        Easing.LINEAR -> t
        Easing.EASE_IN -> t * t
        Easing.EASE_OUT -> 1f - (1f - t) * (1f - t)
        Easing.EASE_IN_OUT -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).pow(2) / 2f
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Evaluates [keyframes] (sorted or not) at [localUs]; falls back to the base values. */
    fun evaluate(
        keyframes: List<Keyframe>,
        localUs: Long,
        base: Transform,
        baseOpacity: Float,
        baseVolume: Float = 1f,
    ): KeyframeValue {
        if (keyframes.isEmpty()) return KeyframeValue(base, baseOpacity, baseVolume)
        val k = if (isSorted(keyframes)) keyframes else keyframes.sortedBy { it.timeUs }
        if (localUs <= k.first().timeUs) return KeyframeValue(k.first().transform, k.first().opacity, k.first().volume)
        if (localUs >= k.last().timeUs) return KeyframeValue(k.last().transform, k.last().opacity, k.last().volume)
        for (i in 0 until k.size - 1) {
            val a = k[i]
            val b = k[i + 1]
            if (localUs >= a.timeUs && localUs <= b.timeUs) {
                val span = (b.timeUs - a.timeUs).coerceAtLeast(1)
                val t = ease(a.easing, (localUs - a.timeUs).toFloat() / span)
                val ta = a.transform
                val tb = b.transform
                return KeyframeValue(
                    Transform(
                        x = lerp(ta.x, tb.x, t),
                        y = lerp(ta.y, tb.y, t),
                        scale = lerp(ta.scale, tb.scale, t),
                        rotation = lerp(ta.rotation, tb.rotation, t),
                        flipH = if (t < 0.5f) ta.flipH else tb.flipH,
                        flipV = if (t < 0.5f) ta.flipV else tb.flipV,
                    ),
                    lerp(a.opacity, b.opacity, t),
                    lerp(a.volume, b.volume, t),
                )
            }
        }
        return KeyframeValue(base, baseOpacity, baseVolume)
    }

    private fun isSorted(k: List<Keyframe>): Boolean {
        for (i in 1 until k.size) if (k[i].timeUs < k[i - 1].timeUs) return false
        return true
    }

    /** Inserts or replaces the keyframe nearest to [timeUs] (within 1 frame). */
    fun upsert(list: List<Keyframe>, kf: Keyframe, toleranceUs: Long = 40_000): List<Keyframe> {
        val filtered = list.filter { kotlin.math.abs(it.timeUs - kf.timeUs) > toleranceUs }
        return (filtered + kf).sortedBy { it.timeUs }
    }

    fun nearest(list: List<Keyframe>, timeUs: Long, toleranceUs: Long = 40_000): Keyframe? =
        list.minByOrNull { kotlin.math.abs(it.timeUs - timeUs) }
            ?.takeIf { kotlin.math.abs(it.timeUs - timeUs) <= toleranceUs }
}
