package com.yahtzee.online.dice3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import kotlin.math.cos
import kotlin.math.sin
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
        populate(DEFAULT_DIE_COUNT)
        renderer = DiceRenderer(world, onAllSettled = { notifySettled() })
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun populate(count: Int) {
        world.dice.clear()
        val offset = (count - 1) / 2f
        repeat(count) { i ->
            val die = DieBody(position = Vec3((i - offset) * 0.75f, DieBody.HALF_SIZE, 0f))
            die.atRest = true
            world.dice.add(die)
        }
    }

    /**
     * Changes how many dice this view shows — a single die for the roll-off, five for a game.
     *
     * The mutation is queued onto the GL thread because the renderer walks this same list every
     * frame; rebuilding it underneath a draw call would risk tearing through it mid-iteration.
     */
    fun setDieCount(count: Int) {
        val safe = count.coerceIn(1, 5)
        if (world.dice.size == safe) return
        queueEvent {
            populate(safe)
            pendingTargets = List(safe) { 1 }
            heldFlags = List(safe) { false }
        }
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
     * Framing for this view, as a multiple of the default camera distance. Below 1 moves the
     * camera closer so the dice appear larger; a game leaves this at 1.
     */
    /** Dark or pale pips; the atlas is regenerated on the GL thread next frame. */
    fun setDarkPips(dark: Boolean) {
        renderer.darkPips = dark
    }

    fun setCameraScale(scale: Float) {
        renderer.cameraScale = scale.coerceIn(0.4f, 2f)
    }

    /**
     * Kicks off a physical throw already rigged to land exactly on [targetValues] — no
     * post-landing correction needed, since each die's rotation is computed up front to
     * arrive precisely on its target face.
     */
    /**
     * Throws the dice toward the middle of the table from the seat at [seatAngleRadians].
     *
     * Zero is screen-right, and the angle sweeps toward screen-bottom — the camera looks down
     * −Z, so +X is right and +Z is bottom. That puts four players on the quarters (right,
     * bottom, left, top) and any other count on evenly spaced intermediate angles, so a throw
     * always arrives from where that player is sitting relative to you.
     *
     * throwToward derives its roll axis from the direction of travel, so the dice tumble like
     * wheels rolling along that heading without any extra work here.
     */
    fun rollTo(targetValues: List<Int>, held: List<Boolean>, seatAngleRadians: Float = 0f) {
        pendingTargets = targetValues
        heldFlags = held
        val random = Random.Default

        // Unit vector pointing at the thrower's seat, and the direction across it, used to fan
        // the dice out along the near edge so they trail in rather than arriving as a rank.
        val seatX = cos(seatAngleRadians)
        val seatZ = sin(seatAngleRadians)
        val acrossX = -seatZ
        val acrossZ = seatX

        val count = world.dice.size
        world.dice.forEachIndexed { i, die ->
            if (held.getOrNull(i) == true) {
                die.snapToUpright(targetValues[i])
                die.atRest = true
                return@forEachIndexed
            }
            val spread = (i - (count - 1) / 2f) * 0.17f + (random.nextFloat() - 0.5f) * 0.12f

            // Spawn just INSIDE the walls: the world clamps every die to the wall bounds on each
            // step, so one started beyond them would snap to the edge on frame one rather than
            // flying in. The table is wider than it is deep, hence the different radii.
            die.position = Vec3(
                (seatX * 1.5f + acrossX * spread).coerceIn(-1.6f, 1.6f),
                1.85f + random.nextFloat() * 0.5f,
                (seatZ * 0.92f + acrossZ * spread).coerceIn(-1.0f, 1.0f)
            )
            val scatter = (random.nextFloat() - 0.5f) * 0.4f
            die.throwToward(
                targetValue = targetValues[i],
                direction = Vec3(
                    -seatX + acrossX * scatter,
                    -0.3f,
                    -seatZ + acrossZ * scatter
                ),
                speed = 5.0f + random.nextFloat() * 1.7f,
                random = random
            )
        }
    }

    private fun notifySettled() {
        mainHandler.post {
            onSettledCallback?.invoke(pendingTargets)
        }
    }

    private companion object {
        const val DEFAULT_DIE_COUNT = 5
    }
}
