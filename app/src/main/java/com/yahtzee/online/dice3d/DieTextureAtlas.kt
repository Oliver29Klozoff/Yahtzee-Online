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
 * while giving each face four times the pixels of the old 256px cells so the pearl pips stay
 * smooth instead of visibly stepped.
 *
 * Cells are drawn FULL-BLEED, with no rounded corners and no transparent margin: [CubeMesh] now
 * carries genuine rounded geometry, and its bevel band samples this cell's outer edge. Anything
 * transparent out there would punch holes along every edge of the die.
 *
 * Layers, back to front:
 *   1. A radial body gradient, deepest at the centre and brightening toward the rim — light
 *      escaping where the glass block is thinnest, which is what conveys thickness.
 *   2. A bright edge band at the cell border, which the bevel geometry maps onto, so the
 *      rounded edges read as ignited.
 *   3. A soft diagonal streak, faking light scattering inside the material.
 *   4. Per pip: recess pocket, lit cavity rim, domed pearl body, specular pinpoint.
 *
 * Every glass tone derives from [baseColor], so the dice can be recoloured at runtime; the pips
 * stay neutral pearl at any hue, which is also what lets the shader separate them from the body
 * by saturation.
 */
object DieTextureAtlas {

    const val DEFAULT_COLOR = 0xFF3D7FFF.toInt()

    private val pipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        1 to listOf(0.5f to 0.5f),
        2 to listOf(0.28f to 0.28f, 0.72f to 0.72f),
        3 to listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
        4 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f),
        5 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.5f to 0.5f, 0.28f to 0.72f, 0.72f to 0.72f),
        6 to listOf(0.28f to 0.22f, 0.72f to 0.22f, 0.28f to 0.5f, 0.72f to 0.5f, 0.28f to 0.78f, 0.72f to 0.78f)
    )

    fun build(baseColor: Int = DEFAULT_COLOR, cellSize: Int = CubeMesh.CELL_PX): Bitmap {
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
            drawFace(canvas, left, top, cellSize, value, palette)
        }
        return bitmap
    }

    /** Glass tones derived from one base colour, so any hue yields a coherent material. */
    private class Palette(val deep: Int, val base: Int, val rim: Int, val edge: Int) {
        companion object {
            fun from(baseColor: Int): Palette {
                val hsv = FloatArray(3)
                Color.colorToHSV(baseColor, hsv)
                val deep = Color.HSVToColor(
                    floatArrayOf(hsv[0], min(1f, hsv[1] * 1.18f), hsv[2] * 0.32f)
                )
                val rim = Color.HSVToColor(
                    floatArrayOf(hsv[0], hsv[1] * 0.58f, min(1f, hsv[2] + 0.26f))
                )
                val edge = Color.HSVToColor(
                    floatArrayOf(hsv[0], hsv[1] * 0.22f, min(1f, hsv[2] + 0.45f))
                )
                return Palette(deep, baseColor, rim, edge)
            }
        }
    }

    private fun drawFace(canvas: Canvas, left: Int, top: Int, size: Int, value: Int, palette: Palette) {
        val rect = RectF(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat())
        val cx = left + size / 2f
        val cy = top + size / 2f

        // 1. Body — deep saturated core brightening toward the rim.
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, size * 0.74f,
                intArrayOf(palette.deep, palette.base, palette.rim),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, bodyPaint)

        // 2. Edge band — the outermost texels, which the rounded bevel geometry samples. Drawn
        //    as a stroke sitting exactly on the cell border so the bevel reads as ignited.
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

        // 3. Internal streak — scattered light travelling through the body.
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                left + size * 0.1f, top + size * 0.05f, left + size * 0.75f, top + size * 0.7f,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(58, 226, 240, 255),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0.26f, 0.42f, 0.6f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, streakPaint)

        // 4. Pips.
        val pipRadius = size * 0.086f
        pipLayouts[value]?.forEach { (fx, fy) ->
            drawPip(canvas, left + fx * size, top + fy * size, pipRadius)
        }
    }

    /** One pearl pip: recess pocket, lit glass rim, domed body, specular pinpoint. */
    private fun drawPip(canvas: Canvas, px: Float, py: Float, radius: Float) {
        val recessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px, py, radius * 1.62f,
                intArrayOf(Color.argb(150, 4, 10, 26), Color.argb(0, 4, 10, 26)),
                floatArrayOf(0.56f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(px, py, radius * 1.62f, recessPaint)

        // The cavity lip, brightest on the lower-right where its wall turns up toward the light.
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.15f
            shader = LinearGradient(
                px - radius, py - radius, px + radius, py + radius,
                intArrayOf(Color.argb(105, 190, 205, 230), Color.argb(235, 240, 246, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(px, py, radius * 1.1f, rimPaint)

        // Dome: highlight offset up-left, shading to a cool grey limb at the lower-right — the
        // off-centre gradient is what makes a flat disc read as a sphere.
        val domePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px - radius * 0.34f, py - radius * 0.36f, radius * 1.42f,
                intArrayOf(
                    Color.rgb(255, 255, 255),
                    Color.rgb(242, 246, 253),
                    Color.rgb(200, 212, 231),
                    Color.rgb(148, 164, 190)
                ),
                floatArrayOf(0f, 0.4f, 0.74f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(px, py, radius, domePaint)

        val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px - radius * 0.36f, py - radius * 0.4f, radius * 0.34f,
                intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(px - radius * 0.36f, py - radius * 0.4f, radius * 0.34f, specPaint)
    }
}
