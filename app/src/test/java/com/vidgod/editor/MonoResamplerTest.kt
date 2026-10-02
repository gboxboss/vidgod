package com.vidgod.editor

import com.vidgod.editor.media.MonoResampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MonoResamplerTest {

    private fun tone(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { i -> (0.5 * sin(2 * PI * freq * i / rate)).toFloat() }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int, chunk: Int = input.size): ShortArray {
        val r = MonoResampler(inRate, outRate)
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            r.process(input.copyOfRange(i, i + n), n) { buf, count -> for (k in 0 until count) out.add(buf[k]) }
            i += n
        }
        return out.toShortArray()
    }

    /** RMS of the middle part (filter warm-up excluded), relative to full scale. */
    private fun rms(s: ShortArray): Double {
        val mid = s.copyOfRange(s.size / 4, s.size * 3 / 4)
        return sqrt(mid.sumOf { (it / 32767.0) * (it / 32767.0) } / mid.size)
    }

    @Test
    fun keepsSpeechFrequencies() {
        val out = resample(tone(1000.0, 48_000, 1.0), 48_000, 16_000)
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        // A sine of amplitude 0.5 has an RMS of 0.354.
        assertEquals(0.354, rms(out), 0.02)
    }

    @Test
    fun removesFrequenciesAboveTheNewNyquist() {
        // 12 kHz cannot be represented at 16 kHz; plain decimation would fold it to 4 kHz.
        val out = resample(tone(12_000.0, 48_000, 1.0), 48_000, 16_000)
        assertTrue("aliasing not removed: rms=${rms(out)}", rms(out) < 0.01)
    }

    @Test
    fun chunkedInputGivesTheSameOutput() {
        val input = tone(440.0, 44_100, 0.5)
        val whole = resample(input, 44_100, 16_000)
        val chunked = resample(input, 44_100, 16_000, chunk = 1000)
        assertArrayEquals(whole, chunked)
    }

    @Test
    fun upsamplingPassesThrough() {
        val out = resample(tone(500.0, 8_000, 1.0), 8_000, 16_000)
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        assertEquals(0.354, rms(out), 0.02)
    }
}
