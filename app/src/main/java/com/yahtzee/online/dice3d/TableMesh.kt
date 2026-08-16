package com.yahtzee.online.dice3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class TableMesh(halfWidth: Float, halfDepth: Float) {
    val vertexBuffer: FloatBuffer
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
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(verts)
                position(0)
            }
    }
}

private const val TABLE_VERTEX_SHADER = """
    uniform mat4 uMVPMatrix;
    attribute vec4 aPosition;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
    }
"""

private const val TABLE_FRAGMENT_SHADER = """
    precision mediump float;
    uniform vec4 uColor;
    void main() {
        gl_FragColor = uColor;
    }
"""

class TableShader {
    val program: Int
    val aPosition: Int
    val uMVPMatrix: Int
    val uColor: Int

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
        uMVPMatrix = android.opengl.GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uColor = android.opengl.GLES20.glGetUniformLocation(program, "uColor")
    }
}
