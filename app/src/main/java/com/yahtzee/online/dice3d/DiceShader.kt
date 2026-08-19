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

// Thick cobalt-glass look, built entirely from per-fragment math (no extra render passes,
// so cost stays identical to a flat-shaded material — safe for continuous 60fps mobile
// rendering). The goal is depth INSIDE the material, not a brighter surface:
//   1. Absorption-driven base color: light is treated as traveling through a slab of colored
//      glass, so face-center (the longest internal path, away from the lit edges) is darkened
//      and pushed toward a deeper, more saturated blue — a cheap stand-in for Beer-Lambert
//      absorption instead of flat diffuse shading.
//   2. A fake transmission term: a darker "behind-the-glass" tone bleeds through based on the
//      normal, as if dim environment light is passing through the material rather than only
//      reflecting off it — kept subtle so the die stays opaque-looking, not crystal-clear.
//   3. A tight Blinn-Phong hotspot plus a soft halo for the lit surface highlight, toned down
//      from previous tuning so it reads as glass, not neon plastic.
//   4. A second, offset "internal reflection" glint using the reflection vector against a
//      fixed fake light — simulates light bouncing off the inside of the far face, the classic
//      cut-glass depth cue — kept faint and only visible near grazing angles.
//   5. A blue-tinted fresnel rim, restrained versus before, so edges stay brightly lit without
//      blooming into a glow.
//   6. Pips (near-white in the source texture) are protected from the absorption/tint pass so
//      they stay crisp and legible instead of being darkened along with the glass.
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

        float luminance = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));
        float pipMask = smoothstep(0.55, 0.8, luminance);

        float diffuse = max(dot(normal, toLight), 0.0);
        float ndotv = max(dot(normal, toCamera), 0.0);

        // Deep, saturated core color the glass absorbs toward — darker and bluer than the
        // texture's own base, standing in for light lost to a thick colored medium.
        vec3 deepCore = vec3(0.035, 0.10, 0.32);
        float absorption = 0.55 * (1.0 - ndotv * 0.6) - 0.18 * diffuse;
        absorption = clamp(absorption, 0.0, 0.62);
        vec3 glassColor = mix(texColor.rgb, deepCore, absorption);

        // Fake transmission: a dim, cool "seen through the material" tone mixed in more at
        // grazing/back-facing angles, so the die reads as translucent rather than solid-opaque.
        vec3 transmissionTint = vec3(0.05, 0.09, 0.22);
        float transmission = pow(1.0 - ndotv, 1.4) * 0.22;
        glassColor = mix(glassColor, transmissionTint, transmission);

        float baseLighting = 0.42 + 0.30 * diffuse;
        glassColor *= baseLighting;

        vec3 halfVec = normalize(toLight + toCamera);
        float specAngle = max(dot(normal, halfVec), 0.0);
        float tightSpecular = pow(specAngle, 130.0) * 0.85;
        float softSpecular = pow(specAngle, 22.0) * 0.16;

        // Internal-reflection glint: light bouncing off the inside of the far wall of the die,
        // approximated with the surface reflection vector against a second fixed light so it
        // slides independently of the primary highlight as the die tumbles.
        vec3 reflectDir = reflect(-toCamera, normal);
        vec3 innerLightDir = normalize(vec3(0.3, 0.6, 0.5));
        float innerGlint = pow(max(dot(reflectDir, innerLightDir), 0.0), 40.0) * 0.18;

        float fresnel = pow(1.0 - ndotv, 3.0);
        vec3 rimColor = vec3(0.45, 0.62, 1.0);
        float rim = fresnel * 0.32;

        vec3 shaded = glassColor
            + vec3(1.0) * (tightSpecular + softSpecular)
            + vec3(0.75, 0.85, 1.0) * innerGlint
            + rimColor * rim;

        shaded = mix(shaded, texColor.rgb, pipMask);

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
