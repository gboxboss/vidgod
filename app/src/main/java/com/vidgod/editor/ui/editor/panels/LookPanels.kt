package com.vidgod.editor.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Panel
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.engine.catalog.AnimKind
import com.vidgod.editor.engine.catalog.Animations
import com.vidgod.editor.engine.catalog.Effects
import com.vidgod.editor.engine.catalog.Filters
import com.vidgod.editor.engine.catalog.Transitions
import com.vidgod.editor.model.Adjust
import com.vidgod.editor.model.AnimRef
import com.vidgod.editor.model.FilterRef
import com.vidgod.editor.model.FxRef
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.TransitionRef
import com.vidgod.editor.ui.common.ChoiceTile
import com.vidgod.editor.ui.common.LabeledSlider
import com.vidgod.editor.ui.common.PanelHeader
import com.vidgod.editor.ui.common.Pill
import com.vidgod.editor.ui.theme.VG
import kotlin.math.roundToInt

enum class FilterMode { CLIP, ADD, EDIT }
enum class FxMode { CLIP, ADD, EDIT }

@Composable
private fun CategoryRow(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        categories.forEach { c -> Pill(c, c == selected, { onSelect(c) }, Modifier.padding(end = 8.dp)) }
    }
}

// ------------------------------------------------------------------ filters

