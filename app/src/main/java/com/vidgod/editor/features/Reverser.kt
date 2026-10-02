package com.vidgod.editor.features

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.vidgod.editor.VidGodApp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.media.Waveforms
import com.vidgod.editor.model.VisualClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Creates a reversed copy (video and sound) of a clip's trimmed range. */
@UnstableApi
object Reverser {

    fun run(vm: EditorViewModel, context: Context, clipId: String) {
        val p = vm.project.value
        val clip = (p.clips + p.overlays).firstOrNull { it.id == clipId } ?: return
        if (clip.isImage) return
        val original = clip.reversedFrom
        if (original != null) {
            // Toggle back to the original.
            vm.editVisual(clipId) {
                it.copy(source = original.source, trimStartUs = original.trimStartUs, trimEndUs = original.trimEndUs, reversedFrom = null)
            }
            vm.toast("Reverse removed")
            return
        }
        if (clip.sourceRangeUs > 180_000_000) return vm.toast("Reverse works on clips up to 3 minutes. Split the clip first.")
        vm.runBusy("Reversing clip…") {
            val dir = VidGodApp.instance.repository.mediaDir(p.id)
            val out = reverse(context, clip, dir) { f -> vm.setBusyProgress("Reversing clip… ${(f * 100).toInt()}%", f) }
            val range = clip.sourceRangeUs
            vm.editVisual(clipId) {
                // The reversed file covers exactly the trimmed range and becomes the clip's source.
                it.copy(
                    source = it.source.copy(uri = Uri.fromFile(out).toString(), durationUs = range, name = it.source.name + " (reversed)"),
                    trimStartUs = 0,
                    trimEndUs = range,
                    reversedFrom = com.vidgod.editor.model.ReverseInfo(clip.source, clip.trimStartUs, clip.trimEndUs),
                )
            }
            vm.toast("Clip reversed")
        }
    }

    /** Reverses [clip] between its trim points into a new file that starts at 0. */
    suspend fun reverse(context: Context, clip: VisualClip, dir: File, onProgress: (Float) -> Unit): File {
        val intra = File(dir, "intra_${clip.id}.mp4")
        val out = File(dir, "reverse_${clip.id}_${clip.trimStartUs}_${clip.trimEndUs}.mp4")
        if (out.exists() && out.length() > 0) return out
        // 1. All-intra transcode of the range (every frame becomes a key frame).
        transcodeIntra(context, clip, intra) { onProgress(it * 0.5f) }
        // 2. Reverse audio into memory, then video frame by frame.
        withContext(Dispatchers.IO) {
            reverseFile(context, clip, intra, out) { onProgress(0.5f + it * 0.5f) }
        }
        intra.delete()
        return out
    }

