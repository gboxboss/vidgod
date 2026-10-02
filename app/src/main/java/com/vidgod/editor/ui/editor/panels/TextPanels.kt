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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Panel
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.engine.text.Fonts
import com.vidgod.editor.engine.text.StickerRenderer
import com.vidgod.editor.model.AnimRef
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.TextAlign
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.TextStyle as VStyle
import com.vidgod.editor.ui.common.ChoiceTile
import com.vidgod.editor.ui.common.LabeledSlider
import com.vidgod.editor.ui.common.PanelHeader
import com.vidgod.editor.ui.common.Pill
import com.vidgod.editor.ui.common.ToolButton
import com.vidgod.editor.ui.theme.VG
import kotlin.math.roundToInt

val palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF2D55, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFFFFE600, 0xFF34C759,
    0xFF00C7BE, 0xFF30B0C7, 0xFF00E1FF, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF6FD8, 0xFF8E8E93,
    0xFF6E4B3A, 0xFFFFD1DC, 0xFFB4F8C8, 0xFFA0E7FF,
).map { it.toInt() }

/** Ready-made text looks. */
object TextTemplates {
    data class Template(val name: String, val style: VStyle, val animIn: AnimRef? = null, val sample: String = "Text")

    val all = listOf(
        Template("Classic", VStyle(fontId = "sans_bold", strokeWidth = 0.12f)),
        Template("Caption", VStyle(fontId = "poppins_bold", color = 0xFFFFE600.toInt(), strokeWidth = 0.14f, size = 0.065f), sample = "CAPTION"),
        Template("Label", VStyle(fontId = "poppins_bold", color = 0xFFFFFFFF.toInt(), backgroundColor = 0xFF000000.toInt(), backgroundAlpha = 0.85f)),
        Template("Highlight", VStyle(fontId = "poppins_black", color = 0xFFFFFFFF.toInt(), backgroundColor = 0xFFFF2D55.toInt(), backgroundAlpha = 1f)),
        Template("Pop", VStyle(fontId = "luckiest", color = 0xFFFFCC00.toInt(), strokeColor = 0xFF000000.toInt(), strokeWidth = 0.18f, shadowRadius = 0.4f), AnimRef("pop_in", 400_000), "POP!"),
        Template("Neon", VStyle(fontId = "monoton", color = 0xFFFF6FD8.toInt(), shadowColor = 0xFFFF2D55.toInt(), shadowRadius = 1.2f), sample = "NEON"),
        Template("Glow", VStyle(fontId = "fredoka", color = 0xFFFFFFFF.toInt(), shadowColor = 0xFF00E1FF.toInt(), shadowRadius = 1.4f)),
        Template("Typewriter", VStyle(fontId = "typewriter", color = 0xFFFFFFFF.toInt(), backgroundAlpha = 0.6f), AnimRef("typewriter", 1_500_000)),
        Template("Cinema", VStyle(fontId = "playfair", color = 0xFFF5F5F5.toInt(), letterSpacing = 0.25f, size = 0.06f), AnimRef("fade_in", 800_000), "CINEMA"),
        Template("Marker", VStyle(fontId = "marker", color = 0xFFFFFFFF.toInt(), strokeWidth = 0.08f)),
        Template("Comic", VStyle(fontId = "bangers", color = 0xFFFFFFFF.toInt(), strokeColor = 0xFF5856D6.toInt(), strokeWidth = 0.16f, size = 0.09f), AnimRef("bounce_in", 600_000), "BOOM"),
        Template("Gradient", VStyle(fontId = "poppins_black", gradientColors = listOf(0xFFFF2D55.toInt(), 0xFFFFCC00.toInt()), strokeWidth = 0.06f)),
        Template("Retro", VStyle(fontId = "bungee", color = 0xFFFF9500.toInt(), shadowColor = 0xFF5856D6.toInt(), shadowRadius = 0.2f)),
        Template("Elegant", VStyle(fontId = "greatvibes", color = 0xFFFFFFFF.toInt(), size = 0.1f, shadowRadius = 0.4f), sample = "Elegant"),
        Template("Bubble", VStyle(fontId = "chewy", color = 0xFFFFFFFF.toInt(), backgroundColor = 0xFFAF52DE.toInt(), backgroundAlpha = 1f, backgroundCorner = 0.6f)),
        Template("Horror", VStyle(fontId = "creepster", color = 0xFFFF3B30.toInt(), shadowColor = 0xFF000000.toInt(), shadowRadius = 0.8f), AnimRef("shake_in", 600_000), "BOO"),
        Template("Game", VStyle(fontId = "pixel", color = 0xFF34C759.toInt(), strokeWidth = 0.1f, size = 0.05f), sample = "LEVEL UP"),
        Template("Bold", VStyle(fontId = "anton", color = 0xFFFFFFFF.toInt(), size = 0.1f, allCaps = true, strokeWidth = 0.04f), AnimRef("zoom_in", 400_000), "BOLD"),
    )
}

