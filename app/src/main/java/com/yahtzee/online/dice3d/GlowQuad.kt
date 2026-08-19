package com.yahtzee.online.dice3d

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A flat quad laid on the table under each die, drawn additively to cast a soft coloured halo
 * onto the surface. The falloff is computed in the fragment shader from the quad's own UVs, so
 * no glow texture has to be generated or uploaded.
 */
class GlowQuad {
    val vertexBuffer: FloatBuffer
    val uvBuffer: FloatBuffer
    val vertexCount = 6

    init {
        val verts = floatArrayOf(
            -1f, 0f, -1f,
            -1f, 0f, 1f,
            1f, 0f, 1f,
            -1f, 0f, -1f,
            1f, 0f, 1f,
            1f, 0f, -1f
        )
        val uvs = floatArrayOf(
            0f, 0f,
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 1f,
            1f, 0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        uvBuffer = ByteBuffer.allocateDirect(uvs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uvs); position(0) }
    }
}

private const val GLOW_VERTEX_SHADER = """
    uniform mat4 uMVPMatrix;
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTexCoord = aTexCoord;
    }
"""

private const val GLOW_FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    uniform vec3 uColor;
    uniform float uIntensity;
    void main() {
        float d = length(vTexCoord - vec2(0.5)) * 2.0;
        float falloff = pow(max(0.0, 1.0 - d), 2.6);
        gl_FragColor = vec4(uColor * falloff * uIntensity, 1.0);
    }
"""

class GlowShader {
    val program: Int
    val aPosition: Int
    val aTexCoord: Int
    val uMVPMatrix: Int
    val uColor: Int
    val uIntensity: Int

    init {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, GLOW_VERTEX_SHADER)
            GLES20.glCompileShader(it)
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, GLOW_FRAGMENT_SHADER)
            GLES20.glCompileShader(it)
        }
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMVPMatrix = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity")
    }
}
