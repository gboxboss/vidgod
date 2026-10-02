package com.vidgod.editor.editor

/** What is selected on the timeline. */
sealed interface Selection {
    val id: String

    data class Main(override val id: String) : Selection
    data class Overlay(override val id: String) : Selection
    data class Text(override val id: String) : Selection
    data class Sticker(override val id: String) : Selection
    data class Audio(override val id: String) : Selection
    data class Effect(override val id: String) : Selection
    data class Filter(override val id: String) : Selection
}

/** Bottom panels (tool sheets). */
enum class Panel {
    // Global menus
    AUDIO_MENU, TEXT_MENU, STICKERS, STYLES, EFFECTS_ADD, FILTERS_ADD, ADJUST_ADD, RATIO, CANVAS, CAPTIONS, TTS, RECORD,
    // Item tools
    SPEED, VOLUME, ANIMATION, FILTERS, ADJUST, EFFECTS, TRANSFORM, OPACITY, CHROMA, MASK, VOICE_FX,
    TRANSITION, TEXT_EDIT, FX_EDIT, FILTER_EDIT, REORDER, EXPORT, BLEND,
}
