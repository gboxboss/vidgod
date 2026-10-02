package com.vidgod.editor.ui.editor.panels

import androidx.compose.runtime.Composable
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Panel
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.model.Project
import com.vidgod.editor.ui.editor.EditorActions

@Composable
fun PanelHost(
    vm: EditorViewModel,
    panel: Panel,
    project: Project,
    selection: Selection?,
    positionUs: Long,
    actions: EditorActions,
) {
    val close = { vm.openPanel(null) }
    when (panel) {
        Panel.SPEED -> SpeedPanel(vm, project, selection, close)
        Panel.VOLUME -> VolumePanel(vm, project, selection, close)
        Panel.OPACITY -> OpacityPanel(vm, project, selection, close)
        Panel.TRANSFORM -> TransformPanel(vm, project, selection, close)
        Panel.CHROMA -> ChromaPanel(vm, project, selection, close)
        Panel.MASK -> MaskPanel(vm, project, selection, close)
        Panel.VOICE_FX -> VoiceFxPanel(vm, project, selection, close)
        Panel.BLEND -> LayerPanel(vm, project, selection, close)
        Panel.FILTERS -> FiltersPanel(vm, project, selection, FilterMode.CLIP, close)
        Panel.FILTERS_ADD -> FiltersPanel(vm, project, selection, FilterMode.ADD, close)
        Panel.FILTER_EDIT -> FiltersPanel(vm, project, selection, FilterMode.EDIT, close)
        Panel.ADJUST, Panel.ADJUST_ADD -> AdjustPanel(vm, project, selection, close)
        Panel.EFFECTS -> EffectsPanel(vm, project, selection, FxMode.CLIP, close)
        Panel.EFFECTS_ADD -> EffectsPanel(vm, project, selection, FxMode.ADD, close)
        Panel.FX_EDIT -> EffectsPanel(vm, project, selection, FxMode.EDIT, close)
        Panel.TRANSITION -> TransitionPanel(vm, project, selection, close)
        Panel.ANIMATION -> AnimationPanel(vm, project, selection, close)
        Panel.TEXT_MENU -> TextMenuPanel(vm, close)
        Panel.TEXT_EDIT -> TextEditPanel(vm, project, selection, close)
        Panel.STICKERS -> StickersPanel(vm, actions, close)
        Panel.RATIO -> RatioPanel(vm, project, close)
        Panel.CANVAS -> CanvasPanel(vm, project, actions, close)
        Panel.REORDER -> ReorderPanel(vm, project, close)
        Panel.AUDIO_MENU -> AudioMenuPanel(vm, actions, close)
        Panel.CAPTIONS -> CaptionsPanel(vm, project, close)
        Panel.TTS -> TtsPanel(vm, project, selection, close)
        Panel.RECORD -> RecordPanel(vm, positionUs, close)
        Panel.EXPORT -> {}
    }
}
