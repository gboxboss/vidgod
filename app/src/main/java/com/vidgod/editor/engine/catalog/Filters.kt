package com.vidgod.editor.engine.catalog

import com.vidgod.editor.model.Adjust

/**
 * Parameters of the colour grading shader. Every field is neutral at its default value.
 * The same structure is used for filters (looks) and for manual adjustments.
 */
data class GradeParams(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpen: Float = 0f,
    val mono: Float = 0f,
    val sepia: Float = 0f,
    /** Shadow tint colour (0xRRGGBB) and amount. */
    val shadowTint: Int = 0x808080,
    val shadowTintAmount: Float = 0f,
    val highlightTint: Int = 0x808080,
    val highlightTintAmount: Float = 0f,
    val gammaR: Float = 1f,
    val gammaG: Float = 1f,
    val gammaB: Float = 1f,
    /** Teal & orange cinematic split, 0..1. */
    val tealOrange: Float = 0f,
) {
    /** Packs the parameters into 7 vec4 uniforms (28 floats). */
    fun pack(out: FloatArray, offset: Int = 0) {
        var i = offset
        fun put(v: Float) { out[i++] = v }
        put(exposure); put(brightness); put(contrast); put(saturation)
        put(vibrance); put(temperature); put(tint); put(hue)
        put(highlights); put(shadows); put(fade); put(vignette)
        put(grain); put(sharpen); put(mono); put(sepia)
        put(((shadowTint shr 16) and 0xFF) / 255f); put(((shadowTint shr 8) and 0xFF) / 255f)
        put((shadowTint and 0xFF) / 255f); put(shadowTintAmount)
        put(((highlightTint shr 16) and 0xFF) / 255f); put(((highlightTint shr 8) and 0xFF) / 255f)
        put((highlightTint and 0xFF) / 255f); put(highlightTintAmount)
        put(gammaR); put(gammaG); put(gammaB); put(tealOrange)
    }

    companion object {
        const val FLOATS = 28
        val NEUTRAL = GradeParams()

        fun fromAdjust(a: Adjust) = GradeParams(
            exposure = a.exposure,
            brightness = a.brightness,
            contrast = a.contrast,
            saturation = a.saturation,
            vibrance = a.vibrance,
            temperature = a.temperature,
            tint = a.tint,
            hue = a.hue,
            highlights = a.highlights,
            shadows = a.shadows,
            fade = a.fade,
            vignette = a.vignette,
            grain = a.grain,
            sharpen = a.sharpen,
        )
    }
}

data class FilterDef(
    val id: String,
    val name: String,
    val category: String,
    val params: GradeParams,
    /** Preview swatch colour for the UI. */
    val swatch: Long,
)

