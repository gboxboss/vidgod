package com.vidgod.editor

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.engine.ExportController
import com.vidgod.editor.features.Reverser
import com.vidgod.editor.model.Adjust
import com.vidgod.editor.model.AnimRef
import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.AudioKind
import com.vidgod.editor.model.EffectClip
import com.vidgod.editor.model.ExportSettings
import com.vidgod.editor.model.FilterClip
import com.vidgod.editor.model.FilterRef
import com.vidgod.editor.model.FxRef
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.SpeedCurves
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.TextStyle
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.TransitionRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Exports real projects on the device and checks the files (format, length, frames). */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportTest {

    private fun export(name: String, project: Project, settings: ExportSettings = ExportSettings()): Pair<File, VideoInfo> {
        val result = runBlocking {
            withTimeout(300_000) {
                withContext(Dispatchers.Main) { ExportController(T.app).export(project, settings) {} }
            }
        }
        val copy = File(T.out, "$name.mp4")
        result.file.copyTo(copy, overwrite = true)
        val info = Inspect.video(copy)
        T.log("EXPORT $name: $info (took ${result.durationMs} ms, gallery=${result.galleryUri})")
        val frames = Inspect.frames(copy)
        Inspect.sheet(name, frames.map { (t, b) ->
            val st = b?.let { Inspect.stats(it) }
            "%.2fs y=%.0f sd=%.0f".format(t / 1e6, st?.first ?: -1.0, st?.second ?: -1.0) to b
        })
        frames.forEach { (t, b) ->
            assertTrue("$name: no frame at $t", b != null)
            assertTrue("$name: blank frame at $t", Inspect.stats(b!!).second > 4.0)
        }
        return copy to info
    }

    private fun assertTikTokFormat(name: String, info: VideoInfo, expectedUs: Long, fps: Double = 30.0) {
        assertEquals("$name video codec", "video/avc", info.videoMime)
        assertEquals("$name width", 1080, info.width)
        assertEquals("$name height", 1920, info.height)
        assertEquals("$name audio codec", "audio/mp4a-latm", info.audioMime)
        assertTrue("$name duration ${info.durationUs} vs $expectedUs", abs(info.durationUs - expectedUs) < 250_000)
        assertTrue("$name fps ${info.fps} vs $fps", abs(info.fps - fps) < fps * 0.1)
    }

    @Test
    fun singlePortraitClip() {
        val p = Project(clips = listOf(ProjectOps.visualFrom(T.source("portrait.mp4"))))
        val (_, info) = export("export_single", p)
        assertTikTokFormat("single", info, 4_000_000)
    }

    @Test
    fun rotatedPhoneClip() {
        val src = T.source("rotated.mp4")
        T.log("rotated source: $src")
        assertTrue("rotated clip should probe as portrait: $src", src.height > src.width)
        val p = Project(clips = listOf(ProjectOps.visualFrom(src)))
        val (_, info) = export("export_rotated", p)
        assertTikTokFormat("rotated", info, 3_000_000)
    }

    @Test
    fun landscape60fpsAt30And60() {
        val p = Project(clips = listOf(ProjectOps.visualFrom(T.source("landscape60.mp4"))))
        val (_, info30) = export("export_landscape_30", p)
        assertTikTokFormat("landscape30", info30, 3_000_000, 30.0)
        val (_, info60) = export("export_landscape_60", p, ExportSettings(frameRate = 60))
        assertTikTokFormat("landscape60", info60, 3_000_000, 60.0)
    }

    @Test
    fun hevcSourceAndHevcOutput() {
        val p = Project(clips = listOf(ProjectOps.visualFrom(T.source("hevc.mp4"))))
        val (_, info) = export("export_hevc_source", p)
        assertTikTokFormat("hevcSource", info, 2_000_000)
        val (_, h) = export("export_hevc_output", p, ExportSettings(hevc = true, tiktokOptimized = false))
        assertEquals("video/hevc", h.videoMime)
    }

    @Test
    fun photoSlideshowHasSound() {
        val photo = T.source("photo.jpg")
        val clips = (0 until 3).map { i ->
            ProjectOps.visualFrom(photo).copy(
                trimEndUs = 2_000_000,
                animCombo = AnimRef("ken_burns", 2_000_000),
                transitionOut = if (i < 2) TransitionRef("dissolve", 500_000) else null,
                transform = Transform(rotation = i * 10f),
            )
        }
        val (_, info) = export("export_slideshow", Project(clips = clips))
        assertTikTokFormat("slideshow", info, 6_000_000)
        assertTrue("slideshow needs an audio track", info.audioDurationUs > 5_000_000)
    }

    @Test
    fun richProject() {
        val a = ProjectOps.visualFrom(T.source("portrait.mp4")).copy(
            filter = FilterRef("vivid", 0.8f),
            animIn = AnimRef("zoom_in", 600_000),
            transitionOut = TransitionRef("push_left", 500_000),
            fx = listOf(FxRef("shake", 0.6f)),
        )
        val b = ProjectOps.visualFrom(T.source("rotated.mp4")).copy(
            speed = 2f,
            adjust = Adjust(temperature = 0.4f, contrast = 0.2f, saturation = 0.2f),
            transitionOut = TransitionRef("dip_white", 400_000),
        )
        val c = ProjectOps.visualFrom(T.source("landscape60.mp4")).let {
            it.copy(speedCurve = SpeedCurves.presets.first { p -> p.name == "Bullet" }.points, speedCurveName = "Bullet")
        }
        var p = Project(clips = listOf(a, b, c))
        val total = p.durationUs
        T.log("rich: clip durations ${p.clips.map { it.durationUs }} total=$total")
        val music = T.source("music.m4a")
        p = p.copy(
            audios = listOf(AudioClip(source = music, kind = AudioKind.MUSIC, trimEndUs = music.durationUs, volume = 0.6f, fadeOutUs = 1_000_000)),
            texts = listOf(
                TextClip(text = "Hello TikTok", startUs = 0, durationUs = 2_500_000, style = TextStyle(strokeWidth = 0.12f, backgroundAlpha = 0.5f), animIn = AnimRef("pop_in")),
                TextClip(text = "Second line", startUs = 3_000_000, durationUs = 3_000_000, transform = Transform(y = 0.3f)),
            ),
            stickers = listOf(
                StickerClip(kind = StickerKind.EMOJI, content = "🔥", startUs = 500_000, durationUs = 3_000_000, transform = Transform(x = 0.25f, y = -0.3f)),
                StickerClip(kind = StickerKind.SHAPE, content = "heart", startUs = 2_000_000, durationUs = 3_000_000, transform = Transform(x = -0.25f, y = 0.25f)),
            ),
            effects = listOf(EffectClip(fx = FxRef("strobe", 0.5f), startUs = 1_000_000, durationUs = 1_000_000)),
            filters = listOf(FilterClip(filter = FilterRef("cool", 0.7f), startUs = 4_000_000, durationUs = 2_000_000)),
        )
        val (_, info) = export("export_rich", p)
        assertTikTokFormat("rich", info, p.durationUs)
    }

    private fun pipProject(): Project {
        val main = ProjectOps.visualFrom(T.source("portrait.mp4"))
        var p = Project(clips = listOf(main))
        p = ProjectOps.addOverlay(p, T.source("photo.jpg"), 500_000).first
        p = ProjectOps.addOverlay(p, T.source("landscape60.mp4"), 1_000_000).first
        p = p.copy(
            overlays = p.overlays.mapIndexed { i, o ->
                o.copy(transform = Transform(x = if (i == 0) -0.22f else 0.22f, y = if (i == 0) -0.25f else 0.25f, scale = 0.45f))
            },
        )
        val music = T.source("music.m4a")
        return p.copy(audios = listOf(AudioClip(source = music, trimEndUs = music.durationUs, volume = 0.5f)))
    }

    @Test
    fun pictureInPicture30() {
        val p = pipProject()
        T.log("pip overlays: ${p.overlays.map { "layer=${it.layer} start=${it.startUs} dur=${it.durationUs}" }}")
        val (_, info) = export("export_pip_30", p)
        assertTikTokFormat("pip30", info, 4_000_000, 30.0)
    }

    @Test
    fun pictureInPicture60() {
        val (_, info) = export("export_pip_60", pipProject(), ExportSettings(frameRate = 60))
        assertTikTokFormat("pip60", info, 4_000_000, 60.0)
    }

    @Test
    fun reverseRotatedClip() {
        val clip = ProjectOps.visualFrom(T.source("rotated.mp4"))
        val dir = File(T.app.cacheDir, "reverse-test").apply { deleteRecursively(); mkdirs() }
        val out = runBlocking { withTimeout(300_000) { Reverser.reverse(T.app, clip, dir) {} } }
        val copy = File(T.out, "reverse_rotated.mp4")
        out.copyTo(copy, overwrite = true)
        val info = Inspect.video(copy)
        T.log("REVERSE: $info")
        Inspect.sheet("reverse_rotated", Inspect.frames(copy).map { (t, b) -> "%.2fs".format(t / 1e6) to b })
        assertTrue("reversed clip should stay portrait: $info", info.height > info.width)
        assertTrue("reversed duration ${info.durationUs}", abs(info.durationUs - 3_000_000) < 300_000)
        val src = runBlocking { com.vidgod.editor.data.MediaProbe.probe(T.app, android.net.Uri.fromFile(out)) }
        T.log("REVERSE probe: $src")
        assertTrue("probe of reversed clip should be portrait: $src", src != null && src.height > src.width)
    }
}
