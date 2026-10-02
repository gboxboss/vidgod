package com.vidgod.editor.ui.editor

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Panel
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.features.BackgroundRemoval
import com.vidgod.editor.features.Reverser
import com.vidgod.editor.model.Project
import com.vidgod.editor.ui.common.ToolButton
import com.vidgod.editor.ui.common.formatTime
import com.vidgod.editor.ui.editor.panels.PanelHost
import com.vidgod.editor.ui.theme.VG

/** What a media picker result should be used for. */
enum class PickTarget { MAIN, MAIN_AT_PLAYHEAD, REPLACE, OVERLAY, STICKER, CANVAS_BG, EXTRACT_AUDIO }

@Composable
fun EditorScreen(projectId: String, initialAction: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditorViewModel = viewModel(
        key = "editor-$projectId",
        factory = viewModelFactory { initializer { EditorViewModel(app, projectId) } },
    )
    val project by vm.project.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val selection by vm.selection.collectAsState()
    val panel by vm.panel.collectAsState()
    val positionState = vm.preview.positionUs.collectAsState()
    val playing by vm.preview.isPlaying.collectAsState()
    val canvasSize by vm.preview.canvasSize.collectAsState()
    val busy by vm.busy.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val timeline = remember { TimelineState() }
    var fullscreen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(loaded) {
        if (loaded) when (initialAction) {
            "captions" -> vm.openPanel(Panel.CAPTIONS)
            "effects" -> vm.openPanel(Panel.EFFECTS_ADD)
        }
    }
    val previewError by vm.preview.error.collectAsState()
    LaunchedEffect(previewError) { previewError?.let { vm.toast("Preview: $it") } }

    var pickTarget by remember { mutableStateOf(PickTarget.MAIN) }
    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        when (pickTarget) {
            PickTarget.MAIN -> vm.importMain(uris)
            PickTarget.MAIN_AT_PLAYHEAD -> vm.importMain(uris, insertAtPlayhead = true)
            PickTarget.REPLACE -> selection?.let { vm.replaceClip(it, uris.first()) }
            PickTarget.OVERLAY -> uris.forEach { vm.addOverlay(it) }
            PickTarget.STICKER -> vm.addStickerImage(uris.first())
            PickTarget.CANVAS_BG -> vm.setCanvasBackgroundImage(uris.first())
            PickTarget.EXTRACT_AUDIO -> vm.extractAudioFrom(uris.first())
        }
    }
    fun pickVisual(target: PickTarget, kind: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo) {
        pickTarget = target
        visualPicker.launch(PickVisualMediaRequest(kind))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.addAudioFile(uri)
    }

    val actions = remember(vm) {
        EditorActions(
            pickMain = { pickVisual(PickTarget.MAIN) },
            pickMainAtPlayhead = { pickVisual(PickTarget.MAIN_AT_PLAYHEAD) },
            pickReplace = { pickVisual(PickTarget.REPLACE) },
            pickOverlay = { pickVisual(PickTarget.OVERLAY) },
            pickSticker = { pickVisual(PickTarget.STICKER, ActivityResultContracts.PickVisualMedia.ImageOnly) },
            pickCanvasBg = { pickVisual(PickTarget.CANVAS_BG, ActivityResultContracts.PickVisualMedia.ImageOnly) },
            pickExtractAudio = { pickVisual(PickTarget.EXTRACT_AUDIO, ActivityResultContracts.PickVisualMedia.VideoOnly) },
            pickAudio = { audioPicker.launch(arrayOf("audio/*")) },
        )
    }

    BackHandler {
        when {
            panel != null -> vm.openPanel(null)
            selection != null -> vm.select(null)
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(VG.Bg)) {
        // imePadding: keep text fields and their Done buttons above the keyboard (edge-to-edge).
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(
                project = project,
                onClose = onBack,
                onExport = { vm.preview.pause(); vm.openPanel(Panel.EXPORT) },
            )
            PreviewPane(vm, project, selection, positionState, canvasSize, Modifier.weight(1f).fillMaxWidth())
            ControlsRow(
                vm = vm,
                position = positionState,
                durationUs = project.durationUs,
                playing = playing,
                canUndo = canUndo,
                canRedo = canRedo,
                keyframeVisible = selection != null && selection !is Selection.Audio && selection !is Selection.Effect && selection !is Selection.Filter,
                onPlay = { vm.preview.togglePlay() },
                onUndo = vm::undo,
                onRedo = vm::redo,
                onKeyframe = vm::toggleKeyframe,
                onFullscreen = { fullscreen = true },
            )
            if (panel != null && panel != Panel.EXPORT) {
                Box(Modifier.fillMaxWidth().height(320.dp).background(VG.Surface)) {
                    PanelHost(vm, panel!!, project, selection, positionState.value, actions)
                }
            } else {
                if (project.clips.isEmpty() && loaded) {
                    EmptyTimeline(onAdd = actions.pickMain)
                } else {
                    Timeline(
                        vm, project, positionState, selection, timeline,
                        onAddMedia = actions.pickMain,
                        onTransitionClick = { id ->
                            vm.select(Selection.Main(id))
                            vm.openPanel(Panel.TRANSITION)
                        },
                        modifier = Modifier.height(232.dp),
                    )
                }
                Toolbar(vm, project, selection, actions)
            }
        }
        if (fullscreen) {
            FullscreenPreview(vm, project, positionState, playing, onClose = { fullscreen = false })
        }
        if (panel == Panel.EXPORT) {
            ExportOverlay(vm, project, onClose = { vm.openPanel(null) })
        }
        busy?.let { b ->
            Box(Modifier.fillMaxSize().background(Color(0xB0000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(VG.Surface2).padding(24.dp).width(260.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (b.progress == null) CircularProgressIndicator(color = VG.Accent)
                    else LinearProgressIndicator(progress = { b.progress }, color = VG.Accent, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Text(b.label, fontSize = 14.sp)
                    if (b.cancellable) {
                        TextButton(onClick = vm::cancelBusy) { Text("Cancel") }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)) {
            Snackbar(it, containerColor = VG.Surface3, contentColor = VG.Text)
        }
    }
}

/** Launchers needed by panels and toolbars. */
class EditorActions(
    val pickMain: () -> Unit,
    val pickMainAtPlayhead: () -> Unit,
    val pickReplace: () -> Unit,
    val pickOverlay: () -> Unit,
    val pickSticker: () -> Unit,
    val pickCanvasBg: () -> Unit,
    val pickExtractAudio: () -> Unit,
    val pickAudio: () -> Unit,
)

@Composable
private fun TopBar(project: Project, onClose: () -> Unit, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = VG.Text) }
        Spacer(Modifier.weight(1f))
        Text(
            "${project.export.resolution}P · ${project.export.frameRate}fps",
            fontSize = 12.sp, color = VG.TextDim,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(VG.Surface2).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Export", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(VG.Accent).clickable(onClick = onExport)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ControlsRow(
    vm: EditorViewModel,
    position: androidx.compose.runtime.State<Long>,
    durationUs: Long,
    playing: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    keyframeVisible: Boolean,
    onPlay: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onKeyframe: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val positionUs = position.value
        val keyframeActive = remember(positionUs / 40_000, vm.project.collectAsState().value, vm.selection.collectAsState().value) { vm.hasKeyframeAtPlayhead() }
        Text(
            "${formatTime(positionUs)} / ${formatTime(durationUs)}", fontSize = 12.sp, color = VG.TextDim,
            modifier = Modifier.width(110.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPlay) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = VG.Text, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.width(150.dp), horizontalArrangement = Arrangement.End) {
            if (keyframeVisible) {
                IconButton(onClick = onKeyframe) {
                    Icon(Icons.Default.Diamond, "Keyframe", tint = if (keyframeActive) VG.Accent else VG.Text)
                }
            }
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (canUndo) VG.Text else VG.TextDim.copy(alpha = 0.4f))
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (canRedo) VG.Text else VG.TextDim.copy(alpha = 0.4f))
            }
            IconButton(onClick = onFullscreen) {
                Icon(Icons.Default.Fullscreen, "Full screen", tint = VG.Text)
            }
        }
    }
}

