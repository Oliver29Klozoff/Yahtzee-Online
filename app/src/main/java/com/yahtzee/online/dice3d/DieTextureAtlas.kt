package com.yahtzee.online.dice3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

object DieTextureAtlas {

    private val pipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        1 to listOf(0.5f to 0.5f),
        2 to listOf(0.28f to 0.28f, 0.72f to 0.72f),
        3 to listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
        4 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f),
        5 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.5f to 0.5f, 0.28f to 0.72f, 0.72f to 0.72f),
        6 to listOf(0.28f to 0.22f, 0.72f to 0.22f, 0.28f to 0.5f, 0.72f to 0.5f, 0.28f to 0.78f, 0.72f to 0.78f)
    )

    fun build(cellSize: Int = 256): Bitmap {
        val width = cellSize * 6
        val height = cellSize
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 210, 200)
            style = Paint.Style.STROKE
            strokeWidth = cellSize * 0.03f
        }
        val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }

        for (value in 1..6) {
            val left = (value - 1) * cellSize
            val rect = RectF(left + 4f, 4f, left + cellSize - 4f, cellSize - 4f)
            canvas.drawRoundRect(rect, cellSize * 0.12f, cellSize * 0.12f, facePaint)
            canvas.drawRoundRect(rect, cellSize * 0.12f, cellSize * 0.12f, borderPaint)

            val pipRadius = cellSize * 0.09f
            pipLayouts[value]?.forEach { (fx, fy) ->
                val cx = left + fx * cellSize
                val cy = fy * cellSize
                canvas.drawCircle(cx, cy, pipRadius, pipPaint)
            }
        }

        return bitmap
    }
}
