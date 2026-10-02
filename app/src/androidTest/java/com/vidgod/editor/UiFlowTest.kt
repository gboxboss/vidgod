package com.vidgod.editor

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
        ActivityScenario.launch<MainActivity>(shareIntent("portrait.mp4", "rotated.mp4", "photo.jpg")).use {
            s.step("open_editor") {
                compose.await(hasText("Export") and hasClickAction(), 40_000)
                pause(3000)
            }
            s.step("play_pause") {
                val (before, after) = playFor(2500)
                check(before != after) { "Playback did not move the playhead ($before -> $after)" }
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
            s.step("trim_clip_end") {
                val before = timeText().substringAfter(" / ")
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("trim_end")).onFirst().performTouchInput {
                    // A slow, slightly wobbly drag, like a finger.
                    down(center)
                    for (k in 1..12) moveBy(Offset(-15f, if (k % 2 == 0) 2f else -2f), 16)
                    up()
                }
                pause(1500)
                val after = timeText().substringAfter(" / ")
                T.log("trim total before=$before after=$after")
                check(before != after) { "Dragging the trim handle did not change the length ($before -> $after)" }
                tapIcon("Undo")
                pause(800)
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
                pause(2000)
                T.screenshot("ui_ratio_square")
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
                T.screenshot("ui_fullscreen")
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
            dumpDiagnostics("ui")
        }
        s.assertAllPassed()
    }

    private fun addToMediaStore(name: String, mime: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val video = mime.startsWith("video")
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "vidgodtest_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else "Pictures") + "/VidGodTest")
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
