package com.vidgod.editor.engine.effects

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES20
import android.util.Log
import com.vidgod.editor.engine.gl.Fbo

/**
 * Keeps the last rendered frame of a main-track clip so that the next clip can blend with it
 * (cross-fades, pushes, ...). Media3 sequences cannot overlap clips, so transitions are rendered
 * by the incoming clip using this frozen frame of the outgoing one.
 *
 * Textures belong to an EGL context, so frames are kept per context, in two slots: a short clip
 * can receive a transition (reading the previous clip's slot) while it already stores its own
 * last frames for the next transition (writing the other slot).
 */
object TransitionStore {
    private const val TAG = "TransitionStore"

    private class Slot {
        var clipId: String? = null
        var fbo: Fbo? = null
    }

    private class Entry(val context: EGLContext) {
        val slots = arrayOf(Slot(), Slot())
        var next = 0
        val missesLogged = HashSet<String>()
    }

    private val entries = ArrayList<Entry>()
    private val blackTex = HashMap<EGLContext, Int>()

    @Synchronized
    private fun entry(): Entry {
        val ctx = EGL14.eglGetCurrentContext()
        entries.firstOrNull { it.context == ctx }?.let { return it }
        if (entries.size > 4) entries.removeAt(0)
        return Entry(ctx).also { entries.add(it) }
    }

    /**
     * Framebuffer that receives the current frame of [clipId] (the caller renders into it and
     * then copies it to its output). Never the slot that holds another clip's frame needed now.
     */
    @Synchronized
    fun targetFor(clipId: String, w: Int, h: Int): Fbo {
        val e = entry()
        var slot = e.slots.firstOrNull { it.clipId == clipId }
        if (slot == null) {
            slot = e.slots[e.next]
            e.next = (e.next + 1) % e.slots.size
            Log.d(TAG, "storing frames of $clipId (${w}x$h) in context ${e.context.nativeHandle}")
        }
        var f = slot.fbo
        if (f == null || f.width != w || f.height != h) {
            val previous = Fbo.current()
            f?.release()
            f = Fbo(w, h)
            slot.fbo = f
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previous)
        }
        slot.clipId = clipId
        return f
    }

    /** Texture holding the last frame of [clipId], or null if not available. */
    @Synchronized
    fun textureFor(clipId: String?): Int? {
        if (clipId == null) return null
        val e = entry()
        val tex = e.slots.firstOrNull { it.clipId == clipId }?.fbo?.texId
        if (tex == null && e.missesLogged.add(clipId)) {
            Log.w(TAG, "no stored frame for $clipId in context ${e.context.nativeHandle}; have ${e.slots.map { it.clipId }}")
        }
        return tex
    }

    /** A 1x1 opaque black texture for the current context. */
    @Synchronized
    fun black(): Int {
        val ctx = EGL14.eglGetCurrentContext()
        blackTex[ctx]?.let { return it }
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        com.vidgod.editor.engine.gl.Shader.linearClamp()
        val px = java.nio.ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1)).apply { position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        if (blackTex.size > 4) blackTex.clear()
        blackTex[ctx] = t[0]
        return t[0]
    }
}
