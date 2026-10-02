package com.vidgod.editor.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.CompositionPlayer
import com.vidgod.editor.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time preview of a [Project] using Media3's CompositionPlayer. Property changes that do
 * not affect the structure of the composition are applied live through [LiveProject].
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class PreviewController(private val context: Context) {

    val live = LiveProject(Project())
    private val factory = CompositionFactory(context)
    private val handler = Handler(Looper.getMainLooper())

    private var player: CompositionPlayer? = null
    private var playerSequences = 0
    /** Attached views, most recent last (fullscreen preview sits on top of the editor's). */
    private val surfaces = ArrayList<SurfaceView>()
    private val surfaceView: SurfaceView? get() = surfaces.lastOrNull()
    private var signature: String? = null
    private var pendingProject: Project? = null
    private var hasComposition = false

    private val _positionUs = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = _positionUs.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _canvasSize = MutableStateFlow(1080 to 1920)
    val canvasSize: StateFlow<Pair<Int, Int>> = _canvasSize.asStateFlow()

    /** Short side of the preview canvas; lower than export for smooth playback. */
    var previewShortSide = 720

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                _positionUs.value = p.currentPosition * 1000
                handler.postDelayed(this, 30)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            handler.removeCallbacks(ticker)
            if (isPlaying) handler.post(ticker)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player?.pause()
                _positionUs.value = live.project.durationUs
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Preview error", error)
            com.vidgod.editor.data.Diagnostics.log(context, "Preview error: ${error.errorCodeName}", error)
            _error.value = error.message ?: error.errorCodeName
            // Recover: rebuild the player on the next update.
            if (errorRetries < 2) {
                errorRetries++
                signature = null
                handler.postDelayed({ rebuildNow(pendingProject ?: live.project, fromError = true) }, 400)
            }
        }
    }

    private fun createPlayer(multi: Boolean): CompositionPlayer {
        val b = CompositionPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .experimentalSetEnableReplayableCache(true)
        if (multi) {
            b.setVideoGraphFactory(
                MultipleInputVideoGraph.Factory(
                    DefaultVideoFrameProcessor.Factory.Builder().setEnableReplayableCache(true).build(),
                ),
            )
        }
        return b.build().also { p ->
            p.addListener(listener)
            surfaceView?.let { p.setVideoSurfaceView(it) }
        }
    }

    fun attach(view: SurfaceView) {
        surfaces.remove(view)
        surfaces.add(view)
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        val wasTop = surfaceView == view
        surfaces.remove(view)
        if (wasTop) {
            player?.clearVideoSurfaceView(view)
            surfaceView?.let { player?.setVideoSurfaceView(it) }
        }
    }

    private val rebuild = Runnable { pendingProject?.let { rebuildNow(it) } }

    /**
     * Applies [project]. Property changes are shown immediately; structural changes (clips,
     * trims, speeds…) rebuild the composition, debounced so that drags stay smooth.
     */
    fun update(project: Project, immediate: Boolean = false) {
        pendingProject = project
        live.project = project
        val sig = Structure.signature(project) + "|" + previewShortSide
        if (sig == signature && player != null) {
            handler.removeCallbacks(rebuild)
            redraw()
            return
        }
        handler.removeCallbacks(rebuild)
        if (immediate || player == null) rebuildNow(project) else handler.postDelayed(rebuild, 180)
    }

    private var errorRetries = 0

    private fun rebuildNow(project: Project, fromError: Boolean = false) {
        if (!fromError) errorRetries = 0
        val sig = Structure.signature(project) + "|" + previewShortSide
        signature = sig
        val built = runCatching { factory.build(project, live, previewShortSide, 30) }
            .onFailure {
                Log.e(TAG, "Composition build failed", it)
                com.vidgod.editor.data.Diagnostics.log(context, "Composition build failed", it)
                _error.value = it.message
            }
            .getOrNull()
        if (built == null) {
            player?.stop()
            hasComposition = false
            return
        }
        _canvasSize.value = built.canvasWidth to built.canvasHeight
        val multi = built.sequenceCount > 1
        val p = player
        val wasPlaying = p?.isPlaying == true
        val positionMs = (_positionUs.value / 1000).coerceIn(0, (built.durationUs / 1000 - 1).coerceAtLeast(0))
        if (p == null || (playerSequences > 1) != multi) {
            p?.release()
            player = createPlayer(multi)
        }
        playerSequences = built.sequenceCount
        val np = player!!
        np.setComposition(built.composition, positionMs)
        np.prepare()
        hasComposition = true
        if (wasPlaying) np.play()
        _error.value = null
    }

    /** Re-renders the current frame after a live property change. */
    fun redraw() {
        val p = player ?: return
        if (p.isPlaying || !hasComposition) return
        try {
            p.experimentalRedrawLastFrame()
        } catch (e: Exception) {
            p.seekTo(p.currentPosition)
        }
    }

    fun play() {
        val p = player ?: return
        if (_positionUs.value >= live.project.durationUs - 50_000) seekTo(0)
        p.play()
    }

    fun pause() {
        player?.pause()
        player?.let { _positionUs.value = it.currentPosition * 1000 }
    }

    fun togglePlay() = if (player?.isPlaying == true) pause() else play()

    fun seekTo(us: Long) {
        val clamped = us.coerceIn(0, (live.project.durationUs - 1000).coerceAtLeast(0))
        _positionUs.value = clamped
        player?.seekTo(clamped / 1000)
    }

    fun setScrubbing(enabled: Boolean) {
        runCatching { player?.setScrubbingModeEnabled(enabled) }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        signature = null
    }

    companion object {
        private const val TAG = "PreviewController"
    }
}

/** Computes which project changes require rebuilding the Media3 composition. */
object Structure {
    fun signature(p: Project): String {
        val sb = StringBuilder()
        sb.append(p.canvas.ratio).append(p.mainMuted).append('|')
        p.clips.forEach { c ->
            sb.append(c.id).append(c.playbackUri).append(c.trimStartUs).append(c.trimEndUs).append(c.speed)
                .append(c.speedCurve.hashCode()).append(c.keepPitch).append(c.voiceFx).append(c.denoise)
                .append(c.fx.hashCode()).append(c.transitionOut?.hashCode()).append(c.removeBackground).append(';')
        }
        sb.append('|')
        p.overlays.forEach { c ->
            sb.append(c.id).append(c.playbackUri).append(c.startUs).append(c.layer).append(c.trimStartUs)
                .append(c.trimEndUs).append(c.speed).append(c.voiceFx).append(c.denoise).append(c.fx.hashCode())
                .append(c.muted).append(c.removeBackground).append(';')
        }
        sb.append('|')
        p.audios.forEach { a ->
            sb.append(a.id).append(a.source.uri).append(a.startUs).append(a.lane).append(a.trimStartUs)
                .append(a.trimEndUs).append(a.speed).append(a.keepPitch).append(a.voiceFx).append(a.denoise)
                .append(a.volume).append(a.fadeInUs).append(a.fadeOutUs).append(';')
        }
        sb.append('|')
        p.texts.forEach { sb.append(it.id).append(it.startUs).append(it.durationUs).append(';') }
        p.stickers.forEach { sb.append(it.id).append(it.startUs).append(it.durationUs).append(';') }
        sb.append('|')
        p.effects.forEach { sb.append(it.id).append(it.lane).append(';') }
        sb.append(p.filters.isNotEmpty())
        return sb.toString()
    }
}
