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
            world.dice.add(DieBody(position = Vec3(x, 3f, 0f)))
        }
        renderer = DiceRenderer(world, onAllSettled = { notifySettled() })
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setOnSettledListener(listener: (List<Int>) -> Unit) {
        onSettledCallback = listener
    }

    /** Kicks off a physical throw; once dice stop moving they're snapped to [targetValues]. */
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
            die.position = Vec3((i - 2) * 0.7f, 3.5f + random.nextFloat() * 0.6f, (random.nextFloat() - 0.5f) * 0.5f)
            die.throwWith(
                direction = Vec3(
                    (random.nextFloat() - 0.5f) * 0.6f,
                    -1f,
                    (random.nextFloat() - 0.5f) * 0.6f
                ),
                speed = 5.5f + random.nextFloat() * 1.5f,
                spin = 14f + random.nextFloat() * 6f,
                random = random
            )
        }
    }

    private fun notifySettled() {
        world.dice.forEachIndexed { i, die ->
            if (heldFlags.getOrNull(i) != true) {
                die.snapToUpright(pendingTargets[i])
            }
        }
        mainHandler.post {
            onSettledCallback?.invoke(pendingTargets)
        }
    }
}
