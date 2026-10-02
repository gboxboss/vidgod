package com.vidgod.editor.features

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.engine.gl.Fbo
import com.vidgod.editor.engine.gl.FboHolder
import com.vidgod.editor.engine.gl.Shader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** "Remove background": keeps people and makes everything else transparent (ML Kit). */
object BackgroundRemoval {
    fun run(vm: EditorViewModel, context: Context, clipId: String) {
        val p = vm.project.value
        val clip = (p.clips + p.overlays).firstOrNull { it.id == clipId } ?: return
        vm.editVisual(clipId) { it.copy(removeBackground = !it.removeBackground) }
        vm.toast(
            if (!clip.removeBackground) "Background removed (people are kept). Works best on clear shots of people."
            else "Background restored",
        )
        @Suppress("UNUSED_VARIABLE") val ctx = context
    }
}

/** GL effect that segments each frame and writes the person mask into alpha. */
@UnstableApi
class BackgroundRemovalEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private var w = 1
        private var h = 1
        private var small = FboHolder()
        private var copy: Shader? = null
        private var composite: Shader? = null
        private var maskTex = 0
        private var segmenter: Segmenter? = null
        private var pixels: ByteBuffer? = null
        private var maskBytes: ByteBuffer? = null
        private var bitmap: Bitmap? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            w = inputWidth
            h = inputHeight
            return Size(w, h)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                if (copy == null) {
                    copy = Shader(Shader.VERTEX, Shader.COMMON + FLIP_FS)
                    composite = Shader(Shader.VERTEX, Shader.COMMON + COMPOSITE_FS)
                    segmenter = Segmentation.getClient(
                        SelfieSegmenterOptions.Builder()
                            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                            .enableRawSizeMask()
                            .build(),
                    )
                    val t = IntArray(1)
                    GLES20.glGenTextures(1, t, 0)
                    maskTex = t[0]
                }
                val out = Fbo.current()
                // 1. Downscale (flipped so rows come out top-down) and read back.
                val sw = 256
                val sh = max(16, (256f * h / max(1, w)).roundToInt())
                val f = small.get(sw, sh)
                f.bind()
                copy!!.use()
                copy!!.texture("uTex", 0, inputTexId)
                copy!!.draw()
                val buf = pixels?.takeIf { it.capacity() == sw * sh * 4 }
                    ?: ByteBuffer.allocateDirect(sw * sh * 4).order(ByteOrder.nativeOrder()).also { pixels = it }
                buf.position(0)
                GLES20.glReadPixels(0, 0, sw, sh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                val bmp = bitmap?.takeIf { it.width == sw && it.height == sh }
                    ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { bitmap = it }
                buf.position(0)
                bmp.copyPixelsFromBuffer(buf)
                // 2. Segment.
                val mask = runCatching {
                    Tasks.await(segmenter!!.process(InputImage.fromBitmap(bmp, 0)), 3, TimeUnit.SECONDS)
                }.getOrNull()
                if (mask != null) {
                    val mw = mask.width
                    val mh = mask.height
                    val fb = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val mb = maskBytes?.takeIf { it.capacity() == mw * mh }
                        ?: ByteBuffer.allocateDirect(mw * mh).also { maskBytes = it }
                    mb.position(0)
                    for (i in 0 until mw * mh) mb.put((fb.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte())
                    mb.position(0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, mw, mh, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mb)
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
                }
                // 3. Composite.
                Fbo.bindTarget(out, w, h)
                composite!!.use()
                composite!!.texture("uTex", 0, inputTexId)
                composite!!.texture("uMask", 1, maskTex)
                composite!!.set1f("uHasMask", if (mask != null) 1f else 0f)
                composite!!.draw()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            small.release()
            copy?.release()
            composite?.release()
            if (maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0)
            runCatching { segmenter?.close() }
        }
    }

    companion object {
        const val FLIP_FS = """
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y)); }
"""
        const val COMPOSITE_FS = """
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform float uHasMask;
void main() {
  vec4 c = texture2D(uTex, vUv);
  float m = uHasMask > 0.5 ? texture2D(uMask, vec2(vUv.x, 1.0 - vUv.y)).r : 1.0;
  m = smoothstep(0.3, 0.7, m);
  gl_FragColor = vec4(c.rgb, c.a * m);
}
"""
    }
}
