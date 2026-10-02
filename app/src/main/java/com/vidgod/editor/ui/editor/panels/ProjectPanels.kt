package com.vidgod.editor.ui.editor.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Panel
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.media.Thumbnails
import com.vidgod.editor.model.AspectRatio
import com.vidgod.editor.model.BackgroundKind
import com.vidgod.editor.model.Project
import com.vidgod.editor.ui.common.LabeledSlider
import com.vidgod.editor.ui.common.PanelHeader
import com.vidgod.editor.ui.common.Pill
import com.vidgod.editor.ui.common.ToolButton
import com.vidgod.editor.ui.common.formatTime
import com.vidgod.editor.ui.editor.EditorActions
import com.vidgod.editor.ui.theme.VG

@Composable
fun RatioPanel(vm: EditorViewModel, p: Project, close: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Format", close)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), verticalAlignment = Alignment.Bottom) {
            AspectRatio.entries.forEach { r ->
                val selected = p.canvas.ratio == r
                Column(
                    Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.update { it.copy(canvas = it.canvas.copy(ratio = r)) } }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val (w, h) = if (r == AspectRatio.ORIGINAL) 3 to 4 else r.w to r.h
                    val scale = 44f / maxOf(w, h)
                    Box(
                        Modifier.size((w * scale).dp, (h * scale).dp)
                            .border(2.dp, if (selected) VG.Accent else VG.TextDim, RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(r.label, fontSize = 12.sp, color = if (selected) VG.Accent else VG.Text)
                    Text(
                        when (r) {
                            AspectRatio.R9_16 -> "TikTok"
                            AspectRatio.R16_9 -> "YouTube"
                            AspectRatio.R1_1 -> "Post"
                            AspectRatio.R4_5 -> "Feed"
                            else -> ""
                        },
                        fontSize = 10.sp, color = VG.TextDim,
                    )
                }
            }
        }
    }
}

@Composable
fun CanvasPanel(vm: EditorViewModel, p: Project, actions: EditorActions, close: () -> Unit) {
    var tab by remember {
        mutableStateOf(
            when (p.canvas.background) {
                BackgroundKind.COLOR -> "Color"
                BackgroundKind.BLUR -> "Blur"
                BackgroundKind.IMAGE -> "Image"
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Canvas", close)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            listOf("Color", "Blur", "Image").forEach { Pill(it, it == tab, { tab = it }, Modifier.padding(end = 8.dp)) }
        }
        when (tab) {
            "Color" -> ColorRow(p.canvas.backgroundColor) { c ->
                vm.update { it.copy(canvas = it.canvas.copy(background = BackgroundKind.COLOR, backgroundColor = c)) }
            }
            "Blur" -> {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0.15f, 0.35f, 0.55f, 0.75f, 1f).forEachIndexed { i, b ->
                        val sel = p.canvas.background == BackgroundKind.BLUR && kotlin.math.abs(p.canvas.blur - b) < 0.05f
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(VG.Surface2)
                                .border(2.dp, if (sel) VG.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { vm.update { it.copy(canvas = it.canvas.copy(background = BackgroundKind.BLUR, blur = b)) } },
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", fontSize = 16.sp) }
                    }
                }
                if (p.canvas.background == BackgroundKind.BLUR) {
                    LabeledSlider("Blur", p.canvas.blur, { v -> vm.update(false) { it.copy(canvas = it.canvas.copy(blur = v)) } },
                        onStart = { vm.beginGesture() }, onEnd = { vm.endGesture() })
                }
            }
            "Image" -> Row(Modifier.padding(12.dp)) {
                ToolButton(Icons.Default.Image, "Choose image", actions.pickCanvasBg)
            }
        }
    }
}

@Composable
fun ReorderPanel(vm: EditorViewModel, p: Project, close: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Reorder clips", close)
        Text("Use the arrows to move clips. Tap a clip to select it.", color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            p.clips.forEachIndexed { i, c ->
                Column(Modifier.padding(4.dp).width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp by produceState(Thumbnails.cached(c.playbackUri, c.trimStartUs, 200), c.id) {
                        if (value == null) value = Thumbnails.get(context, c.playbackUri, c.trimStartUs, 200, c.isImage)
                    }
                    Box(
                        Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).background(VG.Surface2)
                            .clickable { vm.select(Selection.Main(c.id)); vm.openPanel(null) },
                    ) {
                        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Text(
                            "${i + 1}", fontSize = 11.sp, color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).padding(3.dp).background(Color(0x99000000), CircleShape).padding(horizontal = 5.dp),
                        )
                        Text(
                            formatTime(c.durationUs), fontSize = 9.sp, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                        )
                    }
                    Row {
                        IconButton(onClick = { vm.reorderMain(i, i - 1) }, enabled = i > 0) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move left", tint = if (i > 0) VG.Text else VG.TextDim.copy(alpha = 0.3f))
                        }
                        IconButton(onClick = { vm.reorderMain(i, i + 1) }, enabled = i < p.clips.size - 1) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move right", tint = if (i < p.clips.size - 1) VG.Text else VG.TextDim.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioMenuPanel(vm: EditorViewModel, actions: EditorActions, close: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Audio", close)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp)) {
            ToolButton(Icons.Default.LibraryMusic, "Music", actions.pickAudio)
            ToolButton(Icons.Default.Audiotrack, "Extract", actions.pickExtractAudio)
            ToolButton(Icons.Default.Mic, "Voiceover", { vm.openPanel(Panel.RECORD) })
            ToolButton(Icons.Default.RecordVoiceOver, "Text to speech", { vm.openPanel(Panel.TTS) })
        }
        Text(
            "Music: pick any audio file on your phone (MP3, M4A, WAV…).\nExtract: use the sound of another video.",
            color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
