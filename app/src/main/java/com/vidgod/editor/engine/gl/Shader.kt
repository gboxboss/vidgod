package com.vidgod.editor.engine.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Minimal GLES2 program wrapper used by all custom effects. */
class Shader(vertexSource: String, fragmentSource: String) {
    val program: Int
    private val uniformLocations = HashMap<String, Int>()
    private val positionLocation: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        positionLocation = GLES20.glGetAttribLocation(program, "aPos")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log\n$source")
        }
        return shader
    }

    fun use() {
        GLES20.glUseProgram(program)
    }

    fun loc(name: String): Int = uniformLocations.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    fun set1i(name: String, v: Int) { val l = loc(name); if (l >= 0) GLES20.glUniform1i(l, v) }
    fun set1f(name: String, v: Float) { val l = loc(name); if (l >= 0) GLES20.glUniform1f(l, v) }
    fun set2f(name: String, a: Float, b: Float) { val l = loc(name); if (l >= 0) GLES20.glUniform2f(l, a, b) }
    fun set3f(name: String, a: Float, b: Float, c: Float) { val l = loc(name); if (l >= 0) GLES20.glUniform3f(l, a, b, c) }
    fun set4f(name: String, a: Float, b: Float, c: Float, d: Float) {
        val l = loc(name); if (l >= 0) GLES20.glUniform4f(l, a, b, c, d)
    }
    fun set4fv(name: String, values: FloatArray, count: Int) {
        val l = loc(name); if (l >= 0) GLES20.glUniform4fv(l, count, values, 0)
    }
    fun setMat3(name: String, m: FloatArray) { val l = loc(name); if (l >= 0) GLES20.glUniformMatrix3fv(l, 1, false, m, 0) }
    fun setColor(name: String, argb: Int, alpha: Float = ((argb ushr 24) and 0xFF) / 255f) {
        set4f(name, ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, alpha)
    }

    /** Binds a 2D texture to [unit] and points sampler [name] at it. */
    fun texture(name: String, unit: Int, texId: Int, linear: Boolean = true) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val filter = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        set1i(name, unit)
    }

    /** Draws a full-screen quad. */
    fun draw() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnableVertexAttribArray(positionLocation)
        QUAD.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vUv = aPos * 0.5 + 0.5;
}
"""

        /** Common GLSL helpers prepended to fragment shaders. */
        const val COMMON = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
// smoothstep that is well defined when edge0 > edge1 (GLSL leaves that undefined).
float sstep(float e0, float e1, float x) {
  float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}
float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
"""
    }
}

/** An RGBA texture with an attached framebuffer. */
class Fbo(val width: Int, val height: Int) {
    val texId: Int
    val fboId: Int

    init {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0,
        )
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
    }

    companion object {
        /** Returns the currently bound framebuffer. */
        fun current(): Int {
            val v = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, v, 0)
            return v[0]
        }

        fun bindTarget(fboId: Int, width: Int, height: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, width, height)
        }
    }
}

/** Lazily (re)creates an [Fbo] of the requested size. */
class FboHolder {
    private var fbo: Fbo? = null
    fun get(width: Int, height: Int): Fbo {
        val f = fbo
        if (f != null && f.width == width && f.height == height) return f
        f?.release()
        return Fbo(width, height).also { fbo = it }
    }

    fun release() {
        fbo?.release()
        fbo = null
    }
}
