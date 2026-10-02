package com.vidgod.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vidgod.editor.data.MediaProbe
import com.vidgod.editor.model.MediaSource
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Helpers shared by the device tests. Everything a test produces (videos, frames, screenshots,
 * logs) goes to [T.out], which the emulator workflow pulls from the device.
 */
object T {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app: Context get() = instrumentation.targetContext
    private val testContext: Context get() = instrumentation.context

    /** Copies `assets/media/<name>` of the test APK into the app's files dir. */
    fun media(name: String): File {
        val dir = File(app.filesDir, "test-media").apply { mkdirs() }
        val f = File(dir, name)
        if (f.length() == 0L) {
            testContext.assets.open("media/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    fun uri(name: String): Uri = Uri.fromFile(media(name))

    fun source(name: String): MediaSource =
        runBlocking { MediaProbe.probe(app, uri(name)) } ?: error("Could not probe $name")

    val out: File by lazy { File(app.getExternalFilesDir(null), "test-out").apply { mkdirs() } }

    fun log(line: String) {
        Log.i("VidGodTest", line)
        File(out, "log.txt").appendText(line + "\n")
    }

    fun save(bitmap: Bitmap, name: String, jpeg: Boolean = false): File {
        val f = File(out, if (jpeg) "$name.jpg" else "$name.png")
        f.outputStream().use {
            if (jpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return f
    }

    /** Full-screen screenshot including SurfaceViews (the video preview). */
    fun screenshot(name: String): Bitmap? {
        val shot = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull() ?: return null
        val scaled = Bitmap.createScaledBitmap(shot, shot.width / 2, shot.height / 2, true)
        save(scaled, name, jpeg = true)
        return scaled
    }
}

/** What a video file contains, read back with the platform's extractor. */
data class VideoInfo(
    val videoMime: String?,
    val codedWidth: Int,
    val codedHeight: Int,
    val rotation: Int,
    val durationUs: Long,
    val videoFrames: Int,
    val fps: Double,
    val audioMime: String?,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val audioDurationUs: Long,
    val fileBytes: Long,
) {
    /** Size as displayed (rotation applied). */
    val width get() = if (rotation % 180 == 0) codedWidth else codedHeight
    val height get() = if (rotation % 180 == 0) codedHeight else codedWidth
}

object Inspect {

    fun video(file: File): VideoInfo {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var v = -1
        var a = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/") && v < 0) v = i
            if (mime.startsWith("audio/") && a < 0) a = i
        }
        val vf = if (v >= 0) ex.getTrackFormat(v) else null
        val af = if (a >= 0) ex.getTrackFormat(a) else null
        var frames = 0
        var first = -1L
        var last = 0L
        if (v >= 0) {
            ex.selectTrack(v)
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (first < 0) first = t
                last = max(last, t)
                frames++
                ex.advance()
            }
            ex.unselectTrack(v)
        }
        ex.release()
        // A fresh extractor for the audio track (the first one already read to the end).
        var audioLast = 0L
        if (a >= 0) {
            val ax = MediaExtractor()
            ax.setDataSource(file.absolutePath)
            ax.selectTrack(a)
            while (true) {
                val t = ax.sampleTime
                if (t < 0) break
                audioLast = max(audioLast, t)
                ax.advance()
            }
            ax.release()
        }
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val duration = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        mmr.release()
        val fps = if (frames > 1 && last > first) (frames - 1) * 1_000_000.0 / (last - first) else 0.0
        return VideoInfo(
            videoMime = vf?.getString(MediaFormat.KEY_MIME),
            codedWidth = vf?.getInteger(MediaFormat.KEY_WIDTH) ?: 0,
            codedHeight = vf?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0,
            rotation = rotation,
            durationUs = duration,
            videoFrames = frames,
            fps = fps,
            audioMime = af?.getString(MediaFormat.KEY_MIME),
            audioChannels = af?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
            audioSampleRate = af?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
            audioDurationUs = audioLast,
            fileBytes = file.length(),
        )
    }

    /** Frames of [file] at the given fractions of its duration. */
    fun frames(file: File, fractions: List<Float> = listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)): List<Pair<Long, Bitmap?>> {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val duration = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        val result = fractions.map { f ->
            val t = (duration * f).toLong()
            t to runCatching { mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
        }
        mmr.release()
        return result
    }

    /** Mean luma and its standard deviation (0..255); a blank frame has a tiny deviation. */
    fun stats(b: Bitmap): Pair<Double, Double> {
        val small = Bitmap.createScaledBitmap(b, 64, max(1, 64 * b.height / max(1, b.width)), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        var sum = 0.0
        var sq = 0.0
        for (c in px) {
            val y = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            sum += y
            sq += y * y
        }
        val mean = sum / px.size
        return mean to sqrt(max(0.0, sq / px.size - mean * mean))
    }

    /** Puts labelled thumbnails side by side into one image, saved as `<name>.png`. */
    fun sheet(name: String, items: List<Pair<String, Bitmap?>>, thumbHeight: Int = 480): File {
        val thumbs = items.map { (label, b) ->
            val bmp = b ?: Bitmap.createBitmap(270, thumbHeight, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            label to Bitmap.createScaledBitmap(bmp, max(1, bmp.width * thumbHeight / max(1, bmp.height)), thumbHeight, true)
        }
        val gap = 8
        val labelH = 36
        val w = thumbs.sumOf { it.second.width + gap } + gap
        val sheet = Bitmap.createBitmap(max(w, 1), thumbHeight + labelH + gap * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 48))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }
        var x = gap
        for ((label, t) in thumbs) {
            c.drawBitmap(t, x.toFloat(), gap.toFloat(), null)
            c.drawText(label, x.toFloat(), (thumbHeight + gap + 28).toFloat(), paint)
            x += t.width + gap
        }
        return T.save(sheet, name, jpeg = true)
    }

    /** Copies an RGBA_8888 [Image] (ImageReader) into a bitmap. */
    fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val tmp = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        tmp.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(tmp, 0, 0, image.width, image.height)
    }
}

/**
 * Runs named steps, keeps going after failures and records each outcome, so that one run of
 * the device tests shows every broken step at once.
 */
class Steps(private val prefix: String) {
    private var index = 0
    val failures = ArrayList<String>()

    fun step(name: String, screenshot: Boolean = true, block: () -> Unit) {
        index++
        val id = "%s_%02d_%s".format(prefix, index, name.replace(Regex("[^A-Za-z0-9]+"), "_"))
        val start = System.currentTimeMillis()
        val error = runCatching(block).exceptionOrNull()
        val ms = System.currentTimeMillis() - start
        if (screenshot) runCatching { T.screenshot(id) }
        if (error == null) {
            T.log("PASS $id (${ms}ms)")
        } else {
            T.log("FAIL $id (${ms}ms): ${error::class.java.simpleName}: ${error.message}\n${Log.getStackTraceString(error).lines().take(12).joinToString("\n")}")
            failures.add("$id: ${error.message}")
        }
    }

    fun assertAllPassed() {
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} step(s) failed:\n" + failures.joinToString("\n"))
    }
}
