package com.yahtzee.online.dice3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Builds a horizontal 6-cell texture atlas (one 256x256 cell per die face 1-6), used by
 * [DiceShader] as the per-fragment albedo. Each cell is a saturated cobalt-blue "thick glass"
 * face — a radial gradient holding a deep, saturated fill through the center and brightening
 * only right at the rounded edge (matching the shader's own edge-lit / center-absorbed depth
 * treatment rather than fighting it) — plus bright white pips that carry a soft dark inset
 * ring so they read as recessed into the glass rather than painted on top of it.
 */
object DieTextureAtlas {

    private val pipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        1 to listOf(0.5f to 0.5f),
        2 to listOf(0.28f to 0.28f, 0.72f to 0.72f),
        3 to listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
        4 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f),
        5 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.5f to 0.5f, 0.28f to 0.72f, 0.72f to 0.72f),
        6 to listOf(0.28f to 0.22f, 0.72f to 0.22f, 0.28f to 0.5f, 0.72f to 0.5f, 0.28f to 0.78f, 0.72f to 0.78f)
    )

    private const val BASE_COLOR = 0xFF3D7FFF.toInt()
    private const val DEEP_CORE_COLOR = 0xFF14306E.toInt()
    private const val EDGE_LIGHT_COLOR = 0xFF5788E8.toInt()

    fun build(cellSize: Int = 256): Bitmap {
        val width = cellSize * 6
        val height = cellSize
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val pipCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Solid, near-black cavity ring (not a translucent stroke) so the pip reads as a
        // genuine recess in the shader's luminance-driven cavity mask, not a faint outline.
        val pipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(6, 10, 22)
            style = Paint.Style.FILL
        }

        for (value in 1..6) {
            val left = (value - 1) * cellSize
            val cx = left + cellSize / 2f
            val cy = cellSize / 2f
            val radius = cellSize * 0.75f

            // Thick-glass fill: saturated deep-blue core holding through most of the face,
            // brightening only in a narrow band right at the rounded edge — light escaping
            // where the material is thinnest, not a glossy highlight painted on top. Both
            // stops stay solidly blue (no pale/gray edge) so side faces read as colored glass
            // rather than washing out toward white.
            val faceShader = RadialGradient(
                cx, cy, radius,
                intArrayOf(DEEP_CORE_COLOR, BASE_COLOR, EDGE_LIGHT_COLOR),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = faceShader }

            val rect = RectF(left + 4f, 4f, left + cellSize - 4f, cellSize - 4f)
            canvas.drawRoundRect(rect, cellSize * 0.16f, cellSize * 0.16f, facePaint)

            val pipRadius = cellSize * 0.09f
            val ringRadius = pipRadius * 1.55f
            pipLayouts[value]?.forEach { (fx, fy) ->
                val px = left + fx * cellSize
                val py = fy * cellSize
                canvas.drawCircle(px, py, ringRadius, pipRingPaint)
                canvas.drawCircle(px, py, pipRadius, pipCorePaint)
            }
        }

        return bitmap
    }
}
