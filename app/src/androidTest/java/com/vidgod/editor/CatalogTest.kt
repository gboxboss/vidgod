package com.vidgod.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidgod.editor.engine.text.Fonts
import com.vidgod.editor.engine.text.StickerRenderer
import com.vidgod.editor.engine.text.TextRenderer
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.TextStyle
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fonts and stickers of the catalogs load and draw. */
@RunWith(AndroidJUnit4::class)
class CatalogTest {

    @Test
    fun everyFontLoadsAndRenders() {
        val missing = Fonts.all.filter { f -> f.asset != null && runCatching { T.app.assets.open(f.asset!!).close() }.isFailure }
        assertTrue("font files missing from the APK: ${missing.map { it.asset }}", missing.isEmpty())
        val tiles = Fonts.all.map { f ->
            val clip = TextClip(text = "VidGod Aa 123", startUs = 0, style = TextStyle(fontId = f.id, size = 0.08f))
            f.name to TextRenderer.render(T.app, clip, 1080)
        }
        Inspect.grid("catalog_fonts", tiles, cols = 6, cell = 240)
        tiles.forEach { (name, b) -> assertTrue("$name rendered nothing", b.width > 20 && b.height > 10) }
    }

    @Test
    fun everyTextStyleRenders() {
        val styles = listOf(
            "stroke" to TextStyle(strokeWidth = 0.15f),
            "label" to TextStyle(backgroundAlpha = 0.8f, backgroundColor = 0xFFFF2D55.toInt()),
            "shadow" to TextStyle(shadowRadius = 0.2f),
            "gradient" to TextStyle(gradientColors = listOf(0xFFFFD000.toInt(), 0xFFFF2D55.toInt())),
            "caps+spacing" to TextStyle(allCaps = true, letterSpacing = 0.2f),
            "karaoke" to TextStyle(highlightColor = 0xFFFFD000.toInt()),
        )
        val tiles = styles.map { (name, st) ->
            name to TextRenderer.render(T.app, TextClip(text = "Hello TikTok\nsecond line", startUs = 0, style = st), 1080, highlightWord = 1)
        }
        Inspect.grid("catalog_text_styles", tiles, cols = 3, cell = 320)
        tiles.forEach { (name, b) -> assertTrue("$name rendered nothing", b.width > 20 && b.height > 10) }
    }

    @Test
    fun everyStickerRenders() {
        val shapes = StickerRenderer.shapes.map { s ->
            s.name to StickerRenderer.render(T.app, StickerClip(kind = StickerKind.SHAPE, content = s.id, startUs = 0), 1080)
        }
        val emoji = StickerRenderer.emojiPacks.map { (pack, list) ->
            pack to StickerRenderer.render(T.app, StickerClip(kind = StickerKind.EMOJI, content = list.first(), startUs = 0), 1080)
        }
        Inspect.grid("catalog_stickers", shapes + emoji, cols = 8, cell = 140)
        (shapes + emoji).forEach { (name, b) ->
            assertTrue("$name rendered nothing", b.width > 10 && b.height > 10)
            assertTrue("$name is blank", Inspect.stats(b).second > 2.0)
        }
    }
}
