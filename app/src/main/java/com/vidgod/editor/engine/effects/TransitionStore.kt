package com.vidgod.editor.engine.effects

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES20
import com.vidgod.editor.engine.gl.Fbo

/**
 * Keeps a copy of the last rendered frame of a main-track clip so that the next clip can blend
 * with it (cross-fades, pushes, ...). Media3 sequences cannot overlap clips, so transitions are
 * rendered by the incoming clip using this frozen frame of the outgoing one.
 */
object TransitionStore {
    private class Entry(val context: EGLContext) {
        var fbo: Fbo? = null
        var clipId: String? = null
    }

    private val entries = ArrayList<Entry>()
    private var blackTex = HashMap<EGLContext, Int>()

    @Synchronized
    private fun entry(): Entry {
        val ctx = EGL14.eglGetCurrentContext()
        entries.firstOrNull { it.context == ctx }?.let { return it }
        if (entries.size > 4) entries.removeAt(0)
        return Entry(ctx).also { entries.add(it) }
    }

    /** Copies the currently bound framebuffer (size [w]x[h]) as the last frame of [clipId]. */
    fun store(clipId: String, w: Int, h: Int) {
        val e = entry()
        var f = e.fbo
        if (f == null || f.width != w || f.height != h) {
            val previousFbo = Fbo.current()
            f?.release()
            f = Fbo(w, h)
            e.fbo = f
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFbo)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, f.texId)
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
        e.clipId = clipId
    }

    /** Texture holding the last frame of [clipId], or null if not available. */
    fun textureFor(clipId: String?): Int? {
        if (clipId == null) return null
        val e = entry()
        return if (e.clipId == clipId) e.fbo?.texId else null
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
