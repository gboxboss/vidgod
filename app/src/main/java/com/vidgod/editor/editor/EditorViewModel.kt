package com.vidgod.editor.editor

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.vidgod.editor.VidGodApp
import com.vidgod.editor.data.MediaProbe
import com.vidgod.editor.engine.Keyframes
import com.vidgod.editor.engine.PreviewController
import com.vidgod.editor.media.Thumbnails
import com.vidgod.editor.model.AudioKind
import com.vidgod.editor.model.FilterClip
import com.vidgod.editor.model.Keyframe
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.MediaSource
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.StickerClip
import com.vidgod.editor.model.StickerKind
import com.vidgod.editor.model.TextClip
import com.vidgod.editor.model.VisualClip
import com.vidgod.editor.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Busy(val label: String, val progress: Float? = null, val cancellable: Boolean = false)

@OptIn(UnstableApi::class)
class EditorViewModel(app: Application, val projectId: String) : AndroidViewModel(app) {
    private val vg = app as VidGodApp
    private val repo = vg.repository
    val preview = PreviewController(app)

    private val _project = MutableStateFlow(Project(id = projectId))
    val project: StateFlow<Project> = _project.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()
    private val _panel = MutableStateFlow<Panel?>(null)
    val panel: StateFlow<Panel?> = _panel.asStateFlow()
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Clip whose chroma-key colour is being picked from the preview, if any. */
    private val _eyedropper = MutableStateFlow<String?>(null)
    val eyedropper: StateFlow<String?> = _eyedropper.asStateFlow()

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private var gestureSnapshot: Project? = null
    private var saveJob: Job? = null
    var busyJob: Job? = null

    init {
        viewModelScope.launch {
            val p = repo.load(projectId) ?: Project(id = projectId)
            _project.value = p
            preview.update(p)
            _loaded.value = true
        }
    }

    // ---------------------------------------------------------------- core state

    val positionUs get() = preview.positionUs.value

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    /** Applies [f]. With [record], the previous state is pushed to the undo history. */
    fun update(record: Boolean = true, f: (Project) -> Project) {
        val old = _project.value
        val new = f(old).copy(updatedAt = System.currentTimeMillis())
        if (new == old) return
        if (record && gestureSnapshot == null) {
            undoStack.addLast(old)
            if (undoStack.size > 80) undoStack.removeFirst()
            redoStack.clear()
        }
        _project.value = new
        preview.update(new)
        validateSelection(new)
        refreshHistoryFlags()
        scheduleSave()
    }

    /** Starts a continuous edit (slider drag, gesture): one undo step for the whole gesture. */
    fun beginGesture() {
        if (gestureSnapshot == null) gestureSnapshot = _project.value
    }

