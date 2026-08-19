package com.yahtzee.online.dice3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import kotlin.random.Random

class Dice3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val world = DicePhysicsWorld()
    private val renderer: DiceRenderer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onSettledCallback: ((List<Int>) -> Unit)? = null
    private var pendingTargets: List<Int> = List(5) { 1 }
    private var heldFlags: List<Boolean> = List(5) { false }

    init {
        setEGLContextClientVersion(2)
        repeat(5) { i ->
            val x = (i - 2) * 0.75f
            val die = DieBody(position = Vec3(x, DieBody.HALF_SIZE, 0f))
            die.atRest = true
            world.dice.add(die)
        }
        renderer = DiceRenderer(world, onAllSettled = { notifySettled() })
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setOnSettledListener(listener: (List<Int>) -> Unit) {
        onSettledCallback = listener
    }

    /**
     * Recolours the dice. The renderer regenerates its texture atlas on the GL thread the next
     * frame, so this is safe to call from the UI thread at any point.
     */
    fun setDiceColor(color: Int) {
        renderer.diceColor = color
    }

    /**
     * Kicks off a physical throw already rigged to land exactly on [targetValues] — no
     * post-landing correction needed, since each die's rotation is computed up front to
     * arrive precisely on its target face.
     */
    fun rollTo(targetValues: List<Int>, held: List<Boolean>) {
        pendingTargets = targetValues
        heldFlags = held
        val random = Random.Default
        world.dice.forEachIndexed { i, die ->
            if (held.getOrNull(i) == true) {
                die.snapToUpright(targetValues[i])
                die.atRest = true
                return@forEachIndexed
            }
            die.position = Vec3((i - 2) * 0.55f, 2.2f + random.nextFloat() * 0.4f, -1.3f + (random.nextFloat() - 0.5f) * 0.3f)
            die.throwToward(
                targetValue = targetValues[i],
                direction = Vec3(
                    (random.nextFloat() - 0.5f) * 1f,
                    -0.35f,
                    1f
                ),
                speed = 5.5f + random.nextFloat() * 1.5f,
                random = random
            )
        }
    }

    private fun notifySettled() {
        mainHandler.post {
            onSettledCallback?.invoke(pendingTargets)
        }
    }
}
