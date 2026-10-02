package com.vidgod.editor

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Uses the app like a person would: opens media, plays, opens every tool and panel, adds text
 * and stickers, exports. Each step leaves a screenshot in the test output.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val device: UiDevice get() = UiDevice.getInstance(T.instrumentation)

    private fun ComposeTestRule.exists(m: SemanticsMatcher) = onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeTestRule.await(m: SemanticsMatcher, timeoutMs: Long = 15_000) = waitUntil(timeoutMs) { exists(m) }

    /** Clicks the first clickable node showing exactly [label] (toolbar tools, buttons, pills). */
    private fun tap(label: String) {
        val m = hasText(label) and hasClickAction()
        compose.await(m, 8_000)
        compose.onAllNodes(m).onFirst().apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private fun tapIcon(description: String) {
        val m = hasContentDescription(description) and hasClickAction()
        compose.await(m, 8_000)
        compose.onAllNodes(m).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun back() {
        device.pressBack()
        pause(500)
    }

    /** The "00:02 / 00:10" label, read through UI Automator (no Compose idling while playing). */
    private fun uiTime(): String =
        device.findObject(By.text(java.util.regex.Pattern.compile("\\d\\d:\\d\\d / \\d\\d:\\d\\d")))?.text.orEmpty()

    /**
     * Plays for [ms] and pauses. The play button is tapped through UI Automator at its position
     * (while the video plays, the Compose test clock is not advanced, so the UI is not refreshed
     * until playback stops); the time is read once the UI has settled.
     */
    private fun playFor(ms: Long): Pair<String, String> {
        compose.waitForIdle()
        val before = timeText()
        val button = checkNotNull(device.wait(Until.findObject(By.desc("Play")), 8_000)) { "No Play button" }
        val c = button.visibleCenter
        device.click(c.x, c.y)
        Thread.sleep(ms)
        device.click(c.x, c.y)
        Thread.sleep(800)
        compose.waitForIdle()
        val after = timeText()
        T.log("play before=$before after=$after")
        return before to after
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    /** Closes the open panel (system back) and clears the selection. */
    private fun closePanelAndDeselect() {
        back()
        runCatching { tapIcon("Back") }
        pause(300)
    }

    /** Finds a file in the system file picker: among the recent files, else in Downloads. */
    private fun findInFilePicker(namePart: String): androidx.test.uiautomator.UiObject2? {
        device.wait(Until.findObject(By.textContains(namePart)), 6_000)?.let { return it }
        device.findObject(By.desc("Show roots"))?.click()
        Thread.sleep(1000)
        device.wait(Until.findObject(By.text("Downloads")), 3_000)?.click()
        return device.wait(Until.findObject(By.textContains(namePart)), 8_000)
    }

    private val lengthLabel = Regex("^\\d\\d:\\d\\d\\.\\d$")

    /** The length labels ("00:04.0") of the clips shown in the timeline. */
    private fun clipLengths(): List<String> =
        compose.onAllNodes(SemanticsMatcher("clip length") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { lengthLabel.matches(it.text) } == true
        }).fetchSemanticsNodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }

    private fun handleX(tag: String): Float =
        compose.onAllNodes(hasTestTag(tag)).onFirst().fetchSemanticsNode().boundsInRoot.center.x

    /**
     * Drags the trim handle [tag] of the selected clip by 40% of the screen width in direction [dir]
     * (slowly and slightly wobbly, like a finger), checks that the clip length changed, and undoes
     * the trim.
     */
    private fun dragHandle(tag: String, dir: Int) {
        val before = clipLengths()
        val total = timeText().substringAfter(" / ")
        val step = dir * device.displayWidth * 0.025f
        compose.onAllNodes(hasTestTag(tag)).onFirst().performTouchInput {
            down(center)
            for (k in 1..16) moveBy(Offset(step, if (k % 2 == 0) 2f else -2f), 16)
            up()
        }
        pause(1500)
        val after = clipLengths()
        T.log("$tag: clip lengths $before -> $after, total $total -> ${timeText().substringAfter(" / ")}")
        T.screenshot("ui_${tag}_dragged")
        check(before != after) { "Dragging $tag did not change the clip length ($before -> $after)" }
        tapIcon("Undo")
        pause(800)
        check(clipLengths() == before) { "Undo did not restore the clip length (${clipLengths()} instead of $before)" }
    }

    private fun timeText(): String =
        compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private fun dumpDiagnostics(prefix: String) {
        runCatching { device.dumpWindowHierarchy(File(T.out, "${prefix}_hierarchy.xml")) }
        File(T.app.filesDir, "diagnostics").listFiles()?.filter { it.isFile }?.forEach {
            it.copyTo(File(T.out, "${prefix}_app_${it.name}"), overwrite = true)
        }
    }

    private fun shareIntent(vararg names: String): Intent {
        val uris = names.map { FileProvider.getUriForFile(T.app, T.app.packageName + ".files", T.media(it)) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType("video/*")
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
        }
        return intent.setClass(T.app, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun editorFlow() {
        val s = Steps("ui")
        addToMediaStore("music.m4a", "audio/mp4")
        ActivityScenario.launch<MainActivity>(shareIntent("portrait.mp4", "rotated.mp4", "photo.jpg")).use {
            s.step("open_editor") {
                compose.await(hasText("Export") and hasClickAction(), 40_000)
                pause(3000)
            }
            s.step("play_pause") {
                val (before, after) = playFor(2500)
                check(before != after) { "Playback did not move the playhead ($before -> $after)" }
            }
            s.step("background_pauses") {
                // Playing, then another app comes to the front: playback must stop.
                compose.waitForIdle()
                val button = checkNotNull(device.wait(Until.findObject(By.desc("Play")), 8_000)) { "No Play button" }
                val c = button.visibleCenter
                device.click(c.x, c.y)
                Thread.sleep(1200)
                device.pressHome()
                Thread.sleep(2500)
                // Back to the app (through the shell, which may start activities from the background).
                device.executeShellCommand("am start -n ${T.app.packageName}/${MainActivity::class.java.name}")
                // (Compose waits: the test clock must advance for the UI to show the new state.)
                val paused = runCatching { compose.waitUntil(10_000) { compose.exists(hasContentDescription("Play")) } }.isSuccess
                check(paused) { "Still playing after the app went to the background" }
                pause(1000)
                val t1 = timeText()
                Thread.sleep(1500)
                compose.waitForIdle()
                val t2 = timeText()
                T.log("after background: $t1 -> $t2")
                check(t1 == t2) { "The playhead moves while paused ($t1 -> $t2)" }
                // The preview shows the paused frame again (the surface was recreated).
                val shot = checkNotNull(T.screenshot("ui_after_background")) { "No screenshot" }
                val crop = android.graphics.Bitmap.createBitmap(
                    shot, (shot.width * 0.35f).toInt(), (shot.height * 0.15f).toInt(),
                    (shot.width * 0.3f).toInt(), (shot.height * 0.25f).toInt(),
                )
                val (mean, sd) = Inspect.stats(crop)
                T.log("preview after background: mean=%.1f sd=%.1f".format(mean, sd))
                check(sd > 4.0) { "The preview is blank after returning to the app (sd=$sd)" }
            }
            s.step("scrub_timeline") {
                val before = timeText()
                compose.onNodeWithTag("timeline").performTouchInput {
                    swipe(Offset(centerX + width * 0.3f, centerY), Offset(centerX - width * 0.3f, centerY), 700)
                }
                pause(1200)
                val after = timeText()
                T.log("scrub before=$before after=$after")
                check(before != after) { "Dragging the timeline did not move the playhead ($before -> $after)" }
            }
            s.step("tap_clip_selects") {
                // Left of the playhead (which may sit at the very end after the scrub).
                compose.onNodeWithTag("main_track").performTouchInput { click(Offset(width * 0.3f, centerY)) }
                compose.await(hasText("Split"), 5_000)
                tapIcon("Back")
                compose.await(hasText("Stickers"), 5_000)
            }
            s.step("back_to_start") {
                // Drag the timeline far right: the playhead goes back to the first clip.
                compose.waitForIdle()
                compose.onNodeWithTag("timeline").performTouchInput {
                    swipe(Offset(width * 0.05f, centerY), Offset(width * 0.95f, centerY), 500)
                }
                pause(800)
                compose.onNodeWithTag("timeline").performTouchInput {
                    swipe(Offset(width * 0.05f, centerY), Offset(width * 0.95f, centerY), 500)
                }
                pause(1200)
                val t = timeText()
                T.log("after back_to_start: $t")
                check(t.startsWith("00:00")) { "Playhead did not return to the start ($t)" }
            }
            s.step("select_clip") {
                tap("Edit")
                compose.await(hasText("Split"), 5_000)
            }
            s.step("trim_clip_start") {
                // The playhead is at the start of the selected clip, so its left handle is on screen.
                dragHandle("trim_start", 1)
            }
            s.step("trim_clip_end") {
                // The end of the clip is off screen: scroll the timeline (slowly, no fling) until
                // its handle shows.
                val screenW = device.displayWidth
                for (k in 0 until 10) {
                    if (handleX("trim_end") < screenW * 0.8f) break
                    compose.onNodeWithTag("timeline").performTouchInput {
                        swipe(Offset(width * 0.7f, centerY), Offset(width * 0.45f, centerY), 900)
                    }
                    pause(700)
                }
                val x = handleX("trim_end")
                check(x in 0f..screenW * 0.8f) { "The end of the clip did not scroll into view (handle at $x of $screenW)" }
                dragHandle("trim_end", -1)
            }
            s.step("pinch_zoom") {
                fun clipWidth() = handleX("trim_end") - handleX("trim_start")
                val w0 = clipWidth()
                compose.onNodeWithTag("timeline").performTouchInput {
                    pinch(
                        Offset(centerX - width * 0.08f, centerY), Offset(centerX - width * 0.3f, centerY),
                        Offset(centerX + width * 0.08f, centerY), Offset(centerX + width * 0.3f, centerY),
                        500,
                    )
                }
                pause(800)
                val w1 = clipWidth()
                compose.onNodeWithTag("timeline").performTouchInput {
                    pinch(
                        Offset(centerX - width * 0.3f, centerY), Offset(centerX - width * 0.08f, centerY),
                        Offset(centerX + width * 0.3f, centerY), Offset(centerX + width * 0.08f, centerY),
                        500,
                    )
                }
                pause(800)
                val w2 = clipWidth()
                T.log("pinch zoom: clip width $w0 -> $w1 -> $w2")
                check(w1 > w0 * 1.5f) { "Pinching out did not zoom in ($w0 -> $w1)" }
                check(w2 < w1 / 1.5f) { "Pinching in did not zoom out ($w1 -> $w2)" }
            }
            for (tool in listOf("Speed", "Volume", "Animation", "Filters", "Adjust", "Effects", "Transform", "Opacity", "Mask", "Chroma key", "Voice FX", "Transition")) {
                s.step("clip_$tool") {
                    tap(tool)
                    pause(1200)
                    T.screenshot("ui_panel_${tool.replace(' ', '_')}")
                    back()
                    compose.await(hasText("Split"), 5_000)
                }
            }
            s.step("split") {
                tap("Split")
                pause(1500)
            }
            s.step("deselect") {
                tapIcon("Back")
                compose.await(hasText("Stickers"), 5_000)
            }
            s.step("add_text") {
                tap("Text")
                tap("Add text")
                compose.await(hasSetTextAction(), 5_000)
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Hello VidGod")
                pause(1200)
                T.screenshot("ui_text_typed")
                tap("Done")
                pause(1000)
            }
            s.step("deselect_text") {
                runCatching { tapIcon("Back") }
                pause(500)
            }
            s.step("add_sticker") {
                tap("Stickers")
                tap("😀")
                pause(1500)
                back()
            }
            s.step("deselect_sticker") {
                runCatching { tapIcon("Back") }
                compose.await(hasText("Effects") and hasClickAction(), 5_000)
            }
            s.step("add_sound_effect") {
                val before = count("item_audio")
                tap("Audio")
                tap("Whoosh")
                compose.waitUntil(10_000) { count("item_audio") > before }
                pause(800)
                closePanelAndDeselect()
            }
            s.step("add_music_from_files") {
                val before = count("item_audio")
                tap("Audio")
                tap("Music")
                pause(2500)
                check(device.currentPackageName != T.app.packageName) { "The file picker did not open" }
                val file = findInFilePicker("vidgodtest_music")
                T.screenshot("ui_music_picker")
                if (file == null) runCatching { device.dumpWindowHierarchy(File(T.out, "ui_music_picker.xml")) }
                checkNotNull(file) { "The music file is not listed in the file picker" }.click()
                compose.waitUntil(20_000) { count("item_audio") > before }
                pause(1000)
                T.screenshot("ui_music_added")
                closePanelAndDeselect()
            }
            for (tool in listOf("Styles", "Audio", "Effects", "Filters", "Captions", "Ratio", "Canvas", "Reorder")) {
                s.step("panel_$tool") {
                    tap(tool)
                    pause(1500)
                    T.screenshot("ui_root_${tool}")
                    back()
                    runCatching { tapIcon("Back") }
                    pause(300)
                }
            }
            s.step("ratio_1_1") {
                tap("Ratio")
                tap("1:1")
                pause(2500)
                // The canvas is square and the (portrait) video is centred in it.
                val box = compose.onNodeWithTag("preview_canvas").fetchSemanticsNode().boundsInRoot
                val shot = checkNotNull(T.screenshot("ui_ratio_square")) { "No screenshot" }
                T.log("1:1 canvas: ${box.width} x ${box.height}")
                check(kotlin.math.abs(box.width - box.height) < 4f) { "The canvas is not square (${box.width} x ${box.height})" }
                val k = shot.width.toFloat() / device.displayWidth
                val left = (box.left * k).toInt().coerceIn(0, shot.width - 2)
                val top = (box.top * k).toInt().coerceIn(0, shot.height - 2)
                val crop = android.graphics.Bitmap.createBitmap(
                    shot, left, top,
                    (box.width * k).toInt().coerceIn(1, shot.width - left), (box.height * k).toInt().coerceIn(1, shot.height - top),
                )
                val cx = Inspect.brightnessCentreX(crop)
                T.log("1:1 preview: centre of brightness at x=%.2f".format(cx))
                check(kotlin.math.abs(cx - 0.5) < 0.12) { "The video is not centred in the 1:1 canvas (centre at %.2f)".format(cx) }
                tap("9:16")
                pause(1500)
                back()
            }
            s.step("undo_redo") {
                tapIcon("Undo")
                pause(1000)
                tapIcon("Redo")
                pause(1000)
            }
            s.step("fullscreen") {
                tapIcon("Full screen")
                pause(1500)
                // The 9:16 video fills the screen's width: its lower half is below the editor's
                // preview area.
                val shot = checkNotNull(T.screenshot("ui_fullscreen")) { "No screenshot" }
                val crop = android.graphics.Bitmap.createBitmap(
                    shot, (shot.width * 0.35f).toInt(), (shot.height * 0.55f).toInt(),
                    (shot.width * 0.3f).toInt(), (shot.height * 0.15f).toInt(),
                )
                val (mean, sd) = Inspect.stats(crop)
                T.log("full screen lower half: mean=%.1f sd=%.1f".format(mean, sd))
                check(sd > 4.0 || mean > 30.0) { "The full-screen preview does not fill the screen (lower half blank, sd=$sd)" }
                tapIcon("Exit full screen")
                pause(800)
            }
            s.step("play_after_edits") {
                val (before, after) = playFor(2500)
                check(before != after) { "Playback after edits did not move the playhead ($before -> $after)" }
            }
            s.step("export") {
                tap("Export")
                compose.await(hasText("Export video"), 10_000)
                T.screenshot("ui_export_settings")
                tap("Export video")
                val done = hasText("Saved to your gallery") or hasText("Export finished") or hasText("Export failed")
                compose.waitUntil(300_000) { compose.exists(done) }
                pause(500)
                T.screenshot("ui_export_result")
                check(!compose.exists(hasText("Export failed"))) { "Export failed in the UI" }
                tap("Done")
                pause(1500)
            }
            s.step("back_home") {
                repeat(3) { if (!compose.exists(hasText("New project"))) back() }
                compose.await(hasText("New project"), 10_000)
                pause(1500)
            }
            s.step("reopen_project") {
                // The most recent project is first: the one just edited.
                compose.onAllNodes(hasClickAction() and hasAnyChild(hasContentDescription("More"))).onFirst().performClick()
                compose.await(hasText("Export") and hasClickAction(), 30_000)
                pause(2500)
                val total = timeText()
                T.log("reopened project: $total")
                check(total.isNotEmpty() && !total.endsWith("00:00")) { "The reopened project is empty ($total)" }
                back()
                repeat(2) { if (!compose.exists(hasText("New project"))) back() }
                compose.await(hasText("New project"), 10_000)
                pause(1000)
            }
            s.step("duplicate_rename_delete_project") {
                tapIcon("More")
                tap("Duplicate")
                val copy = hasText(" copy", substring = true)
                compose.await(copy, 10_000)
                // Rename the copy.
                compose.onAllNodes(hasContentDescription("More") and hasParent(copy)).onFirst().performClick()
                tap("Rename")
                compose.await(hasSetTextAction(), 5_000)
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Renamed by test")
                tap("Save")
                compose.await(hasText("Renamed by test", substring = true), 10_000)
                T.screenshot("ui_home_renamed")
                // Delete it.
                compose.onAllNodes(hasContentDescription("More") and hasParent(hasText("Renamed by test", substring = true))).onFirst().performClick()
                tap("Delete")
                tap("Delete")
                compose.waitUntil(10_000) { !compose.exists(hasText("Renamed by test", substring = true)) }
                pause(800)
            }
            dumpDiagnostics("ui")
        }
        s.assertAllPassed()
    }

    private fun addToMediaStore(name: String, mime: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val (collection, folder) = when {
            mime.startsWith("video") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/VidGodTest"
            mime.startsWith("image") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/VidGodTest"
            // Audio goes to Downloads, which the system file picker lists.
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "vidgodtest_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = T.app.contentResolver
        val uri = r.insert(collection, values) ?: return
        r.openOutputStream(uri)?.use { os -> T.media(name).inputStream().use { it.copyTo(os) } }
        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /** The real first-run path: Home → New project → system photo picker → editor. */
    @Test
    fun newProjectThroughPhotoPicker() {
        val s = Steps("picker")
        addToMediaStore("portrait.mp4", "video/mp4")
        addToMediaStore("photo.jpg", "image/jpeg")
        ActivityScenario.launch(MainActivity::class.java).use {
            s.step("home") { compose.await(hasText("New project"), 20_000); pause(1000) }
            s.step("open_picker") {
                tap("New project")
                pause(3000)
                check(device.currentPackageName != T.app.packageName) { "Photo picker did not open" }
            }
            s.step("choose_media") {
                val items = device.wait(Until.findObjects(By.descContains("taken on")), 10_000).orEmpty()
                    .ifEmpty { device.wait(Until.findObjects(By.descStartsWith("Video")), 3_000).orEmpty() }
                    .ifEmpty { device.wait(Until.findObjects(By.descStartsWith("Photo")), 3_000).orEmpty() }
                T.log("picker items: ${items.map { it.contentDescription }}")
                check(items.isNotEmpty()) { "No media in the picker" }
                items.take(2).forEach { it.click(); Thread.sleep(500) }
                T.screenshot("picker_selected")
                val add = device.wait(Until.findObject(By.textStartsWith("Add")), 5_000)
                    ?: device.wait(Until.findObject(By.textContains("Done")), 2_000)
                checkNotNull(add) { "No Add button in the picker" }.click()
            }
            s.step("editor_opened") {
                compose.await(hasText("Export") and hasClickAction(), 40_000)
                pause(3000)
            }
            s.step("play") {
                val (before, after) = playFor(2500)
                check(before != after) { "Playback did not move the playhead ($before -> $after)" }
            }
            dumpDiagnostics("picker")
        }
        s.assertAllPassed()
    }
}
