package com.vidgod.editor.engine.text

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.vidgod.editor.engine.Keyframes
import com.vidgod.editor.engine.LiveProject
import com.vidgod.editor.engine.catalog.AnimState
import com.vidgod.editor.engine.catalog.Animations
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.TextClip

/** A text or sticker placed on the timeline, as seen by the renderer. */
private data class LaneItem(val id: String, val isText: Boolean, val startUs: Long, val endUs: Long)

/**
 * Draws texts and stickers. Items are packed into lanes of non-overlapping items; each lane is one
 * [BitmapOverlay] that shows whichever of its items is active, so only active items use textures.
 */
@UnstableApi
object OverlayLanes {
    private const val LANES_PER_EFFECT = 6

    fun effects(context: Context, live: LiveProject, project: Project, canvasW: Int, canvasH: Int): List<OverlayEffect> {
        val items = project.texts.map { LaneItem(it.id, true, it.startUs, it.endUs) } +
            project.stickers.map { LaneItem(it.id, false, it.startUs, it.endUs) }
        if (items.isEmpty()) return emptyList()
        val lanes = ArrayList<ArrayList<LaneItem>>()
        for (item in items.sortedBy { it.startUs }) {
            val lane = lanes.firstOrNull { it.last().endUs <= item.startUs }
            if (lane != null) lane.add(item) else lanes.add(arrayListOf(item))
        }
        val overlays: List<TextureOverlay> = lanes.map { LaneOverlay(context.applicationContext, live, it, canvasW, canvasH) }
        return overlays.chunked(LANES_PER_EFFECT).map { OverlayEffect(it) }
    }

    /** Scale, position etc. of an overlay item at [timeUs]; shared with the editor gesture layer. */
    data class Placement(
        val x: Float,
        val y: Float,
        val scale: Float,
        val scaleX: Float,
        val scaleY: Float,
        val rotation: Float,
        val alpha: Float,
        val reveal: Float,
    )

    fun placement(
        localUs: Long,
        durationUs: Long,
        text: TextClip?,
        sticker: StickerClip?,
        anim: AnimState = AnimState(),
    ): Placement {
        val transform = text?.transform ?: sticker!!.transform
        val keyframes = text?.keyframes ?: sticker!!.keyframes
        val opacity = if (text != null) 1f else sticker!!.opacity.let { 1f }
        val kv = Keyframes.evaluate(keyframes, localUs, transform, opacity)
        Animations.apply(
            anim, localUs, durationUs,
            text?.animIn ?: sticker?.animIn,
            text?.animOut ?: sticker?.animOut,
            text?.animLoop ?: sticker?.animLoop,
        )
        return Placement(
            x = kv.transform.x + anim.dx,
            y = kv.transform.y + anim.dy,
            scale = kv.transform.scale * anim.scale,
            scaleX = anim.scaleX,
            scaleY = anim.scaleY,
            rotation = kv.transform.rotation + anim.rotation,
            alpha = (kv.opacity * anim.alpha).coerceIn(0f, 1f),
            reveal = anim.reveal,
        )
    }

    @UnstableApi
    private class LaneOverlay(
        private val context: Context,
        private val live: LiveProject,
        private val items: List<LaneItem>,
        private val canvasW: Int,
        @Suppress("unused") private val canvasH: Int,
    ) : BitmapOverlay() {
        private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val cache = object : LruCache<Int, Bitmap>(8) {}
        private val anim = AnimState()

        private fun itemAt(t: Long): LaneItem? {
            for (i in items) if (t >= i.startUs && t < i.endUs) return i
            return null
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val item = itemAt(presentationTimeUs) ?: return empty
            if (item.isText) {
                val clip = live.text(item.id) ?: return empty
                val local = presentationTimeUs - clip.startUs
                val p = placement(local, clip.durationUs, clip, null, anim)
                val chars = TextRenderer.visibleChars(clip.text, p.reveal)
                val word = TextRenderer.activeWord(clip, local)
                val key = arrayOf(clip.text, clip.style, chars, word, clip.words.size).contentHashCode()
                return cache.get(key) ?: TextRenderer.render(context, clip, canvasW, chars, word).also { cache.put(key, it) }
            } else {
                val s = live.sticker(item.id) ?: return empty
                val key = arrayOf<Any>(s.kind, s.content, s.size, s.tint, s.opacity, s.transform.flipH, s.transform.flipV)
                    .contentHashCode()
                return cache.get(key) ?: StickerRenderer.render(context, s, canvasW).also { cache.put(key, it) }
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val item = itemAt(presentationTimeUs) ?: return HIDDEN
            val text = if (item.isText) live.text(item.id) else null
            val sticker = if (!item.isText) live.sticker(item.id) else null
            if (text == null && sticker == null) return HIDDEN
            val start = text?.startUs ?: sticker!!.startUs
            val duration = text?.durationUs ?: sticker!!.durationUs
            val p = placement(presentationTimeUs - start, duration, text, sticker, anim)
            return StaticOverlaySettings.Builder()
                .setScale(p.scale * p.scaleX, p.scale * p.scaleY)
                .setRotationDegrees(-p.rotation)
                .setBackgroundFrameAnchor(p.x * 2f, -p.y * 2f)
                .setOverlayFrameAnchor(0f, 0f)
                .setAlphaScale(p.alpha)
                .build()
        }

        companion object {
            val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        }
    }
}
