package com.vidgod.editor.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** All times are in microseconds unless the name says otherwise. */
fun newId(): String = UUID.randomUUID().toString().substring(0, 12)

@Serializable
enum class MediaKind { VIDEO, IMAGE, AUDIO }

/** Description of an imported file. */
@Serializable
data class MediaSource(
    val uri: String,
    val kind: MediaKind,
    val name: String = "",
    val durationUs: Long = 0,
    /** Display size (rotation already applied). */
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    val frameRate: Float = 30f,
    val isHdr: Boolean = false,
)

/** Placement of a visual layer inside the canvas. */
@Serializable
data class Transform(
    /** Offset of the layer centre from the canvas centre, as a fraction of canvas width/height. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** 1 = the layer is fitted inside the canvas. */
    val scale: Float = 1f,
    /** Degrees, clockwise. */
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

/** Normalised crop rectangle of the source frame (0..1). */
@Serializable
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isFull get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
}

/** Colour adjustments, all neutral at 0. Ranges are -1..1 unless noted. */
@Serializable
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vibrance: Float = 0f,
    val hue: Float = 0f,
    /** 0..1 */
    val sharpen: Float = 0f,
    /** 0..1 */
    val vignette: Float = 0f,
    /** 0..1 */
    val fade: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
) {
    val isNeutral get() = this == NEUTRAL

    companion object {
        val NEUTRAL = Adjust()
    }
}

@Serializable
data class FilterRef(val id: String, val intensity: Float = 1f)

@Serializable
data class FxRef(val id: String, val intensity: Float = 1f, val speed: Float = 1f)

@Serializable
data class AnimRef(val id: String, val durationUs: Long = 500_000)

@Serializable
data class TransitionRef(val id: String, val durationUs: Long = 500_000)

@Serializable
data class ChromaKey(
    /** ARGB colour to remove. */
    val color: Int = 0xFF00FF00.toInt(),
    /** 0..1 */
    val intensity: Float = 0.4f,
    /** 0..1 */
    val shadow: Float = 0.1f,
)

@Serializable
enum class MaskShape { LINEAR, MIRROR, CIRCLE, RECTANGLE, HEART, STAR }

@Serializable
data class Mask(
    val shape: MaskShape,
    /** Centre, fraction of the layer size, relative to layer centre. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** Size, fraction of the layer size. */
    val width: Float = 0.6f,
    val height: Float = 0.6f,
    val rotation: Float = 0f,
    /** 0..1 */
    val feather: Float = 0.05f,
    val invert: Boolean = false,
    /** Corner roundness for RECTANGLE, 0..1. */
    val roundness: Float = 0f,
)

@Serializable
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT }

/** A keyframe of a layer, at a time relative to the start of the item on the timeline. */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val transform: Transform,
    val opacity: Float = 1f,
    val volume: Float = 1f,
    val easing: Easing = Easing.EASE_IN_OUT,
)

@Serializable
enum class VoiceFx { NONE, CHIPMUNK, DEEP, ROBOT, ECHO, MONSTER, GIANT, HELIUM, TELEPHONE, MEGAPHONE, RADIO, CAVE }

@Serializable
data class SpeedPoint(
    /** 0..1 position within the source range. */
    val pos: Float,
    val speed: Float,
)

/**
 * A video or image on the main track (when [startUs] is ignored and clips are laid out
 * one after the other) or on an overlay (picture-in-picture) layer.
 */
