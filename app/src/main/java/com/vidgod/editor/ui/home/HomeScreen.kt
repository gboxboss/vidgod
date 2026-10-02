package com.vidgod.editor.ui.home

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidgod.editor.VidGodApp
import com.vidgod.editor.data.MediaProbe
import com.vidgod.editor.data.ProjectSummary
import com.vidgod.editor.editor.ProjectOps
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.ui.common.formatTime
import com.vidgod.editor.ui.theme.VG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as VidGodApp).repository
    val projects = repo.projects
    var creating by mutableStateOf(false)
        private set

    fun refresh() = viewModelScope.launch { repo.refresh() }

    /** Creates a project from picked media and returns its id. */
    suspend fun create(uris: List<Uri>, slideshow: Boolean): String? {
        creating = true
        try {
            val ctx = getApplication<Application>()
            val name = "Project " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
            val p = repo.create(name)
            val sources = withContext(Dispatchers.IO) {
                val dir = repo.mediaDir(p.id)
                uris.mapNotNull { u -> MediaProbe.probe(ctx, MediaProbe.retain(ctx, u, dir)) }
            }.filter { it.kind != MediaKind.AUDIO }
            var clips = sources.map { ProjectOps.visualFrom(it) }
            if (slideshow) {
                clips = clips.mapIndexed { i, c ->
                    if (!c.isImage) c else c.copy(
                        trimEndUs = 2_500_000,
                        animCombo = com.vidgod.editor.model.AnimRef("ken_burns", 2_500_000),
                        transitionOut = if (i < clips.size - 1) com.vidgod.editor.model.TransitionRef("dissolve", 500_000) else null,
                    )
                }
            }
            repo.save(p.copy(clips = clips))
            repo.refresh()
            return p.id
        } finally {
            creating = false
        }
    }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun duplicate(id: String) = viewModelScope.launch { repo.duplicate(id) }
    fun rename(id: String, name: String) = viewModelScope.launch { repo.rename(id, name) }
}

@Composable
fun HomeScreen(openEditor: (String, String?) -> Unit, vm: HomeViewModel = viewModel()) {
    val projects by vm.projects.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            val action = pendingAction
            scope.launch {
                val id = vm.create(uris, slideshow = action == "slideshow")
                if (id != null) openEditor(id, action)
            }
        }
    }
    fun pick(action: String?, imagesOnly: Boolean = false) {
        pendingAction = action
        picker.launch(
            PickVisualMediaRequest(
                if (imagesOnly) ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.ImageAndVideo,
            ),
        )
    }

    val ctx = LocalContext.current
    var crash by remember { mutableStateOf(com.vidgod.editor.data.Diagnostics.takeCrash(ctx)) }
    var licenses by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    Box(Modifier.fillMaxSize().background(VG.Bg)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vid", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VG.Text)
                        Text("God", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VG.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PRO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                            modifier = Modifier.background(VG.Accent2, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = VG.TextDim) }
                            DropdownMenu(menuOpen, { menuOpen = false }) {
                                DropdownMenuItem({ Text("Report a problem") }, {
                                    menuOpen = false
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, com.vidgod.editor.data.Diagnostics.report(ctx))
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(send, "Share diagnostics"))
                                })
                                DropdownMenuItem({ Text("Font licenses") }, {
                                    menuOpen = false
                                    licenses = runCatching { ctx.assets.open("fonts/LICENSES.txt").bufferedReader().readText() }.getOrNull()
                                })
                            }
                        }
                    }
                    Text("Every pro feature unlocked. No watermark.", color = VG.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    NewProjectCard { pick(null) }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(Icons.Default.ClosedCaption, "Auto captions", Modifier.weight(1f)) { pick("captions") }
                        QuickAction(Icons.Default.PhotoLibrary, "Slideshow", Modifier.weight(1f)) { pick("slideshow", imagesOnly = true) }
                        QuickAction(Icons.Default.AutoAwesome, "Effects", Modifier.weight(1f)) { pick("effects") }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Projects", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (projects.isEmpty()) {
                        Text(
                            "Your projects will appear here.", color = VG.TextDim, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
            items(projects, key = { it.id }) { p ->
                ProjectCard(
                    p,
                    onOpen = { openEditor(p.id, null) },
                    onRename = { renaming = p },
                    onDuplicate = { vm.duplicate(p.id) },
                    onDelete = { deleting = p },
                )
            }
        }
        if (vm.creating) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = VG.Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Preparing media…")
                }
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(p.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text("VidGod closed unexpectedly") },
            text = {
                Column(Modifier.height(260.dp).verticalScroll(rememberScrollState())) {
                    Text("Sorry about that. Share this report so it can be fixed:", fontSize = 13.sp, color = VG.TextDim)
                    Spacer(Modifier.height(8.dp))
                    Text(report, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton({
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share crash report"))
                    crash = null
                }) { Text("Share report") }
            },
            dismissButton = { TextButton({ crash = null }) { Text("Close") } },
        )
    }
    licenses?.let { text ->
        AlertDialog(
            onDismissRequest = { licenses = null },
            title = { Text("Open-source licenses") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "VidGod uses AndroidX Media3, Jetpack Compose, ML Kit, Vosk (Apache 2.0), Coil, Kotlin and these fonts:\n\n$text",
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = { TextButton({ licenses = null }) { Text("Close") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete project?") },
            text = { Text("\"${p.name}\" will be removed. Your original photos and videos are not affected.") },
            confirmButton = { TextButton({ vm.delete(p.id); deleting = null }) { Text("Delete", color = VG.Accent2) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF7B2FF7), Color(0xFFFF2D6F))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(Color.White, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, null, tint = Color.Black, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("New project", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Videos & photos · 9:16 for TikTok", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(VG.Surface2).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = VG.Accent)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ProjectCard(
    p: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cover by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.coverFile, p.coverStamp) {
        value = withContext(Dispatchers.IO) {
            p.coverFile?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() }
        }
    }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(VG.Surface).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(VG.Surface2), contentAlignment = Alignment.Center) {
            val c = cover
            if (c != null) {
                Image(c, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Movie, null, tint = VG.TextDim, modifier = Modifier.size(36.dp))
            }
            Text(
                formatTime(p.durationUs), fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(p.updatedAt)),
                    fontSize = 11.sp, color = VG.TextDim,
                )
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = VG.TextDim) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; onDuplicate() })
                    DropdownMenuItem({ Text("Delete", color = VG.Accent2) }, { menu = false; onDelete() })
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = context
}
