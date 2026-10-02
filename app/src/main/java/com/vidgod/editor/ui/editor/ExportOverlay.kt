package com.vidgod.editor.ui.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.engine.ExportController
import com.vidgod.editor.model.ExportSettings
import com.vidgod.editor.model.Project
import com.vidgod.editor.ui.common.Pill
import com.vidgod.editor.ui.editor.panels.SwitchRow
import com.vidgod.editor.ui.theme.VG
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface ExportState {
    data object Settings : ExportState
    data class Running(val progress: Int) : ExportState
    data class Done(val uri: Uri?, val path: String, val sizeBytes: Long, val ms: Long) : ExportState
    data class Failed(val message: String) : ExportState
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ExportOverlay(vm: EditorViewModel, project: Project, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ExportState>(ExportState.Settings) }
    var job by remember { mutableStateOf<Job?>(null) }
    val s = project.export
    fun set(f: (ExportSettings) -> ExportSettings) = vm.update { it.copy(export = f(it.export)) }

    Box(
        Modifier.fillMaxSize().background(VG.Bg).statusBarsPadding().navigationBarsPadding().clickable(enabled = true) {},
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    job?.cancel()
                    onClose()
                }) { Icon(Icons.Default.Close, "Close", tint = VG.Text) }
                Text("Export", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            when (val st = state) {
                ExportState.Settings -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    SwitchRow("TikTok optimized (1080×1920, H.264, AAC)", s.tiktokOptimized) { on ->
                        set { if (on) it.copy(tiktokOptimized = true, resolution = 1080, frameRate = 30, hevc = false, bitrate = 0) else it.copy(tiktokOptimized = false) }
                    }
                    Section("Resolution") {
                        listOf(480, 720, 1080, 1440, 2160).forEach { r ->
                            Pill(if (r == 1440) "2K" else if (r == 2160) "4K" else "${r}p", s.resolution == r, { set { it.copy(resolution = r, tiktokOptimized = it.tiktokOptimized && r == 1080) } })
                        }
                    }
                    Section("Frame rate") {
                        listOf(24, 25, 30, 50, 60).forEach { f ->
                            Pill("$f", s.frameRate == f, { set { it.copy(frameRate = f, tiktokOptimized = it.tiktokOptimized && f in setOf(30, 60)) } })
                        }
                    }
                    val rec = ExportController.recommendedBitrate(s.resolution, s.frameRate)
                    Section("Quality") {
                        listOf("Lower" to (rec * 0.6).toInt(), "Recommended" to 0, "Higher" to (rec * 1.6).toInt()).forEach { (name, b) ->
                            Pill(name, s.bitrate == b, { set { it.copy(bitrate = b) } })
                        }
                    }
                    Section("Codec") {
                        Pill("H.264", !s.hevc, { set { it.copy(hevc = false) } })
                        Pill("HEVC (smaller)", s.hevc, { set { it.copy(hevc = true, tiktokOptimized = false) } })
                    }
                    val (w, h) = project.canvasSize(s.resolution)
                    val size = ExportController.estimateSizeBytes(s, project.durationUs)
                    Text(
                        "Output: ${w}×$h · ${s.frameRate} fps · ${"%.1f".format((if (s.bitrate > 0) s.bitrate else rec) / 1e6)} Mbps\n" +
                            "Estimated size: ${"%.1f".format(size / 1e6)} MB · No watermark",
                        color = VG.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                    )
                    if (s.tiktokOptimized && project.canvas.ratio != com.vidgod.editor.model.AspectRatio.R9_16) {
                        Text(
                            "Tip: TikTok is vertical. Switch Ratio to 9:16 for full-screen videos.",
                            color = VG.Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    BigButton("Export video") {
                        job = scope.launch {
                            state = ExportState.Running(0)
                            try {
                                val r = ExportController(context).export(vm.project.value, vm.project.value.export) { p ->
                                    state = ExportState.Running(p)
                                }
                                state = ExportState.Done(r.galleryUri, r.file.absolutePath, r.sizeBytes, r.durationMs)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                state = ExportState.Settings
                                throw e
                            } catch (e: Exception) {
                                state = ExportState.Failed(e.message ?: e.toString())
                            }
                        }
                    }
                }
                is ExportState.Running -> Column(
                    Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { st.progress / 100f }, modifier = Modifier.size(120.dp), color = VG.Accent, strokeWidth = 8.dp, trackColor = VG.Surface3)
                        Text("${st.progress}%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Exporting… keep VidGod open", color = VG.TextDim)
                    Spacer(Modifier.height(24.dp))
                    Text("Cancel", color = VG.Accent2, modifier = Modifier.clickable { job?.cancel(); state = ExportState.Settings }.padding(12.dp))
                }
                is ExportState.Done -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = VG.Accent, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Saved to your gallery", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Movies/VidGod · ${"%.1f".format(st.sizeBytes / 1e6)} MB · ${"%.1f".format(st.ms / 1000f)} s",
                        color = VG.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))
                    BigButton("Share to TikTok") { share(context, st.uri, st.path, tiktok = true) }
                    Spacer(Modifier.height(10.dp))
                    BigButton("Share…", secondary = true) { share(context, st.uri, st.path, tiktok = false) }
                    Spacer(Modifier.height(10.dp))
                    BigButton("Done", secondary = true) { onClose() }
                }
                is ExportState.Failed -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("Export failed", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = VG.Accent2)
                    Text(st.message, color = VG.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                    Text("Try a lower resolution or H.264.", color = VG.TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    BigButton("Back") { state = ExportState.Settings }
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = mutableIntStateOf(0)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun BigButton(text: String, secondary: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        color = if (secondary) VG.Text else Color.Black,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (secondary) VG.Surface2 else VG.Accent).clickable(onClick = onClick).padding(15.dp),
    )
}

private fun share(context: android.content.Context, uri: Uri?, path: String, tiktok: Boolean) {
    val shareUri = uri ?: androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(path))
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (tiktok) {
        for (pkg in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")) {
            try {
                context.startActivity(Intent(base).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        android.widget.Toast.makeText(context, "TikTok is not installed — choose an app", android.widget.Toast.LENGTH_SHORT).show()
    }
    context.startActivity(Intent.createChooser(base, "Share video"))
}
