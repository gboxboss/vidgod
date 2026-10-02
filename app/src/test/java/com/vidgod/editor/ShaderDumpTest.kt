package com.vidgod.editor

import com.vidgod.editor.engine.catalog.Effects
import com.vidgod.editor.engine.catalog.Transitions
import com.vidgod.editor.engine.effects.CanvasShaders
import com.vidgod.editor.engine.effects.ColorGradeProgramShaders
import com.vidgod.editor.engine.effects.GradeGlsl
import com.vidgod.editor.engine.effects.GradeShaders
import com.vidgod.editor.engine.gl.Shader
import com.vidgod.editor.features.BackgroundRemovalEffect
import org.junit.Test
import java.io.File

/**
 * Writes every GLSL shader used by the app to build/shaders so they can be checked with
 * `glslangValidator` (see tools/validate-shaders.sh). Compiling shaders is otherwise only
 * possible on a device.
 */
class ShaderDumpTest {
    @Test
    fun dumpShaders() {
        val dir = File("build/shaders").apply { deleteRecursively(); mkdirs() }
        fun frag(name: String, body: String) = File(dir, "$name.frag").writeText("#version 100\n" + Shader.COMMON + body)
        File(dir, "quad.vert").writeText("#version 100\n" + Shader.VERTEX)
        frag("canvas_main", CanvasShaders.MAIN_FS)
        frag("canvas_downsample", CanvasShaders.DOWNSAMPLE_FS)
        frag("canvas_blur", CanvasShaders.BLUR_FS)
        frag("grade", GradeGlsl.FUNCTIONS + GradeShaders.FS)
        frag("grade_copy", GradeShaders.COPY_FS)
        frag("fx_copy", ColorGradeProgramShaders.COPY)
        frag("bg_flip", BackgroundRemovalEffect.FLIP_FS)
        frag("bg_composite", BackgroundRemovalEffect.COMPOSITE_FS)
        Effects.all.forEach { frag("fx_${it.id}", Effects.HEADER + it.glsl + "\nvoid main() { gl_FragColor = fx(vUv); }\n") }
        Transitions.all.forEach {
            frag("tr_${it.id}", Transitions.HEADER + it.glsl + "\nvoid main() { gl_FragColor = transition(vUv); }\n")
        }
    }
}