    private suspend fun transcodeIntra(context: Context, clip: VisualClip, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val item = MediaItem.Builder().setUri(clip.source.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(clip.trimStartUs)
                            .setEndPositionUs(clip.trimEndUs)
                            .build(),
                    ).build()
                val edited = EditedMediaItem.Builder(item)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(min(1080, minOf(clip.source.width, clip.source.height).coerceAtLeast(240))))))
                    .build()
                val encoder = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setiFrameIntervalSeconds(0f).setBitrate(20_000_000).build())
                    .setEnableFallback(true)
                    .build()
                val progress = ProgressHolder()
                lateinit var transformer: Transformer
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress / 100f)
                        handler.postDelayed(this, 300)
                    }
                }
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoder)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation { handler.post { handler.removeCallbacks(poll); transformer.cancel() } }
                transformer.start(edited, out.absolutePath)
                handler.post(poll)
            }
        }

    private class Encoded(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private fun reverseFile(context: Context, clip: VisualClip, intra: File, out: File, onProgress: (Float) -> Unit) {
        // ---- audio: decode the original range, reverse, encode to AAC in memory ----
        var audioFormat: MediaFormat? = null
        val audioSamples = ArrayList<Encoded>()
        if (clip.source.hasAudio) {
            runCatching {
                val pcm = java.io.ByteArrayOutputStream()
                var channels = 2
                var rate = 44100
                Waveforms.decode(context, clip.source.uri, clip.trimStartUs, clip.trimEndUs) { samples, count, ch, sr, _ ->
                    channels = ch; rate = sr
                    val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until count) bb.putShort((samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                    pcm.write(bb.array())
                }
                val bytes = pcm.toByteArray()
                val frameBytes = channels * 2
                val frames = min(bytes.size / frameBytes, ((clip.trimEndUs - clip.trimStartUs) * rate / 1_000_000L).toInt())
                val reversed = ByteArray(frames * frameBytes)
                for (f in 0 until frames) {
                    System.arraycopy(bytes, (frames - 1 - f) * frameBytes, reversed, f * frameBytes, frameBytes)
                }
                val fmt = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, rate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val enc = MediaCodec.createEncoderByType(MimeTypes.AUDIO_AAC)
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                val info = MediaCodec.BufferInfo()
                var offset = 0
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = enc.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = enc.getInputBuffer(idx)!!
                            val n = min(buf.capacity(), reversed.size - offset)
                            val pts = offset.toLong() / frameBytes * 1_000_000L / rate
                            if (n <= 0) {
                                enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.clear(); buf.put(reversed, offset, n)
                                enc.queueInputBuffer(idx, 0, n, pts, 0)
                                offset += n
                            }
                        }
                    }
                    val o = enc.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) audioFormat = enc.outputFormat
                    else if (o >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val b = enc.getOutputBuffer(o)!!
                            val data = ByteArray(info.size)
                            b.position(info.offset); b.get(data)
                            audioSamples.add(Encoded(data, info.presentationTimeUs, info.flags))
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                enc.stop(); enc.release()
            }
        }

        // ---- video ----
        val ex = MediaExtractor()
        ex.setDataSource(intra.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        require(track >= 0) { "No video track" }
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val times = ArrayList<Long>()
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            times.add(t)
            ex.advance()
        }
        require(times.isNotEmpty()) { "Empty video" }
        val last = times.maxOrNull()!!
        val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = (times.size * 1_000_000f / (last.coerceAtLeast(1))).coerceIn(1f, 120f)
        val encFormat = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * frameRate * 0.2f).toInt().coerceIn(2_000_000, 40_000_000))
            setFloat(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MimeTypes.VIDEO_H264)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, surface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var muxing = false
        val pendingVideo = ArrayList<Encoded>()
        val info = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var nextInput = times.size - 1
        var decodedFrames = 0
        var decoderDone = false
        var encoderDone = false

        fun startMuxerIfReady(videoFmt: MediaFormat) {
            videoTrack = muxer.addTrack(videoFmt)
            audioFormat?.let { audioTrack = muxer.addTrack(it) }
            muxer.start()
            muxing = true
            if (audioTrack >= 0) {
                val ai = MediaCodec.BufferInfo()
                for (s in audioSamples) {
                    ai.set(0, s.data.size, s.ptsUs, s.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), ai)
                }
            }
            for (s in pendingVideo) {
                val vi = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(s.data), vi)
            }
            pendingVideo.clear()
        }

        try {
            while (!encoderDone) {
                // Feed the decoder in reverse order with increasing timestamps.
                if (nextInput >= -1 && !decoderDone) {
                    val idx = decoder.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        if (nextInput < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            nextInput = -2
                        } else {
                            ex.seekTo(times[nextInput], MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val buf = decoder.getInputBuffer(idx)!!
                            val n = ex.readSampleData(buf, 0)
                            val pts = last - times[nextInput]
                            decoder.queueInputBuffer(idx, 0, n.coerceAtLeast(0), pts, 0)
                            nextInput--
                        }
                    }
                }
                if (!decoderDone) {
                    val o = decoder.dequeueOutputBuffer(info, 5_000)
                    if (o >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(o, info.size > 0)
                        if (info.size > 0) {
                            decodedFrames++
                            onProgress(decodedFrames.toFloat() / times.size)
                        }
                        if (eos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }
                val e = encoder.dequeueOutputBuffer(encInfo, 5_000)
                if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    startMuxerIfReady(encoder.outputFormat)
                } else if (e >= 0) {
                    if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val b = encoder.getOutputBuffer(e)!!
                        b.position(encInfo.offset)
                        b.limit(encInfo.offset + encInfo.size)
                        if (muxing) muxer.writeSampleData(videoTrack, b, encInfo)
                        else {
                            val data = ByteArray(encInfo.size); b.get(data)
                            pendingVideo.add(Encoded(data, encInfo.presentationTimeUs, encInfo.flags))
                        }
                    }
                    encoder.releaseOutputBuffer(e, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
            surface.release()
            ex.release()
            runCatching { if (muxing) muxer.stop() }
            muxer.release()
        }
    }
}
