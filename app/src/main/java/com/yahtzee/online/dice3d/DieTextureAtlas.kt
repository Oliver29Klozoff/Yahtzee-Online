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
 *   4. Per pip: bright cavity lip, dark domed body, specular pinpoint.
 *
 * Every glass tone derives from [baseColor], so the dice can be recoloured at runtime; the pips
 * stay neutral at any hue, which is also what lets the shader separate them from the body
 * by saturation.
 */
object DieTextureAtlas {

    const val DEFAULT_COLOR = 0xFF3D7FFF.toInt()

    /** Printed pips sit a little larger than moulded ones, as they do on a real die. */
    private const val FLAT_PIP_SCALE = 1.18f

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
        flatPips: Boolean = false,
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
            drawFace(canvas, left, top, cellSize, value, palette, darkPips, flatPips)
        }
        return bitmap
    }

    /**
     * A single face rendered on its own, for flat UI outside the 3D view — the roll-off row,
     * for instance. Uses the same drawing as the atlas, so a die shown in a list matches the
     * ones on the table, in whatever colour that player chose.
     */
    fun face(baseColor: Int, value: Int, darkPips: Boolean = true, flatPips: Boolean = false, cellSize: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(cellSize, cellSize, Bitmap.Config.ARGB_8888)
        drawFace(Canvas(bitmap), 0, 0, cellSize, value.coerceIn(1, 6), Palette.from(baseColor), darkPips, flatPips)
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
                // Keeps a good deal of its colour instead of washing out to near-white, so the
                // bevel band the geometry samples glows in the dice colour rather than glaring.
                val edge = Color.HSVToColor(
                    floatArrayOf(hsv[0], hsv[1] * 0.42f, min(1f, hsv[2] + 0.28f))
                )
                return Palette(deep, baseColor, rim, edge)
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
        darkPips: Boolean,
        flatPips: Boolean
    ) {
        val rect = RectF(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat())
        val cx = left + size / 2f
        val cy = top + size / 2f

        // 1. Body. Glass gets a deep saturated core brightening toward the rim, which is what
        //    conveys thickness. A solid die must not: that core reads as grime in the middle of
        //    the face, and on a white one it turns the whole die grey. Its face is near uniform,
        //    lifting only slightly at the very edge.
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = if (flatPips) {
                RadialGradient(
                    cx, cy, size * 0.74f,
                    intArrayOf(palette.base, palette.base, palette.rim),
                    floatArrayOf(0f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                RadialGradient(
                    cx, cy, size * 0.74f,
                    intArrayOf(palette.deep, palette.base, palette.rim),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
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
                    Color.argb(24, 226, 240, 255),
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
            drawPip(canvas, left + fx * size, top + fy * size, pipRadius, darkPips, flatPips)
        }
    }

    /**
     * One polished black pip: a bright glass lip around the cavity, a dark domed body, and a
     * specular pinpoint.
     *
     * Black needs the opposite treatment to white. A white pip separated itself from the blue
     * body by being brighter, so it only needed a soft recess behind it; a dark pip would
     * otherwise read as a flat hole punched in the face, so the definition has to come from a
     * bright lit lip around it and from a strong highlight on the dome. Both stay neutral in
     * hue, which is what keeps the shader classifying them as pips rather than glass — that
     * test is saturation-based, so black qualifies exactly as white did.
     */
    private fun drawPip(
        canvas: Canvas,
        px: Float,
        py: Float,
        radius: Float,
        dark: Boolean,
        flat: Boolean
    ) {
        // A printed pip, for a solid die. Everything below this makes a pip look like a jewelled
        // cavity in glass — a bright lip around it, a dome gradient, a specular catch — which is
        // right on a coloured glass die and wrong on an ordinary one, where it reads as a small
        // grey blob wearing a halo rather than a crisp black dot. An ordinary die's pips are
        // painted on: flat, solid, and a touch larger.
        if (flat) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.rgb(18, 18, 20) else Color.rgb(250, 250, 250)
            }
            canvas.drawCircle(px, py, radius * FLAT_PIP_SCALE, paint)
            return
        }

        // Cavity lip. A dark pip needs a bright lip to stop it reading as a hole punched in the
        // face; a pale pip already separates itself by being brighter than the body, so its lip
        // is a soft shadow that sets it into the surface instead.
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.2f
            shader = if (dark) {
                LinearGradient(
                    px - radius, py - radius, px + radius, py + radius,
                    intArrayOf(Color.argb(240, 236, 244, 255), Color.argb(120, 150, 178, 220)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    px - radius, py - radius, px + radius, py + radius,
                    intArrayOf(Color.argb(150, 6, 14, 32), Color.argb(60, 20, 36, 70)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        canvas.drawCircle(px, py, radius * 1.12f, rimPaint)

        // Dome: the highlight sits up-left and the limb falls away to the lower-right, which is
        // what makes a flat disc read as a sphere in either colour.
        val domeStops = if (dark) {
            intArrayOf(
                Color.rgb(92, 100, 114),
                Color.rgb(42, 47, 56),
                Color.rgb(16, 19, 24),
                Color.rgb(4, 5, 8)
            )
        } else {
            intArrayOf(
                Color.rgb(255, 255, 255),
                Color.rgb(242, 246, 253),
                Color.rgb(200, 212, 231),
                Color.rgb(148, 164, 190)
            )
        }
        val domePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px - radius * 0.34f, py - radius * 0.36f, radius * 1.5f,
                domeStops,
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(px, py, radius, domePaint)

        val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px - radius * 0.36f, py - radius * 0.4f, radius * 0.32f,
                intArrayOf(
                    Color.argb(if (dark) 215 else 240, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(px - radius * 0.36f, py - radius * 0.4f, radius * 0.32f, specPaint)
    }
}