@Serializable
data class VisualClip(
    val id: String = newId(),
    val source: MediaSource,
    /** Timeline start; only used by overlay clips. */
    val startUs: Long = 0,
    /** Overlay layer index (0 = bottom-most overlay). Unused for main-track clips. */
    val layer: Int = 0,
    val trimStartUs: Long = 0,
    /** Source end time. For images this is the chosen display duration. */
    val trimEndUs: Long,
    val speed: Float = 1f,
    /** Optional speed curve; overrides [speed] when non-empty. */
    val speedCurve: List<SpeedPoint> = emptyList(),
    val speedCurveName: String? = null,
    val keepPitch: Boolean = true,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val voiceFx: VoiceFx = VoiceFx.NONE,
    val denoise: Boolean = false,
    val transform: Transform = Transform(),
    val crop: CropRect = CropRect(),
    val opacity: Float = 1f,
    val filter: FilterRef? = null,
    val adjust: Adjust = Adjust(),
    val fx: List<FxRef> = emptyList(),
    val animIn: AnimRef? = null,
    val animOut: AnimRef? = null,
    val animCombo: AnimRef? = null,
    /** Transition into the next main-track clip. */
    val transitionOut: TransitionRef? = null,
    val chromaKey: ChromaKey? = null,
    val mask: Mask? = null,
    val removeBackground: Boolean = false,
    val keyframes: List<Keyframe> = emptyList(),
    val blendMode: BlendMode = BlendMode.NORMAL,
    /** Set when [source] is a reversed copy; holds the original to undo the reverse. */
    val reversedFrom: ReverseInfo? = null,
    val label: String? = null,
) {
    val isReversed get() = reversedFrom != null
    val isImage get() = source.kind == MediaKind.IMAGE
    val sourceRangeUs get() = (trimEndUs - trimStartUs).coerceAtLeast(1)

    /** Duration on the timeline. */
    val durationUs: Long
        get() = if (speedCurve.isNotEmpty() && !isImage) {
            SpeedCurves.timelineDuration(sourceRangeUs, speedCurve)
        } else if (isImage) {
            sourceRangeUs
        } else {
            (sourceRangeUs / speed.toDouble()).toLong().coerceAtLeast(1)
        }

    val endUs get() = startUs + durationUs

    /** The uri that should actually be decoded. */
    val playbackUri get() = source.uri
}

@Serializable
data class ReverseInfo(val source: MediaSource, val trimStartUs: Long, val trimEndUs: Long)

@Serializable
enum class BlendMode { NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, ADD, DIFFERENCE }

@Serializable
enum class AudioKind { MUSIC, SFX, VOICEOVER, TTS, EXTRACTED }

@Serializable
data class AudioClip(
    val id: String = newId(),
    val source: MediaSource,
    val kind: AudioKind = AudioKind.MUSIC,
    val startUs: Long = 0,
    val lane: Int = 0,
    val trimStartUs: Long = 0,
    val trimEndUs: Long,
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    val volume: Float = 1f,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val voiceFx: VoiceFx = VoiceFx.NONE,
    val denoise: Boolean = false,
    val beatsUs: List<Long> = emptyList(),
    val name: String = source.name,
) {
    val durationUs get() = ((trimEndUs - trimStartUs) / speed.toDouble()).toLong().coerceAtLeast(1)
    val endUs get() = startUs + durationUs
}

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
data class TextStyle(
    val fontId: String = "sans_bold",
    /** Text size as a fraction of the canvas width. */
    val size: Float = 0.075f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Stroke width as a fraction of text size. 0 = none. */
    val strokeWidth: Float = 0f,
    val shadowColor: Int = 0x99000000.toInt(),
    val shadowRadius: Float = 0f,
    val backgroundColor: Int = 0xFF000000.toInt(),
    /** 0 = no label background. */
    val backgroundAlpha: Float = 0f,
    val backgroundCorner: Float = 0.25f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: TextAlign = TextAlign.CENTER,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
    val opacity: Float = 1f,
    /** Colour used for the currently spoken word of captions (karaoke). 0 = off. */
    val highlightColor: Int = 0,
    val allCaps: Boolean = false,
    val gradientColors: List<Int> = emptyList(),
)

@Serializable
data class WordTiming(val word: String, val startUs: Long, val endUs: Long)

@Serializable
data class TextClip(
    val id: String = newId(),
    val text: String,
    val startUs: Long,
    val durationUs: Long = 3_000_000,
    val lane: Int = 0,
    val style: TextStyle = TextStyle(),
    val transform: Transform = Transform(y = 0f),
    val animIn: AnimRef? = null,
    val animOut: AnimRef? = null,
    val animLoop: AnimRef? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val isCaption: Boolean = false,
    /** Word timings relative to [startUs], used for karaoke-style captions. */
    val words: List<WordTiming> = emptyList(),
    /** Optional typewriter style reveal. */
    val templateId: String? = null,
) {
    val endUs get() = startUs + durationUs
}

@Serializable
enum class StickerKind { EMOJI, IMAGE, SHAPE }

@Serializable
data class StickerClip(
    val id: String = newId(),
    val kind: StickerKind,
    /** Emoji text, image uri or shape id. */
    val content: String,
    val startUs: Long,
    val durationUs: Long = 3_000_000,
    val lane: Int = 0,
    val transform: Transform = Transform(scale = 1f),
    /** Base size as a fraction of the canvas width. */
    val size: Float = 0.3f,
    val tint: Int = 0,
    val opacity: Float = 1f,
    val animIn: AnimRef? = null,
    val animOut: AnimRef? = null,
    val animLoop: AnimRef? = null,
    val keyframes: List<Keyframe> = emptyList(),
) {
    val endUs get() = startUs + durationUs
}

