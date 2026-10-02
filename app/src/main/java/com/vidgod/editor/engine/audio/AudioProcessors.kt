package com.vidgod.editor.engine.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.vidgod.editor.model.VoiceFx
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Base class for processors that work on float samples. Accepts 16-bit and float PCM and
 * outputs the same encoding. Tracks the stream position so effects can depend on time.
 */
@UnstableApi
abstract class FloatAudioProcessor : BaseAudioProcessor() {
    private var scratch = FloatArray(0)
    /** Position, in frames, of the next frame to be processed (relative to the item start). */
    protected var framePosition = 0L
    protected var channels = 2
    protected var sampleRate = 44100

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        onFormat(sampleRate, channels)
        return inputAudioFormat
    }

    protected open fun onFormat(sampleRate: Int, channels: Int) {}

    /** Processes [frames] interleaved frames in place. */
    protected abstract fun process(samples: FloatArray, frames: Int)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val count = remaining / bytesPerSample
        if (scratch.size < count) scratch = FloatArray(count)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (isFloat) {
            for (i in 0 until count) scratch[i] = src.getFloat(src.position() + i * 4)
        } else {
            for (i in 0 until count) scratch[i] = src.getShort(src.position() + i * 2) / 32768f
        }
        inputBuffer.position(inputBuffer.limit())
        val frames = count / channels
        process(scratch, frames)
        framePosition += frames
        val out = replaceOutputBuffer(count * bytesPerSample)
        if (isFloat) {
            for (i in 0 until count) out.putFloat(scratch[i])
        } else {
            for (i in 0 until count) {
                val v = (scratch[i] * 32767f).coerceIn(-32768f, 32767f)
                out.putShort(v.toInt().toShort())
            }
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framePosition = streamMetadata.positionOffsetUs * sampleRate / 1_000_000L
        onReset(false)
    }

    override fun onReset() {
        framePosition = 0
        onReset(true)
    }

    protected open fun onReset(full: Boolean) {}
}

/** Gain as a function of time (microseconds since the item start). */
fun interface GainCurve {
    fun gainAt(timeUs: Long): Float
}

/** Volume, fades and volume keyframes. Gain may exceed 1 (soft clipped). */
@UnstableApi
class EnvelopeAudioProcessor(private val curve: GainCurve) : FloatAudioProcessor() {
    override fun process(samples: FloatArray, frames: Int) {
        var i = 0
        val ch = channels
        // Evaluate the curve every 64 frames and interpolate.
        var f = 0
        while (f < frames) {
            val block = minOf(64, frames - f)
            val g0 = curve.gainAt((framePosition + f) * 1_000_000L / sampleRate)
            val g1 = curve.gainAt((framePosition + f + block) * 1_000_000L / sampleRate)
            for (k in 0 until block) {
                val g = g0 + (g1 - g0) * (k.toFloat() / block)
                for (c in 0 until ch) {
                    val v = samples[i] * g
                    samples[i] = if (g > 1f) softClip(v) else v
                    i++
                }
            }
            f += block
        }
    }

    private fun softClip(x: Float): Float = if (abs(x) < 0.9f) x else tanh(x.toDouble()).toFloat()
}

/** Second-order IIR filter (RBJ cookbook). */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = FloatArray(8); private var x2 = FloatArray(8); private var y1 = FloatArray(8); private var y2 = FloatArray(8)

    fun lowPass(fs: Int, f0: Float, q: Float = 0.707f) = set(fs, f0, q, 0)
    fun highPass(fs: Int, f0: Float, q: Float = 0.707f) = set(fs, f0, q, 1)
    fun bandPass(fs: Int, f0: Float, q: Float) = set(fs, f0, q, 2)

    private fun set(fs: Int, f0: Float, q: Float, type: Int): Biquad {
        val w0 = 2.0 * PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cw = cos(w0)
        val a0: Double
        when (type) {
            0 -> { b0 = ((1 - cw) / 2).toFloat(); b1 = (1 - cw).toFloat(); b2 = b0 }
            1 -> { b0 = ((1 + cw) / 2).toFloat(); b1 = (-(1 + cw)).toFloat(); b2 = b0 }
            else -> { b0 = alpha.toFloat(); b1 = 0f; b2 = (-alpha).toFloat() }
        }
        a0 = 1 + alpha
        a1 = (-2 * cw / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
        b0 = (b0 / a0).toFloat(); b1 = (b1 / a0).toFloat(); b2 = (b2 / a0).toFloat()
        return this
    }

    fun reset() { x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f) }

    fun process(x: Float, c: Int): Float {
        val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
        x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = y
        return y
    }
}

