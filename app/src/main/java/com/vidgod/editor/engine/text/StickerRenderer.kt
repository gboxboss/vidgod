package com.vidgod.editor.engine.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.StickerKind
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class ShapeDef(val id: String, val name: String, val color: Int)

object StickerRenderer {

    val shapes = listOf(
        ShapeDef("heart", "Heart", 0xFFFF3B6B.toInt()),
        ShapeDef("star", "Star", 0xFFFFD000.toInt()),
        ShapeDef("arrow_right", "Arrow", 0xFFFF2D55.toInt()),
        ShapeDef("arrow_down", "Arrow down", 0xFFFF2D55.toInt()),
        ShapeDef("arrow_curve", "Curved arrow", 0xFFFFFFFF.toInt()),
        ShapeDef("circle", "Circle", 0xFFFF2D55.toInt()),
        ShapeDef("ring", "Ring", 0xFFFF2D55.toInt()),
        ShapeDef("bubble", "Speech", 0xFFFFFFFF.toInt()),
        ShapeDef("burst", "Burst", 0xFFFFD000.toInt()),
        ShapeDef("lightning", "Bolt", 0xFFFFE000.toInt()),
        ShapeDef("check", "Check", 0xFF34C759.toInt()),
        ShapeDef("cross", "Cross", 0xFFFF3B30.toInt()),
        ShapeDef("sparkle", "Sparkle", 0xFFFFFFFF.toInt()),
        ShapeDef("square", "Square", 0xFF0A84FF.toInt()),
        ShapeDef("subscribe", "Follow", 0xFFFF2D55.toInt()),
        ShapeDef("like", "Like", 0xFFFF2D55.toInt()),
    )

    val emojiPacks: List<Pair<String, List<String>>> = listOf(
        "Smileys" to "😀 😂 🤣 😍 🥰 😘 😎 🤩 🥳 😜 🤪 😏 😴 🤯 😱 😭 😡 🤬 🥺 😇 🤔 🤫 🙄 😬 🤡 👻 💀 👽 🤖 😺".split(" "),
        "Gestures" to "👍 👎 👏 🙌 🙏 💪 👉 👈 👆 👇 ✌️ 🤞 🤟 🤘 👌 🤙 👋 ✋ 🖐️ 👊 🫶 🫰 ✍️ 💅 👀 👅 👄 🧠 🫀 🦵".split(" "),
        "Hearts" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💋 💌 🌹 💐 🌸 🌺 🌻 🌷 🥀 🌼".split(" "),
        "Party" to "🎉 🎊 🎈 🎁 🎂 🍾 🥂 🍻 🎆 🎇 ✨ 🌟 ⭐ 💫 🔥 💥 💯 🏆 🥇 🎯 🎮 🎤 🎧 🎵 🎶 🎸 🥁 🎹 📸 🎬".split(" "),
        "Nature" to "☀️ 🌙 ⭐ 🌈 ☁️ ⛈️ ❄️ 🌊 🌴 🌵 🍀 🍁 🍂 🌍 🌋 🐶 🐱 🐻 🐼 🦁 🐯 🦊 🐸 🐵 🦄 🐝 🦋 🐬 🐳 🦖".split(" "),
        "Food" to "🍕 🍔 🍟 🌭 🌮 🌯 🍣 🍜 🍩 🍪 🍫 🍬 🍭 🍦 🍰 🧁 🍓 🍒 🍑 🍉 🍍 🥑 🍋 🍎 ☕ 🧋 🥤 🍹 🍸 🍷".split(" "),
        "Objects" to "💰 💸 💎 👑 💡 📱 💻 ⌚ 📷 🎥 📌 📍 🔔 📢 📣 💬 💭 🗯️ ❗ ❓ ⚠️ 🚫 ✅ ❌ ➡️ ⬅️ ⬆️ ⬇️ 🔝 🆕".split(" "),
        "Travel" to "✈️ 🚗 🏎️ 🚀 🛸 🚲 🛵 🚢 🏖️ 🏝️ 🏔️ 🗽 🗼 🏰 🎡 🎢 🌆 🌃 🌉 🏙️ 🗺️ 🧳 ⛺ 🛶 🚁 🚂 🚇 🚦 🏁 🎌".split(" "),
    )

    fun render(context: Context, sticker: StickerClip, canvasWidth: Int): Bitmap {
        val size = (sticker.size * canvasWidth).roundToInt().coerceIn(16, 2048)
        val bmp = when (sticker.kind) {
            StickerKind.EMOJI -> renderEmoji(sticker.content, size)
            StickerKind.IMAGE -> renderImage(context, sticker.content, size)
            StickerKind.SHAPE -> renderShape(sticker.content, size, sticker.tint)
        }
        if (sticker.transform.flipH || sticker.transform.flipV) {
            val m = Matrix().apply {
                preScale(if (sticker.transform.flipH) -1f else 1f, if (sticker.transform.flipV) -1f else 1f)
            }
            val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (flipped != bmp) bmp.recycle()
            return applyOpacity(flipped, sticker.opacity)
        }
        return applyOpacity(bmp, sticker.opacity)
    }

