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
    varying vec3 vWorldPos;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTexCoord = aTexCoord;
        vWorldNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);
        vWorldPos = (uModelMatrix * aPosition).xyz;
    }
"""

// Glassy look: base diffuse lighting + a tight Blinn-Phong specular highlight (view-dependent,
// so it slides across the face as the die tumbles, unlike flat plastic shading) + a fresnel-style
// rim brightening at grazing angles, which reads as light catching a glossy/glass edge.
private const val FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    varying vec3 vWorldNormal;
    varying vec3 vWorldPos;
    uniform sampler2D uTexture;
    uniform vec3 uLightDir;
    uniform vec3 uCameraPos;
    void main() {
        vec4 texColor = texture2D(uTexture, vTexCoord);
        vec3 normal = normalize(vWorldNormal);
        vec3 toLight = normalize(-uLightDir);
        vec3 toCamera = normalize(uCameraPos - vWorldPos);

        float diffuse = max(dot(normal, toLight), 0.0);
        float baseLighting = 0.45 + 0.45 * diffuse;

        vec3 halfVec = normalize(toLight + toCamera);
        float specAngle = max(dot(normal, halfVec), 0.0);
        float specular = pow(specAngle, 60.0) * 0.9;

        float fresnel = pow(1.0 - max(dot(normal, toCamera), 0.0), 3.0);
        float rim = fresnel * 0.35;

        vec3 shaded = texColor.rgb * baseLighting + vec3(1.0) * specular + vec3(0.75, 0.85, 1.0) * rim;
        gl_FragColor = vec4(shaded, texColor.a);
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
    val uCameraPos: Int

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
        uCameraPos = GLES20.glGetUniformLocation(program, "uCameraPos")
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
    }
}
