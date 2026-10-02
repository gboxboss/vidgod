package com.vidgod.editor.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
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
    /** True while the player uses the multi-input video graph (picture-in-picture layers). */
    private var multi = false
    /**
     * The multi-input video graph cannot be reset: once its inputs ended (end of playback) or
     * after an error, the player must be recreated before it can show frames again.
     */
    private var playerStale = false
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
            if (playbackState == Player.STATE_READY && redrawOnReady) {
                // Safety net: the first frame of a new player may have gone to a surface that was
                // replaced meanwhile (e.g. while the editor screen was being laid out).
                redrawOnReady = false
                if (player?.playWhenReady == false) {
                    handler.removeCallbacks(redrawAfterSurface)
                    handler.postDelayed(redrawAfterSurface, 250)
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                player?.pause()
                _positionUs.value = live.project.durationUs
                if (multi) playerStale = true
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Preview error", error)
            com.vidgod.editor.data.Diagnostics.log(context, "Preview error: ${error.errorCodeName}", error)
            _error.value = error.message ?: error.errorCodeName
            playerStale = true
            // Recover: recreate the player with the current project.
            if (errorRetries < 2) {
                errorRetries++
                signature = null
                handler.postDelayed({ rebuildNow(pendingProject ?: live.project, fromError = true) }, 400)
            }
        }
    }

    /** Redraw once when a new (single-input) player becomes ready while paused. */
    private var redrawOnReady = false

    private fun createPlayer(multi: Boolean): CompositionPlayer {
        redrawOnReady = !multi
        val b = CompositionPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
        if (multi) {
            // Frame redraws are not supported by the multi-input graph; edits are shown by seeking.
            b.setVideoGraphFactory(MultipleInputVideoGraph.Factory(DefaultVideoFrameProcessor.Factory.Builder().build()))
        } else {
            b.experimentalSetEnableReplayableCache(true)
        }
        return b.build().also { p ->
            p.addListener(listener)
            val out = outputSurface
            if (out != null) p.setVideoSurface(out.first, out.second) else surfaceView?.let { p.setVideoSurfaceView(it) }
        }
    }

    /** Releases a player without reporting its release problems (e.g. timeouts) as preview errors. */
    private fun releaseQuietly(p: CompositionPlayer?) {
        p ?: return
        p.removeListener(listener)
        runCatching { p.release() }
    }

    private var outputSurface: Pair<Surface, Size>? = null

    /** Renders into [surface] instead of a SurfaceView (offscreen rendering, tests). */
    fun setOutputSurface(surface: Surface, width: Int, height: Int) {
        outputSurface = surface to Size(width, height)
        player?.setVideoSurface(surface, Size(width, height))
    }

    private val redrawAfterSurface = Runnable { redraw() }

    /** A new surface (e.g. back from the background) starts empty: show the paused frame again. */
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            handler.removeCallbacks(redrawAfterSurface)
            handler.postDelayed(redrawAfterSurface, 150)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {}
    }

    fun attach(view: SurfaceView) {
        surfaces.remove(view)
        surfaces.add(view)
        view.holder.removeCallback(surfaceCallback)
        view.holder.addCallback(surfaceCallback)
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        view.holder.removeCallback(surfaceCallback)
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
        if (suspended) return
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

    private fun rebuildNow(project: Project, fromError: Boolean = false, playAfter: Boolean = false) {
        if (!fromError) errorRetries = 0
        handler.removeCallbacks(rebuild)
        handler.removeCallbacks(multiRebuild)
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
        val wantMulti = built.videoSequenceCount > 1
        val p = player
        val wasPlaying = p?.isPlaying == true
        val positionMs = (_positionUs.value / 1000).coerceIn(0, (built.durationUs / 1000 - 1).coerceAtLeast(0))
        _positionUs.value = positionMs * 1000
        // The multi-input graph keeps its first composition's effects and inputs, so it is
        // recreated for every new composition (as Media3's own composition demo does).
        if (p == null || wantMulti || multi || playerStale) {
            releaseQuietly(p)
            player = createPlayer(wantMulti)
            playerStale = false
        }
        multi = wantMulti
        val np = player!!
        try {
            np.setComposition(built.composition, positionMs)
            np.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "setComposition failed", e)
            com.vidgod.editor.data.Diagnostics.log(context, "Preview setComposition failed", e)
            _error.value = e.message
            playerStale = true
            hasComposition = false
            return
        }
        hasComposition = true
        if (wasPlaying || playAfter) np.play()
        _error.value = null
    }

    /**
     * Media3's multi-input graph (picture-in-picture) cannot seek: its compositor keeps the old
     * frames and playback stalls. Seeks and live edits therefore recreate the player at the
     * current position, debounced while scrubbing or dragging.
     */
    private val multiRebuild = Runnable { pendingProject?.let { rebuildNow(it) } ?: rebuildNow(live.project) }
    private var scrubbing = false

    private fun scheduleMultiRebuild(delayMs: Long) {
        handler.removeCallbacks(multiRebuild)
        handler.postDelayed(multiRebuild, delayMs)
    }

    /** Re-renders the current frame after a live property change. */
    fun redraw() {
        val p = player ?: return
        if (p.isPlaying || !hasComposition) return
        if (multi || playerStale) {
            scheduleMultiRebuild(150)
            return
        }
        try {
            p.experimentalRedrawLastFrame()
        } catch (e: Exception) {
            p.seekTo(p.currentPosition)
        }
    }

    fun play() {
        if (player == null) return
        val atEnd = _positionUs.value >= live.project.durationUs - 50_000
        if (atEnd) _positionUs.value = 0
        if ((multi || playerStale) && hasComposition && (atEnd || playerStale)) {
            rebuildNow(pendingProject ?: live.project, playAfter = true)
            return
        }
        if (atEnd) player?.seekTo(0)
        player?.play()
    }

    fun pause() {
        val p = player ?: return
        val wasPlaying = p.isPlaying || p.playWhenReady
        p.pause()
        // While paused the playhead is ours (the player may still be at an older position until a
        // pending seek or rebuild happens).
        if (!wasPlaying) return
        _positionUs.value = p.currentPosition * 1000
        // Frames that arrive late are dropped during playback; re-render the exact paused frame.
        if (hasComposition && !playerStale && !multi) p.seekTo(p.currentPosition)
    }

    fun togglePlay() = if (player?.isPlaying == true) pause() else play()

    fun seekTo(us: Long) {
        val clamped = us.coerceIn(0, (live.project.durationUs - 1000).coerceAtLeast(0))
        _positionUs.value = clamped
        if ((multi || playerStale) && hasComposition) {
            scheduleMultiRebuild(if (scrubbing) 150 else 0)
            return
        }
        player?.seekTo(clamped / 1000)
    }

    fun setScrubbing(enabled: Boolean) {
        scrubbing = enabled
        if (!multi) runCatching { player?.setScrubbingModeEnabled(enabled) }
    }

    private var suspended = false

    /**
     * Releases the player and its decoders (export needs the device's codecs); updates are
     * remembered and shown again by [resume].
     */
    fun suspend() {
        suspended = true
        handler.removeCallbacksAndMessages(null)
        releaseQuietly(player)
        player = null
        signature = null
        hasComposition = false
        playerStale = false
        _isPlaying.value = false
    }

    fun resume() {
        if (!suspended) return
        suspended = false
        val p = pendingProject ?: live.project
        if (p.clips.isNotEmpty()) rebuildNow(p)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        releaseQuietly(player)
        player = null
        signature = null
        hasComposition = false
        playerStale = false
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
                .append(c.trimEndUs).append(c.speed).append(c.speedCurve.hashCode()).append(c.keepPitch)
                .append(c.voiceFx).append(c.denoise).append(c.fx.hashCode())
                .append(c.muted).append(c.volume > 0f).append(c.removeBackground).append(';')
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
