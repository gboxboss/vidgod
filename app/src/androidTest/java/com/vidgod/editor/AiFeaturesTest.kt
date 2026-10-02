package com.vidgod.editor

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidgod.editor.data.MediaProbe
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.features.AutoCaptions
import com.vidgod.editor.features.TtsEngine
import com.vidgod.editor.model.AudioClip
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.MediaSource
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

    private fun transcribe(speech: MediaSource, lang: AutoCaptions.Language): List<AutoCaptions.Word> {
        val photo = ProjectOps.visualFrom(T.source("photo.jpg")).copy(trimEndUs = speech.durationUs.coerceAtLeast(1_000_000))
        val p = Project(
            clips = listOf(photo),
            audios = listOf(AudioClip(source = speech, trimEndUs = speech.durationUs)),
        )
        return runBlocking { withTimeout(300_000) { AutoCaptions.transcribe(T.app, p, lang, includeAudio = true) {} } }
    }

    /** Speech from the device's text-to-speech engine (a natural voice), or null without one. */
    private fun ttsSpeech(text: String): MediaSource? {
        val engine = runBlocking { withContext(Dispatchers.Main) { TtsEngine(T.app) } }
        try {
            if (!runBlocking { engine.awaitReady() }) return null
            val out = File(T.app.cacheDir, "captions-tts.wav").apply { delete() }
            val ok = runBlocking { withTimeout(90_000) { engine.synthesize(text, out, Locale.US, null, 1f, 0.9f) } }
            if (!ok) return null
            return runBlocking { MediaProbe.probe(T.app, Uri.fromFile(out), MediaKind.AUDIO) }
        } finally {
            engine.shutdown()
        }
    }

    /**
     * Speech from the text-to-speech engine and the CI's espeak-ng sample (media/speech.wav, a
     * robotic voice), both saying "hello world, this is a caption test", is transcribed offline.
     */
    @Test
    fun autoCaptionsTranscribeSpeech() {
        val lang = AutoCaptions.languages.first { it.code == "en" }
        runBlocking { withTimeout(600_000) { if (!AutoCaptions.isInstalled(T.app, lang)) AutoCaptions.install(T.app, lang) {} } }
        val samples = buildList {
            ttsSpeech("Hello world. This is a caption test.")?.let { add("text to speech" to it) }
            if (hasAsset("speech.wav")) add("espeak" to T.source("speech.wav"))
        }
        assumeTrue("no speech sample", samples.isNotEmpty())
        val expected = listOf("hello", "world", "caption", "test")
        var best = 0
        for ((name, speech) in samples) {
            val words = transcribe(speech, lang)
            val text = words.joinToString(" ") { it.word }.lowercase()
            val hits = expected.count { it in text }
            T.log("captions from $name: \"$text\" ($hits of ${expected.size} words) " + words.joinToString { "${it.word}@${it.startUs / 1000}ms" })
            if (words.isNotEmpty()) assertTrue("no caption clips", AutoCaptions.toCaptions(words, TextStyle()).isNotEmpty())
            best = maxOf(best, hits)
        }
        assertTrue("speech not recognised (at best $best of ${expected.size} words)", best >= 3)
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
