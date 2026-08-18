package com.yahtzee.online.dice3d

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
    private lateinit var diceShader: DiceShader
    private lateinit var tableShader: TableShader
    private var textureId = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var lastFrameNanos = 0L
    private var settledNotified = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        cubeMesh = CubeMesh()
        tableMesh = TableMesh(world.tableHalfWidth, world.tableHalfDepth)
        diceShader = DiceShader()
        tableShader = TableShader()
        textureId = loadTexture()

        Matrix.setLookAtM(
            viewMatrix, 0,
            0f, 4.6f, 4.2f,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    private fun loadTexture(): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        val bitmap = DieTextureAtlas.build()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureHandle[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.5f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
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

        drawTable()
        for (die in world.dice) drawDie(die)
    }

    private fun drawTable() {
        GLES20.glUseProgram(tableShader.program)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(tableShader.uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(tableShader.uColor, 0.07f, 0.08f, 0.1f, 1f)

        tableMesh.vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(tableShader.aPosition)
        GLES20.glVertexAttribPointer(tableShader.aPosition, 3, GLES20.GL_FLOAT, false, 0, tableMesh.vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tableMesh.vertexCount)
        GLES20.glDisableVertexAttribArray(tableShader.aPosition)
    }

    private fun drawDie(die: DieBody) {
        GLES20.glUseProgram(diceShader.program)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, die.position.x, die.position.y, die.position.z)
        val rotMatrix = die.orientation.toMatrix4()
        Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, rotMatrix, 0)

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(diceShader.uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(diceShader.uModelMatrix, 1, false, modelMatrix, 0)
        GLES20.glUniform3f(diceShader.uLightDir, -0.4f, -1f, -0.3f)

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
}
