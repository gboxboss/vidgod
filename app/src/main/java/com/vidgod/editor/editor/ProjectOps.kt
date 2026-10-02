package com.vidgod.editor.editor

import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.AudioKind
import com.vidgod.editor.model.EffectClip
import com.vidgod.editor.model.FilterClip
import com.vidgod.editor.model.FxRef
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.MediaSource
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.newId
import com.vidgod.editor.model.sourceToTimelineUs
import com.vidgod.editor.model.timelineToSourceUs

/** Pure functions that edit a [Project]. */
object ProjectOps {
    const val MIN_DURATION_US = 100_000L
    const val DEFAULT_IMAGE_US = 3_000_000L

    fun visualFrom(source: MediaSource, startUs: Long = 0, layer: Int = 0, overlay: Boolean = false): VisualClip {
        val end = if (source.kind == MediaKind.IMAGE) DEFAULT_IMAGE_US else source.durationUs.coerceAtLeast(MIN_DURATION_US)
        return VisualClip(
            source = source,
            startUs = startUs,
            layer = layer,
            trimStartUs = 0,
            trimEndUs = end,
            transform = if (overlay) Transform(scale = 0.6f) else Transform(),
        )
    }

    /** Index of the main clip at [timeUs] and the offset inside it. */
    fun mainClipAt(p: Project, timeUs: Long): Pair<Int, Long>? {
        var t = 0L
        p.clips.forEachIndexed { i, c ->
            if (timeUs < t + c.durationUs) return i to (timeUs - t).coerceAtLeast(0)
            t += c.durationUs
        }
        if (p.clips.isNotEmpty()) return p.clips.lastIndex to p.clips.last().durationUs
        return null
    }

    fun insertMain(p: Project, clips: List<VisualClip>, index: Int = p.clips.size): Project {
        val list = p.clips.toMutableList()
        list.addAll(index.coerceIn(0, list.size), clips)
        return p.copy(clips = list)
    }

    /** Lowest lane where [start, end) does not overlap items of [items]. */
    fun <T> freeLane(items: List<T>, start: Long, end: Long, lane: (T) -> Int, s: (T) -> Long, e: (T) -> Long): Int {
        var l = 0
        while (items.any { lane(it) == l && s(it) < end && e(it) > start }) l++
        return l
    }

    fun addOverlay(p: Project, source: MediaSource, atUs: Long): Pair<Project, VisualClip> {
        val probe = visualFrom(source, atUs, overlay = true)
        val layer = freeLane(p.overlays, atUs, atUs + probe.durationUs, { it.layer }, { it.startUs }, { it.endUs })
        val clip = probe.copy(layer = layer)
        return p.copy(overlays = p.overlays + clip) to clip
    }

    fun addText(p: Project, text: TextClip): Project {
        val lane = freeLane(p.texts + emptyList(), text.startUs, text.endUs, { it.lane }, { it.startUs }, { it.endUs })
        return p.copy(texts = p.texts + text.copy(lane = lane))
    }

    fun addSticker(p: Project, sticker: StickerClip): Project {
        val lane = freeLane(p.stickers, sticker.startUs, sticker.endUs, { it.lane }, { it.startUs }, { it.endUs })
        return p.copy(stickers = p.stickers + sticker.copy(lane = lane))
    }

    fun addAudio(p: Project, source: MediaSource, atUs: Long, kind: AudioKind, trimStartUs: Long = 0, trimEndUs: Long = source.durationUs): Pair<Project, AudioClip> {
        val clip0 = AudioClip(source = source, kind = kind, startUs = atUs, trimStartUs = trimStartUs, trimEndUs = trimEndUs.coerceAtLeast(trimStartUs + MIN_DURATION_US))
        val lane = freeLane(p.audios, clip0.startUs, clip0.endUs, { it.lane }, { it.startUs }, { it.endUs })
        val clip = clip0.copy(lane = lane)
        return p.copy(audios = p.audios + clip) to clip
    }

