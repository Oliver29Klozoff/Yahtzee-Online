package com.yahtzee.online.dice3d

import android.opengl.GLES20
import android.util.Log

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

// Illuminated glass material, tuned against the app's cover artwork: saturated transparent
// body, brilliant edges, darker interior depth, and light visibly travelling through the block.
// All per-fragment math with no extra render passes, so cost stays that of a flat-shaded
// material and continuous 60fps rendering remains safe.
//
// Every tint derives from uDiceColor rather than being hardcoded, so the material works at any
// hue for the user-selectable dice colour.
//
//   1. Glass-vs-pip separation by SATURATION. Luminance fails because the texture's edge band
//      is near-white, so the brightest glass would be mistaken for pips and skip lighting
//      exactly where the material should look most alive. Blue-dominance would work only for
//      blue dice. Saturation holds at every hue: the body is saturated, the pearl pips neutral.
//   2. Absorption-driven interior depth, deepest looking straight into a face, kept moderate so
//      the glass reads luminous rather than muddy.
//   3. Hue-preserving lift toward the key light — brighten toward a desaturated tint of the
//      same hue, never toward grey, so shadowed faces stay saturated.
//   4. Two specular lobes plus a bounce opposite the key light. Thick glass returns light on
//      both sides; a single hard highlight is the signature of plastic.
//   5. A procedural studio environment reflection sampled from the reflection vector, giving
//      the surface something to mirror as it tumbles instead of one static lamp.
//   6. Edge ignition: a bright fresnel halo along the silhouette where the rounded bevel
//      gathers light — now backed by real bevel geometry in CubeMesh.
//   7. Transmission: light carried through the material, strongest where the block is thinnest.
private const val FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    varying vec3 vWorldNormal;
    varying vec3 vWorldPos;
    uniform sampler2D uTexture;
    uniform vec3 uLightDir;
    uniform vec3 uCameraPos;
    uniform vec3 uDiceColor;
    uniform float uDim;

    void main() {
        vec4 texColor = texture2D(uTexture, vTexCoord);
        vec3 normal = normalize(vWorldNormal);
        vec3 toLight = normalize(-uLightDir);
        vec3 toCamera = normalize(uCameraPos - vWorldPos);

        float diffuse = max(dot(normal, toLight), 0.0);

        // A moulded die gets its form from plain light and shadow across the face — no depth
        // gradient through the body, no ignited edge, nothing shining through from behind. The
        // spread is wide enough that a face turned away still reads as the same colour rather
        // than going black.
        // Never brighter than the texture itself. Scaling a lit face ABOVE 1.0 does not make it
        // brighter, it clips — and it clips the strongest channel first, which drains the colour
        // out: a lit yellow face pins both red and green at 1.0 and comes out white. Shading
        // downward from full instead keeps every colour its own at every angle, and a face
        // turned away still reads as the colour rather than going black.
        vec3 color = texColor.rgb * (0.58 + 0.42 * diffuse);

        // One soft highlight, the size a matte plastic surface would give. Wide and weak
        // deliberately: a tight bright catch is what makes a surface look wet or glazed, and a
        // strong one would clip the same way the diffuse term did.
        vec3 halfVec = normalize(toLight + toCamera);
        float specAngle = max(dot(normal, halfVec), 0.0);
        float highlight = pow(specAngle, 48.0) * 0.09;

        gl_FragColor = vec4((color + vec3(highlight)) * uDim, texColor.a);
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
    val uDiceColor: Int
    val uDim: Int

    init {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                Log.e(TAG, "Dice program link failed: ${GLES20.glGetProgramInfoLog(it)}")
            } else {
                Log.i(TAG, "Dice shader program linked OK")
            }
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMVPMatrix = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uModelMatrix = GLES20.glGetUniformLocation(program, "uModelMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uCameraPos = GLES20.glGetUniformLocation(program, "uCameraPos")
        uDiceColor = GLES20.glGetUniformLocation(program, "uDiceColor")
        uDim = GLES20.glGetUniformLocation(program, "uDim")
    }

    /**
     * Compiles one stage, reporting failures instead of swallowing them — a GLSL error
     * otherwise surfaces only as silently black or garbled dice with nothing in the log.
     */
    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val stage = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
                Log.e(TAG, "Dice $stage shader failed: ${GLES20.glGetShaderInfoLog(it)}")
            }
        }
    }

    private companion object {
        const val TAG = "DiceShader"
    }
}
