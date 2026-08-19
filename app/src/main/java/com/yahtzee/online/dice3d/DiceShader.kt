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

// Glass look, built entirely from per-fragment math (no extra render passes, so cost stays
// identical to a flat-shaded material — safe for continuous 60fps mobile rendering):
//   1. Base diffuse lighting, lifted at the low end so the glass never looks flat black.
//   2. A tight, bright Blinn-Phong specular highlight (view-dependent, slides across the face
//      as the die tumbles) tuned hot and narrow for a polished-acrylic hotspot.
//   3. A second, wider/softer specular lobe layered underneath the tight one — real glass and
//      polished acrylic show a soft halo around the hard highlight, not just one hard dot.
//   4. A blue-tinted fresnel rim that brightens sharply at grazing angles, giving the edges of
//      the die a glowing outline as it turns — the strongest single cue for "glass" vs "plastic".
//   5. A faint inner-glow term driven by the texture's own luminance, faking light scattering
//      inside a translucent block rather than only reflecting off an opaque surface.
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
        float baseLighting = 0.5 + 0.42 * diffuse;

        vec3 halfVec = normalize(toLight + toCamera);
        float specAngle = max(dot(normal, halfVec), 0.0);
        float tightSpecular = pow(specAngle, 110.0) * 1.15;
        float softSpecular = pow(specAngle, 18.0) * 0.28;

        float ndotv = max(dot(normal, toCamera), 0.0);
        float fresnel = pow(1.0 - ndotv, 2.6);
        vec3 rimColor = vec3(0.55, 0.72, 1.0);
        float rim = fresnel * 0.55;

        float luminance = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));
        float innerGlow = luminance * 0.12;

        vec3 shaded = texColor.rgb * baseLighting
            + texColor.rgb * innerGlow
            + vec3(1.0) * (tightSpecular + softSpecular)
            + rimColor * rim;

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
