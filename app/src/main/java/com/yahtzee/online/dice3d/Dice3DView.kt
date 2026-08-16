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

    init {
        cameraNode.position = Float3(0f, 5.2f, 5.6f)
        cameraNode.lookAt(Float3(0f, 0f, 0f), Float3(0f, 1f, 0f), Float3(0f, 0f, 0f))

        mainLightNode?.apply {
            intensity = 130_000f
            lightDirection = Float3(-0.4f, -1f, -0.5f)
        }

        val tableMaterial = materialLoader.createColorInstance(Color.rgb(24, 82, 40), 0f, 0.9f, 0.02f)
        val table = PlaneNode(
            engine = engine,
            size = Float3(world.tableHalfWidth * 2f, 0f, world.tableHalfDepth * 2f),
            center = Float3(0f, 0f, 0f),
            normal = Float3(0f, 1f, 0f),
            uvScale = dev.romainguy.kotlin.math.Float2(1f, 1f),
            materialInstance = tableMaterial
        )
        addChildNode(table)

        repeat(5) { i ->
            val x = (i - 2) * 0.9f
            world.dice.add(DieBody(position = Vec3(x, 4f, 0f)))
            val visual = DieVisual(engine, materialLoader)
            visual.planeNodes.forEach { addChildNode(it) }
            visuals.add(visual)
        }

        onFrame = { frameTimeNanos -> stepAndRender(frameTimeNanos) }
    }

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