    fun endGesture() {
        val snap = gestureSnapshot ?: return
        gestureSnapshot = null
        if (snap != _project.value) {
            undoStack.addLast(snap)
            if (undoStack.size > 80) undoStack.removeFirst()
            redoStack.clear()
            refreshHistoryFlags()
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_project.value)
        _project.value = prev
        preview.update(prev)
        validateSelection(prev)
        refreshHistoryFlags()
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_project.value)
        _project.value = next
        preview.update(next)
        validateSelection(next)
        refreshHistoryFlags()
        scheduleSave()
    }

    private fun refreshHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun validateSelection(p: Project) {
        val s = _selection.value ?: return
        if (!ProjectOps.exists(p, s)) {
            _selection.value = null
            if (_panel.value != null && _panel.value !in GLOBAL_PANELS) _panel.value = null
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            repo.save(_project.value)
        }
    }

    fun select(s: Selection?) {
        _selection.value = s
        if (s == null && _panel.value !in GLOBAL_PANELS) _panel.value = null
        if (s != null && _panel.value != null && _panel.value !in itemPanelsFor(s)) _panel.value = null
    }

    fun openPanel(p: Panel?) {
        _panel.value = p
    }

    fun rename(name: String) = update { it.copy(name = name.ifBlank { "Untitled" }) }

    // ---------------------------------------------------------------- import

    private fun persist(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private suspend fun probeAll(uris: List<Uri>, hint: MediaKind? = null): List<MediaSource> {
        val ctx = getApplication<Application>()
        return uris.mapNotNull { u ->
            persist(u)
            MediaProbe.probe(ctx, u, hint)
        }
    }

    fun importMain(uris: List<Uri>, insertAtPlayhead: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = Busy("Importing ${uris.size} file(s)…")
            val sources = probeAll(uris).filter { it.kind != MediaKind.AUDIO && (it.kind == MediaKind.IMAGE || it.durationUs > 0) }
            _busy.value = null
            if (sources.isEmpty()) { toast("Could not open the selected media"); return@launch }
            val clips = sources.map { ProjectOps.visualFrom(it) }
            update { p ->
                val index = if (insertAtPlayhead && p.clips.isNotEmpty()) {
                    val (i, off) = ProjectOps.mainClipAt(p, positionUs) ?: (p.clips.size to 0L)
                    if (off > p.clips[i].durationUs / 2) i + 1 else i
                } else {
                    p.clips.size
                }
                ProjectOps.insertMain(p, clips, index)
            }
            makeCover()
        }
    }

    fun replaceClip(sel: Selection, uri: Uri) {
        viewModelScope.launch {
            val src = probeAll(listOf(uri)).firstOrNull() ?: return@launch toast("Could not open media")
            if (src.kind == MediaKind.AUDIO) return@launch toast("Pick a video or photo")
            update { p ->
                ProjectOps.updateVisual(p, sel.id) { c ->
                    val keep = c.durationUs
                    val end = if (src.kind == MediaKind.IMAGE) keep else (keep * c.speed).toLong().coerceAtMost(src.durationUs)
                    c.copy(source = src, trimStartUs = 0, trimEndUs = end.coerceAtLeast(ProjectOps.MIN_DURATION_US), reversedFrom = null, speedCurve = emptyList())
                }
            }
        }
    }

    fun addOverlay(uri: Uri) {
        viewModelScope.launch {
            val src = probeAll(listOf(uri)).firstOrNull() ?: return@launch toast("Could not open media")
            if (src.kind == MediaKind.AUDIO) return@launch toast("Pick a video or photo")
            var added: VisualClip? = null
            update { p -> ProjectOps.addOverlay(p, src, positionUs).also { added = it.second }.first }
            added?.let { select(Selection.Overlay(it.id)) }
        }
    }

    fun addAudioFile(uri: Uri, kind: AudioKind = AudioKind.MUSIC) {
        viewModelScope.launch {
            val src = probeAll(listOf(uri), MediaKind.AUDIO).firstOrNull()
            if (src == null || src.durationUs <= 0) return@launch toast("Could not open audio")
            addAudioSource(src, kind)
        }
    }

    fun addAudioSource(src: MediaSource, kind: AudioKind, atUs: Long = positionUs) {
        var added: com.vidgod.editor.model.AudioClip? = null
        update { p ->
            val maxEnd = (p.durationUs - atUs).coerceAtLeast(ProjectOps.MIN_DURATION_US)
            ProjectOps.addAudio(p, src, atUs, kind, 0, src.durationUs.coerceAtMost(maxEnd)).also { added = it.second }.first
        }
        added?.let { select(Selection.Audio(it.id)) }
    }

    /** Uses the audio track of a video file as a separate audio clip. */
    fun extractAudioFrom(uri: Uri) {
        viewModelScope.launch {
            val src = probeAll(listOf(uri), MediaKind.VIDEO).firstOrNull() ?: return@launch toast("Could not open video")
            if (!src.hasAudio && src.kind == MediaKind.VIDEO) return@launch toast("That video has no sound")
            addAudioSource(src.copy(kind = MediaKind.AUDIO), AudioKind.EXTRACTED)
        }
    }

    /** Detaches the selected clip's audio onto an audio track and mutes the clip. */
    fun extractAudioOfSelected() {
        val sel = _selection.value ?: return
        val p = _project.value
        val clip = (p.clips + p.overlays).firstOrNull { it.id == sel.id } ?: return
        if (!clip.source.hasAudio) return toast("This clip has no sound")
        val start = ProjectOps.range(p, sel)?.first ?: return
        update { pr ->
            val (p2, _) = ProjectOps.addAudio(pr, clip.source.copy(kind = MediaKind.AUDIO), start, AudioKind.EXTRACTED, clip.trimStartUs, clip.trimEndUs)
            val withSpeed = p2.copy(audios = p2.audios.map { if (it.source.uri == clip.source.uri && it.startUs == start && it.speed == 1f) it.copy(speed = clip.speed, keepPitch = clip.keepPitch) else it })
            ProjectOps.updateVisual(withSpeed, clip.id) { it.copy(muted = true) }
        }
        toast("Audio extracted to its own track")
    }

    // ---------------------------------------------------------------- items

    fun addText(text: String = "Enter text", template: TextClip? = null) {
        val p = _project.value
        if (p.clips.isEmpty()) return toast("Add a video or photo first")
        val start = positionUs.coerceAtMost((p.durationUs - ProjectOps.MIN_DURATION_US).coerceAtLeast(0))
        val clip = (template ?: TextClip(text = text, startUs = 0)).copy(
            id = newId(), text = template?.text ?: text, startUs = start,
            durationUs = 3_000_000L.coerceAtMost((p.durationUs - start).coerceAtLeast(ProjectOps.MIN_DURATION_US)),
        )
        update { ProjectOps.addText(it, clip) }
        select(Selection.Text(clip.id))
        openPanel(Panel.TEXT_EDIT)
    }

    fun addSticker(kind: StickerKind, content: String) {
        val p = _project.value
        if (p.clips.isEmpty()) return toast("Add a video or photo first")
        val start = positionUs.coerceAtMost((p.durationUs - ProjectOps.MIN_DURATION_US).coerceAtLeast(0))
        val s = StickerClip(
            kind = kind, content = content, startUs = start,
            durationUs = 3_000_000L.coerceAtMost((p.durationUs - start).coerceAtLeast(ProjectOps.MIN_DURATION_US)),
        )
        update { ProjectOps.addSticker(it, s) }
        select(Selection.Sticker(s.id))
    }

    fun addStickerImage(uri: Uri) {
        persist(uri)
        addSticker(StickerKind.IMAGE, uri.toString())
    }

    fun addEffect(fxId: String) {
        val p = _project.value
        if (p.clips.isEmpty()) return toast("Add a video or photo first")
        var id: String? = null
        update { pr -> ProjectOps.addEffect(pr, fxId, positionUs.coerceAtMost(pr.durationUs - ProjectOps.MIN_DURATION_US)).also { id = it.second.id }.first }
        id?.let { select(Selection.Effect(it)) }
    }

    fun addFilterClip(filterId: String?, adjustOnly: Boolean = false) {
        val p = _project.value
        if (p.clips.isEmpty()) return toast("Add a video or photo first")
        var id: String? = null
        val c = FilterClip(
            filter = if (adjustOnly || filterId == null) null else com.vidgod.editor.model.FilterRef(filterId),
            startUs = positionUs.coerceAtMost(p.durationUs - ProjectOps.MIN_DURATION_US),
        )
        update { pr -> ProjectOps.addFilterClip(pr, c).also { id = it.second.id }.first }
        id?.let { select(Selection.Filter(it)) }
    }

    fun split() {
        val sel = _selection.value ?: run {
            val p = _project.value
            val (i, _) = ProjectOps.mainClipAt(p, positionUs) ?: return
            Selection.Main(p.clips[i].id)
        }
        var newSel: Selection? = sel
        update { p -> ProjectOps.split(p, sel, positionUs).also { newSel = it.second }.first }
        select(newSel)
    }

    fun deleteSelected() {
        val sel = _selection.value ?: return
        update { ProjectOps.delete(it, sel) }
        select(null)
    }

    fun duplicateSelected() {
        val sel = _selection.value ?: return
        var newSel: Selection? = null
        update { p -> ProjectOps.duplicate(p, sel).also { newSel = it.second }.first }
        newSel?.let { select(it) }
    }

    fun selectMainAtPlayhead(): Boolean {
        val p = _project.value
        val (i, _) = ProjectOps.mainClipAt(p, positionUs) ?: return false
        select(Selection.Main(p.clips[i].id))
        return true
    }

    fun editVisual(id: String, record: Boolean = true, f: (VisualClip) -> VisualClip) =
        update(record) { ProjectOps.updateVisual(it, id, f) }

    fun editText(id: String, record: Boolean = true, f: (TextClip) -> TextClip) =
        update(record) { ProjectOps.updateText(it, id, f) }

    fun editSticker(id: String, record: Boolean = true, f: (StickerClip) -> StickerClip) =
        update(record) { ProjectOps.updateSticker(it, id, f) }

    fun editAudio(id: String, record: Boolean = true, f: (com.vidgod.editor.model.AudioClip) -> com.vidgod.editor.model.AudioClip) =
        update(record) { ProjectOps.updateAudio(it, id, f) }

    fun editEffect(id: String, record: Boolean = true, f: (com.vidgod.editor.model.EffectClip) -> com.vidgod.editor.model.EffectClip) =
        update(record) { ProjectOps.updateEffect(it, id, f) }

    fun editFilter(id: String, record: Boolean = true, f: (FilterClip) -> FilterClip) =
        update(record) { ProjectOps.updateFilter(it, id, f) }

    /** Applies a change to every main-track clip (e.g. "apply to all"). */
    fun editAllMain(f: (VisualClip) -> VisualClip) = update { p -> p.copy(clips = p.clips.map(f)) }

    fun reorderMain(from: Int, to: Int) = update { p ->
        val list = p.clips.toMutableList()
        if (from !in list.indices) return@update p
        val c = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), c)
        p.copy(clips = list)
    }

    // ---------------------------------------------------------------- keyframes

    /** Local time of the playhead inside the selected item, or null if outside. */
    fun selectedLocalUs(): Long? {
        val sel = _selection.value ?: return null
        val r = ProjectOps.range(_project.value, sel) ?: return null
        val t = positionUs
        return if (t >= r.first && t <= r.last + 1) t - r.first else null
    }

    fun hasKeyframeAtPlayhead(): Boolean {
        val sel = _selection.value ?: return false
        val local = selectedLocalUs() ?: return false
        val p = _project.value
        val kfs = when (sel) {
            is Selection.Main, is Selection.Overlay -> (p.clips + p.overlays).firstOrNull { it.id == sel.id }?.keyframes
            is Selection.Text -> p.texts.firstOrNull { it.id == sel.id }?.keyframes
            is Selection.Sticker -> p.stickers.firstOrNull { it.id == sel.id }?.keyframes
            else -> null
        } ?: return false
        return Keyframes.nearest(kfs, local) != null
    }

    fun toggleKeyframe() {
        val sel = _selection.value ?: return toast("Select a clip, text or sticker first")
        val local = selectedLocalUs() ?: return toast("Move the playhead over the selected item")
        when (sel) {
            is Selection.Main, is Selection.Overlay -> editVisual(sel.id) { c ->
                val existing = Keyframes.nearest(c.keyframes, local)
                if (existing != null) c.copy(keyframes = c.keyframes - existing)
                else {
                    val v = Keyframes.evaluate(c.keyframes, local, c.transform, c.opacity, c.volume)
                    c.copy(keyframes = Keyframes.upsert(c.keyframes, Keyframe(local, v.transform, v.opacity, v.volume)))
                }
            }
            is Selection.Text -> editText(sel.id) { c ->
                val existing = Keyframes.nearest(c.keyframes, local)
                if (existing != null) c.copy(keyframes = c.keyframes - existing)
                else {
                    val v = Keyframes.evaluate(c.keyframes, local, c.transform, 1f)
                    c.copy(keyframes = Keyframes.upsert(c.keyframes, Keyframe(local, v.transform, v.opacity)))
                }
            }
            is Selection.Sticker -> editSticker(sel.id) { c ->
                val existing = Keyframes.nearest(c.keyframes, local)
                if (existing != null) c.copy(keyframes = c.keyframes - existing)
                else {
                    val v = Keyframes.evaluate(c.keyframes, local, c.transform, 1f)
                    c.copy(keyframes = Keyframes.upsert(c.keyframes, Keyframe(local, v.transform, v.opacity)))
                }
            }
            else -> toast("Keyframes work on clips, texts and stickers")
        }
    }

    // ---------------------------------------------------------------- eyedropper

    fun startEyedropper(clipId: String) {
        preview.pause()
        _eyedropper.value = clipId
        toast("Tap the colour to remove on the preview")
    }

    fun cancelEyedropper() {
        _eyedropper.value = null
    }

    /** Picks the colour under a tap at canvas fractions (nx, ny) for the chroma key. */
    fun eyedropAt(nx: Float, ny: Float, cw: Int, ch: Int) {
        val id = _eyedropper.value ?: return
        _eyedropper.value = null
        val p = _project.value
        val clip = (p.clips + p.overlays).firstOrNull { it.id == id } ?: return
        val range = ProjectOps.range(p, Selection.Main(id).takeIf { p.clips.any { c -> c.id == id } } ?: Selection.Overlay(id)) ?: return
        val local = (positionUs - range.first).coerceIn(0, clip.durationUs)
        val uv = com.vidgod.editor.engine.LayerMath.canvasToSource(clip, local, nx, ny, cw, ch)
            ?: return toast("Tap inside the clip")
        viewModelScope.launch {
            val frame = if (clip.isImage) Thumbnails.loadImage(getApplication(), clip.playbackUri, 512)
            else Thumbnails.exactFrame(getApplication(), clip.playbackUri, ProjectOps.sourceTimeAt(clip, local), 512)
            if (frame == null) return@launch toast("Could not read the frame")
            val x = (uv.first * frame.width).toInt().coerceIn(0, frame.width - 1)
            val y = (uv.second * frame.height).toInt().coerceIn(0, frame.height - 1)
            // Average a small neighbourhood for a stable colour.
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val px = frame.getPixel((x + dx).coerceIn(0, frame.width - 1), (y + dy).coerceIn(0, frame.height - 1))
                r += (px shr 16) and 0xFF; g += (px shr 8) and 0xFF; b += px and 0xFF; n++
            }
            val color = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            editVisual(id) { it.copy(chromaKey = (it.chromaKey ?: com.vidgod.editor.model.ChromaKey()).copy(color = color)) }
        }
    }

    // ---------------------------------------------------------------- freeze frame

    fun freezeFrame() {
        val p = _project.value
        val (i, offset) = ProjectOps.mainClipAt(p, positionUs) ?: return
        val clip = p.clips[i]
        viewModelScope.launch {
            _busy.value = Busy("Creating freeze frame…")
            val src = clip.timelineToSource(offset)
            val bmp = if (clip.isImage) Thumbnails.loadImage(getApplication(), clip.source.uri, 2160)
            else Thumbnails.exactFrame(getApplication(), clip.playbackUri, src, 2160)
            if (bmp == null) { _busy.value = null; return@launch toast("Could not read that frame") }
            val file = File(repo.mediaDir(projectId), "freeze_${System.currentTimeMillis()}.jpg")
            withContext(Dispatchers.IO) { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
            _busy.value = null
            val still = ProjectOps.visualFrom(
                MediaSource(Uri.fromFile(file).toString(), MediaKind.IMAGE, "Freeze frame", 3_000_000, bmp.width, bmp.height),
            ).copy(transform = clip.transform, crop = clip.crop, filter = clip.filter, adjust = clip.adjust, label = "Freeze")
            update { pr ->
                val (split, _) = ProjectOps.split(pr, Selection.Main(clip.id), positionUs)
                val idx = split.clips.indexOfFirst { it.id == clip.id } + 1
                ProjectOps.insertMain(split, listOf(still), idx)
            }
            select(Selection.Main(still.id))
        }
    }

    private fun VisualClip.timelineToSource(offset: Long) = ProjectOps.sourceTimeAt(this, offset)

    // ---------------------------------------------------------------- cover

    fun makeCover() {
        val first = _project.value.clips.firstOrNull() ?: return
        vg.appScope.launch {
            val bmp = Thumbnails.get(getApplication(), first.playbackUri, first.trimStartUs, 480, first.isImage) ?: return@launch
            repo.saveCover(projectId, bmp)
        }
    }

    fun runBusy(label: String, block: suspend () -> Unit) {
        busyJob?.cancel()
        busyJob = viewModelScope.launch {
            _busy.value = Busy(label, null, cancellable = true)
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Something went wrong")
            } finally {
                _busy.value = null
            }
        }
    }

    fun setBusyProgress(label: String, progress: Float?) {
        _busy.value = Busy(label, progress, cancellable = true)
    }

    fun cancelBusy() {
        busyJob?.cancel()
        _busy.value = null
    }

    override fun onCleared() {
        val p = _project.value
        vg.appScope.launch {
            repo.save(p)
            repo.refresh()
        }
        makeCover()
        preview.release()
        super.onCleared()
    }

    companion object {
        val GLOBAL_PANELS = setOf(
            Panel.AUDIO_MENU, Panel.TEXT_MENU, Panel.STICKERS, Panel.STYLES, Panel.EFFECTS_ADD, Panel.FILTERS_ADD,
            Panel.ADJUST_ADD, Panel.RATIO, Panel.CANVAS, Panel.CAPTIONS, Panel.TTS, Panel.RECORD, Panel.EXPORT, Panel.REORDER,
        )

        fun itemPanelsFor(s: Selection): Set<Panel> = when (s) {
            is Selection.Main, is Selection.Overlay -> setOf(
                Panel.SPEED, Panel.VOLUME, Panel.ANIMATION, Panel.FILTERS, Panel.ADJUST, Panel.EFFECTS, Panel.TRANSFORM,
                Panel.OPACITY, Panel.CHROMA, Panel.MASK, Panel.VOICE_FX, Panel.TRANSITION, Panel.BLEND, Panel.REORDER,
            )
            is Selection.Text -> setOf(Panel.TEXT_EDIT, Panel.ANIMATION, Panel.TTS)
            is Selection.Sticker -> setOf(Panel.ANIMATION, Panel.OPACITY)
            is Selection.Audio -> setOf(Panel.VOLUME, Panel.SPEED, Panel.VOICE_FX)
            is Selection.Effect -> setOf(Panel.FX_EDIT)
            is Selection.Filter -> setOf(Panel.FILTER_EDIT, Panel.ADJUST)
        } + GLOBAL_PANELS
    }
}