@Composable
fun FiltersPanel(vm: EditorViewModel, p: Project, s: Selection?, mode: FilterMode, close: () -> Unit) {
    var category by remember { mutableStateOf(Filters.categories.first()) }
    val clip = if (mode == FilterMode.CLIP) visualOf(p, s) else null
    val fclip = if (mode == FilterMode.EDIT && s is Selection.Filter) p.filters.firstOrNull { it.id == s.id } else null
    if (mode == FilterMode.CLIP && clip == null) return Hint("Select a clip")
    val current: FilterRef? = clip?.filter ?: fclip?.filter
    fun apply(ref: FilterRef?) {
        when (mode) {
            FilterMode.CLIP -> vm.editVisual(clip!!.id) { it.copy(filter = ref) }
            FilterMode.EDIT -> fclip?.let { f -> vm.editFilter(f.id) { it.copy(filter = ref) } }
            FilterMode.ADD -> if (ref != null) {
                vm.addFilterClip(ref.id)
                vm.openPanel(Panel.FILTER_EDIT)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader(
            "Filters", close,
            onApplyAll = if (mode == FilterMode.CLIP && s is Selection.Main) ({ vm.editAllMain { it.copy(filter = current) } }) else null,
        )
        if (current != null && mode != FilterMode.ADD) {
            LabeledSlider("Intensity", current.intensity, { v ->
                val ref = current.copy(intensity = v)
                when (mode) {
                    FilterMode.CLIP -> vm.editVisual(clip!!.id, false) { it.copy(filter = ref) }
                    FilterMode.EDIT -> fclip?.let { f -> vm.editFilter(f.id, false) { it.copy(filter = ref) } }
                    else -> {}
                }
            }, valueText = { "${(it * 100).roundToInt()}" }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        }
        CategoryRow(Filters.categories, category) { category = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            if (mode != FilterMode.ADD) {
                ChoiceTile("None", current == null, { apply(null) }) { Text("✕", color = VG.TextDim) }
            }
            Filters.all.filter { it.category == category }.forEach { f ->
                ChoiceTile(
                    f.name, current?.id == f.id, { apply(FilterRef(f.id, current?.intensity ?: 1f)) },
                    swatch = Color(f.swatch.toInt()),
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000)))))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ adjust

private data class AdjParam(val name: String, val get: (Adjust) -> Float, val set: (Adjust, Float) -> Adjust, val signed: Boolean = true)

private val adjParams = listOf(
    AdjParam("Brightness", { it.brightness }, { a, v -> a.copy(brightness = v) }),
    AdjParam("Contrast", { it.contrast }, { a, v -> a.copy(contrast = v) }),
    AdjParam("Saturation", { it.saturation }, { a, v -> a.copy(saturation = v) }),
    AdjParam("Exposure", { it.exposure }, { a, v -> a.copy(exposure = v) }),
    AdjParam("Temperature", { it.temperature }, { a, v -> a.copy(temperature = v) }),
    AdjParam("Tint", { it.tint }, { a, v -> a.copy(tint = v) }),
    AdjParam("Highlights", { it.highlights }, { a, v -> a.copy(highlights = v) }),
    AdjParam("Shadows", { it.shadows }, { a, v -> a.copy(shadows = v) }),
    AdjParam("Vibrance", { it.vibrance }, { a, v -> a.copy(vibrance = v) }),
    AdjParam("Hue", { it.hue }, { a, v -> a.copy(hue = v) }),
    AdjParam("Sharpen", { it.sharpen }, { a, v -> a.copy(sharpen = v) }, signed = false),
    AdjParam("Vignette", { it.vignette }, { a, v -> a.copy(vignette = v) }, signed = false),
    AdjParam("Fade", { it.fade }, { a, v -> a.copy(fade = v) }, signed = false),
    AdjParam("Grain", { it.grain }, { a, v -> a.copy(grain = v) }, signed = false),
)

@Composable
fun AdjustPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s)
    val fclip = if (s is Selection.Filter) p.filters.firstOrNull { it.id == s.id } else null
    if (clip == null && fclip == null) return Hint("Select a clip or adjustment")
    val adjust = clip?.adjust ?: fclip!!.adjust
    var selected by remember { mutableStateOf(adjParams.first().name) }
    val param = adjParams.first { it.name == selected }
    fun set(a: Adjust, live: Boolean) {
        if (clip != null) vm.editVisual(clip.id, !live) { it.copy(adjust = a) } else vm.editFilter(fclip!!.id, !live) { it.copy(adjust = a) }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Adjust", close, onApplyAll = if (s is Selection.Main) ({ vm.editAllMain { it.copy(adjust = adjust) } }) else null)
        LabeledSlider(
            param.name, param.get(adjust), { v -> set(param.set(adjust, v), true) },
            range = if (param.signed) -1f..1f else 0f..1f,
            valueText = { "${(it * 100).roundToInt()}" },
            onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ChoiceTile("Reset", false, { set(Adjust(), false) }) { Text("↺", fontSize = 22.sp) }
            adjParams.forEach { a ->
                val v = a.get(adjust)
                ChoiceTile(a.name, a.name == selected, { selected = a.name }) {
                    Text(if (v == 0f) "0" else "${(v * 100).roundToInt()}", color = if (v != 0f) VG.Accent else VG.TextDim, fontSize = 14.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ effects

@Composable
fun EffectsPanel(vm: EditorViewModel, p: Project, s: Selection?, mode: FxMode, close: () -> Unit) {
    var category by remember { mutableStateOf(Effects.categories.first()) }
    val clip = if (mode == FxMode.CLIP) visualOf(p, s) else null
    val eclip = if (mode == FxMode.EDIT && s is Selection.Effect) p.effects.firstOrNull { it.id == s.id } else null
    if (mode == FxMode.CLIP && clip == null) return Hint("Select a clip")
    val activeIds: Set<String> = when (mode) {
        FxMode.CLIP -> clip!!.fx.map { it.id }.toSet()
        FxMode.EDIT -> setOfNotNull(eclip?.fx?.id)
        FxMode.ADD -> emptySet()
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Effects", close)
        if (mode == FxMode.EDIT && eclip != null) {
            LabeledSlider("Intensity", eclip.fx.intensity, { v -> vm.editEffect(eclip.id, false) { it.copy(fx = it.fx.copy(intensity = v)) } },
                onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
            LabeledSlider("Speed", eclip.fx.speed, { v -> vm.editEffect(eclip.id, false) { it.copy(fx = it.fx.copy(speed = v)) } },
                range = 0.2f..3f, valueText = { "%.1fx".format(it) }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        }
        if (mode == FxMode.CLIP && clip!!.fx.isNotEmpty()) {
            val f = clip.fx.last()
            LabeledSlider("Intensity", f.intensity, { v ->
                vm.editVisual(clip.id, false) { c -> c.copy(fx = c.fx.map { if (it.id == f.id) it.copy(intensity = v) else it }) }
            }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        }
        CategoryRow(Effects.categories, category) { category = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            if (mode == FxMode.CLIP) {
                ChoiceTile("None", activeIds.isEmpty(), { vm.editVisual(clip!!.id) { it.copy(fx = emptyList()) } }) { Text("✕", color = VG.TextDim) }
            }
            Effects.all.filter { it.category == category }.forEach { fx ->
                ChoiceTile(fx.name, fx.id in activeIds, {
                    when (mode) {
                        FxMode.CLIP -> vm.editVisual(clip!!.id) { c ->
                            if (c.fx.any { it.id == fx.id }) c.copy(fx = c.fx.filter { it.id != fx.id })
                            else c.copy(fx = (c.fx + FxRef(fx.id)).takeLast(3))
                        }
                        FxMode.EDIT -> eclip?.let { e -> vm.editEffect(e.id) { it.copy(fx = it.fx.copy(id = fx.id)) } }
                        FxMode.ADD -> { vm.addEffect(fx.id); vm.openPanel(Panel.FX_EDIT) }
                    }
                }, swatch = Color(fx.swatch.toInt())) {
                    Text("✦", color = Color.White, fontSize = 20.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ transitions

@Composable
fun TransitionPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = if (s is Selection.Main) p.clips.firstOrNull { it.id == s.id } else null
    if (clip == null || p.clips.lastOrNull()?.id == clip.id) return Hint("Select a clip that has a clip after it")
    var category by remember { mutableStateOf(Transitions.categories.first()) }
    val current = clip.transitionOut
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Transition", close, onApplyAll = {
            vm.update { pr -> pr.copy(clips = pr.clips.mapIndexed { i, c -> if (i < pr.clips.size - 1) c.copy(transitionOut = current) else c }) }
            vm.toast("Applied to all clips")
        })
        if (current != null) {
            LabeledSlider("Duration", current.durationUs / 1e6f, { v ->
                vm.editVisual(clip.id, false) { it.copy(transitionOut = it.transitionOut?.copy(durationUs = (v * 1e6).toLong())) }
            }, range = 0.1f..2f, valueText = { "%.1fs".format(it) }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
        }
        CategoryRow(Transitions.categories, category) { category = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ChoiceTile("None", current == null, { vm.editVisual(clip.id) { it.copy(transitionOut = null) } }) { Text("✕", color = VG.TextDim) }
            Transitions.all.filter { it.category == category }.forEach { t ->
                ChoiceTile(t.name, current?.id == t.id, {
                    vm.editVisual(clip.id) { it.copy(transitionOut = TransitionRef(t.id, current?.durationUs ?: 500_000)) }
                }) { Text("⇄", color = VG.Accent, fontSize = 20.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------ animations

@Composable
fun AnimationPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val clip = visualOf(p, s)
    val text = if (s is Selection.Text) p.texts.firstOrNull { it.id == s.id } else null
    val sticker = if (s is Selection.Sticker) p.stickers.firstOrNull { it.id == s.id } else null
    if (clip == null && text == null && sticker == null) return Hint("Select a clip, text or sticker")
    var tab by remember { mutableStateOf(AnimKind.IN) }
    val animIn = clip?.animIn ?: text?.animIn ?: sticker?.animIn
    val animOut = clip?.animOut ?: text?.animOut ?: sticker?.animOut
    val combo = clip?.animCombo ?: text?.animLoop ?: sticker?.animLoop
    val current = when (tab) { AnimKind.IN -> animIn; AnimKind.OUT -> animOut; AnimKind.COMBO -> combo }
    fun set(ref: AnimRef?, live: Boolean = false) {
        when {
            clip != null -> vm.editVisual(clip.id, !live) {
                when (tab) { AnimKind.IN -> it.copy(animIn = ref); AnimKind.OUT -> it.copy(animOut = ref); AnimKind.COMBO -> it.copy(animCombo = ref) }
            }
            text != null -> vm.editText(text.id, !live) {
                when (tab) { AnimKind.IN -> it.copy(animIn = ref); AnimKind.OUT -> it.copy(animOut = ref); AnimKind.COMBO -> it.copy(animLoop = ref) }
            }
            sticker != null -> vm.editSticker(sticker.id, !live) {
                when (tab) { AnimKind.IN -> it.copy(animIn = ref); AnimKind.OUT -> it.copy(animOut = ref); AnimKind.COMBO -> it.copy(animLoop = ref) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Animation", close)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            listOf(AnimKind.IN to "In", AnimKind.OUT to "Out", AnimKind.COMBO to if (clip != null) "Combo" else "Loop").forEach { (k, label) ->
                Pill(label, tab == k, { tab = k }, Modifier.padding(end = 8.dp))
            }
        }
        if (current != null) {
            LabeledSlider(
                if (tab == AnimKind.COMBO) "Cycle" else "Duration", current.durationUs / 1e6f,
                { v -> set(current.copy(durationUs = (v * 1e6).toLong()), live = true) },
                range = 0.1f..5f, valueText = { "%.1fs".format(it) }, onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() },
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ChoiceTile("None", current == null, { set(null) }) { Text("✕", color = VG.TextDim) }
            Animations.ofKind(tab, forText = text != null).forEach { a ->
                ChoiceTile(a.name, current?.id == a.id, {
                    val d = current?.durationUs ?: if (tab == AnimKind.COMBO) 1_500_000 else 500_000
                    set(AnimRef(a.id, d))
                }) { Text("✧", color = VG.Accent, fontSize = 20.sp) }
            }
        }
    }
}

@Composable
internal fun GridOf(items: List<String>, columns: Int = 8, onClick: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(items) { e ->
            Box(
                Modifier.padding(2.dp).size(44.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { onClick(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 26.sp) }
        }
    }
}
