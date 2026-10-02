package com.vidgod.editor.ui.editor.panels

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vidgod.editor.VidGodApp
import com.vidgod.editor.data.MediaProbe
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.editor.Selection
import com.vidgod.editor.features.AutoCaptions
import com.vidgod.editor.features.TtsEngine
import com.vidgod.editor.features.VoiceRecorder
import com.vidgod.editor.model.AudioKind
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.TextStyle
import com.vidgod.editor.ui.common.LabeledSlider
import com.vidgod.editor.ui.common.PanelHeader
import com.vidgod.editor.ui.common.Pill
import com.vidgod.editor.ui.common.formatTime
import com.vidgod.editor.ui.theme.VG
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val captionStyles = listOf(
    "Bold" to TextStyle(fontId = "poppins_black", size = 0.065f, strokeWidth = 0.14f, highlightColor = 0xFFFFE600.toInt(), allCaps = true),
    "Clean" to TextStyle(fontId = "sans_bold", size = 0.055f, strokeWidth = 0f, backgroundAlpha = 0.7f, highlightColor = 0xFF00E1FF.toInt()),
    "Pop" to TextStyle(fontId = "luckiest", size = 0.07f, color = 0xFFFFFFFF.toInt(), strokeWidth = 0.18f, highlightColor = 0xFF34C759.toInt()),
    "Minimal" to TextStyle(fontId = "montserrat", size = 0.05f, shadowRadius = 0.6f, highlightColor = 0),
    "Neon" to TextStyle(fontId = "fredoka", size = 0.06f, color = 0xFFFFFFFF.toInt(), shadowColor = 0xFFFF2D55.toInt(), shadowRadius = 1.2f, highlightColor = 0xFFFF6FD8.toInt()),
)

