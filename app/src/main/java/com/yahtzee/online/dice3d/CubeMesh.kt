package com.yahtzee.online.dice3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rounded ("chamfered") die body: six flat faces carrying the pip atlas, joined by genuinely
 * curved bevels along every edge and corner.
 *
 * The bevel is the difference between glass and a printed box. A hard 90° edge gives each face
 * exactly one normal, so the specular highlight is near-flat across the face and then snaps
 * discontinuously at the edge; no amount of painted-on highlight can fix that, because a
 * texture is fixed in UV space and cannot track the light as the die tumbles. Sweeping the
 * normal around a real bevel arc is what produces the bright curved edge streaks that dominate
 * the cover artwork.
 *
 * Construction: each face is sampled on a non-uniform grid, dense near the rim, and every point
 * is projected onto a box of half-extent [CORE_FRACTION] swept by a sphere of radius `bevel` —
 *
 *     position = clamp(p) + bevel * normalize(p - clamp(p))
 *
 * That one formula yields flat faces, cylindrical edges and spherical corners in a single pass,
 * and hands back exact analytic normals for free.
 *
 * UVs come from the CLAMPED (core) coordinate rather than the surface point, so the flat region
 * maps across the whole atlas cell while the bevel band samples the cell's outer edge — where
 * [DieTextureAtlas] paints its brightest glass — instead of smearing the pips around the curve.
 *
 * Collision is unaffected: [DicePhysicsWorld] treats dice as boxes of [DieBody.HALF_SIZE], and
 * the rigged-landing system works on orientation quaternions, both independent of this mesh.
 *
 * Face order: +Y(1), -Y(6), +X(2), -X(5), +Z(3), -Z(4), matching DieBody.faceValueUp().
 */
class CubeMesh {

    val vertexBuffer: FloatBuffer
    val uvBuffer: FloatBuffer
    val normalBuffer: FloatBuffer
    val indexBuffer: ShortBuffer
    val indexCount: Int

    init {
        val h = DieBody.HALF_SIZE
        val bevel = h * BEVEL_FRACTION
        val core = h - bevel

        // Samples across one face axis. The flat middle needs almost nothing (it is planar with
        // linear UVs); the bevel gets an arc of points spaced by equal ANGLE, so the curve stays
        // evenly tessellated instead of bunching up at one end.
        val axis = buildList {
            for (k in ARC_STEPS downTo 0) add(-(core + bevel * tan(QUARTER_TURN * k / ARC_STEPS)))
            add(-core * 0.45f)
            add(0f)
            add(core * 0.45f)
            for (k in 0..ARC_STEPS) add(core + bevel * tan(QUARTER_TURN * k / ARC_STEPS))
        }
        val n = axis.size

        val positions = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Short>()

        // Each face: outward normal, then two tangents chosen so that uTan x vTan == normal.
        // Keeping that handedness consistent guarantees outward-facing (CCW) winding for the
        // generated quads and a non-mirrored pip layout.
        val faces = listOf(
            Face(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), 0),
            Face(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), 5),
            Face(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), 1),
            Face(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), 4),
            Face(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 2),
            Face(floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), 3)
        )

        val texW = (CELL_PX * ATLAS_COLS).toFloat()
        val texH = (CELL_PX * ATLAS_ROWS).toFloat()
        val cellW = 1f / ATLAS_COLS
        val cellH = 1f / ATLAS_ROWS
        // Half-texel inset stops bilinear filtering from bleeding in the neighbouring cell where
        // the bevel band lands exactly on a cell boundary.
        val insetU = 0.5f / texW
        val insetV = 0.5f / texH

        for (face in faces) {
            val base = positions.size / 3
            val col = face.cell % ATLAS_COLS
            val row = face.cell / ATLAS_COLS

            for (i in 0 until n) {
                val s = axis[i]
                for (j in 0 until n) {
                    val t = axis[j]

                    val px = face.normal[0] * h + face.uTan[0] * s + face.vTan[0] * t
                    val py = face.normal[1] * h + face.uTan[1] * s + face.vTan[1] * t
                    val pz = face.normal[2] * h + face.uTan[2] * s + face.vTan[2] * t

                    val ix = px.coerceIn(-core, core)
                    val iy = py.coerceIn(-core, core)
                    val iz = pz.coerceIn(-core, core)

                    var dx = px - ix
                    var dy = py - iy
                    var dz = pz - iz
                    val len = sqrt(dx * dx + dy * dy + dz * dz)
                    if (len > 1e-6f) {
                        dx /= len; dy /= len; dz /= len
                    } else {
                        dx = face.normal[0]; dy = face.normal[1]; dz = face.normal[2]
                    }

                    positions.add(ix + dx * bevel)
                    positions.add(iy + dy * bevel)
                    positions.add(iz + dz * bevel)
                    normals.add(dx); normals.add(dy); normals.add(dz)

                    val fu = (s.coerceIn(-core, core) + core) / (2f * core)
                    val fv = (t.coerceIn(-core, core) + core) / (2f * core)
                    uvs.add(col * cellW + insetU + fu * (cellW - 2f * insetU))
                    uvs.add(row * cellH + insetV + fv * (cellH - 2f * insetV))
                }
            }

            for (i in 0 until n - 1) {
                for (j in 0 until n - 1) {
                    val a = (base + i * n + j).toShort()
                    val b = (base + (i + 1) * n + j).toShort()
                    val c = (base + (i + 1) * n + (j + 1)).toShort()
                    val d = (base + i * n + (j + 1)).toShort()
                    indices.add(a); indices.add(b); indices.add(c)
                    indices.add(a); indices.add(c); indices.add(d)
                }
            }
        }

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

    private class Face(
        val normal: FloatArray,
        val uTan: FloatArray,
        val vTan: FloatArray,
        val cell: Int
    )

    private fun toFloatBuffer(data: List<Float>): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data.toFloatArray())
                position(0)
            }

    companion object {
        /** Atlas is 3x2 rather than 6x1 so cells can be 512px and stay under the 2048 limit. */
        const val ATLAS_COLS = 3
        const val ATLAS_ROWS = 2
        const val CELL_PX = 512

        /** Edge radius as a fraction of the half-size — the rounded "casino die" corner. */
        private const val BEVEL_FRACTION = 0.26f

        /** Segments across each bevel arc; more means a smoother curve at the silhouette. */
        private const val ARC_STEPS = 4
        private const val QUARTER_TURN = (PI / 4).toFloat()
    }
}