    fun addEffect(p: Project, fxId: String, atUs: Long, durationUs: Long = 3_000_000): Pair<Project, EffectClip> {
        val dur = durationUs.coerceAtMost((p.durationUs - atUs).coerceAtLeast(MIN_DURATION_US))
        val e0 = EffectClip(fx = FxRef(fxId), startUs = atUs, durationUs = dur)
        val lane = freeLane(p.effects, e0.startUs, e0.endUs, { it.lane }, { it.startUs }, { it.endUs })
        val e = e0.copy(lane = lane)
        return p.copy(effects = p.effects + e) to e
    }

    fun addFilterClip(p: Project, clip: FilterClip): Pair<Project, FilterClip> {
        val dur = clip.durationUs.coerceAtMost((p.durationUs - clip.startUs).coerceAtLeast(MIN_DURATION_US))
        val c0 = clip.copy(durationUs = dur)
        val lane = freeLane(p.filters, c0.startUs, c0.endUs, { it.lane }, { it.startUs }, { it.endUs })
        val c = c0.copy(lane = lane)
        return p.copy(filters = p.filters + c) to c
    }

    // ---------------------------------------------------------------- split

    fun split(p: Project, sel: Selection, atUs: Long): Pair<Project, Selection?> = when (sel) {
        is Selection.Main -> splitMain(p, sel.id, atUs)
        is Selection.Overlay -> {
            val c = p.overlays.firstOrNull { it.id == sel.id }
            if (c == null || atUs <= c.startUs + MIN_DURATION_US || atUs >= c.endUs - MIN_DURATION_US) p to sel
            else {
                val (a, b) = splitVisual(c, atUs - c.startUs)
                val b2 = b.copy(startUs = atUs)
                p.copy(overlays = p.overlays.flatMap { if (it.id == c.id) listOf(a, b2) else listOf(it) }) to Selection.Overlay(b2.id)
            }
        }
        is Selection.Text -> {
            val c = p.texts.firstOrNull { it.id == sel.id }
            if (c == null || atUs <= c.startUs + MIN_DURATION_US || atUs >= c.endUs - MIN_DURATION_US) p to sel
            else {
                val a = c.copy(durationUs = atUs - c.startUs)
                val b = c.copy(id = newId(), startUs = atUs, durationUs = c.endUs - atUs)
                p.copy(texts = p.texts.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) to Selection.Text(b.id)
            }
        }
        is Selection.Sticker -> {
            val c = p.stickers.firstOrNull { it.id == sel.id }
            if (c == null || atUs <= c.startUs + MIN_DURATION_US || atUs >= c.endUs - MIN_DURATION_US) p to sel
            else {
                val a = c.copy(durationUs = atUs - c.startUs)
                val b = c.copy(id = newId(), startUs = atUs, durationUs = c.endUs - atUs)
                p.copy(stickers = p.stickers.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) to Selection.Sticker(b.id)
            }
        }
        is Selection.Audio -> {
            val c = p.audios.firstOrNull { it.id == sel.id }
            if (c == null || atUs <= c.startUs + MIN_DURATION_US || atUs >= c.endUs - MIN_DURATION_US) p to sel
            else {
                val srcSplit = c.trimStartUs + ((atUs - c.startUs) * c.speed.toDouble()).toLong()
                val a = c.copy(trimEndUs = srcSplit, fadeOutUs = 0)
                val b = c.copy(id = newId(), startUs = atUs, trimStartUs = srcSplit, fadeInUs = 0)
                p.copy(audios = p.audios.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) to Selection.Audio(b.id)
            }
        }
        is Selection.Effect -> {
            val c = p.effects.firstOrNull { it.id == sel.id }
            if (c == null || atUs <= c.startUs + MIN_DURATION_US || atUs >= c.endUs - MIN_DURATION_US) p to sel
            else {
                val a = c.copy(durationUs = atUs - c.startUs)
                val b = c.copy(id = newId(), startUs = atUs, durationUs = c.endUs - atUs)
                p.copy(effects = p.effects.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) to Selection.Effect(b.id)
            }
        }
        is Selection.Filter -> {
            val c = p.filters.firstOrNull { it.id == sel.id }
            if (c == null || atUs <= c.startUs + MIN_DURATION_US || atUs >= c.endUs - MIN_DURATION_US) p to sel
            else {
                val a = c.copy(durationUs = atUs - c.startUs)
                val b = c.copy(id = newId(), startUs = atUs, durationUs = c.endUs - atUs)
                p.copy(filters = p.filters.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) to Selection.Filter(b.id)
            }
        }
    }

