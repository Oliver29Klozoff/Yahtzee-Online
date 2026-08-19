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
// rendering). Earlier tuning still read as lit plastic because brightness and saturation were
// coupled: scaling the whole RGB color by a diffuse term darkens AND grays it at once, and
// additive white highlights on top of that is exactly the "shiny plastic sphere" signature.
// This version keeps hue/saturation controlled independently of the lighting response:
//   1. Base hue never grays out — side faces in shadow stay a deep, saturated blue (mixed
//      toward a dark-but-still-blue core), never toward a neutral gray or black.
//   2. Interior/core darkening for optical depth is applied as a hue-preserving mix toward
//      that dark-blue core, strongest away from lit edges — this is what makes the block read
//      as "thick" rather than a thin painted shell.
//   3. Two specular bands: the primary Blinn-Phong hotspot near the light, AND a dimmer,
//      wider "secondary reflected band" on the geometrically opposite side of the face
//      (mirrored half-vector) — real glass/acrylic bounces light back on both the near and
//      far side of a curved-looking surface, one hard plastic highlight does not.
//   4. Fake internal caustic streaks: a few thin, angled sine bands modulated by the surface
//      normal, brightening in stripes that shift as the die rotates — a cheap stand-in for
//      light refracting/scattering inside a glass block, not a flat glow.
//   5. Fresnel is restricted to a narrow, sharp band right at the silhouette edge (high power)
//      instead of a broad soft glow across the whole face, so it doesn't read as neon.
//   6. Pips carry a dark cavity ring baked into the texture (see DieTextureAtlas) and are
//      additionally darkened by a faint occlusion term here so they read as recessed rather
//      than floating on the surface.
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
        float pipMask = smoothstep(0.5, 0.78, luminance);
        float cavityMask = smoothstep(0.02, 0.16, luminance) - pipMask;

        float diffuse = max(dot(normal, toLight), 0.0);
        float ndotv = max(dot(normal, toCamera), 0.0);

        // Deep, still-saturated core the glass darkens toward for depth — never neutral gray,
        // always a darker, denser version of the same cobalt hue.
        vec3 deepCore = vec3(0.03, 0.09, 0.30);
        float depth = clamp(0.62 - 0.5 * ndotv - 0.16 * diffuse, 0.08, 0.6);
        vec3 glassColor = mix(texColor.rgb, deepCore, depth);

        // Hue-preserving brightness response: brighten to a lighter version of the SAME hue
        // near the key light, rather than scaling the color toward gray/black.
        vec3 litBlue = vec3(0.42, 0.58, 1.0);
        glassColor = mix(glassColor, litBlue, diffuse * 0.22);
        glassColor *= (0.72 + 0.28 * diffuse);

        vec3 halfVec = normalize(toLight + toCamera);
        float specAngle = max(dot(normal, halfVec), 0.0);
        float tightSpecular = pow(specAngle, 150.0) * 0.8;
        float softSpecular = pow(specAngle, 24.0) * 0.12;

        // Secondary reflected-light band: mirror the light across the normal's tangent plane
        // to place a dimmer, wider highlight on the opposite side of the face from the main
        // hotspot — the two-highlight look of a curved, thick glass/acrylic surface.
        vec3 oppositeLight = reflect(toLight, normal);
        float oppAngle = max(dot(-oppositeLight, toCamera), 0.0);
        float secondaryBand = pow(oppAngle, 10.0) * 0.14;

        // Fake internal caustic streaks: thin diagonal bands driven by world position and the
        // normal, so they slide across the face as the die tumbles instead of sitting static.
        float streak = sin((vWorldPos.x + vWorldPos.y) * 9.0 + normal.z * 6.0);
        float caustic = smoothstep(0.85, 1.0, streak) * (0.5 + 0.5 * diffuse) * 0.10;

        // Fresnel narrowed to a sharp silhouette band, not a broad soft rim glow.
        float fresnel = pow(1.0 - ndotv, 5.0);
        vec3 rimColor = vec3(0.55, 0.72, 1.0);
        float rim = fresnel * 0.4;

        vec3 shaded = glassColor
            + vec3(1.0) * tightSpecular
            + vec3(0.8, 0.88, 1.0) * (softSpecular + secondaryBand)
            + vec3(0.6, 0.78, 1.0) * caustic
            + rimColor * rim;

        // Recessed pips: dark cavity ring darkens the glass around each pip; the pip disc
        // itself sits at a slightly dimmed white so it reads as inset rather than glowing.
        shaded = mix(shaded, shaded * 0.45, cavityMask);
        vec3 pipColor = vec3(0.92, 0.94, 0.98) * (0.85 + 0.15 * diffuse);
        shaded = mix(shaded, pipColor, pipMask);

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
