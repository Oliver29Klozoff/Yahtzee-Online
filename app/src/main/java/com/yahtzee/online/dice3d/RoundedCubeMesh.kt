package com.yahtzee.online.dice3d

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.geometries.Geometry
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a beveled (rounded-look) cube: each of the 6 faces is inset slightly and
 * surrounded by angled bevel strips at the 12 edges, approximating rounded corners without
 * true curved geometry. Returns one submesh (vertex/index range) per face value 1-6, in
 * face-value order, so each can get its own pip-textured material.
 */
object RoundedCubeMesh {

    data class Submesh(val vertexStart: Int, val indexStart: Int, val indexCount: Int, val faceValue: Int)

    class Built(
        val vertices: List<Geometry.Vertex>,
        val indices: List<Int>,
        val submeshes: List<Submesh>
    )

    private const val HALF = 0.5f
    private const val BEVEL = 0.09f

    // face value -> (normal, right, up) defining the face's local frame
    private val faceFrames = listOf(
        Triple(1, Float3(0f, 1f, 0f), Float3(1f, 0f, 0f)) to Float3(0f, 0f, -1f),
        Triple(6, Float3(0f, -1f, 0f), Float3(1f, 0f, 0f)) to Float3(0f, 0f, 1f),
        Triple(2, Float3(1f, 0f, 0f), Float3(0f, 0f, -1f)) to Float3(0f, 1f, 0f),
        Triple(5, Float3(-1f, 0f, 0f), Float3(0f, 0f, 1f)) to Float3(0f, 1f, 0f),
        Triple(3, Float3(0f, 0f, 1f), Float3(1f, 0f, 0f)) to Float3(0f, 1f, 0f),
        Triple(4, Float3(0f, 0f, -1f), Float3(-1f, 0f, 0f)) to Float3(0f, 1f, 0f)
    )

    fun build(): Built {
        val vertices = mutableListOf<Geometry.Vertex>()
        val indices = mutableListOf<Int>()
        val submeshes = mutableListOf<Submesh>()
        val white = Float4(1f, 1f, 1f, 1f)

        val inset = HALF - BEVEL

        for ((frame, up) in faceFrames) {
            val (value, normal, right) = frame
            val indexStart = indices.size
            val vertexStart = vertices.size

            val center = normal * HALF
            val corners = listOf(
                center + right * -inset + up * -inset,
                center + right * inset + up * -inset,
                center + right * inset + up * inset,
                center + right * -inset + up * inset
            )
            val uvs = listOf(Float2(0f, 1f), Float2(1f, 1f), Float2(1f, 0f), Float2(0f, 0f))

            corners.forEachIndexed { i, pos ->
                vertices.add(Geometry.Vertex(pos, normal, uvs[i], white))
            }
            indices.addAll(listOf(vertexStart, vertexStart + 1, vertexStart + 2, vertexStart, vertexStart + 2, vertexStart + 3))

            submeshes.add(Submesh(vertexStart, indexStart, indices.size - indexStart, value))
        }

        val bevelIndexStart = indices.size
        val bevelVertexStart = vertices.size
        addBevels(vertices, indices)
        submeshes.add(Submesh(bevelVertexStart, bevelIndexStart, indices.size - bevelIndexStart, BEVEL_SUBMESH_VALUE))

        return Built(vertices, indices, submeshes)
    }

    const val BEVEL_SUBMESH_VALUE = 0

    private fun addBevels(vertices: MutableList<Geometry.Vertex>, indices: MutableList<Int>) {
        val bevelColor = Float4(0.31f, 0.53f, 0.92f, 1f)
        val inset = HALF - BEVEL

        val axes = listOf(
            Triple(Float3(1f, 0f, 0f), Float3(0f, 1f, 0f), Float3(0f, 0f, 1f)),
            Triple(Float3(0f, 1f, 0f), Float3(0f, 0f, 1f), Float3(1f, 0f, 0f)),
            Triple(Float3(0f, 0f, 1f), Float3(1f, 0f, 0f), Float3(0f, 1f, 0f))
        )

        for (signA in intArrayOf(-1, 1)) {
            for (signB in intArrayOf(-1, 1)) {
                for ((primary, axisB, axisC) in axes) {
                    addEdgeStrip(vertices, indices, primary, axisB * signA.toFloat(), axisC * signB.toFloat(), inset, bevelColor)
                }
            }
        }
    }

    private fun addEdgeStrip(
        vertices: MutableList<Geometry.Vertex>,
        indices: MutableList<Int>,
        primaryAxis: Float3,
        dirB: Float3,
        dirC: Float3,
        inset: Float,
        color: Float4
    ) {
        val cornerBase = dirB * inset + dirC * inset
        val steps = 3
        val startVertex = vertices.size
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val angle = t * (Math.PI.toFloat() / 2f)
            val n = (dirB * cos(angle) + dirC * sin(angle)).normalized2()
            val posOnBevel = cornerBase + n * BEVEL
            for (sign in intArrayOf(-1, 1)) {
                val pos = primaryAxis * (inset * sign.toFloat()) + posOnBevel
                vertices.add(Geometry.Vertex(pos, n, Float2(t, if (sign < 0) 0f else 1f), color))
            }
        }
        for (i in 0 until steps) {
            val a = startVertex + i * 2
            val b = a + 1
            val c = a + 2
            val d = a + 3
            indices.addAll(listOf(a, c, b, b, c, d))
        }
    }

    private fun Float3.normalized2(): Float3 {
        val len = kotlin.math.sqrt(x * x + y * y + z * z)
        return if (len < 1e-6f) this else Float3(x / len, y / len, z / len)
    }
}
