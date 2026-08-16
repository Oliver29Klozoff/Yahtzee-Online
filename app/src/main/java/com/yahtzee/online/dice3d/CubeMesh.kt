package com.yahtzee.online.dice3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Unit cube (half-size 0.5) with per-face UVs mapped into a 6-cell texture atlas
 * (one cell per die face, laid out left-to-right for pip values 1..6).
 * Face order: +Y(1), -Y(6), +X(2), -X(5), +Z(3), -Z(4) matching DieBody.faceValueUp().
 */
class CubeMesh {

    val vertexBuffer: FloatBuffer
    val uvBuffer: FloatBuffer
    val normalBuffer: FloatBuffer
    val indexBuffer: ShortBuffer
    val indexCount: Int

    init {
        val h = DieBody.HALF_SIZE
        val cellW = 1f / 6f

        val positions = mutableListOf<Float>()
        val uvs = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        fun addFace(
            v0: FloatArray, v1: FloatArray, v2: FloatArray, v3: FloatArray,
            normal: FloatArray, cell: Int
        ) {
            val base = (positions.size / 3).toShort()
            for (v in listOf(v0, v1, v2, v3)) positions.addAll(v.toList())
            repeat(4) { normals.addAll(normal.toList()) }
            val u0 = cell * cellW
            val u1 = (cell + 1) * cellW
            uvs.addAll(listOf(u0, 1f, u1, 1f, u1, 0f, u0, 0f))
            indices.addAll(listOf(base, (base + 1).toShort(), (base + 2).toShort(), base, (base + 2).toShort(), (base + 3).toShort()))
        }

        // +Y face = value 1 (cell 0)
        addFace(
            floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h),
            floatArrayOf(0f, 1f, 0f), 0
        )
        // -Y face = value 6 (cell 5)
        addFace(
            floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h),
            floatArrayOf(0f, -1f, 0f), 5
        )
        // +X face = value 2 (cell 1)
        addFace(
            floatArrayOf(h, -h, h), floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h),
            floatArrayOf(1f, 0f, 0f), 1
        )
        // -X face = value 5 (cell 4)
        addFace(
            floatArrayOf(-h, -h, -h), floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h),
            floatArrayOf(-1f, 0f, 0f), 4
        )
        // +Z face = value 3 (cell 2)
        addFace(
            floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
            floatArrayOf(0f, 0f, 1f), 2
        )
        // -Z face = value 4 (cell 3)
        addFace(
            floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h),
            floatArrayOf(0f, 0f, -1f), 3
        )

        vertexBuffer = toFloatBuffer(positions)
        uvBuffer = toFloatBuffer(uvs)
        normalBuffer = toFloatBuffer(normals)
        indexCount = indices.size
        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(indices.toShortArray())
                position(0)
            }
    }

    private fun toFloatBuffer(data: List<Float>): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data.toFloatArray())
                position(0)
            }
}