@Composable
fun TextMenuPanel(vm: EditorViewModel, close: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Text", close)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ToolButton(Icons.Default.TextFields, "Add text", { vm.addText() })
            ToolButton(Icons.Default.ClosedCaption, "Auto captions", { vm.openPanel(Panel.CAPTIONS) })
            ToolButton(Icons.Default.RecordVoiceOver, "Text to speech", { vm.openPanel(Panel.TTS) })
        }
        Text("Templates", color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            TextTemplates.all.forEach { t ->
                TemplateTile(context, t) {
                    vm.addText(t.sample, TextClip(text = t.sample, startUs = 0, style = t.style, animIn = t.animIn))
                }
            }
        }
    }
}

@Composable
private fun TemplateTile(context: android.content.Context, t: TextTemplates.Template, onClick: () -> Unit) {
    val family = remember(t.style.fontId) { FontFamily(Fonts.typeface(context, t.style.fontId)) }
    Column(
        Modifier.padding(4.dp).width(84.dp).clip(RoundedCornerShape(10.dp)).background(VG.Surface2).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            Text(
                t.sample, fontFamily = family, fontSize = 18.sp, maxLines = 1,
                color = Color(if (t.style.gradientColors.isNotEmpty()) t.style.gradientColors.first() else t.style.color),
                modifier = if (t.style.backgroundAlpha > 0f) Modifier.background(Color(t.style.backgroundColor).copy(alpha = t.style.backgroundAlpha), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp) else Modifier,
            )
        }
        Text(t.name, fontSize = 11.sp, color = VG.TextDim)
    }
}