    private fun applyOpacity(b: Bitmap, opacity: Float): Bitmap {
        if (opacity >= 0.999f) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { alpha = (opacity.coerceIn(0f, 1f) * 255).toInt() })
        b.recycle()
        return out
    }

    private fun renderEmoji(emoji: String, size: Int): Bitmap {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.8f
            textAlign = Paint.Align.CENTER
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fm = p.fontMetrics
        val y = size / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(emoji, size / 2f, y, p)
        return bmp
    }

    private fun renderImage(context: Context, uri: String, size: Int): Bitmap {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = runCatching {
            context.contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull() ?: return renderEmoji("🖼️", size)
        val scale = size.toFloat() / max(decoded.width, decoded.height)
        val w = max(1, (decoded.width * scale).roundToInt())
        val h = max(1, (decoded.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
        if (scaled != decoded) decoded.recycle()
        return if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun renderShape(id: String, size: Int, tint: Int): Bitmap {
        val def = shapes.firstOrNull { it.id == id } ?: shapes.first()
        val color = if (tint != 0) tint else def.color
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.07f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK; alpha = 70; style = Paint.Style.STROKE; strokeWidth = s * 0.02f
        }
        when (def.id) {
            "heart" -> {
                val p = Path()
                p.moveTo(s * 0.5f, s * 0.86f)
                p.cubicTo(s * 0.1f, s * 0.6f, s * 0.02f, s * 0.32f, s * 0.24f, s * 0.2f)
                p.cubicTo(s * 0.38f, s * 0.12f, s * 0.48f, s * 0.22f, s * 0.5f, s * 0.3f)
                p.cubicTo(s * 0.52f, s * 0.22f, s * 0.62f, s * 0.12f, s * 0.76f, s * 0.2f)
                p.cubicTo(s * 0.98f, s * 0.32f, s * 0.9f, s * 0.6f, s * 0.5f, s * 0.86f)
                p.close()
                c.drawPath(p, fill); c.drawPath(p, outline)
            }
            "star", "burst", "sparkle" -> {
                val points = when (def.id) { "star" -> 5; "burst" -> 12; else -> 4 }
                val inner = when (def.id) { "star" -> 0.4f; "burst" -> 0.72f; else -> 0.22f }
                val p = Path()
                for (i in 0 until points * 2) {
                    val r = if (i % 2 == 0) s * 0.46f else s * 0.46f * inner
                    val a = Math.PI * i / points - Math.PI / 2
                    val x = s / 2 + (r * cos(a)).toFloat()
                    val y = s / 2 + (r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, fill); c.drawPath(p, outline)
            }
            "arrow_right", "arrow_down" -> {
                c.save()
                if (def.id == "arrow_down") c.rotate(90f, s / 2, s / 2)
                val p = Path()
                p.moveTo(s * 0.08f, s * 0.4f); p.lineTo(s * 0.58f, s * 0.4f); p.lineTo(s * 0.58f, s * 0.22f)
                p.lineTo(s * 0.94f, s * 0.5f); p.lineTo(s * 0.58f, s * 0.78f); p.lineTo(s * 0.58f, s * 0.6f)
                p.lineTo(s * 0.08f, s * 0.6f); p.close()
                c.drawPath(p, fill); c.drawPath(p, outline)
                c.restore()
            }
            "arrow_curve" -> {
                val p = Path()
                p.moveTo(s * 0.15f, s * 0.8f)
                p.quadTo(s * 0.2f, s * 0.25f, s * 0.75f, s * 0.3f)
                c.drawPath(p, stroke)
                val head = Path()
                head.moveTo(s * 0.62f, s * 0.15f); head.lineTo(s * 0.88f, s * 0.31f); head.lineTo(s * 0.64f, s * 0.47f)
                c.drawPath(head, stroke)
            }
            "circle" -> c.drawCircle(s / 2, s / 2, s * 0.44f, fill)
            "ring" -> c.drawCircle(s / 2, s / 2, s * 0.42f, stroke)
            "square" -> c.drawRoundRect(RectF(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f), s * 0.12f, s * 0.12f, fill)
            "bubble" -> {
                val p = Path()
                p.addRoundRect(RectF(s * 0.05f, s * 0.1f, s * 0.95f, s * 0.7f), s * 0.18f, s * 0.18f, Path.Direction.CW)
                p.moveTo(s * 0.25f, s * 0.65f); p.lineTo(s * 0.18f, s * 0.92f); p.lineTo(s * 0.45f, s * 0.68f); p.close()
                c.drawPath(p, fill)
                c.drawPath(p, outline)
            }
            "lightning" -> {
                val p = Path()
                p.moveTo(s * 0.58f, s * 0.04f); p.lineTo(s * 0.2f, s * 0.56f); p.lineTo(s * 0.46f, s * 0.56f)
                p.lineTo(s * 0.36f, s * 0.96f); p.lineTo(s * 0.8f, s * 0.4f); p.lineTo(s * 0.54f, s * 0.4f); p.close()
                c.drawPath(p, fill); c.drawPath(p, outline)
            }
            "check" -> {
                val p = Path()
                p.moveTo(s * 0.18f, s * 0.52f); p.lineTo(s * 0.42f, s * 0.76f); p.lineTo(s * 0.84f, s * 0.26f)
                c.drawPath(p, stroke.apply { strokeWidth = s * 0.12f })
            }
            "cross" -> {
                val st = stroke.apply { strokeWidth = s * 0.12f }
                c.drawLine(s * 0.22f, s * 0.22f, s * 0.78f, s * 0.78f, st)
                c.drawLine(s * 0.78f, s * 0.22f, s * 0.22f, s * 0.78f, st)
            }
            "subscribe", "like" -> {
                val rect = RectF(s * 0.02f, s * 0.3f, s * 0.98f, s * 0.7f)
                c.drawRoundRect(rect, s * 0.2f, s * 0.2f, fill)
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    textSize = s * 0.17f
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                val label = if (def.id == "subscribe") "+ FOLLOW" else "♥ LIKE"
                c.drawText(label, s / 2, s * 0.5f - (tp.ascent() + tp.descent()) / 2, tp)
            }
        }
        return bmp
    }

    /** Smallest square that fully contains a sticker; used for hit testing in the editor. */
    fun aspect(bitmap: Bitmap): Float = bitmap.width.toFloat() / max(1, min(bitmap.height, Int.MAX_VALUE))
}
