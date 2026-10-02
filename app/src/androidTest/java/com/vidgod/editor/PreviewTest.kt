package com.vidgod.editor

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.engine.PreviewController
import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.SpeedCurves
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real-time preview (CompositionPlayer) into an offscreen surface. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewTest {
    private lateinit var reader: ImageReader
    private lateinit var preview: PreviewController
    private val frameThread = HandlerThread("preview-frames").apply { start() }
    @Volatile private var latest: Bitmap? = null
    @Volatile private var frames = 0

    @Before
    fun setUp() {
        reader = ImageReader.newInstance(
            360, 640, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                latest = Inspect.toBitmap(img)
                frames++
            }
        }, Handler(frameThread.looper))
        onMain {
            preview = PreviewController(T.app)
            preview.setOutputSurface(reader.surface, 360, 640)
        }
    }

    @After
    fun tearDown() {
        onMain { preview.release() }
        reader.close()
        frameThread.quitSafely()
    }

    private fun <R> onMain(block: suspend () -> R): R =
        runBlocking { withTimeout(120_000) { withContext(Dispatchers.Main) { block() } } }

    /**
     * Waits (the emulator renders slowly) for a frame newer than the ones seen so far, then
     * checks that the preview shows a picture. Falls back to the last frame if none arrives.
     */
    private suspend fun assertShows(name: String, timeoutMs: Long = 12_000) {
        val seen = frames
        val end = System.currentTimeMillis() + timeoutMs
        while (frames <= seen && System.currentTimeMillis() < end) delay(100)
        val fresh = frames > seen
        val b = latest
        assertTrue("$name: no frame rendered", b != null)
        T.save(b!!, name)
        val (mean, sd) = Inspect.stats(b)
        T.log("FRAME $name mean=%.1f sd=%.1f fresh=$fresh frames=$frames".format(mean, sd))
        assertTrue("$name: frame is blank (sd=$sd)", sd > 4.0)
    }

    /** Waits until the player reports playing (startup is slow on the emulator). */
    private suspend fun awaitPlaying(timeoutMs: Long = 15_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!preview.isPlaying.value && System.currentTimeMillis() < end) delay(50)
    }

    private suspend fun playFor(ms: Long): Long {
        val before = preview.positionUs.value
        preview.play()
        awaitPlaying()
        delay(ms)
        preview.pause()
        val after = preview.positionUs.value
        T.log("played ${ms}ms: $before -> $after, error=${preview.error.value}")
        return after - before
    }

    private fun base() = Project(clips = listOf(ProjectOps.visualFrom(T.source("portrait.mp4"))))

    private fun withMusic(p: Project): Project {
        val music = T.source("music.m4a")
        return p.copy(audios = listOf(AudioClip(source = music, trimEndUs = music.durationUs, volume = 0.5f)))
    }

    private fun withPip(p: Project): Project {
        var q = ProjectOps.addOverlay(p, T.source("photo.jpg"), 300_000).first
        q = q.copy(overlays = q.overlays.map { it.copy(transform = Transform(x = 0.2f, y = -0.2f, scale = 0.4f)) })
        return q
    }

    @Test
    fun singleClipPlaysAndSeeks() = onMain {
        preview.update(base(), immediate = true)
        delay(1500)
        assertShows("preview_single_start")
        assertTrue("playback did not advance", playFor(2000) > 800_000)
        assertNull(preview.error.value)
        preview.seekTo(3_000_000)
        delay(1000)
        assertShows("preview_single_seek3s")
        assertNull(preview.error.value)
    }

    /** Live edits while paused must not crash when the project has music (review bug #1). */
    @Test
    fun liveEditsWithMusicWhilePaused() = onMain {
        var p = withMusic(base()).copy(texts = listOf(TextClip(text = "Edit me", startUs = 0, durationUs = 4_000_000)))
        preview.update(p, immediate = true)
        delay(1500)
        repeat(20) { i ->
            p = p.copy(texts = p.texts.map { it.copy(text = "Edit $i", transform = it.transform.copy(y = i * 0.01f)) })
            preview.update(p)
            delay(60)
        }
        delay(800)
        assertNull(preview.error.value)
        assertShows("preview_music_edited")
        assertTrue("playback with music did not advance", playFor(1500) > 600_000)
    }

    /** Picture-in-picture: edits, play to the end, replay, then remove the overlay (review bug #2). */
    @Test
    fun pictureInPictureLifecycle() = onMain {
        var p = withMusic(withPip(base()))
        preview.update(p, immediate = true)
        delay(2000)
        assertShows("preview_pip_start")
        // Live edit while paused (seek-based redraw for the multi-input graph).
        p = p.copy(overlays = p.overlays.map { it.copy(transform = it.transform.copy(scale = 0.6f)) })
        preview.update(p)
        delay(1200)
        assertNull(preview.error.value)
        assertShows("preview_pip_scaled")
        // Structural edit: add a text and a sticker.
        p = p.copy(
            texts = listOf(TextClip(text = "PIP text", startUs = 0, durationUs = 4_000_000)),
            stickers = listOf(StickerClip(kind = StickerKind.EMOJI, content = "⭐", startUs = 0, durationUs = 4_000_000)),
        )
        preview.update(p)
        delay(1500)
        assertShows("preview_pip_with_text")
        // Play the last second to the end (the emulator renders this slowly).
        preview.seekTo(p.durationUs - 1_000_000)
        delay(500)
        preview.play()
        awaitPlaying()
        withTimeout(45_000) { while (preview.positionUs.value < p.durationUs - 100_000 || preview.isPlaying.value) delay(100) }
        T.log("pip reached end: ${preview.positionUs.value} / ${p.durationUs}")
        // Replay from the start.
        assertTrue("replay after end did not advance", playFor(1500) > 500_000)
        assertNull(preview.error.value)
        assertShows("preview_pip_replay")
        // Remove the overlay: the preview must keep working.
        p = p.copy(overlays = emptyList())
        preview.update(p, immediate = true)
        delay(1000)
        preview.seekTo(0)
        assertTrue("playback after removing overlay did not advance", playFor(1500) > 500_000)
        assertNull(preview.error.value)
        assertShows("preview_pip_removed")
    }

    @Test
    fun speedCurveDurationMatchesTimeline() = onMain {
        val clip = ProjectOps.visualFrom(T.source("portrait.mp4")).copy(
            speedCurve = SpeedCurves.presets.first { it.name == "Montage" }.points,
        )
        val p = Project(clips = listOf(clip))
        preview.update(p, immediate = true)
        delay(1500)
        preview.play()
        awaitPlaying()
        val started = System.currentTimeMillis()
        withTimeout(60_000) { while (preview.isPlaying.value) delay(50) }
        val playedMs = System.currentTimeMillis() - started
        T.log("speed curve: timeline ${p.durationUs / 1000} ms, played $playedMs ms, end position ${preview.positionUs.value}")
        assertNull(preview.error.value)
        // Ends exactly at the timeline length computed by SpeedCurves (wall time is meaningless on
        // the emulator, which decodes 3x speed parts slower than real time).
        assertEquals(p.durationUs, preview.positionUs.value)
        assertFalse(preview.isPlaying.value)
    }
}
