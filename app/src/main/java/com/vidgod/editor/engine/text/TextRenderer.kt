package com.vidgod.editor.engine.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ScaleXSpan
import com.vidgod.editor.model.TextAlign
import com.vidgod.editor.model.TextClip
import kotlin.math.ceil
import kotlin.math.max

/** Renders [TextClip]s to bitmaps sized for a canvas of a given width. */
object TextRenderer {

    /** Characters that should be visible for a typewriter [reveal] fraction. */
    fun visibleChars(text: String, reveal: Float): Int =
        if (reveal >= 1f) text.length else ceil(text.length * reveal.coerceIn(0f, 1f)).toInt()

    /** Index of the caption word being spoken at [localUs], or -1. */
    fun activeWord(clip: TextClip, localUs: Long): Int {
        if (clip.words.isEmpty() || clip.style.highlightColor == 0) return -1
        return clip.words.indexOfFirst { localUs >= it.startUs && localUs < it.endUs }
    }

    fun render(
        context: Context,
        clip: TextClip,
        canvasWidth: Int,
        visibleChars: Int = Int.MAX_VALUE,
        highlightWord: Int = -1,
    ): Bitmap {
        val style = clip.style
        val raw = if (style.allCaps) clip.text.uppercase() else clip.text
        val shown = raw.take(visibleChars.coerceAtLeast(0)).ifEmpty { " " }
        val textSize = (style.size * canvasWidth).coerceIn(6f, 800f)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.textSize = textSize
            val base = Fonts.typeface(context, style.fontId)
            val wanted = (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
            typeface = if (wanted != 0) Typeface.create(base, wanted or base.style) else base
            Fonts.get(style.fontId).variation?.let { fontVariationSettings = it }
            letterSpacing = style.letterSpacing
            isUnderlineText = style.underline
            color = style.color
        }
        val align = when (style.align) {
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val maxWidth = (canvasWidth * 0.92f).toInt().coerceAtLeast(10)
        val natural = shown.split('\n').maxOf { paint.measureText(it) }
        val layoutWidth = ceil(natural.coerceAtMost(maxWidth.toFloat())).toInt().coerceAtLeast(1)

        fun layoutFor(cs: CharSequence, p: TextPaint) = StaticLayout.Builder.obtain(cs, 0, cs.length, p, layoutWidth)
            .setAlignment(align)
            .setLineSpacing(0f, style.lineSpacing.coerceIn(0.5f, 3f))
            .setIncludePad(false)
            .build()

        val spanned: CharSequence = if (highlightWord >= 0 && clip.words.isNotEmpty()) {
            highlightSpan(shown, clip, highlightWord, style.highlightColor)
        } else {
            shown
        }
        val fillLayout = layoutFor(spanned, paint)
        val strokeW = style.strokeWidth * textSize
        val shadowR = style.shadowRadius * textSize * 0.3f
        val bgPad = if (style.backgroundAlpha > 0f) textSize * 0.28f else 0f
        val pad = (strokeW + shadowR * 2f + bgPad + textSize * 0.08f).toInt() + 2
        val w = layoutWidth + pad * 2
        val h = fillLayout.height + pad * 2
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(pad.toFloat(), pad.toFloat())

        if (style.backgroundAlpha > 0f) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.backgroundColor
                alpha = (style.backgroundAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            val r = textSize * style.backgroundCorner
            for (i in 0 until fillLayout.lineCount) {
                val left = fillLayout.getLineLeft(i)
                val right = fillLayout.getLineRight(i)
                if (right - left < 1f) continue
                val top = fillLayout.getLineTop(i).toFloat()
                val bottom = fillLayout.getLineBottom(i).toFloat()
                canvas.drawRoundRect(RectF(left - bgPad, top - bgPad * 0.35f, right + bgPad, bottom + bgPad * 0.35f), r, r, bgPaint)
            }
        }
        if (strokeW > 0f) {
            val strokePaint = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = strokeW * 2f
                strokeJoin = Paint.Join.ROUND
                strokeMiter = 10f
                color = style.strokeColor
                if (shadowR > 0f) setShadowLayer(shadowR, 0f, shadowR * 0.4f, style.shadowColor)
            }
            layoutFor(shown, strokePaint).draw(canvas)
        }
        if (shadowR > 0f && strokeW <= 0f) paint.setShadowLayer(shadowR, 0f, shadowR * 0.4f, style.shadowColor)
        if (style.gradientColors.size >= 2) {
            paint.shader = LinearGradient(
                0f, 0f, layoutWidth.toFloat(), fillLayout.height.toFloat(),
                style.gradientColors.toIntArray(), null, Shader.TileMode.CLAMP,
            )
        }
        fillLayout.draw(canvas)
        if (style.opacity < 1f) {
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { alpha = (style.opacity * 255).toInt() })
            bmp.recycle()
            return out
        }
        return bmp
    }

    private fun highlightSpan(text: String, clip: TextClip, wordIndex: Int, color: Int): CharSequence {
        val s = SpannableString(text)
        var searchFrom = 0
        val lower = text.lowercase()
        clip.words.forEachIndexed { i, w ->
            val idx = lower.indexOf(w.word.lowercase(), searchFrom)
            if (idx >= 0) {
                if (i == wordIndex) {
                    s.setSpan(ForegroundColorSpan(color), idx, idx + w.word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ScaleXSpan(1.0f), idx, idx + w.word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                searchFrom = idx + w.word.length
            }
        }
        return s
    }
}
