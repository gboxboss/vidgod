package com.vidgod.editor.features

import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.model.AnimRef
import com.vidgod.editor.model.EffectClip
import com.vidgod.editor.model.FilterRef
import com.vidgod.editor.model.FxRef
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.TransitionRef

/** One-tap edit styles ("templates") applied to the whole project. */
object AutoStyles {
    data class Style(val id: String, val name: String, val emoji: String, val description: String, val apply: (Project) -> Project)

    private fun transitions(p: Project, id: String, durUs: Long) = p.copy(
        clips = p.clips.mapIndexed { i, c -> if (i < p.clips.size - 1) c.copy(transitionOut = TransitionRef(id, durUs)) else c.copy(transitionOut = null) },
    )

    private fun filterAll(p: Project, filterId: String, intensity: Float) =
        p.copy(clips = p.clips.map { it.copy(filter = FilterRef(filterId, intensity)) })

    private fun cutEffects(p: Project, fxId: String, lengthUs: Long): Project {
        val starts = p.clipStarts()
        val extra = starts.drop(1).map { s -> EffectClip(fx = FxRef(fxId), startUs = s, durationUs = lengthUs, lane = 0) }
        val kept = p.effects.filter { e -> extra.none { it.startUs < e.endUs && it.endUs > e.startUs && e.lane == 0 } }
        return ProjectOps.normalizeLanes(p.copy(effects = kept + extra))
    }

    val all = listOf(
        Style("vlog", "Vlog", "🎒", "Soft dissolves, warm look, fade in & out") { p0 ->
            var p = transitions(p0, "dissolve", 400_000)
            p = filterAll(p, "warm", 0.6f)
            p.copy(clips = p.clips.mapIndexed { i, c ->
                c.copy(
                    animIn = if (i == 0) AnimRef("fade_in", 600_000) else c.animIn,
                    animOut = if (i == p.clips.lastIndex) AnimRef("fade_out", 800_000) else c.animOut,
                )
            })
        },
        Style("hype", "Hype", "⚡", "Zoom transitions, flash on every cut, vivid colours") { p0 ->
            var p = transitions(p0, "cross_zoom", 300_000)
            p = filterAll(p, "vivid", 0.8f)
            cutEffects(p, "flash", 250_000)
        },
        Style("cinematic", "Cinematic", "🎬", "Blockbuster grade, cinema bars, slow fades") { p0 ->
            var p = transitions(p0, "dip_black", 700_000)
            p = filterAll(p, "teal_orange", 0.8f)
            val bars = EffectClip(fx = FxRef("letterbox"), startUs = 0, durationUs = p.durationUs.coerceAtLeast(1), lane = 0)
            ProjectOps.normalizeLanes(p.copy(effects = p.effects + bars))
        },
        Style("retro", "Retro VHS", "📼", "70s colours, VHS look, glitch cuts") { p0 ->
            var p = transitions(p0, "glitch", 300_000)
            p = filterAll(p, "retro70", 0.9f)
            val vhs = EffectClip(fx = FxRef("vhs", 0.7f), startUs = 0, durationUs = p.durationUs.coerceAtLeast(1), lane = 0)
            ProjectOps.normalizeLanes(p.copy(effects = p.effects + vhs))
        },
        Style("dreamy", "Dreamy", "☁️", "Soft glow, pastel colours, blur transitions") { p0 ->
            var p = transitions(p0, "blur", 500_000)
            p = filterAll(p, "dream", 0.8f)
            val glow = EffectClip(fx = FxRef("glow", 0.6f), startUs = 0, durationUs = p.durationUs.coerceAtLeast(1), lane = 0)
            ProjectOps.normalizeLanes(p.copy(effects = p.effects + glow))
        },
        Style("slideshow", "Slideshow", "🖼️", "Ken Burns motion on photos with dissolves") { p0 ->
            val p = transitions(p0, "dissolve", 500_000)
            p.copy(clips = p.clips.map { c -> if (c.isImage) c.copy(animCombo = AnimRef("ken_burns", c.durationUs)) else c })
        },
        Style("energy", "Energy", "🔥", "Push transitions and shake on every cut") { p0 ->
            var p = transitions(p0, "push_left", 300_000)
            p = filterAll(p, "neon", 0.5f)
            cutEffects(p, "shake", 400_000)
        },
        Style("clean", "Remove style", "🧽", "Removes transitions, filters and style effects") { p ->
            p.copy(
                clips = p.clips.map { it.copy(transitionOut = null, filter = null, animIn = null, animOut = null, animCombo = null) },
                effects = emptyList(),
            )
        },
    )
}
