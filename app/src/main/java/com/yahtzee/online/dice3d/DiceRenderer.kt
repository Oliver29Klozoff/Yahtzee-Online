package com.yahtzee.online.dice3d

import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DiceRenderer(
    val world: DicePhysicsWorld,
    private val onAllSettled: () -> Unit
) : GLSurfaceView.Renderer {

    private lateinit var cubeMesh: CubeMesh
    private lateinit var tableMesh: TableMesh
    private lateinit var glowQuad: GlowQuad
    private lateinit var diceShader: DiceShader
    private lateinit var tableShader: TableShader
    private lateinit var glowShader: GlowShader
    private var textureId = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var lastFrameNanos = 0L
    private var settledNotified = true

    /**
     * Player-selected dice colour. Written from the UI thread, consumed on the GL thread, which
     * is why the texture is not rebuilt here — [onDrawFrame] picks up the flag and regenerates
     * on the GL thread where a texture upload is legal.
     */
    @Volatile
    var diceColor: Int = DieTextureAtlas.DEFAULT_COLOR
        set(value) {
            if (field != value) {
                field = value
                textureDirty = true
            }
        }

    @Volatile
    private var textureDirty = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        cubeMesh = CubeMesh()
        tableMesh = TableMesh(world.tableHalfWidth, world.tableHalfDepth)
        glowQuad = GlowQuad()
        diceShader = DiceShader()
        tableShader = TableShader()
        glowShader = GlowShader()
        textureId = createTexture()
        uploadAtlas()

        Matrix.setLookAtM(
            viewMatrix, 0,
            CAMERA_X, CAMERA_Y, CAMERA_Z,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    private fun createTexture(): Int {
        val handle = IntArray(1)
        GLES20.glGenTextures(1, handle, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return handle[0]
    }

    private fun uploadAtlas() {
        val bitmap = DieTextureAtlas.build(diceColor)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.5f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (textureDirty) {
            textureDirty = false
            uploadAtlas()
        }

        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(1f / 30f)
        lastFrameNanos = now

        world.step(dt)

        val allSettled = world.allAtRest()
        if (allSettled && !settledNotified) {
            settledNotified = true
            onAllSettled()
        } else if (!allSettled) {
            settledNotified = false
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 1. Table.
        drawTable()

        // 2. Additive halo pooling on the surface under each die. Depth writes are off so the
        //    glow never occludes the dice drawn afterwards.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        for (die in world.dice) drawGlow(die)
        GLES20.glDepthMask(true)

        // 3. The dice, translucent so light passes through the material rather than only
        //    reflecting off it. Transparency imposes three requirements:
        //      - Depth writes off, or a nearer die would reject the ones behind it instead of
        //        letting them blend through.
        //      - Painter's ordering, so blending composites in the right sequence; without the
        //        depth buffer arbitrating, draw order IS the depth order.
        //      - Each die drawn twice: interior (far) walls first, then exterior. Seeing the
        //        inside of the far faces through the body is the strongest cue that the block
        //        is solid glass rather than a hollow shell.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (die in sortedBackToFront()) {
            GLES20.glCullFace(GLES20.GL_FRONT)
            drawDie(die, dim = INTERIOR_DIM)
            GLES20.glCullFace(GLES20.GL_BACK)
            drawDie(die, dim = 1f)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Dice ordered farthest-first for correct alpha compositing. Reuses one list so a sort per
     * frame costs no allocation.
     */
    private val sortScratch = ArrayList<DieBody>(8)

    private fun sortedBackToFront(): List<DieBody> {
        sortScratch.clear()
        sortScratch.addAll(world.dice)
        sortScratch.sortByDescending { die ->
            val dx = die.position.x - CAMERA_X
            val dy = die.position.y - CAMERA_Y
            val dz = die.position.z - CAMERA_Z
            dx * dx + dy * dy + dz * dz
        }
        return sortScratch
    }

    private fun drawTable() {
        GLES20.glUseProgram(tableShader.program)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(tableShader.uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(tableShader.uColor, 0f, 0f, 0f, 1f)

        tableMesh.vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(tableShader.aPosition)
        GLES20.glVertexAttribPointer(tableShader.aPosition, 3, GLES20.GL_FLOAT, false, 0, tableMesh.vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tableMesh.vertexCount)
        GLES20.glDisableVertexAttribArray(tableShader.aPosition)
    }

    private fun drawGlow(die: DieBody) {
        // Fade the pool out as a die flies up, so airborne dice do not drag a bright disc around
        // the table under them.
        val height = (die.position.y - DieBody.HALF_SIZE).coerceAtLeast(0f)
        val intensity = (1f - height / 1.4f).coerceIn(0f, 1f) * GLOW_STRENGTH
        if (intensity <= 0.001f) return

        GLES20.glUseProgram(glowShader.program)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, die.position.x, GLOW_HEIGHT, die.position.z)
        Matrix.scaleM(modelMatrix, 0, GLOW_RADIUS, 1f, GLOW_RADIUS)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(glowShader.uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(glowShader.uColor, Color.red(diceColor) / 255f, Color.green(diceColor) / 255f, Color.blue(diceColor) / 255f)
        GLES20.glUniform1f(glowShader.uIntensity, intensity)

        glowQuad.vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(glowShader.aPosition)
        GLES20.glVertexAttribPointer(glowShader.aPosition, 3, GLES20.GL_FLOAT, false, 0, glowQuad.vertexBuffer)
        glowQuad.uvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(glowShader.aTexCoord)
        GLES20.glVertexAttribPointer(glowShader.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, glowQuad.uvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, glowQuad.vertexCount)

        GLES20.glDisableVertexAttribArray(glowShader.aPosition)
        GLES20.glDisableVertexAttribArray(glowShader.aTexCoord)
    }

    private fun drawDie(die: DieBody, dim: Float) {
        GLES20.glUseProgram(diceShader.program)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, die.position.x, die.position.y, die.position.z)
        val rotMatrix = die.orientation.toMatrix4()
        Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, rotMatrix, 0)

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(diceShader.uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(diceShader.uModelMatrix, 1, false, modelMatrix, 0)
        GLES20.glUniform3f(diceShader.uLightDir, -0.4f, -1f, -0.3f)
        GLES20.glUniform3f(diceShader.uCameraPos, CAMERA_X, CAMERA_Y, CAMERA_Z)
        GLES20.glUniform3f(
            diceShader.uDiceColor,
            Color.red(diceColor) / 255f,
            Color.green(diceColor) / 255f,
            Color.blue(diceColor) / 255f
        )
        GLES20.glUniform1f(diceShader.uDim, dim)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(diceShader.uTexture, 0)

        cubeMesh.vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(diceShader.aPosition)
        GLES20.glVertexAttribPointer(diceShader.aPosition, 3, GLES20.GL_FLOAT, false, 0, cubeMesh.vertexBuffer)

        cubeMesh.uvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(diceShader.aTexCoord)
        GLES20.glVertexAttribPointer(diceShader.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, cubeMesh.uvBuffer)

        cubeMesh.normalBuffer.position(0)
        GLES20.glEnableVertexAttribArray(diceShader.aNormal)
        GLES20.glVertexAttribPointer(diceShader.aNormal, 3, GLES20.GL_FLOAT, false, 0, cubeMesh.normalBuffer)

        cubeMesh.indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, cubeMesh.indexCount, GLES20.GL_UNSIGNED_SHORT, cubeMesh.indexBuffer)

        GLES20.glDisableVertexAttribArray(diceShader.aPosition)
        GLES20.glDisableVertexAttribArray(diceShader.aTexCoord)
        GLES20.glDisableVertexAttribArray(diceShader.aNormal)
    }

    private companion object {
        const val CAMERA_X = 0f
        const val CAMERA_Y = 4.6f
        const val CAMERA_Z = 4.2f

        /**
         * Interior walls sit deeper in the material so they read dimmer than the near faces,
         * but not so dim that the far pips and internal edges stop showing through — seeing
         * those is what makes the block look clear rather than merely shiny.
         */
        const val INTERIOR_DIM = 0.80f
        const val GLOW_RADIUS = 0.95f
        const val GLOW_HEIGHT = 0.012f
        const val GLOW_STRENGTH = 0.30f
    }
}