/** A time range on which a video effect is applied to the whole frame. */
@Serializable
data class EffectClip(
    val id: String = newId(),
    val fx: FxRef,
    val startUs: Long,
    val durationUs: Long = 3_000_000,
    val lane: Int = 0,
) {
    val endUs get() = startUs + durationUs
}

/** A time range on which a filter and/or adjustment is applied to the whole frame. */
@Serializable
data class FilterClip(
    val id: String = newId(),
    val filter: FilterRef? = null,
    val adjust: Adjust = Adjust(),
    val startUs: Long,
    val durationUs: Long = 3_000_000,
    val lane: Int = 0,
) {
    val endUs get() = startUs + durationUs
}

@Serializable
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    R9_16("9:16", 9, 16),
    R16_9("16:9", 16, 9),
    R1_1("1:1", 1, 1),
    R4_5("4:5", 4, 5),
    R3_4("3:4", 3, 4),
    R4_3("4:3", 4, 3),
    R2_1("2:1", 2, 1),
    R235_1("2.35:1", 235, 100),
    R185_1("1.85:1", 185, 100),
    ORIGINAL("Original", 0, 0),
}

@Serializable
enum class BackgroundKind { COLOR, BLUR, IMAGE }

@Serializable
data class CanvasConfig(
    val ratio: AspectRatio = AspectRatio.R9_16,
    val background: BackgroundKind = BackgroundKind.BLUR,
    val backgroundColor: Int = 0xFF000000.toInt(),
    /** 0..1 */
    val blur: Float = 0.5f,
    val backgroundImageUri: String? = null,
)

@Serializable
data class ExportSettings(
    /** Short side of the output in pixels: 480, 720, 1080, 1440, 2160. */
    val resolution: Int = 1080,
    val frameRate: Int = 30,
    /** Bits per second; 0 = automatic (recommended for the resolution). */
    val bitrate: Int = 0,
    val hevc: Boolean = false,
    val tiktokOptimized: Boolean = true,
)

@Serializable
data class Project(
    val id: String = newId(),
    val name: String = "Untitled",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val canvas: CanvasConfig = CanvasConfig(),
    val export: ExportSettings = ExportSettings(),
    val clips: List<VisualClip> = emptyList(),
    val overlays: List<VisualClip> = emptyList(),
    val audios: List<AudioClip> = emptyList(),
    val texts: List<TextClip> = emptyList(),
    val stickers: List<StickerClip> = emptyList(),
    val effects: List<EffectClip> = emptyList(),
    val filters: List<FilterClip> = emptyList(),
    /** Main track original audio muted. */
    val mainMuted: Boolean = false,
    val version: Int = 1,
) {
    /** Start time of each main-track clip on the timeline. */
    fun clipStarts(): LongArray {
        val starts = LongArray(clips.size)
        var t = 0L
        clips.forEachIndexed { i, c -> starts[i] = t; t += c.durationUs }
        return starts
    }

    val mainDurationUs: Long get() = clips.sumOf { it.durationUs }

    /** Total length of the edit. The main track defines the length when it is not empty. */
    val durationUs: Long
        get() {
            val main = mainDurationUs
            if (main > 0) return main
            val others = listOf(
                overlays.maxOfOrNull { it.endUs } ?: 0,
                audios.maxOfOrNull { it.endUs } ?: 0,
                texts.maxOfOrNull { it.endUs } ?: 0,
                stickers.maxOfOrNull { it.endUs } ?: 0,
            )
            return others.max()
        }

    /** Canvas size in pixels for a given short side. */
    fun canvasSize(shortSide: Int): Pair<Int, Int> {
        val (rw, rh) = ratioDims()
        return if (rw <= rh) {
            val w = shortSide
            val h = (shortSide.toLong() * rh / rw).toInt()
            even(w) to even(h)
        } else {
            val h = shortSide
            val w = (shortSide.toLong() * rw / rh).toInt()
            even(w) to even(h)
        }
    }

    /** Width:height of the canvas as integers. */
    fun ratioDims(): Pair<Int, Int> {
        if (canvas.ratio != AspectRatio.ORIGINAL) return canvas.ratio.w to canvas.ratio.h
        val first = clips.firstOrNull()?.source
        if (first == null || first.width <= 0 || first.height <= 0) return 9 to 16
        return first.width to first.height
    }

    val canvasAspect: Float get() = ratioDims().let { it.first.toFloat() / it.second }

    private fun even(v: Int) = (v / 2) * 2
}
