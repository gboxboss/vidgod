package com.vidgod.editor.engine.effects

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.vidgod.editor.engine.catalog.GradeParams
import com.vidgod.editor.engine.gl.Fbo
import com.vidgod.editor.engine.gl.Shader

/** Values for one frame of colour grading. */
class GradeFrame {
    val filter = FloatArray(GradeParams.FLOATS)
    var filterIntensity = 0f
    val adjust = FloatArray(GradeParams.FLOATS)

    fun clear() {
        GradeParams.NEUTRAL.pack(filter)
        GradeParams.NEUTRAL.pack(adjust)
        filterIntensity = 0f
    }
}

/** Fills [out] for a presentation time; returns false when no grading applies (pass-through). */
fun interface GradeProvider {
    fun gradeAt(timeUs: Long, out: GradeFrame): Boolean
}

/** Applies a filter (look) and manual adjustments. */
@UnstableApi
class ColorGradeEffect(private val provider: GradeProvider) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ColorGradeProgram(provider, useHdr)
}

@UnstableApi
private class ColorGradeProgram(private val provider: GradeProvider, useHdr: Boolean) :
    BaseGlShaderProgram(useHdr, 1) {
    private var w = 1
    private var h = 1
    private var shader: Shader? = null
    private var passthrough: Shader? = null
    private val frame = GradeFrame()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth
        h = inputHeight
        return Size(w, h)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (shader == null) {
                shader = Shader(Shader.VERTEX, Shader.COMMON + GradeGlsl.FUNCTIONS + GradeShaders.FS)
                passthrough = Shader(Shader.VERTEX, Shader.COMMON + GradeShaders.COPY_FS)
            }
            val out = Fbo.current()
            Fbo.bindTarget(out, w, h)
            frame.clear()
            if (!provider.gradeAt(presentationTimeUs, frame)) {
                passthrough!!.use()
                passthrough!!.texture("uTex", 0, inputTexId)
                passthrough!!.draw()
                return
            }
            val sh = shader!!
            sh.use()
            sh.texture("uTex", 0, inputTexId)
            sh.set4fv("uF", frame.filter, 7)
            sh.set4fv("uA", frame.adjust, 7)
            sh.set1f("uFI", frame.filterIntensity)
            sh.set2f("uRes", w.toFloat(), h.toFloat())
            sh.set1f("uSeed", (presentationTimeUs / 33_333L % 1000L) / 1000f)
            sh.draw()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        shader?.release()
        passthrough?.release()
    }
}

internal object GradeShaders {
    const val COPY_FS = """
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""
    const val FS = """
uniform sampler2D uTex;
uniform vec4 uF[7];
uniform vec4 uA[7];
uniform float uFI;
uniform vec2 uRes;
uniform float uSeed;
void main() {
  vec4 src = texture2D(uTex, vUv);
  vec3 c = src.rgb;
  float sharp = uF[3].y * uFI + uA[3].y;
  if (abs(sharp) > 0.001) {
vec2 t = 1.0 / uRes;
vec3 blur = (texture2D(uTex, vUv + vec2(t.x, 0.0)).rgb + texture2D(uTex, vUv - vec2(t.x, 0.0)).rgb
  + texture2D(uTex, vUv + vec2(0.0, t.y)).rgb + texture2D(uTex, vUv - vec2(0.0, t.y)).rgb) * 0.25;
c = clamp(c + (c - blur) * sharp * 1.5, 0.0, 1.0);
  }
  if (uFI > 0.001) {
vec3 f = grade(c, uF[0], uF[1], uF[2], uF[3], uF[4], uF[5], uF[6], vUv, uRes, uSeed);
c = mix(c, f, clamp(uFI, 0.0, 1.0));
  }
  c = grade(c, uA[0], uA[1], uA[2], uA[3], uA[4], uA[5], uA[6], vUv, uRes, uSeed);
  gl_FragColor = vec4(c, src.a);
}
"""
}
