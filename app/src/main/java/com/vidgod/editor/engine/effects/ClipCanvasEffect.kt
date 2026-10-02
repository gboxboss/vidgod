package com.vidgod.editor.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.vidgod.editor.engine.Keyframes
import com.vidgod.editor.engine.LiveProject
import com.vidgod.editor.engine.catalog.AnimState
import com.vidgod.editor.engine.catalog.Animations
import com.vidgod.editor.engine.catalog.Transitions
import com.vidgod.editor.engine.gl.Fbo
import com.vidgod.editor.engine.gl.FboHolder
import com.vidgod.editor.engine.gl.Shader
import com.vidgod.editor.model.BackgroundKind
import com.vidgod.editor.model.MaskShape
import com.vidgod.editor.model.TransitionRef
import com.vidgod.editor.model.VisualClip
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where a visual clip sits in the composition. */
data class ClipSpec(
    val clipId: String,
    /** Main-track clips draw the canvas background; overlays are transparent outside the layer. */
    val isMain: Boolean,
    /** Composition time at which the clip starts. */
    val timelineStartUs: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fallback: VisualClip,
    /** Transition from the previous main-track clip into this one. */
    val transitionIn: TransitionRef? = null,
    val prevClipId: String? = null,
    /** Duration of the transition that follows this clip (0 = none). */
    val transitionOutUs: Long = 0,
)

/**
 * Places a clip on the canvas: crop, flip, position/scale/rotation (with keyframes and
 * animations), mask, chroma key, opacity, canvas background and transitions.
 * Output frames always have the canvas size.
 */
@UnstableApi
class ClipCanvasEffect(private val live: LiveProject, private val spec: ClipSpec) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipCanvasProgram(context.applicationContext, live, spec, useHdr)
}