/** Voice effects that are not plain pitch shifts (pitch is handled by Sonic). */
@UnstableApi
class VoiceFxAudioProcessor(private val fx: VoiceFx) : FloatAudioProcessor() {
    private val hp = Biquad()
    private val lp = Biquad()
    private var delay = FloatArray(0)
    private var delayPos = 0
    private var phase = 0.0

    override fun isActive(): Boolean = super.isActive() && fx in ACTIVE

    override fun onFormat(sampleRate: Int, channels: Int) {
        when (fx) {
            VoiceFx.TELEPHONE -> { hp.highPass(sampleRate, 400f); lp.lowPass(sampleRate, 3200f) }
            VoiceFx.MEGAPHONE -> { hp.highPass(sampleRate, 600f); lp.lowPass(sampleRate, 4000f) }
            VoiceFx.RADIO -> { hp.highPass(sampleRate, 250f); lp.lowPass(sampleRate, 5000f) }
            else -> { hp.highPass(sampleRate, 20f); lp.lowPass(sampleRate, 18000f) }
        }
        val delayMs = when (fx) { VoiceFx.ECHO -> 280; VoiceFx.CAVE -> 90; else -> 0 }
        delay = FloatArray((sampleRate * delayMs / 1000 * channels).coerceAtLeast(channels))
        delayPos = 0
    }

    override fun onReset(full: Boolean) {
        hp.reset(); lp.reset(); delay.fill(0f); delayPos = 0; phase = 0.0
    }

    override fun process(samples: FloatArray, frames: Int) {
        val ch = channels
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x = samples[i]
                when (fx) {
                    VoiceFx.ROBOT -> {
                        x *= (0.5 + 0.5 * sin(phase)).toFloat() * 1.6f
                    }
                    VoiceFx.TELEPHONE, VoiceFx.RADIO -> {
                        x = lp.process(hp.process(x, c), c)
                        x = tanh((x * 2.5f).toDouble()).toFloat() * 0.7f
                        if (fx == VoiceFx.RADIO) x += (Math.random().toFloat() - 0.5f) * 0.01f
                    }
                    VoiceFx.MEGAPHONE -> {
                        x = lp.process(hp.process(x, c), c)
                        x = tanh((x * 5f).toDouble()).toFloat() * 0.6f
                    }
                    VoiceFx.ECHO, VoiceFx.CAVE -> {
                        val fb = if (fx == VoiceFx.ECHO) 0.45f else 0.6f
                        val d = delay[delayPos]
                        val y = x + d * fb
                        delay[delayPos] = y
                        delayPos = (delayPos + 1) % delay.size
                        x = y * 0.8f
                    }
                    else -> {}
                }
                samples[i++] = x
            }
            phase += 2.0 * PI * 55.0 / sampleRate
            if (phase > 2 * PI) phase -= 2 * PI
        }
    }

    companion object {
        val ACTIVE = setOf(VoiceFx.ROBOT, VoiceFx.TELEPHONE, VoiceFx.RADIO, VoiceFx.MEGAPHONE, VoiceFx.ECHO, VoiceFx.CAVE)

        /** Pitch factor for pitch-based voice effects (1 = unchanged). */
        fun pitchFor(fx: VoiceFx): Float = when (fx) {
            VoiceFx.CHIPMUNK -> 1.7f
            VoiceFx.HELIUM -> 1.35f
            VoiceFx.DEEP -> 0.78f
            VoiceFx.MONSTER -> 0.6f
            VoiceFx.GIANT -> 0.5f
            VoiceFx.ROBOT -> 0.92f
            else -> 1f
        }
    }
}

/** Simple noise reduction: rumble/hiss filtering plus a soft downward expander (noise gate). */
@UnstableApi
class DenoiseAudioProcessor : FloatAudioProcessor() {
    private val hp = Biquad()
    private val lp = Biquad()
    private var env = FloatArray(8)

    override fun onFormat(sampleRate: Int, channels: Int) {
        hp.highPass(sampleRate, 90f)
        lp.lowPass(sampleRate, 7500f)
    }

    override fun onReset(full: Boolean) { hp.reset(); lp.reset(); env.fill(0f) }

    override fun process(samples: FloatArray, frames: Int) {
        val ch = channels
        val attack = 0.01f
        val release = 0.0008f
        val threshold = 0.02f
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x = lp.process(hp.process(samples[i], c), c)
                val a = abs(x)
                env[c] = if (a > env[c]) env[c] + (a - env[c]) * attack else env[c] + (a - env[c]) * release
                val g = if (env[c] >= threshold) 1f else (env[c] / threshold).let { it * it }
                x *= g
                samples[i++] = x
            }
        }
    }
}
