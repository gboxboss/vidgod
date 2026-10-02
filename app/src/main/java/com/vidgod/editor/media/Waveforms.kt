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
import kotlin.math.abs
import kotlin.math.max

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
                    if (info.size > 0 && info.presentationTimeUs >= startUs - 100_000) {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val count = if (pcmFloat) info.size / 4 else info.size / 2
                        if (scratch.size < count) scratch = FloatArray(count)
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer(); fb.get(scratch, 0, count)
                        } else {
                            val sb = buf.asShortBuffer()
                            for (i in 0 until count) scratch[i] = sb.get(i) / 32768f
                        }
                        onPcm(scratch, count, channels, sampleRate, state)
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
        var pos = 0.0
        var outBuf = ShortArray(8192)
        return decode(context, uri, startUs, endUs) { samples, count, channels, sampleRate, _ ->
            val frames = count / channels
            val step = sampleRate.toDouble() / targetRate
            var n = 0
            if (outBuf.size < frames + 16) outBuf = ShortArray(frames + 16)
            while (pos < frames) {
                val idx = pos.toInt()
                var mono = 0f
                for (c in 0 until channels) mono += samples[idx * channels + c]
                mono /= channels
                outBuf[n++] = (mono * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                pos += step
            }
            pos -= frames
            if (n > 0) sink(outBuf, n)
        }
    }
}