@UnstableApi
private class ClipCanvasProgram(
    private val context: Context,
    private val live: LiveProject,
    private val spec: ClipSpec,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private var inW = 1
    private var inH = 1
    private val outW = spec.canvasWidth
    private val outH = spec.canvasHeight

    private var main: Shader? = null
    private var copy: Shader? = null
    private var blurH: Shader? = null
    private var transition: Shader? = null
    private var transitionId: String? = null

    private val blurA = FboHolder()
    private val blurB = FboHolder()
    private val bgBlurA = FboHolder()
    private val bgBlurB = FboHolder()
    private val bgMap = FloatArray(4)
    private val temp = FboHolder()

    private var bgImageTex = 0
    private var bgImageUri: String? = null
    private var bgImageW = 1
    private var bgImageH = 1

    private val anim = AnimState()
    private val layer = FloatArray(9)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth
        inH = inputHeight
        return Size(outW, outH)
    }

    private fun ensurePrograms() {
        if (main == null) {
            main = Shader(Shader.VERTEX, Shader.COMMON + CanvasShaders.MAIN_FS)
            copy = Shader(Shader.VERTEX, Shader.COMMON + CanvasShaders.DOWNSAMPLE_FS)
            blurH = Shader(Shader.VERTEX, Shader.COMMON + CanvasShaders.BLUR_FS)
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            ensurePrograms()
            val outFbo = Fbo.current()
            val clip = live.visual(spec.clipId) ?: spec.fallback
            val canvas = live.project.canvas
            val local = (presentationTimeUs - spec.timelineStartUs).coerceAtLeast(0)
            val duration = clip.durationUs

            val kv = Keyframes.evaluate(clip.keyframes, local, clip.transform, clip.opacity)
            Animations.apply(anim, local, duration, clip.animIn, clip.animOut, clip.animCombo)

            // ---- layer geometry ----
            val crop = clip.crop
            val cropW = max(1f, inW * (crop.right - crop.left))
            val cropH = max(1f, inH * (crop.bottom - crop.top))
            val fit = min(outW / cropW, outH / cropH)
            val t = kv.transform
            val s = t.scale * anim.scale
            val dw = cropW * fit * s * anim.scaleX
            val dh = cropH * fit * s * anim.scaleY
            val cx = outW / 2f + (t.x + anim.dx) * outW
            val cy = outH / 2f + (t.y + anim.dy) * outH
            val theta = Math.toRadians((t.rotation + anim.rotation).toDouble())
            val c = cos(theta).toFloat()
            val sn = sin(theta).toFloat()
            val wc = outW.toFloat()
            val hc = outH.toFloat()
            // Column-major mat3: canvas uv (y up) -> layer uv (y down).
            layer[0] = c * wc / dw
            layer[1] = -sn * wc / dh
            layer[2] = 0f
            layer[3] = -sn * hc / dw
            layer[4] = -c * hc / dh
            layer[5] = 0f
            layer[6] = (c * (-cx) + sn * (hc - cy)) / dw + 0.5f
            layer[7] = (sn * cx + c * (hc - cy)) / dh + 0.5f
            layer[8] = 1f

            // ---- blurred copy of the input (background and blur animations) ----
            val bgMode = when {
                !spec.isMain -> 0f
                canvas.background == BackgroundKind.COLOR -> 1f
                canvas.background == BackgroundKind.BLUR -> 2f
                canvas.background == BackgroundKind.IMAGE && canvas.backgroundImageUri != null -> 3f
                else -> 1f
            }
            coverMap(inW.toFloat() / inH, outW.toFloat() / outH, bgMap)
            // Background blur is rendered in canvas space (no blocky magnification of a small copy).
            val bgBlurTex = if (bgMode == 2f) computeBlur(inputTexId, canvas.blur, bgMap, bgBlurA, bgBlurB) else inputTexId
            val blurTex = if (anim.blur > 0.01f) computeBlur(inputTexId, 0.6f, null, blurA, blurB) else inputTexId
            val bgTex = if (bgMode == 3f) ensureBgImage(canvas.backgroundImageUri!!) else 0

            // ---- transition into this clip ----
            val tr = spec.transitionIn
            val trDef = tr?.let { Transitions.get(it.id) }
            val trActive = trDef != null && local < tr!!.durationUs
            val target = if (trActive) temp.get(outW, outH).also { it.bind() } else null
            if (target == null) Fbo.bindTarget(outFbo, outW, outH)
            if (target != null) {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }

            val sh = main!!
            sh.use()
            sh.texture("uTex", 0, inputTexId)
            sh.texture("uBlurTex", 1, blurTex)
            sh.texture("uBgTex", 2, if (bgTex != 0) bgTex else inputTexId)
            sh.texture("uBgBlurTex", 3, bgBlurTex)
            sh.setMat3("uLayer", layer)
            sh.set4f("uCrop", crop.left, crop.top, crop.right, crop.bottom)
            sh.set2f("uFlip", if (t.flipH) 1f else 0f, if (t.flipV) 1f else 0f)
            sh.set2f("uLayerPx", dw, dh)
            sh.set1f("uOpacity", (kv.opacity * anim.alpha).coerceIn(0f, 1f))
            sh.set1f("uBright", anim.brightness)
            sh.set1f("uBgMode", bgMode)
            sh.setColor("uBgColor", canvas.backgroundColor, 1f)
            // cover-fit mapping from canvas uv to input uv / background image uv
            putCover(sh, "uBgImgMap", bgImageW.toFloat() / bgImageH, wc / hc)
            sh.set1f("uLayerBlur", anim.blur.coerceIn(0f, 1f))
            sh.set2f("uWipe", anim.wipe, anim.wipeDir.toFloat())
            val mask = clip.mask
            if (mask != null) {
                sh.set1f("uMaskType", mask.shape.ordinal.toFloat())
                sh.set4f("uMaskP", mask.x, mask.y, mask.width, mask.height)
                sh.set4f(
                    "uMaskQ", Math.toRadians(mask.rotation.toDouble()).toFloat(), mask.feather.coerceAtLeast(0.002f),
                    if (mask.invert) 1f else 0f, mask.roundness,
                )
            } else {
                sh.set1f("uMaskType", -1f)
            }
            val ck = clip.chromaKey
            if (ck != null) {
                sh.set4f(
                    "uChroma", ((ck.color shr 16) and 0xFF) / 255f, ((ck.color shr 8) and 0xFF) / 255f,
                    (ck.color and 0xFF) / 255f, 1f,
                )
                sh.set2f("uChromaP", ck.intensity, ck.shadow)
            } else {
                sh.set4f("uChroma", 0f, 0f, 0f, 0f)
            }
            sh.draw()

            if (target != null) {
                val tsh = transitionShader(trDef!!.id, trDef.glsl)
                Fbo.bindTarget(outFbo, outW, outH)
                tsh.use()
                val from = TransitionStore.textureFor(spec.prevClipId) ?: TransitionStore.black()
                tsh.texture("uFrom", 0, from)
                tsh.texture("uTo", 1, target.texId)
                tsh.set1f("progress", (local.toFloat() / tr!!.durationUs).coerceIn(0f, 1f))
                tsh.set1f("ratio", wc / hc)
                tsh.draw()
            }

            if (spec.transitionOutUs > 0 && duration - local <= spec.transitionOutUs + 300_000) {
                Fbo.bindTarget(outFbo, outW, outH)
                TransitionStore.store(spec.clipId, outW, outH)
            }
            Fbo.bindTarget(outFbo, outW, outH)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun putCover(sh: Shader, name: String, srcAspect: Float, dstAspect: Float) {
        val m = FloatArray(4)
        coverMap(srcAspect, dstAspect, m)
        sh.set4f(name, m[0], m[1], m[2], m[3])
    }

    /** uv_src = uv_dst * (m0, m1) + (m2, m3), so that the source covers the destination. */
    private fun coverMap(srcAspect: Float, dstAspect: Float, m: FloatArray) {
        if (srcAspect > dstAspect) {
            val sx = dstAspect / srcAspect
            m[0] = sx; m[1] = 1f; m[2] = 0.5f - 0.5f * sx; m[3] = 0f
        } else {
            val sy = srcAspect / dstAspect
            m[0] = 1f; m[1] = sy; m[2] = 0f; m[3] = 0.5f - 0.5f * sy
        }
    }

    /**
     * Blurs the input into a small texture. With [map] (canvas uv -> input uv) the result is in
     * canvas space (used for the background); without, it is in input space (layer blur).
     */
    private fun computeBlur(inputTexId: Int, strength: Float, map: FloatArray?, holderA: FboHolder, holderB: FboHolder): Int {
        val longSide = 128
        val srcW = if (map != null) outW else inW
        val srcH = if (map != null) outH else inH
        val w: Int
        val h: Int
        if (srcW >= srcH) {
            w = longSide; h = max(8, longSide * srcH / max(1, srcW))
        } else {
            h = longSide; w = max(8, longSide * srcW / max(1, srcH))
        }
        val mx = map?.get(0) ?: 1f
        val my = map?.get(1) ?: 1f
        val a = holderA.get(w, h)
        val b = holderB.get(w, h)
        a.bind()
        copy!!.use()
        copy!!.texture("uTex", 0, inputTexId)
        copy!!.set2f("uTexel", 1f / inW, 1f / inH)
        copy!!.set4f("uMap", mx, my, map?.get(2) ?: 0f, map?.get(3) ?: 0f)
        // Spread the 3x3 taps over the input area that one output texel covers.
        copy!!.set2f("uStep", max(1f, inW * mx / w / 3f), max(1f, inH * my / h / 3f))
        copy!!.draw()
        val radius = 1f + strength.coerceIn(0f, 1f) * 2.2f
        repeat(3) {
            b.bind()
            blurH!!.use()
            blurH!!.texture("uTex", 0, a.texId)
            blurH!!.set2f("uDir", radius / w, 0f)
            blurH!!.draw()
            a.bind()
            blurH!!.use()
            blurH!!.texture("uTex", 0, b.texId)
            blurH!!.set2f("uDir", 0f, radius / h)
            blurH!!.draw()
        }
        return a.texId
    }

    private fun ensureBgImage(uri: String): Int {
        if (uri == bgImageUri && bgImageTex != 0) return bgImageTex
        val bmp = loadBitmap(uri) ?: return 0
        if (bgImageTex == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            bgImageTex = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgImageTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bgImageW = bmp.width
        bgImageH = bmp.height
        bmp.recycle()
        bgImageUri = uri
        return bgImageTex
    }

    private fun loadBitmap(uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        // GL textures have their origin at the bottom-left: flip vertically.
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
            if (it != decoded) decoded.recycle()
        }
    }.getOrNull()

    private fun transitionShader(id: String, glsl: String): Shader {
        val existing = transition
        if (existing != null && transitionId == id) return existing
        existing?.release()
        val created = Shader(
            Shader.VERTEX,
            Shader.COMMON + Transitions.HEADER + glsl + "\nvoid main() { gl_FragColor = transition(vUv); }\n",
        )
        transition = created
        transitionId = id
        return created
    }

    override fun release() {
        super.release()
        main?.release(); copy?.release(); blurH?.release(); transition?.release()
        blurA.release(); blurB.release(); bgBlurA.release(); bgBlurB.release(); temp.release()
        if (bgImageTex != 0) GLES20.glDeleteTextures(1, intArrayOf(bgImageTex), 0)
    }

}

internal object CanvasShaders {
    const val DOWNSAMPLE_FS = """
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform vec2 uStep;
uniform vec4 uMap;
void main() {
  vec2 uv = vUv * uMap.xy + uMap.zw;
  vec4 acc = vec4(0.0);
  for (int x = -1; x <= 1; x++) {
for (int y = -1; y <= 1; y++) {
  acc += texture2D(uTex, clamp(uv + vec2(float(x), float(y)) * uTexel * uStep, 0.0, 1.0));
}
  }
  gl_FragColor = acc / 9.0;
}
"""
    const val BLUR_FS = """
uniform sampler2D uTex;
uniform vec2 uDir;
void main() {
  vec4 c = texture2D(uTex, vUv) * 0.2270270270;
  c += texture2D(uTex, vUv + uDir * 1.3846153846) * 0.3162162162;
  c += texture2D(uTex, vUv - uDir * 1.3846153846) * 0.3162162162;
  c += texture2D(uTex, vUv + uDir * 3.2307692308) * 0.0702702703;
  c += texture2D(uTex, vUv - uDir * 3.2307692308) * 0.0702702703;
  gl_FragColor = c;
}
"""
    val MAIN_FS = """
uniform sampler2D uTex;
uniform sampler2D uBlurTex;
uniform sampler2D uBgTex;
uniform sampler2D uBgBlurTex;
uniform mat3 uLayer;
uniform vec4 uCrop;
uniform vec2 uFlip;
uniform vec2 uLayerPx;
uniform float uOpacity;
uniform float uBright;
uniform float uBgMode;
uniform vec4 uBgColor;
uniform vec4 uBgImgMap;
uniform float uLayerBlur;
uniform vec2 uWipe;
uniform float uMaskType;
uniform vec4 uMaskP;
uniform vec4 uMaskQ;
uniform vec4 uChroma;
uniform vec2 uChromaP;

float sdBox(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + vec2(r);
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float sdHeart(vec2 p) {
  p.y = -p.y;
  p.x = abs(p.x);
  p.y += 0.5;
  if (p.y + p.x > 1.0) return sqrt(dot(p - vec2(0.25, 0.75), p - vec2(0.25, 0.75))) - sqrt(2.0) / 4.0;
  float a = dot(p - vec2(0.0, 1.0), p - vec2(0.0, 1.0));
  float m = 0.5 * max(p.x + p.y, 0.0);
  float b = dot(p - vec2(m), p - vec2(m));
  return sqrt(min(a, b)) * sign(p.x - p.y);
}
float sdStar(vec2 p, float r, float rf) {
  vec2 k1 = vec2(0.809016994375, -0.587785252292);
  vec2 k2 = vec2(-k1.x, k1.y);
  p.x = abs(p.x);
  p -= 2.0 * max(dot(k1, p), 0.0) * k1;
  p -= 2.0 * max(dot(k2, p), 0.0) * k2;
  p.x = abs(p.x);
  p.y -= r;
  vec2 ba = rf * vec2(-k1.y, k1.x) - vec2(0.0, 1.0);
  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
  return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
}
float maskAlpha(vec2 luv) {
  if (uMaskType < -0.5) return 1.0;
  float asp = uLayerPx.x / max(uLayerPx.y, 1.0);
  vec2 p = (luv - 0.5 - uMaskP.xy) * vec2(asp, 1.0);
  float c = cos(uMaskQ.x);
  float s = sin(uMaskQ.x);
  p = vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
  float f = max(uMaskQ.y, 0.002);
  float a = 1.0;
  if (uMaskType < 0.5) {
a = sstep(-f, f, p.y);
  } else if (uMaskType < 1.5) {
a = 1.0 - sstep(uMaskP.w * 0.5 - f, uMaskP.w * 0.5 + f, abs(p.y));
  } else if (uMaskType < 2.5) {
vec2 r = max(vec2(uMaskP.z * asp, uMaskP.w) * 0.5, vec2(0.001));
float d = (length(p / r) - 1.0) * min(r.x, r.y);
a = 1.0 - sstep(-f, f, d);
  } else if (uMaskType < 3.5) {
vec2 b = vec2(uMaskP.z * asp, uMaskP.w) * 0.5;
float d = sdBox(p, b, uMaskQ.w * min(b.x, b.y));
a = 1.0 - sstep(-f, f, d);
  } else if (uMaskType < 4.5) {
float sc = max(uMaskP.w, 0.01);
float d = sdHeart(p / sc) * sc;
a = 1.0 - sstep(-f, f, d);
  } else {
float sc = max(uMaskP.w, 0.01);
float d = sdStar(p / sc, 0.5, 0.45) * sc;
a = 1.0 - sstep(-f, f, d);
  }
  return uMaskQ.z > 0.5 ? 1.0 - a : a;
}
vec2 cbcr(vec3 c) {
  return vec2(-0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b, 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b);
}
void main() {
  vec3 l = uLayer * vec3(vUv, 1.0);
  vec2 luv = l.xy;
  float cov = clamp(min(luv.x, 1.0 - luv.x) * uLayerPx.x + 0.5, 0.0, 1.0)
        * clamp(min(luv.y, 1.0 - luv.y) * uLayerPx.y + 0.5, 0.0, 1.0);
  vec2 fuv = vec2(mix(luv.x, 1.0 - luv.x, uFlip.x), mix(luv.y, 1.0 - luv.y, uFlip.y));
  vec2 suv = vec2(uCrop.x + fuv.x * (uCrop.z - uCrop.x), uCrop.y + fuv.y * (uCrop.w - uCrop.y));
  vec2 tuv = clamp(vec2(suv.x, 1.0 - suv.y), 0.0, 1.0);
  vec4 c = texture2D(uTex, tuv);
  if (uLayerBlur > 0.001) c = mix(c, texture2D(uBlurTex, tuv), uLayerBlur);
  if (uChroma.a > 0.5) {
float d = distance(cbcr(c.rgb), cbcr(uChroma.rgb));
float lo = 0.02 + uChromaP.x * 0.22;
float key = sstep(lo, lo + 0.04 + uChromaP.y * 0.12, d);
c.a *= key;
float spill = 1.0 - key;
float lum = luma(c.rgb);
c.rgb = mix(c.rgb, vec3(lum), spill * 0.6);
  }
  float a = c.a * cov * uOpacity * maskAlpha(luv);
  if (uWipe.x < 0.999) {
float coord = uWipe.y < 0.5 ? luv.x : (uWipe.y < 1.5 ? 1.0 - luv.x : (uWipe.y < 2.5 ? luv.y : 1.0 - luv.y));
a *= 1.0 - sstep(uWipe.x - 0.03, uWipe.x, coord);
  }
  c.rgb = mix(c.rgb, vec3(1.0), clamp(uBright, 0.0, 1.0));
  if (uBgMode > 0.5) {
vec3 bg = uBgColor.rgb;
if (uBgMode > 1.5 && uBgMode < 2.5) bg = texture2D(uBgBlurTex, vUv).rgb;
else if (uBgMode > 2.5) bg = texture2D(uBgTex, vUv * uBgImgMap.xy + uBgImgMap.zw).rgb;
gl_FragColor = vec4(mix(bg, c.rgb, a), 1.0);
  } else {
gl_FragColor = vec4(c.rgb, a);
  }
}
"""
}

/** Mask shapes in the order used by the shader. */
val MaskShape.label: String
    get() = when (this) {
        MaskShape.LINEAR -> "Linear"
        MaskShape.MIRROR -> "Mirror"
        MaskShape.CIRCLE -> "Circle"
        MaskShape.RECTANGLE -> "Rectangle"
        MaskShape.HEART -> "Heart"
        MaskShape.STAR -> "Star"
    }
