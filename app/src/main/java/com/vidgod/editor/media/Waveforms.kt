package com.vidgod.editor.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Decoded audio helpers: waveform peaks and mono PCM extraction. */
object Waveforms {
    /** Peaks per second of audio. */
    const val RATE = 50

    private val cache = LruCache<String, FloatArray>(24)
    private val mutex = Mutex()

    suspend fun peaks(context: Context, uri: String): FloatArray? {
        cache.get(uri)?.let { return it }
        return mutex.withLock {
            cache.get(uri) ?: withContext(Dispatchers.IO) {
                val disk = File(context.cacheDir, "waveforms/" + uri.hashCode().toString(16) + ".bin")
                if (disk.exists()) {
                    runCatching {
                        val bytes = disk.readBytes()
                        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        FloatArray(fb.remaining()).also { fb.get(it) }
                    }.getOrNull()?.let { cache.put(uri, it); return@withContext it }
                }
                val result = runCatching { computePeaks(context, uri) }.getOrNull()
                if (result != null) {
                    cache.put(uri, result)
                    runCatching {
                        disk.parentFile?.mkdirs()
                        val bb = ByteBuffer.allocate(result.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        bb.asFloatBuffer().put(result)
                        disk.writeBytes(bb.array())
                    }
                }
                result
            }
        }
    }

    private fun computePeaks(context: Context, uri: String): FloatArray? {
        val out = ArrayList<Float>()
        decode(context, uri) { samples, count, channels, sampleRate, state ->
            val window = max(1, sampleRate / RATE)
            var i = 0
            while (i < count) {
                val v = abs(samples[i]) // first channel only
                if (v > state.peak) state.peak = v
                state.frames++
                if (state.frames >= window) {
                    out.add(state.peak)
                    state.peak = 0f
                    state.frames = 0
                }
                i += channels
            }
        }
        if (out.isEmpty()) return null
        val m = out.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f
        return FloatArray(out.size) { (out[it] / m).coerceIn(0f, 1f) }
    }

    class DecodeState {
        var peak = 0f
        var frames = 0
    }

    /** Decodes the first audio track to float PCM, calling [onPcm] for each output buffer. */
    fun decode(
        context: Context,
        uri: String,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
        onPcm: (FloatArray, Int, Int, Int, DecodeState) -> Unit,
    ): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(context, Uri.parse(uri), null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) { ex.release(); return false }
        ex.selectTrack(track)
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var pcmFloat = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var scratch = FloatArray(16384)
        val state = DecodeState()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val bytesPerSample = if (pcmFloat) 4 else 2
                    val totalFrames = info.size / bytesPerSample / channels
                    val bufferEndUs = info.presentationTimeUs + totalFrames * 1_000_000L / sampleRate
                    if (info.size > 0 && bufferEndUs > startUs) {
                        // Drop the part of the first buffer that lies before startUs.
                        val skipFrames = if (info.presentationTimeUs < startUs) {
                            ((startUs - info.presentationTimeUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, totalFrames)
                        } else 0
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset + skipFrames * channels * bytesPerSample)
                        buf.limit(info.offset + info.size)
                        val count = (totalFrames - skipFrames) * channels
                        if (scratch.size < count) scratch = FloatArray(count)
                        if (pcmFloat) {
                            val fb = buf.slice().order(ByteOrder.nativeOrder()).asFloatBuffer(); fb.get(scratch, 0, count)
                        } else {
                            val sb = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                            for (i in 0 until count) scratch[i] = sb.get(i) / 32768f
                        }
                        if (count > 0) onPcm(scratch, count, channels, sampleRate, state)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return true
    }

    /** Decodes and resamples to mono 16-bit PCM at [targetRate] (for speech recognition). */
    fun decodeMono16(context: Context, uri: String, startUs: Long, endUs: Long, targetRate: Int, sink: (ShortArray, Int) -> Unit): Boolean {
        var resampler: MonoResampler? = null
        var mono = FloatArray(8192)
        return decode(context, uri, startUs, endUs) { samples, count, channels, sampleRate, _ ->
            val frames = count / channels
            if (mono.size < frames) mono = FloatArray(frames)
            for (f in 0 until frames) {
                var m = 0f
                for (c in 0 until channels) m += samples[f * channels + c]
                mono[f] = m / channels
            }
            val r = resampler?.takeIf { it.inRate == sampleRate } ?: MonoResampler(sampleRate, targetRate).also { resampler = it }
            r.process(mono, frames, sink)
        }
    }
}

/**
 * Streaming mono resampler: a low-pass filter (windowed sinc) against aliasing when the rate goes
 * down, then linear interpolation. Picking every n-th sample folds everything above the new
 * Nyquist frequency (sibilants, music) back into the speech band, which hurts speech recognition.
 */
internal class MonoResampler(val inRate: Int, private val outRate: Int) {
    // Filter length grows with the ratio, for a transition band of about 0.2 x the output rate.
    private val half = if (inRate > outRate) ceil(16.0 * inRate / outRate).toInt() else 0
    private val taps = FloatArray(2 * half + 1).also { t ->
        if (half == 0) {
            t[0] = 1f
            return@also
        }
        val cutoff = 0.45 * outRate / inRate // cycles per input sample
        val sinc = DoubleArray(t.size) { i ->
            val k = i - half
            val h = if (k == 0) 2 * cutoff else sin(2 * PI * cutoff * k) / (PI * k)
            val blackman = 0.42 - 0.5 * cos(2 * PI * i / (t.size - 1)) + 0.08 * cos(4 * PI * i / (t.size - 1))
            h * blackman
        }
        val sum = sinc.sum()
        for (i in t.indices) t[i] = (sinc[i] / sum).toFloat()
    }

    /** The last input samples of the previous chunk (filter state). */
    private val history = FloatArray(2 * half)
    private var window = FloatArray(0)
    private var filtered = FloatArray(0)
    private var out = ShortArray(0)
    private var last = 0f
    // Output sample k lies at input position k * inRate / outRate (exact, in integers).
    private var produced = 0L
    private var consumed = 0L

    fun process(input: FloatArray, n: Int, sink: (ShortArray, Int) -> Unit) {
        if (n <= 0) return
        val w = history.size
        if (window.size < w + n) window = FloatArray(w + n)
        System.arraycopy(history, 0, window, 0, w)
        System.arraycopy(input, 0, window, w, n)
        if (filtered.size < n) filtered = FloatArray(n)
        for (j in 0 until n) {
            var acc = 0f
            for (k in taps.indices) acc += taps[k] * window[j + k]
            filtered[j] = acc
        }
        System.arraycopy(window, n, history, 0, w)
        // Linear interpolation over [last] + filtered.
        val maxOut = (n.toLong() * outRate / inRate).toInt() + 2
        if (out.size < maxOut) out = ShortArray(maxOut)
        var m = 0
        while (m < out.size) {
            val num = produced * inRate - consumed * outRate // position in this chunk, times outRate
            if (num >= n.toLong() * outRate) break
            val i = (num / outRate).toInt()
            val frac = (num % outRate).toFloat() / outRate
            val a = if (i == 0) last else filtered[i - 1]
            val b = filtered[i]
            out[m++] = ((a + (b - a) * frac) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            produced++
        }
        consumed += n
        last = filtered[n - 1]
        if (m > 0) sink(out, m)
    }
}