@Composable
private fun EmptyTimeline(onAdd: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(232.dp), contentAlignment = Alignment.Center) {
        Text(
            "+ Add videos or photos", color = Color.Black, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(VG.Accent).clickable(onClick = onAdd)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

private data class Tool(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val selected: Boolean = false, val action: () -> Unit)

@Composable
private fun Toolbar(vm: EditorViewModel, project: Project, selection: Selection?, actions: EditorActions) {
    val context = LocalContext.current
    val tools: List<Tool> = when (selection) {
        null -> listOf(
            Tool(Icons.Default.ContentCut, "Edit") { if (!vm.selectMainAtPlayhead()) actions.pickMain() },
            Tool(Icons.Default.AutoAwesome, "Styles") { vm.openPanel(Panel.STYLES) },
            Tool(Icons.Default.MusicNote, "Audio") { vm.openPanel(Panel.AUDIO_MENU) },
            Tool(Icons.Default.TextFields, "Text") { vm.openPanel(Panel.TEXT_MENU) },
            Tool(Icons.Default.EmojiEmotions, "Stickers") { vm.openPanel(Panel.STICKERS) },
            Tool(Icons.Default.PictureInPicture, "Overlay") { actions.pickOverlay() },
            Tool(Icons.Default.AutoAwesome, "Effects") { vm.openPanel(Panel.EFFECTS_ADD) },
            Tool(Icons.Default.FilterVintage, "Filters") { vm.openPanel(Panel.FILTERS_ADD) },
            Tool(Icons.Default.Tune, "Adjust") { vm.addFilterClip(null, adjustOnly = true); vm.openPanel(Panel.ADJUST) },
            Tool(Icons.Default.ClosedCaption, "Captions") { vm.openPanel(Panel.CAPTIONS) },
            Tool(Icons.Default.AspectRatio, "Ratio") { vm.openPanel(Panel.RATIO) },
            Tool(Icons.Default.Wallpaper, "Canvas") { vm.openPanel(Panel.CANVAS) },
            Tool(Icons.Default.Reorder, "Reorder") { vm.openPanel(Panel.REORDER) },
            Tool(Icons.Default.ContentCut, "Auto cut") { com.vidgod.editor.features.SilenceCutter.run(vm, context) },
        )
        is Selection.Main, is Selection.Overlay -> {
            val clip = (project.clips + project.overlays).firstOrNull { it.id == selection.id }
            val isMain = selection is Selection.Main
            buildList {
                add(Tool(Icons.Default.ContentCut, "Split") { vm.split() })
                add(Tool(Icons.Default.Speed, "Speed") { vm.openPanel(Panel.SPEED) })
                add(Tool(Icons.AutoMirrored.Filled.VolumeUp, "Volume") { vm.openPanel(Panel.VOLUME) })
                add(Tool(Icons.Default.Animation, "Animation") { vm.openPanel(Panel.ANIMATION) })
                add(Tool(Icons.Default.Delete, "Delete") { vm.deleteSelected() })
                add(Tool(Icons.Default.FilterVintage, "Filters", clip?.filter != null) { vm.openPanel(Panel.FILTERS) })
                add(Tool(Icons.Default.Tune, "Adjust", clip?.adjust?.isNeutral == false) { vm.openPanel(Panel.ADJUST) })
                add(Tool(Icons.Default.AutoAwesome, "Effects", clip?.fx?.isNotEmpty() == true) { vm.openPanel(Panel.EFFECTS) })
                add(Tool(Icons.Default.Crop, "Transform") { vm.openPanel(Panel.TRANSFORM) })
                add(Tool(Icons.Default.Opacity, "Opacity") { vm.openPanel(Panel.OPACITY) })
                add(Tool(Icons.Default.Gradient, "Mask", clip?.mask != null) { vm.openPanel(Panel.MASK) })
                add(Tool(Icons.Default.Colorize, "Chroma key", clip?.chromaKey != null) { vm.openPanel(Panel.CHROMA) })
                add(Tool(Icons.Default.PersonRemove, "Remove BG") { BackgroundRemoval.run(vm, context, selection.id) })
                if (clip?.isImage == false) add(Tool(Icons.Default.Replay, "Reverse", clip.isReversed) { Reverser.run(vm, context, selection.id) })
                if (isMain) add(Tool(Icons.Default.AcUnit, "Freeze") { vm.freezeFrame() })
                add(Tool(Icons.Default.ContentCopy, "Duplicate") { vm.duplicateSelected() })
                add(Tool(Icons.Default.SwapHoriz, "Replace") { actions.pickReplace() })
                if (clip?.source?.hasAudio == true) {
                    add(Tool(Icons.Default.Audiotrack, "Extract audio") { vm.extractAudioOfSelected() })
                    add(Tool(Icons.Default.RecordVoiceOver, "Voice FX", clip.voiceFx != com.vidgod.editor.model.VoiceFx.NONE) { vm.openPanel(Panel.VOICE_FX) })
                    add(Tool(Icons.Default.GraphicEq, "Denoise", clip.denoise) { vm.editVisual(clip.id) { it.copy(denoise = !it.denoise) }; vm.toast(if (!clip.denoise) "Noise reduction on" else "Noise reduction off") })
                }
                if (isMain && project.clips.lastOrNull()?.id != selection.id) add(Tool(Icons.Default.SwapHoriz, "Transition") { vm.openPanel(Panel.TRANSITION) })
                if (!isMain) add(Tool(Icons.Default.Layers, "Layer") { vm.openPanel(Panel.BLEND) })
                if (isMain) add(Tool(Icons.Default.PictureInPicture, "Insert") { actions.pickMainAtPlayhead() })
            }
        }
        is Selection.Text -> listOf(
            Tool(Icons.Default.Edit, "Edit") { vm.openPanel(Panel.TEXT_EDIT) },
            Tool(Icons.Default.Animation, "Animation") { vm.openPanel(Panel.ANIMATION) },
            Tool(Icons.Default.RecordVoiceOver, "Text to speech") { vm.openPanel(Panel.TTS) },
            Tool(Icons.Default.ContentCut, "Split") { vm.split() },
            Tool(Icons.Default.ContentCopy, "Duplicate") { vm.duplicateSelected() },
            Tool(Icons.Default.Delete, "Delete") { vm.deleteSelected() },
        )
        is Selection.Sticker -> listOf(
            Tool(Icons.Default.Animation, "Animation") { vm.openPanel(Panel.ANIMATION) },
            Tool(Icons.Default.Opacity, "Opacity") { vm.openPanel(Panel.OPACITY) },
            Tool(Icons.Default.Flip, "Flip") { vm.editSticker(selection.id) { it.copy(transform = it.transform.copy(flipH = !it.transform.flipH)) } },
            Tool(Icons.Default.ContentCut, "Split") { vm.split() },
            Tool(Icons.Default.ContentCopy, "Duplicate") { vm.duplicateSelected() },
            Tool(Icons.Default.Delete, "Delete") { vm.deleteSelected() },
        )
        is Selection.Audio -> {
            val a = project.audios.firstOrNull { it.id == selection.id }
            listOf(
                Tool(Icons.AutoMirrored.Filled.VolumeUp, "Volume") { vm.openPanel(Panel.VOLUME) },
                Tool(Icons.Default.Speed, "Speed") { vm.openPanel(Panel.SPEED) },
                Tool(Icons.Default.RecordVoiceOver, "Voice FX", a?.voiceFx != com.vidgod.editor.model.VoiceFx.NONE) { vm.openPanel(Panel.VOICE_FX) },
                Tool(Icons.Default.GraphicEq, "Denoise", a?.denoise == true) { vm.editAudio(selection.id) { it.copy(denoise = !it.denoise) } },
                Tool(Icons.Default.MusicNote, "Beats", a?.beatsUs?.isNotEmpty() == true) { com.vidgod.editor.features.BeatDetector.run(vm, context, selection.id) },
                Tool(Icons.Default.AutoAwesome, "Beat sync") { com.vidgod.editor.features.BeatDetector.syncCuts(vm, selection.id) },
                Tool(Icons.Default.ContentCut, "Split") { vm.split() },
                Tool(Icons.Default.ContentCopy, "Duplicate") { vm.duplicateSelected() },
                Tool(Icons.Default.Delete, "Delete") { vm.deleteSelected() },
            )
        }
        is Selection.Effect -> listOf(
            Tool(Icons.Default.AutoAwesome, "Edit") { vm.openPanel(Panel.FX_EDIT) },
            Tool(Icons.Default.ContentCut, "Split") { vm.split() },
            Tool(Icons.Default.ContentCopy, "Duplicate") { vm.duplicateSelected() },
            Tool(Icons.Default.Delete, "Delete") { vm.deleteSelected() },
        )
        is Selection.Filter -> listOf(
            Tool(Icons.Default.FilterVintage, "Filter") { vm.openPanel(Panel.FILTER_EDIT) },
            Tool(Icons.Default.Tune, "Adjust") { vm.openPanel(Panel.ADJUST) },
            Tool(Icons.Default.ContentCut, "Split") { vm.split() },
            Tool(Icons.Default.ContentCopy, "Duplicate") { vm.duplicateSelected() },
            Tool(Icons.Default.Delete, "Delete") { vm.deleteSelected() },
        )
    }
    Row(
        Modifier.fillMaxWidth().height(76.dp).background(VG.Surface).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) {
            IconButton(onClick = { vm.select(null) }) { Icon(Icons.Default.Close, "Back", tint = VG.TextDim) }
        }
        tools.forEach { t -> ToolButton(t.icon, t.label, t.action, selected = t.selected) }
    }
}


@Composable
private fun FullscreenPreview(
    vm: EditorViewModel,
    project: Project,
    position: androidx.compose.runtime.State<Long>,
    playing: Boolean,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { vm.preview.togglePlay() }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val aspect = project.canvasAspect
            val boxAspect = maxWidth.value / maxHeight.value
            val (w, h) = if (aspect > boxAspect) maxWidth to maxWidth / aspect else maxHeight * aspect to maxHeight
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx -> android.view.SurfaceView(ctx).also { vm.preview.attach(it) } },
                onRelease = { vm.preview.detach(it) },
                modifier = Modifier.size(w, h),
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.preview.togglePlay() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White)
            }
            val dur = project.durationUs.coerceAtLeast(1)
            androidx.compose.material3.Slider(
                value = (position.value.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { vm.preview.seekTo((it * dur).toLong()) },
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = VG.Accent),
            )
            Text(formatTime(position.value), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Default.Close, "Exit full screen", tint = Color.White)
        }
    }
}
