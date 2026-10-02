package com.vidgod.editor.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.vidgod.editor.model.ExportSettings
import com.vidgod.editor.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Renders projects to MP4 files using Media3 Transformer (hardware encoders). */
@UnstableApi
class ExportController(private val context: Context) {

    data class Result(val file: File, val galleryUri: Uri?, val sizeBytes: Long, val durationMs: Long)

    companion object {
        /** Bitrate recommended for a short side and frame rate (TikTok friendly). */
        fun recommendedBitrate(shortSide: Int, fps: Int): Int {
            val base = when {
                shortSide <= 480 -> 3_000_000
                shortSide <= 720 -> 6_000_000
                shortSide <= 1080 -> 12_000_000
                shortSide <= 1440 -> 20_000_000
                else -> 40_000_000
            }
            return if (fps > 30) (base * 1.5).toInt() else base
        }

        fun estimateSizeBytes(settings: ExportSettings, durationUs: Long): Long {
            val v = if (settings.bitrate > 0) settings.bitrate else recommendedBitrate(settings.resolution, settings.frameRate)
            return ((v + 192_000L) * durationUs / 1_000_000L / 8L)
        }
    }

    /**
     * Exports [project]. [onProgress] receives 0..100. Must be called from the main thread.
     */
    suspend fun export(project: Project, settings: ExportSettings, onProgress: (Int) -> Unit): Result {
        val live = LiveProject(project)
        val effective = if (settings.tiktokOptimized) {
            settings.copy(frameRate = settings.frameRate.coerceAtMost(60), hevc = false)
        } else {
            settings
        }
        val built = CompositionFactory(context).build(project, live, effective.resolution, effective.frameRate)
            ?: throw IllegalStateException("Nothing to export: add a clip first")
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "VidGod_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val out = File(dir, name)
        val bitrate = if (effective.bitrate > 0) effective.bitrate else recommendedBitrate(effective.resolution, effective.frameRate)
        val started = System.currentTimeMillis()
        runTransformer(built.composition, out, bitrate, effective.hevc, onProgress)
        val uri = withContext(Dispatchers.IO) { saveToGallery(out, name) }
        return Result(out, uri, out.length(), System.currentTimeMillis() - started)
    }

    private suspend fun runTransformer(
        composition: Composition,
        out: File,
        bitrate: Int,
        hevc: Boolean,
        onProgress: (Int) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val video = VideoEncoderSettings.Builder()
            .setBitrate(bitrate)
            .setiFrameIntervalSeconds(1f)
            .build()
        val audio = AudioEncoderSettings.Builder().setBitrate(256_000).build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(video)
            .setRequestedAudioEncoderSettings(audio)
            .setEnableFallback(true)
            .build()
        lateinit var transformer: Transformer
        val progress = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progress.progress)
                }
                handler.postDelayed(this, 250)
            }
        }
        transformer = Transformer.Builder(context)
            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setPortraitEncodingEnabled(true)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(poll)
                    onProgress(100)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacks(poll)
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()
        cont.invokeOnCancellation {
            handler.post {
                handler.removeCallbacks(poll)
                transformer.cancel()
                out.delete()
            }
        }
        transformer.start(composition, out.absolutePath)
        handler.post(poll)
    }

    /** Copies the export to Movies/VidGod so it shows up in the gallery and TikTok's picker. */
    private fun saveToGallery(file: File, name: String): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidGod")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VidGod")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    }.getOrNull()
}
