package com.vidgod.editor.engine.text

import android.content.Context
import android.graphics.Typeface

data class FontDef(
    val id: String,
    val name: String,
    val asset: String? = null,
    val family: String? = null,
    val style: Int = Typeface.NORMAL,
    /** Font variation settings for variable fonts, e.g. `'wght' 800`. */
    val variation: String? = null,
)

/** Built-in system fonts plus bundled Google Fonts (see assets/fonts/LICENSES.txt). */
object Fonts {
    val all = listOf(
        FontDef("sans_bold", "Classic", family = "sans-serif", style = Typeface.BOLD),
        FontDef("sans", "Regular", family = "sans-serif"),
        FontDef("sans_black", "Heavy", family = "sans-serif-black", style = Typeface.BOLD),
        FontDef("condensed", "Condensed", family = "sans-serif-condensed", style = Typeface.BOLD),
        FontDef("poppins_bold", "Poppins", asset = "fonts/poppinsbold.ttf"),
        FontDef("poppins_black", "Poppins Black", asset = "fonts/poppinsblack.ttf"),
        FontDef("montserrat", "Montserrat", asset = "fonts/montserrat.ttf", variation = "'wght' 800"),
        FontDef("anton", "Anton", asset = "fonts/antonregular.ttf"),
        FontDef("bebas", "Bebas Neue", asset = "fonts/bebasneueregular.ttf"),
        FontDef("oswald", "Oswald", asset = "fonts/oswald.ttf", variation = "'wght' 600"),
        FontDef("archivo", "Archivo Black", asset = "fonts/archivoblackregular.ttf"),
        FontDef("bangers", "Bangers", asset = "fonts/bangersregular.ttf"),
        FontDef("luckiest", "Luckiest Guy", asset = "fonts/luckiestguyregular.ttf"),
        FontDef("titan", "Titan One", asset = "fonts/titanoneregular.ttf"),
        FontDef("bungee", "Bungee", asset = "fonts/bungeeregular.ttf"),
        FontDef("fredoka", "Fredoka", asset = "fonts/fredoka.ttf", variation = "'wght' 600"),
        FontDef("chewy", "Chewy", asset = "fonts/chewyregular.ttf"),
        FontDef("comfortaa", "Comfortaa", asset = "fonts/comfortaa.ttf", variation = "'wght' 700"),
        FontDef("righteous", "Righteous", asset = "fonts/righteousregular.ttf"),
        FontDef("rubik_mono", "Rubik Mono", asset = "fonts/rubikmonooneregular.ttf"),
        FontDef("marker", "Marker", asset = "fonts/permanentmarkerregular.ttf"),
        FontDef("caveat", "Caveat", asset = "fonts/caveat.ttf", variation = "'wght' 700"),
        FontDef("shadows", "Handwritten", asset = "fonts/shadowsintolight.ttf"),
        FontDef("amatic", "Amatic", asset = "fonts/amaticscbold.ttf"),
        FontDef("pacifico", "Pacifico", asset = "fonts/pacificoregular.ttf"),
        FontDef("lobster", "Lobster", asset = "fonts/lobsterregular.ttf"),
        FontDef("dancing", "Dancing Script", asset = "fonts/dancingscript.ttf", variation = "'wght' 700"),
        FontDef("satisfy", "Satisfy", asset = "fonts/satisfyregular.ttf"),
        FontDef("greatvibes", "Great Vibes", asset = "fonts/greatvibesregular.ttf"),
        FontDef("playfair", "Playfair", asset = "fonts/playfairdisplay.ttf", variation = "'wght' 800"),
        FontDef("abril", "Abril Fatface", asset = "fonts/abrilfatfaceregular.ttf"),
        FontDef("serif", "Serif", family = "serif", style = Typeface.BOLD),
        FontDef("typewriter", "Typewriter", asset = "fonts/specialeliteregular.ttf"),
        FontDef("mono", "Mono", family = "monospace", style = Typeface.BOLD),
        FontDef("pixel", "Pixel", asset = "fonts/pressstart2pregular.ttf"),
        FontDef("blackops", "Black Ops", asset = "fonts/blackopsoneregular.ttf"),
        FontDef("monoton", "Neon", asset = "fonts/monotonregular.ttf"),
        FontDef("creepster", "Horror", asset = "fonts/creepsterregular.ttf"),
        FontDef("casual", "Casual", family = "casual"),
        FontDef("cursive", "Cursive", family = "cursive"),
    )

    private val byId = all.associateBy { it.id }
    private val cache = HashMap<String, Typeface>()

    fun get(id: String): FontDef = byId[id] ?: all.first()

    @Synchronized
    fun typeface(context: Context, id: String): Typeface {
        cache[id]?.let { return it }
        val def = get(id)
        val tf = runCatching {
            if (def.asset != null) {
                Typeface.createFromAsset(context.assets, def.asset)
            } else {
                Typeface.create(def.family ?: "sans-serif", def.style)
            }
        }.getOrDefault(Typeface.DEFAULT_BOLD)
        cache[id] = tf
        return tf
    }
}