object Filters {
    val all: List<FilterDef> = listOf(
        // Life
        FilterDef("fresh", "Fresh", "Life", GradeParams(brightness = 0.08f, saturation = 0.15f, vibrance = 0.2f, temperature = -0.05f, contrast = 0.05f), 0xFF8FD3F4),
        FilterDef("vivid", "Vivid", "Life", GradeParams(saturation = 0.35f, vibrance = 0.3f, contrast = 0.15f), 0xFFFF6B6B),
        FilterDef("glow", "Glow", "Life", GradeParams(brightness = 0.12f, highlights = 0.2f, fade = 0.15f, saturation = 0.05f, temperature = 0.05f), 0xFFFFE0B2),
        FilterDef("warm", "Warm", "Life", GradeParams(temperature = 0.35f, saturation = 0.1f, contrast = 0.05f), 0xFFFFB74D),
        FilterDef("cool", "Cool", "Life", GradeParams(temperature = -0.35f, tint = -0.05f, contrast = 0.05f), 0xFF64B5F6),
        FilterDef("peach", "Peach", "Life", GradeParams(temperature = 0.2f, tint = 0.1f, brightness = 0.06f, fade = 0.1f, highlightTint = 0xFFC0A0, highlightTintAmount = 0.25f), 0xFFFFCCBC),
        FilterDef("pink", "Pink", "Life", GradeParams(tint = 0.25f, brightness = 0.05f, highlightTint = 0xFF9EC8, highlightTintAmount = 0.3f), 0xFFF48FB1),
        FilterDef("food", "Delicious", "Life", GradeParams(saturation = 0.25f, temperature = 0.18f, contrast = 0.12f, sharpen = 0.3f), 0xFFFF8A65),
        FilterDef("clear", "Clarity", "Life", GradeParams(contrast = 0.2f, sharpen = 0.6f, vibrance = 0.15f, highlights = -0.1f, shadows = 0.1f), 0xFFB2EBF2),
        // Film
        FilterDef("kodak", "Kodachrome", "Film", GradeParams(contrast = 0.2f, saturation = 0.15f, temperature = 0.12f, gammaR = 0.95f, gammaB = 1.08f, shadowTint = 0x1A3050, shadowTintAmount = 0.2f, grain = 0.15f), 0xFFE57373),
        FilterDef("fuji", "Fuji", "Film", GradeParams(contrast = 0.1f, saturation = 0.05f, temperature = -0.05f, tint = -0.08f, gammaG = 0.96f, highlightTint = 0xF0FFF0, highlightTintAmount = 0.2f, grain = 0.12f), 0xFF81C784),
        FilterDef("portra", "Portra", "Film", GradeParams(contrast = -0.05f, saturation = -0.05f, temperature = 0.1f, fade = 0.12f, highlightTint = 0xFFE6C8, highlightTintAmount = 0.3f, grain = 0.1f), 0xFFFFE0B2),
        FilterDef("polaroid", "Polaroid", "Film", GradeParams(fade = 0.3f, contrast = -0.1f, temperature = 0.08f, shadowTint = 0x204060, shadowTintAmount = 0.25f, highlightTint = 0xFFF0C8, highlightTintAmount = 0.2f, vignette = 0.25f), 0xFFD7CCC8),
        FilterDef("expired", "Expired", "Film", GradeParams(fade = 0.25f, saturation = -0.2f, tint = 0.12f, gammaB = 0.9f, grain = 0.3f, vignette = 0.3f), 0xFFBCAAA4),
        FilterDef("cinestill", "Cinestill", "Film", GradeParams(contrast = 0.15f, temperature = -0.15f, highlightTint = 0xFF7050, highlightTintAmount = 0.35f, shadowTint = 0x103040, shadowTintAmount = 0.3f, grain = 0.15f), 0xFF4DB6AC),
        // Movie
        FilterDef("teal_orange", "Blockbuster", "Movie", GradeParams(contrast = 0.2f, tealOrange = 0.8f, saturation = 0.05f, vignette = 0.2f), 0xFF26A69A),
        FilterDef("noir_movie", "Thriller", "Movie", GradeParams(contrast = 0.35f, saturation = -0.5f, temperature = -0.15f, shadowTint = 0x002040, shadowTintAmount = 0.35f, vignette = 0.45f), 0xFF37474F),
        FilterDef("matrix", "Matrix", "Movie", GradeParams(contrast = 0.2f, tint = -0.3f, saturation = -0.2f, gammaG = 0.85f, shadowTint = 0x003010, shadowTintAmount = 0.3f), 0xFF2E7D32),
        FilterDef("dune", "Desert", "Movie", GradeParams(temperature = 0.4f, contrast = 0.15f, saturation = -0.1f, highlightTint = 0xFFD090, highlightTintAmount = 0.35f, fade = 0.08f), 0xFFFFA726),
        FilterDef("moody", "Moody", "Movie", GradeParams(contrast = 0.25f, saturation = -0.3f, exposure = -0.2f, shadowTint = 0x203040, shadowTintAmount = 0.3f, vignette = 0.35f), 0xFF455A64),
        FilterDef("dream", "Dreamy", "Movie", GradeParams(brightness = 0.1f, contrast = -0.15f, fade = 0.2f, highlightTint = 0xFFD8F0, highlightTintAmount = 0.35f, saturation = -0.05f), 0xFFE1BEE7),
        FilterDef("cyber", "Cyberpunk", "Movie", GradeParams(contrast = 0.25f, saturation = 0.3f, highlightTint = 0xFF40C0, highlightTintAmount = 0.35f, shadowTint = 0x00A0FF, shadowTintAmount = 0.35f), 0xFFAB47BC),
        // Retro
        FilterDef("vintage", "Vintage", "Retro", GradeParams(sepia = 0.35f, fade = 0.2f, contrast = -0.05f, vignette = 0.35f, grain = 0.2f), 0xFFD7B98E),
        FilterDef("retro70", "70s", "Retro", GradeParams(temperature = 0.3f, tint = 0.08f, saturation = -0.1f, fade = 0.18f, gammaB = 1.12f, grain = 0.2f), 0xFFFFCC80),
        FilterDef("vhs", "VHS", "Retro", GradeParams(saturation = 0.2f, contrast = 0.1f, fade = 0.15f, tint = 0.1f, grain = 0.35f, sharpen = -0.3f), 0xFF7986CB),
        FilterDef("lomo", "Lomo", "Retro", GradeParams(contrast = 0.3f, saturation = 0.25f, vignette = 0.7f, gammaB = 1.1f, gammaR = 0.95f), 0xFFEF5350),
        FilterDef("sepia", "Sepia", "Retro", GradeParams(sepia = 1f, contrast = 0.05f), 0xFFA1887F),
        // B&W
        FilterDef("bw", "Mono", "B&W", GradeParams(mono = 1f), 0xFF9E9E9E),
        FilterDef("noir", "Noir", "B&W", GradeParams(mono = 1f, contrast = 0.4f, vignette = 0.4f), 0xFF424242),
        FilterDef("silver", "Silver", "B&W", GradeParams(mono = 1f, contrast = 0.1f, fade = 0.2f, brightness = 0.05f), 0xFFBDBDBD),
        FilterDef("ink", "Ink", "B&W", GradeParams(mono = 1f, contrast = 0.8f, sharpen = 0.5f), 0xFF212121),
        FilterDef("blue_tone", "Blue tone", "B&W", GradeParams(mono = 1f, shadowTint = 0x20407F, shadowTintAmount = 0.6f, highlightTint = 0xC0D8FF, highlightTintAmount = 0.3f), 0xFF5C6BC0),
        // Style
        FilterDef("pastel", "Pastel", "Style", GradeParams(saturation = -0.25f, brightness = 0.12f, fade = 0.25f, contrast = -0.1f), 0xFFF8BBD0),
        FilterDef("neon", "Neon", "Style", GradeParams(saturation = 0.6f, contrast = 0.3f, exposure = 0.1f, vibrance = 0.4f), 0xFFE040FB),
        FilterDef("golden", "Golden hour", "Style", GradeParams(temperature = 0.45f, highlights = 0.15f, highlightTint = 0xFFC060, highlightTintAmount = 0.4f, saturation = 0.15f), 0xFFFFCA28),
        FilterDef("ocean", "Ocean", "Style", GradeParams(temperature = -0.3f, tint = -0.1f, saturation = 0.2f, shadowTint = 0x004060, shadowTintAmount = 0.35f), 0xFF0288D1),
        FilterDef("forest", "Forest", "Style", GradeParams(tint = -0.2f, saturation = 0.15f, contrast = 0.1f, gammaG = 0.92f, shadowTint = 0x103020, shadowTintAmount = 0.25f), 0xFF388E3C),
        FilterDef("sunset", "Sunset", "Style", GradeParams(temperature = 0.35f, tint = 0.15f, highlightTint = 0xFF8060, highlightTintAmount = 0.4f, shadowTint = 0x402060, shadowTintAmount = 0.25f), 0xFFFF7043),
        FilterDef("matte", "Matte", "Style", GradeParams(fade = 0.35f, contrast = -0.05f, saturation = -0.1f), 0xFF90A4AE),
        FilterDef("hdr", "HDR", "Style", GradeParams(contrast = 0.25f, highlights = -0.35f, shadows = 0.35f, sharpen = 0.5f, vibrance = 0.3f), 0xFF26C6DA),
        FilterDef("dramatic", "Dramatic", "Style", GradeParams(contrast = 0.45f, saturation = -0.15f, exposure = -0.1f, vignette = 0.5f, sharpen = 0.3f), 0xFF5D4037),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String?): FilterDef? = id?.let { byId[it] }
    val categories: List<String> = all.map { it.category }.distinct()
}
