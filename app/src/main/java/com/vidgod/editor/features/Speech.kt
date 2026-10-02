package com.vidgod.editor.features

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/** Text-to-speech using the phone's TTS engine (works offline with installed voices). */
class TtsEngine(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    suspend fun awaitReady(): Boolean = runCatching { withTimeout(8_000) { ready.await() } }.getOrDefault(false)

    fun voices(): List<Voice> = runCatching {
        tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired || it.features?.contains("notInstalled") != true }
            .sortedWith(compareBy({ it.locale.displayLanguage }, { it.name }))
    }.getOrDefault(emptyList())

    fun languages(): List<Locale> = runCatching {
        tts.availableLanguages.orEmpty().sortedBy { it.displayName }
    }.getOrDefault(emptyList())

    /** Writes [text] spoken with the given options to a WAV file. */
    suspend fun synthesize(text: String, out: File, locale: Locale?, voice: Voice?, pitch: Float, rate: Float): Boolean {
        if (!awaitReady()) return false
        val done = CompletableDeferred<Boolean>()
        val id = "tts_" + System.nanoTime()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
        })
        if (voice != null) tts.voice = voice else if (locale != null) tts.language = locale
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val r = tts.synthesizeToFile(text, null, out, id)
        if (r != TextToSpeech.SUCCESS) return false
        return runCatching { withTimeout(60_000) { done.await() } }.getOrDefault(false) && out.length() > 44
    }

    fun shutdown() = tts.shutdown()
}

/** Records the microphone to an AAC file for voice-overs. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set
    private var startedAt = 0L

    fun start(out: File) {
        stop()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioChannels(1)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
    }

    val elapsedMs get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    /** 0..1 input level. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)

    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            file
        } catch (e: RuntimeException) {
            file?.delete()
            null
        } finally {
            r.release()
        }
    }
}

suspend fun ttsFile(context: Context, dir: File): File = withContext(Dispatchers.IO) {
    File(dir, "tts_${System.currentTimeMillis()}.wav")
}
