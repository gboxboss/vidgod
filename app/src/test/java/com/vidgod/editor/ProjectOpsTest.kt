package com.vidgod.editor

import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.engine.Keyframes
import com.vidgod.editor.features.AutoCaptions
import com.vidgod.editor.model.Keyframe
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.MediaSource
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.SpeedCurves
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.TextStyle
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.sourceToTimelineUs
import com.vidgod.editor.model.timelineToSourceUs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProjectOpsTest {
    private val video = MediaSource("file:///a.mp4", MediaKind.VIDEO, "a", 10_000_000, 1080, 1920, hasAudio = true)
    private val image = MediaSource("file:///b.jpg", MediaKind.IMAGE, "b", 3_000_000, 1000, 1000)

    private fun project(): Project = Project(clips = listOf(ProjectOps.visualFrom(video), ProjectOps.visualFrom(image)))

    @Test
    fun durationsAndStarts() {
        val p = project()
        assertEquals(13_000_000L, p.durationUs)
        assertEquals(listOf(0L, 10_000_000L), p.clipStarts().toList())
    }

    @Test
    fun splitMainClipKeepsTotalDuration() {
        val p = project()
        val (p2, sel) = ProjectOps.split(p, Selection.Main(p.clips[0].id), 4_000_000)
        assertEquals(3, p2.clips.size)
        assertEquals(p.durationUs, p2.durationUs)
        assertEquals(4_000_000L, p2.clips[0].trimEndUs)
        assertEquals(4_000_000L, p2.clips[1].trimStartUs)
        assertEquals(p2.clips[1].id, sel?.id)
    }

    @Test
    fun splitWithSpeed() {
        var p = project()
        p = ProjectOps.updateVisual(p, p.clips[0].id) { it.copy(speed = 2f) }
        assertEquals(5_000_000L, p.clips[0].durationUs)
        val (p2, _) = ProjectOps.split(p, Selection.Main(p.clips[0].id), 2_000_000)
        assertEquals(4_000_000L, p2.clips[0].trimEndUs)
        assertEquals(p.durationUs, p2.durationUs)
    }

    @Test
    fun speedCurveMappingIsConsistent() {
        val curve = SpeedCurves.presets.first().points
        val range = 6_000_000L
        val total = SpeedCurves.timelineDuration(range, curve)
        assertTrue(total > 0)
        for (t in listOf(0L, total / 4, total / 2, total * 3 / 4, total)) {
            val src = SpeedCurves.timelineToSource(range, curve, t)
            val back = SpeedCurves.sourceToTimeline(range, curve, src)
            assertTrue("t=$t back=$back", abs(back - t) < 2_000)
        }
        val clip = ProjectOps.visualFrom(video).copy(trimEndUs = range, speedCurve = curve)
        assertEquals(total, clip.durationUs)
        assertEquals(range, clip.timelineToSourceUs(clip.durationUs))
        assertEquals(0L, clip.sourceToTimelineUs(0))
    }

    @Test
    fun keyframeInterpolation() {
        val kfs = listOf(
            Keyframe(0, Transform(x = 0f, scale = 1f), easing = com.vidgod.editor.model.Easing.LINEAR),
            Keyframe(1_000_000, Transform(x = 1f, scale = 3f)),
        )
        val mid = Keyframes.evaluate(kfs, 500_000, Transform(), 1f)
        assertEquals(0.5f, mid.transform.x, 0.001f)
        assertEquals(2f, mid.transform.scale, 0.001f)
        assertEquals(1f, Keyframes.evaluate(kfs, 5_000_000, Transform(), 1f).transform.x, 0.001f)
    }

    @Test
    fun lanesDoNotOverlapAfterMove() {
        var p = project()
        p = ProjectOps.addText(p, TextClip(text = "a", startUs = 0, durationUs = 3_000_000))
        p = ProjectOps.addText(p, TextClip(text = "b", startUs = 4_000_000, durationUs = 3_000_000))
        assertEquals(0, p.texts[1].lane)
        p = ProjectOps.moveTo(p, Selection.Text(p.texts[1].id), 1_000_000)
        p = ProjectOps.normalizeLanes(p)
        assertTrue(p.texts[0].lane != p.texts[1].lane)
    }

    @Test
    fun captionsGrouping() {
        val words = (0 until 10).map { AutoCaptions.Word("w$it", it * 300_000L, it * 300_000L + 250_000) }
        val caps = AutoCaptions.toCaptions(words, TextStyle(), maxWords = 4)
        assertEquals(3, caps.size)
        assertEquals("w0 w1 w2 w3", caps[0].text)
        for (i in 0 until caps.size - 1) assertTrue(caps[i].endUs <= caps[i + 1].startUs)
        assertEquals(4, caps[0].words.size)
    }

    @Test
    fun projectJsonRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val p = project().copy(texts = listOf(TextClip(text = "hi", startUs = 0)))
        val s = json.encodeToString(Project.serializer(), p)
        val back = json.decodeFromString(Project.serializer(), s)
        assertEquals(p, back)
    }

    @Test
    fun canvasSizes() {
        val p = project()
        assertEquals(1080 to 1920, p.canvasSize(1080))
        assertEquals(720 to 1280, p.canvasSize(720))
        val wide = p.copy(canvas = p.canvas.copy(ratio = com.vidgod.editor.model.AspectRatio.R16_9))
        assertEquals(1920 to 1080, wide.canvasSize(1080))
    }
}
