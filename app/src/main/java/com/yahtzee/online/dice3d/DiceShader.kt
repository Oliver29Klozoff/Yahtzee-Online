package com.yahtzee.online.dice3d

import android.opengl.GLES20

private const val VERTEX_SHADER = """
    uniform mat4 uMVPMatrix;
    uniform mat4 uModelMatrix;
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    attribute vec3 aNormal;
    varying vec2 vTexCoord;
    varying vec3 vWorldNormal;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTexCoord = aTexCoord;
        vWorldNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);
    }
"""

private const val FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    varying vec3 vWorldNormal;
    uniform sampler2D uTexture;
    uniform vec3 uLightDir;
    void main() {
        vec4 texColor = texture2D(uTexture, vTexCoord);
        float diffuse = max(dot(vWorldNormal, -uLightDir), 0.0);
        float lighting = 0.55 + 0.45 * diffuse;
        gl_FragColor = vec4(texColor.rgb * lighting, texColor.a);
    }
"""

class DiceShader {
    val program: Int
    val aPosition: Int
    val aTexCoord: Int
    val aNormal: Int
    val uMVPMatrix: Int
    val uModelMatrix: Int
    val uTexture: Int
    val uLightDir: Int

    init {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMVPMatrix = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uModelMatrix = GLES20.glGetUniformLocation(program, "uModelMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
    }
}
