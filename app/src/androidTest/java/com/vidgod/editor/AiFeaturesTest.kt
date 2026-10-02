package com.vidgod.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.features.AutoCaptions
import com.vidgod.editor.features.TtsEngine
import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Offline speech recognition (auto captions) and text to speech on the device. */
@RunWith(AndroidJUnit4::class)
class AiFeaturesTest {

    private fun hasAsset(name: String) =
        runCatching { T.instrumentation.context.assets.open("media/$name").close() }.isSuccess

    /** The CI generates media/speech.wav with espeak-ng ("hello world, this is a caption test"). */
    @Test
    fun autoCaptionsTranscribeSpeech() {
        assumeTrue("speech sample not generated", hasAsset("speech.wav"))
        val speech = T.source("speech.wav")
        T.log("speech source: $speech")
        val photo = ProjectOps.visualFrom(T.source("photo.jpg")).copy(trimEndUs = speech.durationUs.coerceAtLeast(1_000_000))
        val p = Project(
            clips = listOf(photo),
            audios = listOf(AudioClip(source = speech, trimEndUs = speech.durationUs)),
        )
        val lang = AutoCaptions.languages.first { it.code == "en" }
        val words = runBlocking {
            withTimeout(600_000) {
                if (!AutoCaptions.isInstalled(T.app, lang)) AutoCaptions.install(T.app, lang) {}
                AutoCaptions.transcribe(T.app, p, lang, includeAudio = true) {}
            }
        }
        T.log("caption words: ${words.joinToString { "${it.word}@${it.startUs / 1000}ms" }}")
        assertTrue("no words recognised", words.isNotEmpty())
        val text = words.joinToString(" ") { it.word }.lowercase()
        assertTrue("unexpected transcript: $text", listOf("hello", "world", "caption", "test").count { it in text } >= 2)
        val captions = AutoCaptions.toCaptions(words, TextStyle())
        assertTrue("no caption clips", captions.isNotEmpty())
    }

    @Test
    fun textToSpeechWritesAudio() {
        val engine = runBlocking { withContext(Dispatchers.Main) { TtsEngine(T.app) } }
        val ready = runBlocking { engine.awaitReady() }
        assumeTrue("no text-to-speech engine on this device", ready)
        val out = File(T.app.cacheDir, "tts-test.wav").apply { delete() }
        val ok = runBlocking { withTimeout(90_000) { engine.synthesize("Hello from VidGod", out, Locale.US, null, 1f, 1f) } }
        T.log("tts ok=$ok size=${out.length()}")
        engine.shutdown()
        assertTrue("speech synthesis failed", ok && out.length() > 1000)
    }
}
