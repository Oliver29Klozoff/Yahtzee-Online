package com.yahtzee.online.dice3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class TableMesh(halfWidth: Float, halfDepth: Float) {
    val vertexBuffer: FloatBuffer
    val texCoordBuffer: FloatBuffer
    val vertexCount = 6

    init {
        val y = 0f
        val verts = floatArrayOf(
            -halfWidth, y, -halfDepth,
            -halfWidth, y, halfDepth,
            halfWidth, y, halfDepth,
            -halfWidth, y, -halfDepth,
            halfWidth, y, halfDepth,
            halfWidth, y, -halfDepth
        )

        // The artwork printed on the felt is square, so it is fitted to the table's shorter
        // dimension — its depth — rather than stretched across a surface that is wider than it
        // is deep. Past the edges of that square the coordinates run outside 0..1 and clamp,
        // which costs nothing: the artwork's own border is black, and black adds nothing to the
        // felt under the additive blend the table shader uses.
        fun u(x: Float) = x / (2f * halfDepth) + 0.5f
        // Row 0 of the bitmap uploads at v = 0, so v = 0 is put at the far edge of the table.
        // The camera looks down the -Z axis, which stands the wordmark up the right way round
        // from where the player is sitting rather than upside down across the table.
        fun v(z: Float) = z / (2f * halfDepth) + 0.5f

        val uvs = floatArrayOf(
            u(-halfWidth), v(-halfDepth),
            u(-halfWidth), v(halfDepth),
            u(halfWidth), v(halfDepth),
            u(-halfWidth), v(-halfDepth),
            u(halfWidth), v(halfDepth),
            u(halfWidth), v(-halfDepth)
        )

        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(verts)
                position(0)
            }
        texCoordBuffer = ByteBuffer.allocateDirect(uvs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(uvs)
                position(0)
            }
    }
}

private const val TABLE_VERTEX_SHADER = """
    uniform mat4 uMVPMatrix;
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        vTexCoord = aTexCoord;
        gl_Position = uMVPMatrix * aPosition;
    }
"""

/**
 * The artwork is added to the felt rather than blended over it. The image is bright dice and a
 * white wordmark on black, so adding it leaves the black border contributing exactly nothing —
 * the graphic appears printed into the felt with no rectangle around it, and no second pass or
 * blend state is needed to get there.
 */
private const val TABLE_FRAGMENT_SHADER = """
    precision mediump float;
    uniform vec4 uColor;
    uniform sampler2D uTexture;
    uniform float uLogoStrength;
    varying vec2 vTexCoord;
    void main() {
        vec3 logo = texture2D(uTexture, vTexCoord).rgb;
        gl_FragColor = vec4(uColor.rgb + logo * uLogoStrength, 1.0);
    }
"""

class TableShader {
    val program: Int
    val aPosition: Int
    val aTexCoord: Int
    val uMVPMatrix: Int
    val uColor: Int
    val uTexture: Int
    val uLogoStrength: Int

    init {
        val vs = android.opengl.GLES20.glCreateShader(android.opengl.GLES20.GL_VERTEX_SHADER).also {
            android.opengl.GLES20.glShaderSource(it, TABLE_VERTEX_SHADER)
            android.opengl.GLES20.glCompileShader(it)
        }
        val fs = android.opengl.GLES20.glCreateShader(android.opengl.GLES20.GL_FRAGMENT_SHADER).also {
            android.opengl.GLES20.glShaderSource(it, TABLE_FRAGMENT_SHADER)
            android.opengl.GLES20.glCompileShader(it)
        }
        program = android.opengl.GLES20.glCreateProgram().also {
            android.opengl.GLES20.glAttachShader(it, vs)
            android.opengl.GLES20.glAttachShader(it, fs)
            android.opengl.GLES20.glLinkProgram(it)
        }
        aPosition = android.opengl.GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = android.opengl.GLES20.glGetAttribLocation(program, "aTexCoord")
        uMVPMatrix = android.opengl.GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uColor = android.opengl.GLES20.glGetUniformLocation(program, "uColor")
        uTexture = android.opengl.GLES20.glGetUniformLocation(program, "uTexture")
        uLogoStrength = android.opengl.GLES20.glGetUniformLocation(program, "uLogoStrength")
    }
}
