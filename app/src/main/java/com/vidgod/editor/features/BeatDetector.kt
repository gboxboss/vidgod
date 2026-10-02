package com.vidgod.editor.features

import android.content.Context
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.media.Waveforms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Finds beats (energy onsets) in audio so cuts can be placed on the rhythm. */
object BeatDetector {
    suspend fun detect(context: Context, uri: String, startUs: Long, endUs: Long): List<Long> = withContext(Dispatchers.Default) {
        val rate = 11025
        val window = 512
        val energies = ArrayList<Float>()
        var acc = 0f
        var n = 0
        Waveforms.decodeMono16(context, uri, startUs, endUs, rate) { buf, count ->
            for (i in 0 until count) {
                val v = buf[i] / 32768f
                acc += v * v
                n++
                if (n == window) {
                    energies.add(sqrt(acc / window))
                    acc = 0f
                    n = 0
                }
            }
        }
        val hopUs = window * 1_000_000L / rate
        val history = 43 // about one second
        val beats = ArrayList<Long>()
        var lastBeat = -1_000_000L
        for (i in energies.indices) {
            val from = (i - history).coerceAtLeast(0)
            var mean = 0f
            for (j in from until i) mean += energies[j]
            mean /= (i - from).coerceAtLeast(1)
            var variance = 0f
            for (j in from until i) variance += (energies[j] - mean) * (energies[j] - mean)
            variance /= (i - from).coerceAtLeast(1)
            val threshold = mean * (1.35f + 1.5f * (1f - (variance / (mean * mean + 1e-6f)).coerceIn(0f, 1f)) * 0.2f)
            val t = startUs + i * hopUs
            if (energies[i] > threshold && energies[i] > 0.02f && t - lastBeat > 280_000) {
                beats.add(t)
                lastBeat = t
            }
        }
        beats
    }

    /** Detects beats of the selected audio clip and stores them for snapping. */
    fun run(vm: EditorViewModel, context: Context, audioId: String) {
        val a = vm.project.value.audios.firstOrNull { it.id == audioId } ?: return
        vm.runBusy("Detecting beats…") {
            val beats = detect(context, a.source.uri, a.trimStartUs, a.trimEndUs)
            vm.editAudio(audioId) { it.copy(beatsUs = beats) }
            vm.toast("Found ${beats.size} beats — yellow marks on the audio track")
        }
    }

    /** Splits main-track clips so that cuts land on beats ("auto beat sync"). */
    fun syncCuts(vm: EditorViewModel, audioId: String) {
        val p = vm.project.value
        val a = p.audios.firstOrNull { it.id == audioId } ?: return
        if (a.beatsUs.isEmpty()) return vm.toast("Detect beats first")
        val beatTimes = a.beatsUs.map { a.startUs + ((it - a.trimStartUs) / a.speed).toLong() }.filter { it > 0 && it < p.durationUs }
        if (beatTimes.size < 2) return vm.toast("Not enough beats")
        // Give every clip the length of one beat interval, cycling clips if needed.
        vm.update { pr ->
            val clips = pr.clips
            if (clips.isEmpty()) return@update pr
            val out = ArrayList<com.vidgod.editor.model.VisualClip>()
            var prev = 0L
            var i = 0
            for (b in beatTimes) {
                val len = b - prev
                if (len < 150_000) continue
                val c = clips[i % clips.size]
                val target = if (c.isImage) c.copy(id = com.vidgod.editor.model.newId(), trimStartUs = 0, trimEndUs = len, transitionOut = null)
                else {
                    val src = c.trimStartUs + (len * c.speed).toLong()
                    c.copy(id = com.vidgod.editor.model.newId(), trimEndUs = src.coerceAtMost(c.source.durationUs), transitionOut = null)
                }
                out.add(target)
                prev = b
                i++
                if (out.size > 200) break
            }
            pr.copy(clips = out)
        }
        vm.toast("Clips cut to the beat")
    }
}