    /** Splits a visual clip at a timeline offset from its start. */
    fun splitVisual(c: VisualClip, offsetUs: Long): Pair<VisualClip, VisualClip> {
        val srcSplit = c.timelineToSourceUs(offsetUs)
        val curveSplit = c.speedCurve.isNotEmpty()
        val a = c.copy(
            trimEndUs = srcSplit, animOut = null, transitionOut = null, fadeOutUs = 0,
            speedCurve = if (curveSplit) emptyList() else c.speedCurve,
            keyframes = c.keyframes.filter { it.timeUs <= offsetUs },
        )
        val b = c.copy(
            id = newId(), trimStartUs = srcSplit, animIn = null, fadeInUs = 0,
            speedCurve = if (curveSplit) emptyList() else c.speedCurve,
            keyframes = c.keyframes.filter { it.timeUs >= offsetUs }.map { it.copy(timeUs = it.timeUs - offsetUs) },
        )
        return a to b
    }

    private fun splitMain(p: Project, id: String, atUs: Long): Pair<Project, Selection?> {
        val starts = p.clipStarts()
        val idx = p.clips.indexOfFirst { it.id == id }
        if (idx < 0) return p to null
        val c = p.clips[idx]
        val offset = atUs - starts[idx]
        if (offset <= MIN_DURATION_US || offset >= c.durationUs - MIN_DURATION_US) return p to Selection.Main(id)
        val (a, b) = splitVisual(c, offset)
        val list = p.clips.toMutableList()
        list[idx] = a
        list.add(idx + 1, b)
        return p.copy(clips = list) to Selection.Main(b.id)
    }

    // ---------------------------------------------------------------- delete / duplicate

    fun delete(p: Project, sel: Selection): Project = when (sel) {
        is Selection.Main -> p.copy(clips = p.clips.filter { it.id != sel.id })
        is Selection.Overlay -> p.copy(overlays = p.overlays.filter { it.id != sel.id })
        is Selection.Text -> p.copy(texts = p.texts.filter { it.id != sel.id })
        is Selection.Sticker -> p.copy(stickers = p.stickers.filter { it.id != sel.id })
        is Selection.Audio -> p.copy(audios = p.audios.filter { it.id != sel.id })
        is Selection.Effect -> p.copy(effects = p.effects.filter { it.id != sel.id })
        is Selection.Filter -> p.copy(filters = p.filters.filter { it.id != sel.id })
    }

