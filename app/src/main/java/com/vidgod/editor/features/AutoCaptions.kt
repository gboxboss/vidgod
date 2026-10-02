package com.vidgod.editor.features

import android.content.Context
import com.vidgod.editor.media.Waveforms
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.TextStyle
import com.vidgod.editor.model.Transform
import com.vidgod.editor.model.WordTiming
import com.vidgod.editor.model.newId
import com.vidgod.editor.model.sourceToTimelineUs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Offline speech recognition (Vosk) that turns the project's speech into timed captions. */
object AutoCaptions {

    data class Language(val code: String, val name: String, val model: String, val sizeMb: Int)

    val languages = listOf(
        Language("en", "English", "vosk-model-small-en-us-0.15", 40),
        Language("es", "Español", "vosk-model-small-es-0.42", 39),
        Language("fr", "Français", "vosk-model-small-fr-0.22", 41),
        Language("de", "Deutsch", "vosk-model-small-de-0.15", 45),
        Language("pt", "Português", "vosk-model-small-pt-0.3", 31),
        Language("it", "Italiano", "vosk-model-small-it-0.22", 48),
        Language("ru", "Русский", "vosk-model-small-ru-0.22", 45),
        Language("hi", "हिन्दी", "vosk-model-small-hi-0.22", 42),
        Language("zh", "中文", "vosk-model-small-cn-0.22", 42),
        Language("ja", "日本語", "vosk-model-small-ja-0.22", 48),
        Language("ko", "한국어", "vosk-model-small-ko-0.22", 82),
        Language("tr", "Türkçe", "vosk-model-small-tr-0.3", 35),
        Language("nl", "Nederlands", "vosk-model-small-nl-0.22", 39),
        Language("pl", "Polski", "vosk-model-small-pl-0.22", 50),
        Language("uk", "Українська", "vosk-model-small-uk-v3-small", 133),
        Language("vi", "Tiếng Việt", "vosk-model-small-vn-0.4", 32),
        Language("fa", "فارسی", "vosk-model-small-fa-0.42", 53),
    )

    private fun modelDir(context: Context, lang: Language) = File(context.filesDir, "vosk/${lang.model}")

    /** Written after a model was fully unpacked (an interrupted install must not count). */
    private fun marker(context: Context, lang: Language) = File(modelDir(context, lang), ".installed")

    fun isInstalled(context: Context, lang: Language) = marker(context, lang).exists()

    /** Downloads and unpacks a model. [onProgress] receives 0..1. */
    suspend fun install(context: Context, lang: Language, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        modelDir(context, lang).deleteRecursively()
        val zip = File(root, lang.model + ".zip")
        val url = URL("https://alphacephei.com/vosk/models/${lang.model}.zip")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("Download failed (${conn.responseCode})")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (lang.sizeMb * 1_000_000L)
        conn.inputStream.use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress((read.toFloat() / total).coerceIn(0f, 0.95f))
                }
            }
        }
        // Unzip: archives contain a single top-level folder named like the model.
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(root, e.name)
                if (!out.canonicalPath.startsWith(root.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        check(File(modelDir(context, lang), "conf").exists() || File(modelDir(context, lang), "am").exists()) {
            "The downloaded speech model is incomplete. Please try again."
        }
        marker(context, lang).writeText("ok")
        onProgress(1f)
    }

    data class Word(val word: String, val startUs: Long, val endUs: Long)

    /**
     * Transcribes all speech in the main track (and voice-over/audio tracks when [includeAudio]).
     * Returns words with timeline times.
     */
    suspend fun transcribe(
        context: Context,
        project: Project,
        lang: Language,
        includeAudio: Boolean,
        onProgress: (Float) -> Unit,
    ): List<Word> = withContext(Dispatchers.Default) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(modelDir(context, lang).absolutePath)
        val words = ArrayList<Word>()
        try {
            data class Job(val uri: String, val startSrc: Long, val endSrc: Long, val map: (Long) -> Long)
            val jobs = ArrayList<Job>()
            var t = 0L
            if (!project.mainMuted) {
                for (c in project.clips) {
                    val start = t
                    if (c.source.kind == MediaKind.VIDEO && c.source.hasAudio && !c.muted && c.volume > 0f) {
                        jobs.add(Job(c.playbackUri, c.trimStartUs, c.trimEndUs) { src -> start + c.sourceToTimelineUs(src) })
                    }
                    t += c.durationUs
                }
            }
            if (includeAudio) {
                for (a in project.audios) {
                    jobs.add(Job(a.source.uri, a.trimStartUs, a.trimEndUs) { src -> a.startUs + ((src - a.trimStartUs) / a.speed).toLong() })
                }
            }
            val totalUs = jobs.sumOf { it.endSrc - it.startSrc }.coerceAtLeast(1)
            var doneUs = 0L
            for (job in jobs) {
                coroutineContext.ensureActive()
                val rec = Recognizer(model, 16000f)
                rec.setWords(true)
                var samples = 0L
                fun collect(json: String) {
                    val o = JSONObject(json)
                    val arr = o.optJSONArray("result") ?: return
                    for (i in 0 until arr.length()) {
                        val w = arr.getJSONObject(i)
                        val s = job.startSrc + (w.getDouble("start") * 1e6).toLong()
                        val e = job.startSrc + (w.getDouble("end") * 1e6).toLong()
                        if (s > job.endSrc) continue
                        words.add(Word(w.getString("word"), job.map(s), job.map(e.coerceAtMost(job.endSrc))))
                    }
                }
                Waveforms.decodeMono16(context, job.uri, job.startSrc, job.endSrc, 16000) { buf, n ->
                    // Stop at the trim end.
                    val remaining = ((job.endSrc - job.startSrc) * 16 / 1000 - samples).toInt()
                    val use = minOf(n, remaining.coerceAtLeast(0))
                    if (use > 0 && rec.acceptWaveForm(buf, use)) collect(rec.result)
                    samples += use
                    onProgress(((doneUs + samples * 1000 / 16).toFloat() / totalUs).coerceIn(0f, 1f))
                }
                collect(rec.finalResult)
                rec.close()
                doneUs += job.endSrc - job.startSrc
            }
        } finally {
            model.close()
        }
        words.sortedBy { it.startUs }
    }

    /** Groups words into short caption lines. */
    fun toCaptions(words: List<Word>, style: TextStyle, maxWords: Int = 4, maxUs: Long = 2_500_000): List<TextClip> {
        val out = ArrayList<TextClip>()
        var group = ArrayList<Word>()
        fun flush() {
            if (group.isEmpty()) return
            val start = group.first().startUs
            val end = maxOf(group.last().endUs, start + 300_000)
            out.add(
                TextClip(
                    id = newId(),
                    text = group.joinToString(" ") { it.word },
                    startUs = start,
                    durationUs = end - start,
                    style = style,
                    transform = Transform(y = 0.28f),
                    isCaption = true,
                    words = group.map { WordTiming(it.word, it.startUs - start, it.endUs - start) },
                ),
            )
            group = ArrayList()
        }
        for (w in words) {
            val first = group.firstOrNull()
            val last = group.lastOrNull()
            if (first != null && (group.size >= maxWords || w.endUs - first.startUs > maxUs || (last != null && w.startUs - last.endUs > 600_000))) flush()
            group.add(w)
        }
        flush()
        // Avoid overlaps: each caption ends when the next one starts.
        for (i in 0 until out.size - 1) {
            val a = out[i]
            val b = out[i + 1]
            if (a.endUs > b.startUs) out[i] = a.copy(durationUs = (b.startUs - a.startUs).coerceAtLeast(100_000))
        }
        return out
    }
}
