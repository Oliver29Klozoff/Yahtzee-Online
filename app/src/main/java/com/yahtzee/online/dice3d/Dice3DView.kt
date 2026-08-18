package com.yahtzee.online.dice3d

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.node.PlaneNode
import kotlin.random.Random

class Dice3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SceneView(context, attrs) {

    private val world = DicePhysicsWorld()
    private val visuals = mutableListOf<DieVisual>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onSettledCallback: ((List<Int>) -> Unit)? = null
    private var pendingTargets: List<Int> = List(5) { 1 }
    private var heldFlags: List<Boolean> = List(5) { false }
    private var lastFrameNanos = 0L
    private var settledNotified = true

    private var camHeight = 10.5f
    private var camDist = 12.5f
    private var camFovDegrees = 55.0

    init {
        applyCameraSettings()

        mainLightNode?.apply {
            intensity = 150_000f
            lightDirection = Float3(-0.4f, -1f, -0.5f)
        }

        buildTable()

        repeat(5) { i ->
            val x = (i - 2) * 0.9f
            val die = DieBody(position = Vec3(x, DieBody.HALF_SIZE, 0f))
            die.atRest = true
            world.dice.add(die)
            val visual = DieVisual(engine, materialLoader)
            visual.applyTransform(die.position, die.orientation)
            addChildNode(visual.node)
            visuals.add(visual)
        }

        onFrame = { frameTimeNanos -> stepAndRender(frameTimeNanos) }
    }

    private fun buildTable() {
        val uv1 = dev.romainguy.kotlin.math.Float2(1f, 1f)
        val feltMaterial = materialLoader.createColorInstance(Color.rgb(18, 22, 28), 0f, 0.85f, 0.02f)
        val rimMaterial = materialLoader.createColorInstance(Color.rgb(0x3d, 0x7f, 0xff), 0f, 0.35f, 0.15f)

        val w = world.tableHalfWidth
        val d = world.tableHalfDepth
        val rimHeight = 0.35f

        val felt = PlaneNode(
            engine = engine,
            size = Float3(w * 2f, 0f, d * 2f),
            center = Float3(0f, 0f, 0f),
            normal = Float3(0f, 1f, 0f),
            uvScale = uv1,
            materialInstance = feltMaterial
        )
        addChildNode(felt)

        // Vertical rim walls: a plane whose normal points sideways (toward the table center)
        // rather than up, built directly at x/z=const facing inward.
        fun sideWall(x: Float, length: Float, facingPositiveX: Boolean) {
            val wall = PlaneNode(
                engine = engine,
                size = Float3(length, 0f, rimHeight),
                center = Float3(0f, 0f, 0f),
                normal = Float3(0f, 1f, 0f),
                uvScale = uv1,
                materialInstance = rimMaterial
            )
            wall.rotation = Float3(0f, 0f, if (facingPositiveX) 90f else -90f)
            wall.position = Float3(x, rimHeight / 2f, 0f)
            addChildNode(wall)
        }

        fun endWall(z: Float, length: Float, facingPositiveZ: Boolean) {
            val wall = PlaneNode(
                engine = engine,
                size = Float3(length, 0f, rimHeight),
                center = Float3(0f, 0f, 0f),
                normal = Float3(0f, 1f, 0f),
                uvScale = uv1,
                materialInstance = rimMaterial
            )
            wall.rotation = Float3(if (facingPositiveZ) -90f else 90f, 0f, 0f)
            wall.position = Float3(0f, rimHeight / 2f, z)
            addChildNode(wall)
        }

        sideWall(-w, d * 2f, facingPositiveX = true)
        sideWall(w, d * 2f, facingPositiveX = false)
        endWall(-d, w * 2f, facingPositiveZ = true)
        endWall(d, w * 2f, facingPositiveZ = false)
    }

    private fun applyCameraSettings() {
        cameraNode.setProjection(
            fovInDegrees = camFovDegrees,
            near = 0.1f,
            far = 50f,
            direction = com.google.android.filament.Camera.Fov.VERTICAL
        )
        cameraNode.position = Float3(0f, camHeight, camDist)
        cameraNode.lookAt(Float3(0f, 0f, 0f), Float3(0f, 1f, 0f), Float3(0f, 0f, 0f))
    }

    /** Debug helper: adjust camera height live to compare angles. */
    fun setCameraHeight(height: Float) {
        camHeight = height.coerceIn(1f, 20f)
    }

    fun getCameraHeight(): Float = camHeight

    /** Debug helper: adjust camera distance (pan/zoom back-forth) live. */
    fun setCameraDist(distance: Float) {
        camDist = distance.coerceIn(2f, 25f)
    }

    fun getCameraDist(): Float = camDist

    /** Debug helper: adjust field of view live. */
    fun setCameraFov(fovDegrees: Double) {
        camFovDegrees = fovDegrees.coerceIn(20.0, 90.0)
    }

    fun getCameraFov(): Double = camFovDegrees

    fun setOnSettledListener(listener: (List<Int>) -> Unit) {
        onSettledCallback = listener
    }

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
            die.position = Vec3((i - 2) * 0.8f, 4.2f + random.nextFloat() * 0.6f, (random.nextFloat() - 0.5f) * 0.5f)
            die.throwWith(
                direction = Vec3(
                    (random.nextFloat() - 0.5f) * 1.2f,
                    -0.6f,
                    0.8f + random.nextFloat() * 0.4f
                ),
                speed = 6.5f + random.nextFloat() * 2f,
                spin = 16f + random.nextFloat() * 8f,
                random = random
            )
        }
        settledNotified = false
    }

    private fun stepAndRender(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(1f / 30f)
        lastFrameNanos = frameTimeNanos

        applyCameraSettings()
        world.step(dt)

        world.dice.forEachIndexed { i, die ->
            visuals[i].applyTransform(die.position, die.orientation)
        }

        val allSettled = world.allAtRest()
        if (allSettled && !settledNotified) {
            settledNotified = true
            notifySettled()
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
