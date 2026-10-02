package com.vidgod.editor.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.vidgod.editor.engine.audio.DenoiseAudioProcessor
import com.vidgod.editor.engine.audio.EnvelopeAudioProcessor
import com.vidgod.editor.engine.audio.GainCurve
import com.vidgod.editor.engine.audio.VoiceFxAudioProcessor
import com.vidgod.editor.engine.catalog.Filters
import com.vidgod.editor.engine.catalog.GradeParams
import com.vidgod.editor.engine.effects.ClipCanvasEffect
import com.vidgod.editor.engine.effects.ClipSpec
import com.vidgod.editor.engine.effects.ColorGradeEffect
import com.vidgod.editor.engine.effects.FxEffect
import com.vidgod.editor.engine.effects.GradeProvider
import com.vidgod.editor.engine.text.OverlayLanes
import com.vidgod.editor.features.BackgroundRemovalEffect
import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.SpeedCurves
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.VoiceFx
import kotlin.math.min

/** Turns a [Project] into a Media3 [Composition] used for both preview and export. */
@UnstableApi
class CompositionFactory(private val context: Context) {

    data class Built(
        val composition: Composition,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val durationUs: Long,
        val sequenceCount: Int,
    )

    /**
     * @param shortSide short side of the output canvas in pixels
     * @param frameRate maximum frame rate of the output
     */
    fun build(project: Project, live: LiveProject, shortSide: Int, frameRate: Int): Built? {
        if (project.clips.isEmpty()) return null
        val (cw, ch) = project.canvasSize(shortSide)
        val total = project.mainDurationUs
        val sequences = ArrayList<EditedMediaItemSequence>()

        // ---- overlay layers, top-most first (Media3 draws the first sequence on top) ----
        val layers = project.overlays
            .filter { it.startUs < total }
            .groupBy { it.layer }
            .toSortedMap(compareByDescending { it })
            .values
            .map { clips -> clips.sortedBy { it.startUs } }
        val layerRanges = ArrayList<List<LongArray>>()
        for (clips in layers) {
            val hasAudio = clips.any { it.source.hasAudio && !it.muted && it.volume > 0f }
            val types = if (hasAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
            val b = EditedMediaItemSequence.Builder(types)
            var cursor = 0L
            val ranges = ArrayList<LongArray>()
            for (raw in clips) {
                if (raw.startUs < cursor) continue // overlapping clips on one layer are not allowed
                val clip = clampToEnd(raw, total - raw.startUs) ?: continue
                if (clip.startUs > cursor) b.addGap(clip.startUs - cursor)
                val spec = ClipSpec(clip.id, false, clip.startUs, cw, ch, clip)
                b.addItem(visualItem(clip, live, spec, project, frameRate))
                ranges.add(longArrayOf(clip.startUs, clip.startUs + clip.durationUs))
                cursor = clip.startUs + clip.durationUs
            }
            if (ranges.isEmpty()) continue
            if (cursor < total) b.addGap(total - cursor)
            sequences.add(b.build())
            layerRanges.add(ranges)
        }

        // ---- main track ----
        val starts = project.clips.let { clips ->
            val s = LongArray(clips.size); var t = 0L
            clips.forEachIndexed { i, c -> s[i] = t; t += c.durationUs }
            s
        }
        val mainHasAudio = !project.mainMuted && project.clips.any { it.source.hasAudio }
        val mainTypes = if (mainHasAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
        val main = EditedMediaItemSequence.Builder(mainTypes)
        project.clips.forEachIndexed { i, clip ->
            val prev = project.clips.getOrNull(i - 1)
            val tin = prev?.transitionOut?.let { tr ->
                tr.copy(durationUs = min(tr.durationUs, clip.durationUs / 2).coerceAtLeast(1))
            }
            val spec = ClipSpec(
                clipId = clip.id,
                isMain = true,
                timelineStartUs = starts[i],
                canvasWidth = cw,
                canvasHeight = ch,
                fallback = clip,
                transitionIn = tin,
                prevClipId = prev?.id,
                transitionOutUs = if (i < project.clips.size - 1) clip.transitionOut?.durationUs ?: 0 else 0,
            )
            main.addItem(visualItem(clip, live, spec, project, frameRate, mainTrack = true))
        }
        sequences.add(main.build())
        val videoSequenceCount = sequences.size

        // ---- audio lanes ----
        project.audios.filter { it.startUs < total }.groupBy { it.lane }.toSortedMap().values.forEach { lane ->
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var cursor = 0L
            var added = 0
            for (raw in lane.sortedBy { it.startUs }) {
                if (raw.startUs < cursor) continue
                val a = clampAudio(raw, total - raw.startUs) ?: continue
                if (a.startUs > cursor) b.addGap(a.startUs - cursor)
                b.addItem(audioItem(a))
                cursor = a.startUs + a.durationUs
                added++
            }
            if (added > 0) {
                if (cursor < total) b.addGap(total - cursor)
                sequences.add(b.build())
            }
        }

        // ---- whole-frame effects: filters, effects, texts & stickers ----
        val compositionEffects = ArrayList<Effect>()
        if (project.filters.isNotEmpty()) {
            compositionEffects.add(ColorGradeEffect(globalFilterProvider(live)))
        }
        val fxLanes = project.effects.groupBy { it.lane }.keys.sorted()
        for (laneIndex in fxLanes) {
            compositionEffects.add(FxEffect { t, out ->
                val e = live.project.effects.firstOrNull { it.lane == laneIndex && t >= it.startUs && t < it.endUs }
                    ?: return@FxEffect false
                out.id = e.fx.id
                out.intensity = e.fx.intensity
                out.speed = e.fx.speed
                out.localUs = t - e.startUs
                out.durationUs = e.durationUs
                true
            })
        }
        compositionEffects.addAll(OverlayLanes.effects(context, live, project, cw, ch))

        val builder = Composition.Builder(sequences)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .setEffects(Effects(emptyList(), compositionEffects))
        if (videoSequenceCount > 1) {
            builder.setVideoCompositorSettings(compositorSettings(cw, ch, layerRanges))
        }
        return Built(builder.build(), cw, ch, total, sequences.size)
    }

    private fun compositorSettings(cw: Int, ch: Int, layerRanges: List<List<LongArray>>) =
        object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: MutableList<Size>): Size = Size(cw, ch)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                val ranges = layerRanges.getOrNull(inputId) ?: return VISIBLE
                for (r in ranges) if (presentationTimeUs >= r[0] && presentationTimeUs < r[1]) return VISIBLE
                return HIDDEN
            }
        }

    /** Shortens an overlay clip so it ends at most [maxDurationUs] after its start. */
    private fun clampToEnd(clip: VisualClip, maxDurationUs: Long): VisualClip? {
        if (maxDurationUs <= 33_000) return null
        if (clip.durationUs <= maxDurationUs) return clip
        return if (clip.isImage) {
            clip.copy(trimEndUs = clip.trimStartUs + maxDurationUs)
        } else {
            val src = clip.copy(speedCurve = emptyList())
            clip.copy(trimEndUs = clip.trimStartUs + (maxDurationUs * src.speed.toDouble()).toLong(), speedCurve = emptyList())
        }
    }

    private fun clampAudio(a: AudioClip, maxDurationUs: Long): AudioClip? {
        if (maxDurationUs <= 33_000) return null
        if (a.durationUs <= maxDurationUs) return a
        return a.copy(trimEndUs = a.trimStartUs + (maxDurationUs * a.speed.toDouble()).toLong())
    }

    private fun visualItem(
        clip: VisualClip,
        live: LiveProject,
        spec: ClipSpec,
        project: Project,
        frameRate: Int,
        mainTrack: Boolean = false,
    ): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(clip.playbackUri)
        if (clip.isImage) {
            mi.setImageDurationMs((clip.durationUs / 1000).coerceAtLeast(1))
        } else {
            mi.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.trimStartUs.coerceAtLeast(0))
                    .setEndPositionUs(clip.trimEndUs)
                    .build(),
            )
        }
        val video = ArrayList<Effect>()
        video.add(ColorGradeEffect(clipGradeProvider(live, clip.id)))
        if (clip.removeBackground) video.add(BackgroundRemovalEffect())
        video.add(ClipCanvasEffect(live, spec))
        clip.fx.forEach { ref ->
            video.add(FxEffect { t, out ->
                out.id = ref.id
                out.intensity = ref.intensity
                out.speed = ref.speed
                out.localUs = t - spec.timelineStartUs
                out.durationUs = clip.durationUs
                true
            })
        }
        val audio = ArrayList<AudioProcessor>()
        if (!clip.isImage && clip.source.hasAudio) {
            val muted = clip.muted || (mainTrack && project.mainMuted)
            audio.addAll(voiceChain(clip.voiceFx, clip.denoise))
            audio.add(EnvelopeAudioProcessor(visualGain(live, clip, muted)))
        }
        val b = EditedMediaItem.Builder(mi.build())
            .setEffects(Effects(audio, video))
            .setDurationUs(if (clip.isImage) clip.durationUs else clip.source.durationUs.coerceAtLeast(clip.trimEndUs))
            .setFrameRate(frameRate)
        speedParameters(clip)?.let { b.setSpeed(it) }
        return b.build()
    }

    private fun audioItem(a: AudioClip): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(a.source.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(a.trimStartUs.coerceAtLeast(0))
                    .setEndPositionUs(a.trimEndUs)
                    .build(),
            )
            .build()
        val audio = ArrayList<AudioProcessor>()
        audio.addAll(voiceChain(a.voiceFx, a.denoise))
        val dur = a.durationUs
        audio.add(EnvelopeAudioProcessor { t ->
            fade(t, dur, a.fadeInUs, a.fadeOutUs) * a.volume
        })
        val b = EditedMediaItem.Builder(mi)
            .setEffects(Effects(audio, emptyList()))
            .setDurationUs(a.source.durationUs.coerceAtLeast(a.trimEndUs))
        if (a.speed != 1f) {
            b.setSpeed(SpeedParameters(constantSpeed(a.speed), a.keepPitch))
        }
        return b.build()
    }

    private fun voiceChain(fx: VoiceFx, denoise: Boolean): List<AudioProcessor> {
        val list = ArrayList<AudioProcessor>()
        if (denoise) list.add(DenoiseAudioProcessor())
        val pitch = VoiceFxAudioProcessor.pitchFor(fx)
        if (pitch != 1f) list.add(SonicAudioProcessor().apply { setPitch(pitch) })
        if (fx in VoiceFxAudioProcessor.ACTIVE) list.add(VoiceFxAudioProcessor(fx))
        return list
    }

    private fun visualGain(live: LiveProject, original: VisualClip, muted: Boolean) = GainCurve { t ->
        if (muted) return@GainCurve 0f
        val clip = live.visual(original.id) ?: original
        if (clip.muted || live.project.mainMuted && live.project.clips.any { it.id == clip.id }) return@GainCurve 0f
        val kv = if (clip.keyframes.isNotEmpty()) Keyframes.evaluate(clip.keyframes, t, clip.transform, 1f, clip.volume).volume else clip.volume
        kv * fade(t, clip.durationUs, clip.fadeInUs, clip.fadeOutUs)
    }

    private fun fade(t: Long, duration: Long, fadeIn: Long, fadeOut: Long): Float {
        var g = 1f
        if (fadeIn > 0 && t < fadeIn) g *= (t.toFloat() / fadeIn).coerceIn(0f, 1f)
        if (fadeOut > 0 && t > duration - fadeOut) g *= ((duration - t).toFloat() / fadeOut).coerceIn(0f, 1f)
        return g
    }

    private fun speedParameters(clip: VisualClip): SpeedParameters? {
        if (clip.isImage) return null
        if (clip.speedCurve.isNotEmpty()) {
            val range = clip.sourceRangeUs
            val speeds = SpeedCurves.segmentSpeeds(clip.speedCurve)
            val provider = object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float {
                    val i = ((timeUs.toDouble() / range) * SpeedCurves.SEGMENTS).toInt().coerceIn(0, SpeedCurves.SEGMENTS - 1)
                    return speeds[i]
                }

                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                    for (i in 1 until SpeedCurves.SEGMENTS) {
                        val boundary = SpeedCurves.segmentStartUs(range, i)
                        if (boundary > timeUs) return boundary
                    }
                    return C.TIME_UNSET
                }
            }
            return SpeedParameters(provider, clip.keepPitch)
        }
        if (clip.speed == 1f) return null
        return SpeedParameters(constantSpeed(clip.speed), clip.keepPitch)
    }

    private fun constantSpeed(speed: Float) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private fun clipGradeProvider(live: LiveProject, clipId: String) = GradeProvider { _, out ->
        val clip = live.visual(clipId) ?: return@GradeProvider false
        val filter = Filters.get(clip.filter?.id)
        if (filter == null && clip.adjust.isNeutral) return@GradeProvider false
        if (filter != null) {
            filter.params.pack(out.filter)
            out.filterIntensity = clip.filter!!.intensity
        }
        GradeParams.fromAdjust(clip.adjust).pack(out.adjust)
        true
    }

    private fun globalFilterProvider(live: LiveProject) = GradeProvider { t, out ->
        val active = live.project.filters.filter { t >= it.startUs && t < it.endUs }
        if (active.isEmpty()) return@GradeProvider false
        val withFilter = active.firstOrNull { it.filter != null }
        Filters.get(withFilter?.filter?.id)?.let {
            it.params.pack(out.filter)
            out.filterIntensity = withFilter!!.filter!!.intensity
        }
        val adj = active.firstOrNull { !it.adjust.isNeutral }?.adjust
        if (adj != null) GradeParams.fromAdjust(adj).pack(out.adjust)
        true
    }

    companion object {
        private val VISIBLE: OverlaySettings = StaticOverlaySettings.Builder().build()
        private val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        /** True if the media kind is decodable as a visual clip. */
        fun isVisual(kind: MediaKind) = kind == MediaKind.VIDEO || kind == MediaKind.IMAGE
    }
}