    fun duplicate(p: Project, sel: Selection): Pair<Project, Selection?> = when (sel) {
        is Selection.Main -> {
            val idx = p.clips.indexOfFirst { it.id == sel.id }
            if (idx < 0) p to null else {
                val copy = p.clips[idx].copy(id = newId())
                p.copy(clips = p.clips.toMutableList().apply { add(idx + 1, copy) }) to Selection.Main(copy.id)
            }
        }
        is Selection.Overlay -> p.overlays.firstOrNull { it.id == sel.id }?.let { c ->
            val copy = c.copy(id = newId(), startUs = c.endUs)
            val layer = freeLane(p.overlays, copy.startUs, copy.endUs, { it.layer }, { it.startUs }, { it.endUs })
            p.copy(overlays = p.overlays + copy.copy(layer = layer)) to Selection.Overlay(copy.id)
        } ?: (p to null)
        is Selection.Text -> p.texts.firstOrNull { it.id == sel.id }?.let { c ->
            val copy = c.copy(id = newId(), startUs = c.endUs)
            addText(p, copy) to Selection.Text(copy.id)
        } ?: (p to null)
        is Selection.Sticker -> p.stickers.firstOrNull { it.id == sel.id }?.let { c ->
            val copy = c.copy(id = newId(), startUs = c.endUs)
            addSticker(p, copy) to Selection.Sticker(copy.id)
        } ?: (p to null)
        is Selection.Audio -> p.audios.firstOrNull { it.id == sel.id }?.let { c ->
            val copy = c.copy(id = newId(), startUs = c.endUs)
            val lane = freeLane(p.audios, copy.startUs, copy.endUs, { it.lane }, { it.startUs }, { it.endUs })
            p.copy(audios = p.audios + copy.copy(lane = lane)) to Selection.Audio(copy.id)
        } ?: (p to null)
        is Selection.Effect -> p.effects.firstOrNull { it.id == sel.id }?.let { c ->
            val copy = c.copy(id = newId(), startUs = c.endUs)
            val lane = freeLane(p.effects, copy.startUs, copy.endUs, { it.lane }, { it.startUs }, { it.endUs })
            p.copy(effects = p.effects + copy.copy(lane = lane)) to Selection.Effect(copy.id)
        } ?: (p to null)
        is Selection.Filter -> p.filters.firstOrNull { it.id == sel.id }?.let { c ->
            val copy = c.copy(id = newId(), startUs = c.endUs)
            val lane = freeLane(p.filters, copy.startUs, copy.endUs, { it.lane }, { it.startUs }, { it.endUs })
            p.copy(filters = p.filters + copy.copy(lane = lane)) to Selection.Filter(copy.id)
        } ?: (p to null)
    }

    // ---------------------------------------------------------------- generic updates

    fun updateVisual(p: Project, id: String, f: (VisualClip) -> VisualClip): Project {
        if (p.clips.any { it.id == id }) return p.copy(clips = p.clips.map { if (it.id == id) f(it) else it })
        return p.copy(overlays = p.overlays.map { if (it.id == id) f(it) else it })
    }

    fun updateText(p: Project, id: String, f: (TextClip) -> TextClip) = p.copy(texts = p.texts.map { if (it.id == id) f(it) else it })
    fun updateSticker(p: Project, id: String, f: (StickerClip) -> StickerClip) = p.copy(stickers = p.stickers.map { if (it.id == id) f(it) else it })
    fun updateAudio(p: Project, id: String, f: (AudioClip) -> AudioClip) = p.copy(audios = p.audios.map { if (it.id == id) f(it) else it })
    fun updateEffect(p: Project, id: String, f: (EffectClip) -> EffectClip) = p.copy(effects = p.effects.map { if (it.id == id) f(it) else it })
    fun updateFilter(p: Project, id: String, f: (FilterClip) -> FilterClip) = p.copy(filters = p.filters.map { if (it.id == id) f(it) else it })

    /** Start and end of a selected item on the timeline. */
    fun range(p: Project, sel: Selection): LongRange? = when (sel) {
        is Selection.Main -> {
            val idx = p.clips.indexOfFirst { it.id == sel.id }
            if (idx < 0) null else {
                val s = p.clipStarts()[idx]
                s until s + p.clips[idx].durationUs
            }
        }
        is Selection.Overlay -> p.overlays.firstOrNull { it.id == sel.id }?.let { it.startUs until it.endUs }
        is Selection.Text -> p.texts.firstOrNull { it.id == sel.id }?.let { it.startUs until it.endUs }
        is Selection.Sticker -> p.stickers.firstOrNull { it.id == sel.id }?.let { it.startUs until it.endUs }
        is Selection.Audio -> p.audios.firstOrNull { it.id == sel.id }?.let { it.startUs until it.endUs }
        is Selection.Effect -> p.effects.firstOrNull { it.id == sel.id }?.let { it.startUs until it.endUs }
        is Selection.Filter -> p.filters.firstOrNull { it.id == sel.id }?.let { it.startUs until it.endUs }
    }

