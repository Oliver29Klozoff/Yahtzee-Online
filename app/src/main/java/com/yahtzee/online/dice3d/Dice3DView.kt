package com.yahtzee.online.dice3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.TableLogoStore
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
    private var motionScale: Float = 1f

    init {
        setEGLContextClientVersion(2)
        populate(DEFAULT_DIE_COUNT)
        renderer = DiceRenderer(
            world,
            context.applicationContext,
            onAllSettled = { notifySettled() }
        )
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
     * Called when the player flings the dice across the table.
     *
     * The gesture only asks for a roll — what the dice come up as is the game's business, not the
     * physics'. What the fling does own is how the throw looks: where it comes from and how hard,
     * carried into the next [rollTo] so the dice go where they were thrown.
     */
    fun setOnThrowListener(listener: (() -> Unit)?) {
        onThrowCallback = listener
    }

    private var onThrowCallback: (() -> Unit)? = null

    /** Direction of the last fling in table space, and how hard, or null if none is pending. */
    private var flingAngle: Float? = null
    private var flingStrength: Float = 1f

    private val flingDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                down: MotionEvent?,
                up: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val throwListener = onThrowCallback ?: return false
                val speed = hypot(velocityX, velocityY)
                if (speed < MIN_FLING_SPEED) return false

                // The dice are thrown from where the finger came FROM, so they travel with the
                // gesture. Screen y grows downward and the table's z grows toward the viewer, so
                // the seat sits opposite the fling: negate both to point back at its origin.
                flingAngle = atan2(-velocityY, -velocityX)
                flingStrength = (speed / REFERENCE_FLING_SPEED).coerceIn(0.75f, 1.6f)
                throwListener()
                return true
            }
        }
    )

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only claims the gesture when someone is listening for a throw, so the view stays inert
        // on screens where the dice are something to look at rather than something to roll.
        if (onThrowCallback == null) return super.onTouchEvent(event)
        return flingDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * Recolours the dice. The renderer regenerates its texture atlas on the GL thread the next
     * frame, so this is safe to call from the UI thread at any point.
     */
    fun setDiceColor(color: Int) {
        renderer.diceColor = color
    }

    /** Table felt colour behind the dice. */
    fun setTableColor(color: Int) {
        renderer.tableColor = color
    }

    /** What is printed on the felt: the app artwork, the player's own picture, or nothing. */
    fun setTableLogo(mode: TableLogoStore.Mode) {
        renderer.tableLogo = mode
    }

    /**
     * How much of the throw animation to play, as a fraction of full. Zero snaps to the result.
     * Speed is divided by it so a quicker roll still crosses the table in the shorter time,
     * rather than the dice being cut off mid-flight.
     */
    fun setMotionScale(scale: Float) {
        motionScale = scale.coerceIn(0f, 1f)
    }

    /**
     * Framing for this view, as a multiple of the default camera distance. Below 1 moves the
     * camera closer so the dice appear larger; a game leaves this at 1.
     */
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

        // A throw the player made overrides where their seat is: the dice should leave the hand
        // that threw them. Consumed here so it applies to exactly one roll — the next roll, from
        // a button or from a bot, goes back to the seat.
        val thrownFrom = flingAngle
        val thrownStrength = flingStrength
        flingAngle = null
        flingStrength = 1f
        val seatAngle = thrownFrom ?: seatAngleRadians

        // Motion off: place the result straight away. Still reports settled, so anything waiting
        // on the roll — the landing sound, the next bot step — carries on as normal.
        if (motionScale <= 0f) {
            world.dice.forEachIndexed { i, die ->
                die.snapToUpright(targetValues[i])
                die.atRest = true
            }
            notifySettled()
            return
        }

        // Unit vector pointing at the thrower's seat, and the direction across it, used to fan
        // the dice out along the near edge so they trail in rather than arriving as a rank.
        val seatX = cos(seatAngle)
        val seatZ = sin(seatAngle)
        val acrossX = -seatZ
        val acrossZ = seatX

        // Spawn just INSIDE the walls: the world clamps every die to the wall bounds on each
        // step, so one started beyond them would snap to the edge on frame one rather than
        // flying in. Taken from the table rather than fixed, so resizing the surface moves the
        // throw line with it instead of leaving the dice appearing mid-table.
        val spawnX = world.tableHalfWidth - DieBody.HALF_SIZE - 0.1f
        val spawnZ = world.tableHalfDepth - DieBody.HALF_SIZE - 0.1f

        val count = world.dice.size
        world.dice.forEachIndexed { i, die ->
            if (held.getOrNull(i) == true) {
                die.snapToUpright(targetValues[i])
                die.atRest = true
                return@forEachIndexed
            }
            // Fanned wider than the dice are thick, so they enter the table already separated
            // rather than as a column that has to untangle itself on the way across.
            val spread = (i - (count - 1) / 2f) * 0.24f + (random.nextFloat() - 0.5f) * 0.12f

            die.position = Vec3(
                (seatX * spawnX * 0.94f + acrossX * spread).coerceIn(-spawnX, spawnX),
                1.85f + random.nextFloat() * 0.5f,
                (seatZ * spawnZ * 0.94f + acrossZ * spread).coerceIn(-spawnZ, spawnZ)
            )
            val scatter = (random.nextFloat() - 0.5f) * 0.4f
            die.throwToward(
                targetValue = targetValues[i],
                direction = Vec3(
                    -seatX + acrossX * scatter,
                    -0.3f,
                    -seatZ + acrossZ * scatter
                ),
                // A little quicker than before so a throw still crosses the longer table.
                // A harder fling carries the dice further across the table.
                speed = (5.6f + random.nextFloat() * 1.9f) * thrownStrength / motionScale,
                random = random,
                durationScale = motionScale
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

        /** Below this a fling is a stray swipe rather than a throw. */
        const val MIN_FLING_SPEED = 900f

        /** The fling speed treated as a normal throw; harder or softer scales around it. */
        const val REFERENCE_FLING_SPEED = 4500f
    }
}
