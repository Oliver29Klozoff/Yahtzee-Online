package com.yahtzee.online.dice3d

import android.graphics.Color
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.TableLogoStore
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DiceRenderer(
    val world: DicePhysicsWorld,
    /** Application context — used once, to decode the artwork printed on the felt. */
    private val context: android.content.Context,
    private val onAllSettled: () -> Unit
) : GLSurfaceView.Renderer {

    private lateinit var cubeMesh: CubeMesh
    private lateinit var tableMesh: TableMesh
    private lateinit var glowQuad: GlowQuad
    private lateinit var diceShader: DiceShader
    private lateinit var tableShader: TableShader
    private lateinit var glowShader: GlowShader
    private var textureId = 0
    private var logoTextureId = 0

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

    /**
     * How pips are coloured. Resolved against the current dice colour at upload time rather
     * than stored as a boolean, so Auto follows the colour as it changes from player to player
     * without the caller having to recompute it.
     */
    @Volatile
    var pipStyle: DicePreferences.PipStyle = DicePreferences.PipStyle.AUTO
        set(value) {
            if (field != value) {
                field = value
                textureDirty = true
            }
        }

    @Volatile
    private var textureDirty = false

    /**
     * Camera distance as a multiple of the default. Below 1 moves the camera closer, so the dice
     * fill more of the view — the roll-off uses that to show its single die large, while a game
     * keeps 1.0 and its original framing.
     *
     * Per-renderer rather than a shared constant: each Dice3DView needs its own framing, and a
     * global would zoom every view at once.
     */
    @Volatile
    var cameraScale: Float = 1f

    /** Table felt colour, a local look preference. */
    @Volatile
    var tableColor: Int = 0xFF000000.toInt()

    /**
     * What is printed on the felt. Like [diceColor] this is written from the UI thread and acted
     * on in [onDrawFrame], since decoding and uploading a texture is only legal on the GL thread.
     */
    @Volatile
    var tableLogo: TableLogoStore.Mode = TableLogoStore.Mode.ARTWORK
        set(value) {
            if (field != value) {
                field = value
                logoDirty = true
            }
        }

    @Volatile
    private var logoDirty = false

    /** Set at upload time from whichever source was loaded, so the shader blends it correctly. */
    private var logoIsPhoto = false

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
        uploadLogo()

        updateCamera()
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

    /** Rebuilt each frame so a scale change from the UI thread takes effect immediately. */
    private fun updateCamera() {
        Matrix.setLookAtM(
            viewMatrix, 0,
            CAMERA_X, CAMERA_Y * cameraScale, CAMERA_Z * cameraScale,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    /**
     * Uploads the app artwork onto its own texture unit. Decoded once at surface creation and
     * recycled straight away — it never changes, unlike the dice atlas, which is rebuilt every
     * time the active player's colour does.
     */
    private fun uploadLogo() {
        logoDirty = false
        val mode = tableLogo

        if (mode == TableLogoStore.Mode.NONE) {
            logoTextureId = 0
            return
        }

        val custom = mode == TableLogoStore.Mode.CUSTOM
        val bitmap = runCatching {
            if (custom) {
                android.graphics.BitmapFactory.decodeFile(
                    TableLogoStore.customFile(context).absolutePath
                )
            } else {
                android.graphics.BitmapFactory.decodeResource(
                    context.resources,
                    com.yahtzee.online.R.drawable.splash_full,
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = LOGO_SAMPLE_SIZE
                    }
                )
            }
        }.getOrNull()

        if (bitmap == null) {
            logoTextureId = 0
            return
        }
        logoIsPhoto = custom

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LOGO_TEXTURE_UNIT)
        // Reuses the handle once one exists: re-generating on every change would leak a texture
        // per switch for as long as the surface lives.
        if (logoTextureId == 0) logoTextureId = createTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, logoTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        // Back to unit 0, which everything else assumes is the one that is active.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun uploadAtlas() {
        val bitmap = DieTextureAtlas.build(diceColor, pipStyle.darkFor(diceColor))
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
        if (logoDirty) uploadLogo()

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

        // The whole view is the felt, not a table quad floating on black. Set every frame rather
        // than once at creation so a colour picked in Settings takes effect on the preview
        // immediately, the same reason the camera is rebuilt here.
        GLES20.glClearColor(
            Color.red(tableColor) / 255f,
            Color.green(tableColor) / 255f,
            Color.blue(tableColor) / 255f,
            1f
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        updateCamera()
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 1. Table. Now the same colour as the cleared background, so it is not visible as a
        //    shape in its own right — it is drawn anyway to lay the ground plane into the depth
        //    buffer, which the glow pooling below tests against.
        drawTable()

        // 2. Additive halo pooling on the surface under each die. Depth writes are off so the
        //    glow never occludes the dice drawn afterwards.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        for (die in world.dice) drawGlow(die)
        GLES20.glDepthMask(true)

        GLES20.glDisable(GLES20.GL_BLEND)

        // 3. The dice, drawn opaque. Transparency was tried and looked wrong — against a black
        //    table, alpha blending is multiplicative, so see-through dice read as dim and
        //    washed out rather than as glass. Staying opaque also means the depth buffer sorts
        //    them correctly on its own, with no back-to-front ordering, no second interior
        //    pass, and no depth-mask juggling.
        for (die in world.dice) drawDie(die, dim = 1f)
    }

    private fun drawTable() {
        GLES20.glUseProgram(tableShader.program)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(tableShader.uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(
            tableShader.uColor,
            Color.red(tableColor) / 255f,
            Color.green(tableColor) / 255f,
            Color.blue(tableColor) / 255f,
            1f
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LOGO_TEXTURE_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, logoTextureId)
        GLES20.glUniform1i(tableShader.uTexture, LOGO_TEXTURE_UNIT)
        // Nothing to add if the artwork failed to decode, leaving plain felt rather than
        // whatever happens to be bound on that unit.
        GLES20.glUniform1f(
            tableShader.uLogoStrength,
            when {
                logoTextureId == 0 -> 0f
                // A photo replaces some of the felt rather than being added to it, so it needs
                // to be laid on more strongly than the artwork to read at all.
                logoIsPhoto -> PHOTO_STRENGTH
                else -> LOGO_STRENGTH
            }
        )
        GLES20.glUniform1f(tableShader.uLogoMix, if (logoIsPhoto) 1f else 0f)

        tableMesh.vertexBuffer.position(0)
        tableMesh.texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(tableShader.aPosition)
        GLES20.glEnableVertexAttribArray(tableShader.aTexCoord)
        GLES20.glVertexAttribPointer(tableShader.aPosition, 3, GLES20.GL_FLOAT, false, 0, tableMesh.vertexBuffer)
        GLES20.glVertexAttribPointer(tableShader.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, tableMesh.texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tableMesh.vertexCount)
        GLES20.glDisableVertexAttribArray(tableShader.aTexCoord)
        GLES20.glDisableVertexAttribArray(tableShader.aPosition)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
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
        GLES20.glUniform3f(
            diceShader.uCameraPos,
            CAMERA_X, CAMERA_Y * cameraScale, CAMERA_Z * cameraScale
        )
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

        const val GLOW_RADIUS = 0.95f
        const val GLOW_HEIGHT = 0.012f
        const val GLOW_STRENGTH = 0.30f

        /**
         * How strongly the app artwork shows through the felt. Low enough that it reads as
         * printed into the surface rather than as a picture laid on top of it, and that the real
         * dice rolling over it stay the thing being looked at.
         */
        const val LOGO_STRENGTH = 0.28f

        /**
         * A player's own picture is mixed into the felt rather than added, so it needs a heavier
         * hand to be visible — but stays well under half, so the table still reads as felt with
         * something printed on it rather than as a photo with dice on top.
         */
        const val PHOTO_STRENGTH = 0.40f

        /** Ample for a table graphic, and a quarter of the memory of the full-size artwork. */
        const val LOGO_SAMPLE_SIZE = 2

        /** Texture unit for the felt artwork, leaving unit 0 to the dice atlas. */
        const val LOGO_TEXTURE_UNIT = 1
    }
}
