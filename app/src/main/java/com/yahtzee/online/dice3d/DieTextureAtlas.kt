package com.yahtzee.online.dice3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Builds one square bitmap per die face value (1-6), each with a white rounded face and pips. */
object DieTextureAtlas {

    private val pipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        1 to listOf(0.5f to 0.5f),
        2 to listOf(0.28f to 0.28f, 0.72f to 0.72f),
        3 to listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
        4 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f),
        5 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.5f to 0.5f, 0.28f to 0.72f, 0.72f to 0.72f),
        6 to listOf(0.28f to 0.22f, 0.72f to 0.22f, 0.28f to 0.5f, 0.72f to 0.5f, 0.28f to 0.78f, 0.72f to 0.78f)
    )

    fun buildFace(value: Int, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.rgb(255, 255, 255))

        val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20) }
        val pipRadius = size * 0.1f
        pipLayouts[value]?.forEach { (fx, fy) ->
            canvas.drawCircle(fx * size, fy * size, pipRadius, pipPaint)
        }

        return bitmap
    }
}
