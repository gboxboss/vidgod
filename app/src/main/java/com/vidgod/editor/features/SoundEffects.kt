package com.vidgod.editor.features

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in sound effects, synthesised on the device (no copyrighted samples).
 * Each effect is rendered once to a WAV file and cached.
 */
object SoundEffects {
    private const val RATE = 44_100

    data class Sfx(val id: String, val name: String, val emoji: String, val seconds: Float, val render: (FloatArray) -> Unit)

    private fun t(i: Int) = i.toFloat() / RATE
    private val rnd = Random(7)
    private fun noise() = rnd.nextFloat() * 2f - 1f

    /** One-pole low-pass state helper. */
    private class Lp(var a: Float) { var y = 0f; fun f(x: Float): Float { y += a * (x - y); return y } }

    val all: List<Sfx> = listOf(
        Sfx("whoosh", "Whoosh", "💨", 0.7f) { b ->
            val lp = Lp(0.05f); val hp = Lp(0.02f)
            for (i in b.indices) {
                val p = i.toFloat() / b.size
                lp.a = 0.02f + 0.25f * p
                val x = lp.f(noise())
                val y = x - hp.f(x)
                b[i] = y * sin(PI.toFloat() * p) * 2.2f
            }
        },
        Sfx("swoosh", "Swoosh down", "🌀", 0.6f) { b ->
            val lp = Lp(0.3f)
            for (i in b.indices) {
                val p = i.toFloat() / b.size
                lp.a = 0.3f - 0.27f * p
                b[i] = lp.f(noise()) * sin(PI.toFloat() * p) * 2f
            }
        },
        Sfx("pop", "Pop", "🫧", 0.18f) { b ->
            var ph = 0.0
            for (i in b.indices) {
                val f = 150 + 650 * exp(-t(i) * 40.0)
                ph += 2 * PI * f / RATE
                b[i] = (sin(ph) * exp(-t(i) * 28.0)).toFloat() * 0.9f
            }
        },
        Sfx("ding", "Ding", "🔔", 1.6f) { b ->
            for (i in b.indices) {
                val x = t(i)
                b[i] = ((sin(2 * PI * 1318.5 * x) * 0.6 + sin(2 * PI * 2637.0 * x) * 0.25 + sin(2 * PI * 3955.5 * x) * 0.1) * exp(-x * 3.2)).toFloat()
            }
        },
        Sfx("success", "Success", "✅", 0.9f) { b ->
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
            for (i in b.indices) {
                val x = t(i)
                val n = (x / 0.15).toInt().coerceAtMost(notes.size - 1)
                val local = x - n * 0.15
                b[i] = (sin(2 * PI * notes[n] * x) * exp(-local * 6.0) * 0.6).toFloat()
            }
        },
        Sfx("error", "Error", "❌", 0.5f) { b ->
            for (i in b.indices) {
                val x = t(i)
                val sq = if (sin(2 * PI * 150 * x) > 0) 1f else -1f
                b[i] = sq * 0.35f * (if (x < 0.2 || x > 0.27) 1f else 0f) * (1f - x / 0.5f).coerceAtLeast(0f)
            }
        },
        Sfx("boom", "Boom", "💥", 1.8f) { b ->
            val lp = Lp(0.02f); var ph = 0.0
            for (i in b.indices) {
                val x = t(i)
                val f = 35 + 50 * exp(-x * 6.0)
                ph += 2 * PI * f / RATE
                b[i] = ((sin(ph) * 0.9 + lp.f(noise()) * 1.5) * exp(-x * 2.5)).toFloat()
            }
        },
        Sfx("laser", "Laser", "🔫", 0.45f) { b ->
            var ph = 0.0
            for (i in b.indices) {
                val x = t(i)
                val f = 1800 * exp(-x * 6.0) + 120
                ph += 2 * PI * f / RATE
                b[i] = ((if (sin(ph) > 0) 0.5 else -0.5) * exp(-x * 4.0)).toFloat()
            }
        },
        Sfx("riser", "Riser", "📈", 2.5f) { b ->
            var ph = 0.0
            val lp = Lp(0.1f)
            for (i in b.indices) {
                val p = i.toFloat() / b.size
                val f = 200 + 1800 * p * p
                ph += 2 * PI * f / RATE
                b[i] = ((sin(ph) * 0.4 + lp.f(noise()) * 0.6) * p * p).toFloat() * 1.2f
            }
        },
        Sfx("heartbeat", "Heartbeat", "💓", 1.0f) { b ->
            for (i in b.indices) {
                val x = t(i)
                fun thump(at: Double) = if (x >= at) sin(2 * PI * 55 * (x - at)) * exp(-(x - at) * 18.0) else 0.0
                b[i] = ((thump(0.0) + thump(0.22) * 0.8)).toFloat()
            }
        },
        Sfx("click", "Click", "🖱️", 0.06f) { b ->
            for (i in b.indices) b[i] = noise() * exp(-t(i) * 120.0).toFloat()
        },
        Sfx("shutter", "Camera", "📸", 0.35f) { b ->
            for (i in b.indices) {
                val x = t(i)
                fun burst(at: Double) = if (x >= at) exp(-(x - at) * 90.0) else 0.0
                b[i] = noise() * (burst(0.0) + burst(0.12) * 0.8).toFloat()
            }
        },
        Sfx("typing", "Typing", "⌨️", 1.6f) { b ->
            val hits = (0 until 12).map { 0.05 + it * 0.12 + rnd.nextDouble() * 0.04 }
            for (i in b.indices) {
                val x = t(i)
                var v = 0.0
                for (h in hits) if (x >= h && x < h + 0.03) v += exp(-(x - h) * 160.0)
                b[i] = noise() * v.toFloat() * 0.7f
            }
        },
        Sfx("glitch", "Glitch", "👾", 0.6f) { b ->
            var hold = 0f
            for (i in b.indices) {
                if (i % 900 == 0) hold = if (rnd.nextFloat() > 0.4f) noise() else 0f
                val f = if ((i / 1800) % 2 == 0) 440.0 else 880.0
                b[i] = (hold * 0.5f + (if (sin(2 * PI * f * t(i)) > 0) 0.25f else -0.25f) * abs(hold))
            }
        },
        Sfx("cash", "Cha-ching", "💰", 1.2f) { b ->
            for (i in b.indices) {
                val x = t(i)
                val click = if (x < 0.05) noise() * exp(-x * 80.0).toFloat() else 0f
                val ring = if (x >= 0.08) ((sin(2 * PI * 2093.0 * x) * 0.5 + sin(2 * PI * 3136.0 * x) * 0.3) * exp(-(x - 0.08) * 4.0)).toFloat() else 0f
                b[i] = click + ring
            }
        },
        Sfx("bass_drop", "Bass drop", "🔊", 1.4f) { b ->
            var ph = 0.0
            for (i in b.indices) {
                val x = t(i)
                val f = 40 + 180 * exp(-x * 3.0)
                ph += 2 * PI * f / RATE
                b[i] = (sin(ph) * (1 - exp(-x * 30.0)) * exp(-x * 1.2) * 0.95).toFloat()
            }
        },
    )

    /** Returns (rendering if needed) the WAV file of [sfx]. */
    suspend fun file(context: Context, sfx: Sfx): File = withContext(Dispatchers.Default) {
        val dir = File(context.filesDir, "sfx").apply { mkdirs() }
        val f = File(dir, "${sfx.id}.wav")
        if (f.exists() && f.length() > 44) return@withContext f
        val buf = FloatArray((sfx.seconds * RATE).toInt())
        sfx.render(buf)
        // Normalise to -1 dBFS with a short fade-out to avoid clicks.
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1e-4f)
        val gain = 0.89f / peak
        val fade = (RATE * 0.01f).toInt()
        for (i in buf.indices) {
            var v = buf[i] * gain
            if (i > buf.size - fade) v *= (buf.size - i).toFloat() / fade
            buf[i] = v
        }
        writeWav(f, buf, RATE)
        f
    }

    fun writeWav(f: File, samples: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        f.outputStream().use { it.write(header.array()); it.write(data.array()) }
    }
}
