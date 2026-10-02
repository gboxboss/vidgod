package com.vidgod.editor.engine.effects

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.vidgod.editor.engine.catalog.Effects
import com.vidgod.editor.engine.gl.Fbo
import com.vidgod.editor.engine.gl.Shader

/** One active effect at a given time. */
class FxFrame {
    var id: String? = null
    var intensity = 1f
    var speed = 1f
    var localUs = 0L
    var durationUs = 1L
}

/** Supplies the effect active in [slot] at a time; returns false if none (pass-through). */
fun interface FxProvider {
    fun fxAt(timeUs: Long, out: FxFrame): Boolean
}

/** Applies one video effect from [Effects] chosen per frame by [provider]. */
@UnstableApi
class FxEffect(private val provider: FxProvider) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FxProgram(provider, useHdr)
}

@UnstableApi
private class FxProgram(private val provider: FxProvider, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private var w = 1
    private var h = 1
    private val programs = HashMap<String, Shader>()
    private var passthrough: Shader? = null
    private val frame = FxFrame()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth
        h = inputHeight
        return Size(w, h)
    }

    private fun program(id: String): Shader? {
        programs[id]?.let { return it }
        val def = Effects.get(id) ?: return null
        val sh = Shader(
            Shader.VERTEX,
            Shader.COMMON + Effects.HEADER + def.glsl + "\nvoid main() { gl_FragColor = fx(vUv); }\n",
        )
        programs[id] = sh
        return sh
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val out = Fbo.current()
            Fbo.bindTarget(out, w, h)
            frame.id = null
            val active = provider.fxAt(presentationTimeUs, frame)
            val sh = if (active) frame.id?.let { program(it) } else null
            if (sh == null) {
                val p = passthrough ?: Shader(Shader.VERTEX, Shader.COMMON + ColorGradeProgramShaders.COPY).also { passthrough = it }
                p.use()
                p.texture("uTex", 0, inputTexId)
                p.draw()
                return
            }
            sh.use()
            sh.texture("uTex", 0, inputTexId)
            sh.set1f("uTime", frame.localUs / 1_000_000f)
            sh.set1f("uProgress", (frame.localUs.toFloat() / frame.durationUs.coerceAtLeast(1)).coerceIn(0f, 1f))
            sh.set1f("uIntensity", frame.intensity.coerceIn(0f, 1f))
            sh.set1f("uSpeed", frame.speed)
            sh.set2f("uRes", w.toFloat(), h.toFloat())
            sh.draw()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        programs.values.forEach { it.release() }
        programs.clear()
        passthrough?.release()
    }
}

object ColorGradeProgramShaders {
    const val COPY = """
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""
}