@Composable
fun CaptionsPanel(vm: EditorViewModel, p: Project, close: () -> Unit) {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(AutoCaptions.languages.first()) }
    var styleName by remember { mutableStateOf(captionStyles.first().first) }
    var includeAudio by remember { mutableStateOf(true) }
    var wordsPerLine by remember { mutableFloatStateOf(4f) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelHeader("Auto captions", close)
        Text(
            "Speech is recognised on your phone (offline). The first time, the language model (~40 MB) is downloaded.",
            color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            AutoCaptions.languages.forEach { l ->
                Pill(l.name + if (AutoCaptions.isInstalled(context, l)) " ✓" else "", l == lang, { lang = l }, Modifier.padding(end = 6.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            captionStyles.forEach { (name, _) -> Pill(name, name == styleName, { styleName = name }, Modifier.padding(end = 6.dp)) }
        }
        SwitchRow("Include music & voice-over tracks", includeAudio) { includeAudio = it }
        LabeledSlider("Words/line", wordsPerLine, { wordsPerLine = it }, range = 1f..8f, steps = 6, valueText = { "${it.toInt()}" })
        Spacer(Modifier.height(8.dp))
        Text(
            "Generate captions", fontWeight = FontWeight.Bold, color = Color.Black,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(VG.Accent)
                .clickable {
                    vm.runBusy("Preparing…") {
                        if (!AutoCaptions.isInstalled(context, lang)) {
                            AutoCaptions.install(context, lang) { f -> vm.setBusyProgress("Downloading ${lang.name} model… ${(f * 100).toInt()}%", f) }
                        }
                        vm.setBusyProgress("Listening to your video…", 0f)
                        val words = AutoCaptions.transcribe(context, vm.project.value, lang, includeAudio) { f ->
                            vm.setBusyProgress("Recognising speech… ${(f * 100).toInt()}%", f)
                        }
                        if (words.isEmpty()) {
                            vm.toast("No speech found")
                        } else {
                            val style = captionStyles.first { it.first == styleName }.second
                            val captions = AutoCaptions.toCaptions(words, style, wordsPerLine.toInt())
                            vm.update { pr ->
                                val others = pr.texts.filter { !it.isCaption }
                                val lane = (others.maxOfOrNull { it.lane } ?: -1) + 1
                                pr.copy(texts = others + captions.map { it.copy(lane = lane) })
                            }
                            vm.toast("Added ${captions.size} captions")
                            vm.openPanel(null)
                        }
                    }
                }
                .padding(14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (p.texts.any { it.isCaption }) {
            Text(
                "Remove all captions", color = VG.Accent2,
                modifier = Modifier.padding(16.dp).clickable { vm.update { pr -> pr.copy(texts = pr.texts.filter { !it.isCaption }) } },
            )
            Text(
                "Restyle captions: $styleName", color = VG.Accent,
                modifier = Modifier.padding(horizontal = 16.dp).clickable {
                    val style = captionStyles.first { it.first == styleName }.second
                    vm.update { pr -> pr.copy(texts = pr.texts.map { if (it.isCaption) it.copy(style = style) else it }) }
                },
            )
        }
    }
}

@Composable
fun TtsPanel(vm: EditorViewModel, p: Project, s: Selection?, close: () -> Unit) {
    val context = LocalContext.current
    val selectedText = if (s is Selection.Text) p.texts.firstOrNull { it.id == s.id } else null
    var text by remember(selectedText?.id) { mutableStateOf(selectedText?.text ?: "") }
    var pitch by remember { mutableFloatStateOf(1f) }
    var rate by remember { mutableFloatStateOf(1f) }
    val engine = remember { TtsEngine(context) }
    DisposableEffect(Unit) { onDispose { engine.shutdown() } }
    var locales by remember { mutableStateOf<List<Locale>>(emptyList()) }
    var locale by remember { mutableStateOf(Locale.getDefault()) }
    LaunchedEffect(Unit) {
        if (engine.awaitReady()) locales = engine.languages().take(60)
    }
    val presets = listOf("Narrator" to (1f to 1f), "Deep" to (0.7f to 0.95f), "Kid" to (1.6f to 1.1f), "Fast" to (1f to 1.5f), "Calm" to (0.9f to 0.85f), "Robot" to (0.5f to 1f))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelHeader("Text to speech", close)
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), minLines = 2, maxLines = 4,
            placeholder = { Text("Type what the voice should say") },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VG.Accent, unfocusedBorderColor = VG.Surface3),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            presets.forEach { (name, v) ->
                Pill(name, pitch == v.first && rate == v.second, { pitch = v.first; rate = v.second }, Modifier.padding(end = 6.dp))
            }
        }
        if (locales.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                locales.forEach { l -> Pill(l.displayName, l == locale, { locale = l }, Modifier.padding(end = 6.dp)) }
            }
        }
        LabeledSlider("Pitch", pitch, { pitch = it }, range = 0.4f..2f, valueText = { "%.1f".format(it) })
        LabeledSlider("Speed", rate, { rate = it }, range = 0.5f..2f, valueText = { "%.1fx".format(it) })
        Text(
            "Add voice", fontWeight = FontWeight.Bold, color = Color.Black,
            modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(VG.Accent)
                .clickable {
                    if (text.isBlank()) return@clickable vm.toast("Type some text first")
                    vm.runBusy("Generating voice…") {
                        val dir = VidGodApp.instance.repository.mediaDir(p.id)
                        val file = File(dir, "tts_${System.currentTimeMillis()}.wav")
                        val ok = engine.synthesize(text, file, locale, null, pitch, rate)
                        if (!ok) {
                            vm.toast("Text-to-speech failed. Install a voice in Android settings › Text-to-speech.")
                            return@runBusy
                        }
                        val src = MediaProbe.probe(context, Uri.fromFile(file), MediaKind.AUDIO)
                            ?: return@runBusy vm.toast("Could not read generated voice")
                        val at = selectedText?.startUs ?: vm.positionUs
                        vm.addAudioSource(src.copy(name = "Voice: " + text.take(24)), AudioKind.TTS, at)
                        if (selectedText == null) {
                            vm.toast("Voice added at the playhead")
                        }
                        vm.openPanel(null)
                    }
                }
                .padding(14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun RecordPanel(vm: EditorViewModel, positionUs: Long, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var startAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.toast("Microphone permission is needed to record")
    }
    DisposableEffect(Unit) { onDispose { recorder.stop()?.delete() } }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            level = recorder.level()
            delay(60)
        }
    }
    fun toggle() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!recording) {
            val dir = VidGodApp.instance.repository.mediaDir(vm.projectId)
            startAt = vm.positionUs
            runCatching { recorder.start(File(dir, "voice_${System.currentTimeMillis()}.m4a")) }
                .onFailure { return vm.toast("Could not start recording") }
            recording = true
            vm.preview.play()
        } else {
            recording = false
            vm.preview.pause()
            val file = recorder.stop() ?: return vm.toast("Recording failed")
            scope.launch {
                val src = MediaProbe.probe(context, Uri.fromFile(file), MediaKind.AUDIO) ?: return@launch
                vm.addAudioSource(src.copy(name = "Voice-over"), AudioKind.VOICEOVER, startAt)
                vm.toast("Voice-over added")
            }
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        PanelHeader("Voice-over", close)
        Text(
            if (recording) "Recording… ${formatTime(elapsed * 1000, true)}" else "Starts at ${formatTime(positionUs, true)} — the video plays while you speak",
            color = if (recording) VG.Accent2 else VG.TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.height(30.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(16) { i ->
                val h = (6 + 24 * (level * (0.5f + 0.5f * kotlin.math.sin(i * 0.9f + elapsed / 80f)))).dp
                Box(Modifier.width(4.dp).height(h.coerceIn(4.dp, 30.dp)).background(if (recording) VG.Accent2 else VG.Surface3, RoundedCornerShape(2.dp)))
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable { toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(if (recording) 30.dp else 58.dp).clip(if (recording) RoundedCornerShape(6.dp) else CircleShape).background(VG.Accent2),
            )
        }
        Text(if (recording) "Tap to stop" else "Tap to record", color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}
