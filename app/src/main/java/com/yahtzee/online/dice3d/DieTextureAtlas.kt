package com.yahtzee.online.dice3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min

/**
 * Builds the die face texture: a 3x2 atlas (one 512px cell per face value 1..6), used by
 * [DiceShader] as the per-fragment albedo. The 3x2 layout — rather than 6x1 — keeps the atlas
 * at 1536x1024, comfortably inside the 2048 texture limit that some GLES2 devices enforce,
 * while giving each face four times the pixels of the old 256px cells so the pips stay
 * smooth instead of visibly stepped.
 *
 * Cells are drawn FULL-BLEED, with no rounded corners and no transparent margin: [CubeMesh]
 * carries genuine rounded geometry, and its bevel band samples this cell's outer edge. Anything
 * transparent out there would punch holes along every edge of the die.
 *
 * These are ordinary moulded dice. There was a glass material here too — a deep saturated core
 * brightening to a bleached rim, a streak of light scattering through the body, and pips built
 * as jewelled cavities with a bright lip and a specular catch. It looked striking in a single
 * vivid colour and wrong everywhere else: the core read as grime in the middle of the face, the
 * rim drained the colour out of the edges, and the pips came out as grey blobs wearing haloes
 * rather than dots. What is left is what a die actually looks like — one colour through to the
 * edge, and pips printed flat on the surface.
 *
 * Layers, back to front:
 *   1. A near-uniform body, lifting slightly at the rim.
 *   2. A faint edge band at the cell border, which the bevel geometry maps onto.
 *   3. Per pip: one flat filled circle.
 */
object DieTextureAtlas {

    const val DEFAULT_COLOR = 0xFF3D7FFF.toInt()

    /** Pips as printed on a real die: a shade larger than a moulded dimple would be. */
    private const val PIP_SCALE = 1.18f

    private val pipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        1 to listOf(0.5f to 0.5f),
        2 to listOf(0.28f to 0.28f, 0.72f to 0.72f),
        3 to listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
        4 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f),
        5 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.5f to 0.5f, 0.28f to 0.72f, 0.72f to 0.72f),
        6 to listOf(0.28f to 0.22f, 0.72f to 0.22f, 0.28f to 0.5f, 0.72f to 0.5f, 0.28f to 0.78f, 0.72f to 0.78f)
    )

    fun build(
        baseColor: Int = DEFAULT_COLOR,
        darkPips: Boolean = true,
        cellSize: Int = CubeMesh.CELL_PX
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            cellSize * CubeMesh.ATLAS_COLS,
            cellSize * CubeMesh.ATLAS_ROWS,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val palette = Palette.from(baseColor)

        for (value in 1..6) {
            val cell = value - 1
            val left = (cell % CubeMesh.ATLAS_COLS) * cellSize
            val top = (cell / CubeMesh.ATLAS_COLS) * cellSize
            drawFace(canvas, left, top, cellSize, value, palette, darkPips)
        }
        return bitmap
    }

    /**
     * A single face rendered on its own, for flat UI outside the 3D view — the roll-off row,
     * for instance. Uses the same drawing as the atlas, so a die shown in a list matches the
     * ones on the table, in whatever colour that player chose.
     */
    fun face(baseColor: Int, value: Int, darkPips: Boolean = true, cellSize: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(cellSize, cellSize, Bitmap.Config.ARGB_8888)
        drawFace(Canvas(bitmap), 0, 0, cellSize, value.coerceIn(1, 6), Palette.from(baseColor), darkPips)
        return bitmap
    }

    /**
     * Tones derived from one base colour, so any hue yields a coherent die.
     *
     * The edge keeps its hue and lifts only slightly in brightness. Draining colour toward the
     * rim is how glass reads — light escaping where the block is thinnest — and doing it to
     * moulded plastic simply bleaches the edges.
     */
    private class Palette(val base: Int, val rim: Int, val edge: Int) {
        companion object {
            fun from(baseColor: Int): Palette {
                val hsv = FloatArray(3)
                Color.colorToHSV(baseColor, hsv)
                return Palette(
                    base = baseColor,
                    rim = Color.HSVToColor(
                        floatArrayOf(hsv[0], hsv[1] * 0.94f, min(1f, hsv[2] + 0.05f))
                    ),
                    edge = Color.HSVToColor(
                        floatArrayOf(hsv[0], hsv[1] * 0.90f, min(1f, hsv[2] + 0.08f))
                    )
                )
            }
        }
    }

    private fun drawFace(
        canvas: Canvas,
        left: Int,
        top: Int,
        size: Int,
        value: Int,
        palette: Palette,
        darkPips: Boolean
    ) {
        val rect = RectF(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat())
        val cx = left + size / 2f
        val cy = top + size / 2f

        // 1. Body — one colour, lifting only at the very edge.
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, size * 0.74f,
                intArrayOf(palette.base, palette.base, palette.rim),
                floatArrayOf(0f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, bodyPaint)

        // 2. Edge band — the outermost texels, which the rounded bevel geometry samples. Drawn
        //    as a stroke sitting exactly on the cell border, so the rounded edge catches a
        //    little more light than the flat of the face rather than lighting up.
        val bandWidth = size * 0.11f
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = bandWidth
            shader = LinearGradient(
                left.toFloat(), top.toFloat(), left + size.toFloat(), top + size.toFloat(),
                intArrayOf(palette.edge, palette.rim, palette.base),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(
            RectF(rect).apply { inset(bandWidth / 2f, bandWidth / 2f) },
            bandPaint
        )

        // 3. Pips.
        val pipRadius = size * 0.086f * PIP_SCALE
        val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkPips) Color.rgb(18, 18, 20) else Color.rgb(250, 250, 250)
        }
        pipLayouts[value]?.forEach { (fx, fy) ->
            canvas.drawCircle(left + fx * size, top + fy * size, pipRadius, pipPaint)
        }
    }
}