@Composable
fun TextEditPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val text = if (s is Selection.Text) p.texts.firstOrNull { it.id == s.id } else null
    if (text == null) return Hint("Select a text")
    val context = LocalContext.current
    var tab by remember { mutableStateOf("Style") }
    var sub by remember { mutableStateOf("Text") }
    DisposableEffect(text.id) {
        vm.beginGesture()
        onDispose { vm.endGesture() }
    }
    fun style(live: Boolean = true, f: (VStyle) -> VStyle) = vm.editText(text.id, record = false) { it.copy(style = f(it.style)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            var field by remember(text.id) {
                mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text.text, androidx.compose.ui.text.TextRange(0, text.text.length)))
            }
            LaunchedEffect(text.id) { if (text.text == "Enter text") runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    field = v
                    if (v.text != text.text) vm.editText(text.id, record = false) { it.copy(text = v.text, words = emptyList()) }
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
                maxLines = 3,
                textStyle = TextStyle(fontSize = 15.sp, color = VG.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VG.Accent, unfocusedBorderColor = VG.Surface3),
            )
            IconButton(onClick = close) { Text("Done", color = VG.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            listOf("Style", "Font", "Templates").forEach { Pill(it, tab == it, { tab = it }, Modifier.padding(end = 8.dp)) }
            Pill("Animation", false, { vm.openPanel(Panel.ANIMATION) }, Modifier.padding(end = 8.dp))
        }
        when (tab) {
            "Font" -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(Fonts.all) { f ->
                    val family = remember(f.id) { FontFamily(Fonts.typeface(context, f.id)) }
                    Box(
                        Modifier.padding(3.dp).height(44.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (text.style.fontId == f.id) VG.Surface3 else VG.Surface2)
                            .border(1.5.dp, if (text.style.fontId == f.id) VG.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { style(false) { it.copy(fontId = f.id) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(f.name, fontFamily = family, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            "Templates" -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                TextTemplates.all.forEach { t ->
                    TemplateTile(context, t) { vm.editText(text.id, record = false) { it.copy(style = t.style.copy(size = it.style.size), animIn = t.animIn ?: it.animIn) } }
                }
            }
            else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    listOf("Text", "Stroke", "Background", "Shadow", "Spacing").forEach { Pill(it, sub == it, { sub = it }, Modifier.padding(end = 6.dp)) }
                }
                val st = text.style
                when (sub) {
                    "Text" -> {
                        ColorRow(st.color) { c -> style { it.copy(color = c, gradientColors = emptyList()) } }
                        LabeledSlider("Size", st.size, { v -> style { it.copy(size = v) } }, range = 0.02f..0.25f, valueText = { "${(it * 1000).roundToInt()}" })
                        LabeledSlider("Opacity", st.opacity, { v -> style { it.copy(opacity = v) } })
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Toggle(Icons.Default.FormatBold, st.bold) { style { it.copy(bold = !it.bold) } }
                            Toggle(Icons.Default.FormatItalic, st.italic) { style { it.copy(italic = !it.italic) } }
                            Toggle(Icons.Default.FormatUnderlined, st.underline) { style { it.copy(underline = !it.underline) } }
                            Toggle(Icons.Default.TextFormat, st.allCaps) { style { it.copy(allCaps = !it.allCaps) } }
                            Toggle(Icons.AutoMirrored.Filled.FormatAlignLeft, st.align == TextAlign.LEFT) { style { it.copy(align = TextAlign.LEFT) } }
                            Toggle(Icons.Default.FormatAlignCenter, st.align == TextAlign.CENTER) { style { it.copy(align = TextAlign.CENTER) } }
                            Toggle(Icons.AutoMirrored.Filled.FormatAlignRight, st.align == TextAlign.RIGHT) { style { it.copy(align = TextAlign.RIGHT) } }
                        }
                    }
                    "Stroke" -> {
                        ColorRow(st.strokeColor) { c -> style { it.copy(strokeColor = c, strokeWidth = if (it.strokeWidth == 0f) 0.1f else it.strokeWidth) } }
                        LabeledSlider("Thickness", st.strokeWidth, { v -> style { it.copy(strokeWidth = v) } }, range = 0f..0.3f, valueText = { "${(it * 333).roundToInt()}" })
                    }
                    "Background" -> {
                        ColorRow(st.backgroundColor) { c -> style { it.copy(backgroundColor = c, backgroundAlpha = if (it.backgroundAlpha == 0f) 1f else it.backgroundAlpha) } }
                        LabeledSlider("Opacity", st.backgroundAlpha, { v -> style { it.copy(backgroundAlpha = v) } })
                        LabeledSlider("Corners", st.backgroundCorner, { v -> style { it.copy(backgroundCorner = v) } }, range = 0f..0.8f)
                    }
                    "Shadow" -> {
                        ColorRow(st.shadowColor) { c -> style { it.copy(shadowColor = c, shadowRadius = if (it.shadowRadius == 0f) 0.5f else it.shadowRadius) } }
                        LabeledSlider("Blur", st.shadowRadius, { v -> style { it.copy(shadowRadius = v) } }, range = 0f..2f)
                    }
                    "Spacing" -> {
                        LabeledSlider("Letters", st.letterSpacing, { v -> style { it.copy(letterSpacing = v) } }, range = -0.1f..0.5f)
                        LabeledSlider("Lines", st.lineSpacing, { v -> style { it.copy(lineSpacing = v) } }, range = 0.6f..2.5f, valueText = { "%.1f".format(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(3.dp).size(38.dp).clip(RoundedCornerShape(8.dp)).background(if (on) VG.Accent else VG.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) Color.Black else VG.Text) }
}

@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        palette.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(2.dp, if ((selected or 0xFF000000.toInt()) == c) VG.Accent else VG.Surface3, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
fun StickersPanel(vm: EditorViewModel, actions: com.vidgod.editor.ui.editor.EditorActions, close: () -> Unit) {
    val context = LocalContext.current
    val tabs = StickerRenderer.emojiPacks.map { it.first } + listOf("Shapes", "Photo")
    var tab by remember { mutableStateOf(tabs.first()) }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Stickers", close)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            tabs.forEach { Pill(it, it == tab, { tab = it }, Modifier.padding(end = 6.dp)) }
        }
        when (tab) {
            "Shapes" -> LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize().padding(8.dp)) {
                items(StickerRenderer.shapes) { s ->
                    val img = remember(s.id) { StickerRenderer.renderShape(s.id, 96, 0).asImageBitmap() }
                    Box(
                        Modifier.padding(4.dp).size(56.dp).clip(RoundedCornerShape(10.dp)).background(VG.Surface2)
                            .clickable { vm.addSticker(StickerKind.SHAPE, s.id) },
                        contentAlignment = Alignment.Center,
                    ) { Image(img, s.name, Modifier.size(40.dp)) }
                }
            }
            "Photo" -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ToolButton(Icons.Default.AddPhotoAlternate, "Pick image (PNG with transparency works best)", actions.pickSticker)
            }
            else -> GridOf(StickerRenderer.emojiPacks.first { it.first == tab }.second) { e -> vm.addSticker(StickerKind.EMOJI, e) }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = context to Stroke(1f)
}