    fun exists(p: Project, sel: Selection) = range(p, sel) != null

    /** Moves a timed (non main-track) item to start at [startUs]. */
    fun moveTo(p: Project, sel: Selection, startUs: Long): Project {
        val s = startUs.coerceAtLeast(0)
        return when (sel) {
            is Selection.Overlay -> updateVisual(p, sel.id) { it.copy(startUs = s) }
            is Selection.Text -> updateText(p, sel.id) { it.copy(startUs = s) }
            is Selection.Sticker -> updateSticker(p, sel.id) { it.copy(startUs = s) }
            is Selection.Audio -> updateAudio(p, sel.id) { it.copy(startUs = s) }
            is Selection.Effect -> updateEffect(p, sel.id) { it.copy(startUs = s) }
            is Selection.Filter -> updateFilter(p, sel.id) { it.copy(startUs = s) }
            is Selection.Main -> p
        }
    }

    /** Re-packs lanes after moves so that items never overlap within a lane. */
    fun normalizeLanes(p: Project): Project {
        fun <T> pack(items: List<T>, lane: (T) -> Int, s: (T) -> Long, e: (T) -> Long, set: (T, Int) -> T): List<T> {
            val result = items.toMutableList()
            val finalLanes = IntArray(items.size)
            val done = ArrayList<Int>()
            for (i in items.indices.sortedBy { lane(items[it]) }) {
                var l = lane(items[i])
                while (done.any { j -> finalLanes[j] == l && s(items[j]) < e(items[i]) && e(items[j]) > s(items[i]) }) l++
                finalLanes[i] = l
                done.add(i)
                if (l != lane(items[i])) result[i] = set(items[i], l)
            }
            return result
        }
        return p.copy(
            overlays = pack(p.overlays, { it.layer }, { it.startUs }, { it.endUs }) { c, l -> c.copy(layer = l) },
            texts = pack(p.texts, { it.lane }, { it.startUs }, { it.endUs }) { c, l -> c.copy(lane = l) },
            stickers = pack(p.stickers, { it.lane }, { it.startUs }, { it.endUs }) { c, l -> c.copy(lane = l) },
            audios = pack(p.audios, { it.lane }, { it.startUs }, { it.endUs }) { c, l -> c.copy(lane = l) },
            effects = pack(p.effects, { it.lane }, { it.startUs }, { it.endUs }) { c, l -> c.copy(lane = l) },
            filters = pack(p.filters, { it.lane }, { it.startUs }, { it.endUs }) { c, l -> c.copy(lane = l) },
        )
    }

    /** Trims a main or overlay clip; [newStartSrc]/[newEndSrc] are source times. */
    fun trimVisual(c: VisualClip, newStartSrc: Long, newEndSrc: Long): VisualClip {
        // The probed length can be missing (0) for some files: never go below the current trim.
        val maxEnd = if (c.isImage) Long.MAX_VALUE / 4 else maxOf(c.source.durationUs, c.trimEndUs, 2 * MIN_DURATION_US)
        val s = newStartSrc.coerceIn(0, maxEnd - MIN_DURATION_US)
        val e = newEndSrc.coerceIn(s + MIN_DURATION_US, maxEnd)
        return c.copy(trimStartUs = if (c.isImage) 0 else s, trimEndUs = if (c.isImage) e - s else e)
    }

    /** Keeps keyframe times valid after a clip changed length. */
    fun clampKeyframes(c: VisualClip): VisualClip = c.copy(keyframes = c.keyframes.filter { it.timeUs <= c.durationUs })

    fun sourceTimeAt(c: VisualClip, offsetUs: Long) = c.timelineToSourceUs(offsetUs)
    fun timelineOffsetOf(c: VisualClip, sourceUs: Long) = c.sourceToTimelineUs(sourceUs)
}
