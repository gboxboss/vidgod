package com.vidgod.editor.features

import android.content.Context
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.media.Waveforms
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** "Remove silences": cuts out pauses from talking videos (automatic jump cuts). */
object SilenceCutter {
    private const val WINDOW_US = 20_000L

    /** Returns speech ranges (source time) of [clip]. */
    suspend fun speechRanges(context: Context, clip: VisualClip, minSilenceUs: Long, padUs: Long): List<LongRange> =
        withContext(Dispatchers.Default) {
            val levels = ArrayList<Float>()
            var acc = 0.0
            var n = 0
            val windowFrames = (16_000 * WINDOW_US / 1_000_000).toInt()
            Waveforms.decodeMono16(context, clip.playbackUri, clip.trimStartUs, clip.trimEndUs, 16_000) { buf, count ->
                for (i in 0 until count) {
                    val v = buf[i] / 32768.0
                    acc += v * v
                    n++
                    if (n == windowFrames) {
                        levels.add(sqrt(acc / n).toFloat())
                        acc = 0.0
                        n = 0
                    }
                }
            }
            if (levels.isEmpty()) return@withContext listOf(clip.trimStartUs..clip.trimEndUs)
            val sorted = levels.sorted()
            val floor = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.size - 1)]
            val loud = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.size - 1)]
            val threshold = maxOf(0.008f, floor * 2.5f, loud * 0.12f)
            val ranges = ArrayList<LongRange>()
            var start = -1L
            var lastLoud = -1L
            val minWindows = minSilenceUs / WINDOW_US
            for (i in levels.indices) {
                val tUs = clip.trimStartUs + i * WINDOW_US
                if (levels[i] >= threshold) {
                    if (start < 0) start = tUs
                    lastLoud = i.toLong()
                } else if (start >= 0 && i - lastLoud > minWindows) {
                    ranges.add((start - padUs).coerceAtLeast(clip.trimStartUs)..(clip.trimStartUs + (lastLoud + 1) * WINDOW_US + padUs).coerceAtMost(clip.trimEndUs))
                    start = -1
                }
            }
            if (start >= 0) ranges.add((start - padUs).coerceAtLeast(clip.trimStartUs)..clip.trimEndUs)
            // Merge overlapping ranges after padding.
            val merged = ArrayList<LongRange>()
            for (r in ranges) {
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged.add(r)
            }
            merged.filter { it.last - it.first >= 150_000 }
        }

    fun run(vm: EditorViewModel, context: Context) {
        val p = vm.project.value
        val candidates = p.clips.filter { it.source.kind == MediaKind.VIDEO && it.source.hasAudio && !it.muted && it.speedCurve.isEmpty() }
        if (candidates.isEmpty()) return vm.toast("No clips with sound to analyse")
        vm.runBusy("Finding silences…") {
            val replacement = HashMap<String, List<VisualClip>>()
            candidates.forEachIndexed { i, c ->
                vm.setBusyProgress("Finding silences… ${i + 1}/${candidates.size}", i.toFloat() / candidates.size)
                val ranges = speechRanges(context, c, minSilenceUs = 450_000, padUs = 90_000)
                if (ranges.isEmpty()) return@forEachIndexed
                if (ranges.size == 1 && ranges[0].first <= c.trimStartUs + 50_000 && ranges[0].last >= c.trimEndUs - 50_000) return@forEachIndexed
                replacement[c.id] = ranges.mapIndexed { k, r ->
                    c.copy(
                        id = if (k == 0) c.id else newId(),
                        trimStartUs = r.first,
                        trimEndUs = r.last,
                        keyframes = emptyList(),
                        animIn = if (k == 0) c.animIn else null,
                        animOut = if (k == ranges.lastIndex) c.animOut else null,
                        transitionOut = if (k == ranges.lastIndex) c.transitionOut else null,
                    )
                }
            }
            if (replacement.isEmpty()) return@runBusy vm.toast("No long pauses found")
            val before = vm.project.value.durationUs
            vm.update { pr -> pr.copy(clips = pr.clips.flatMap { replacement[it.id] ?: listOf(it) }) }
            val saved = (before - vm.project.value.durationUs) / 1e6
            vm.toast("Removed %.1fs of silence".format(saved))
        }
    }
}
