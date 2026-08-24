package com.yahtzee.online.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders the invite link as a QR code for the lobby.
 *
 * Drawn here rather than with ZXing's Android helper so the colours are ours: a white block on a
 * black page, because a phone camera reads a QR by contrast and an inverted one — dark modules on
 * a dark background — is a code many scanners simply will not see.
 */
object QrCode {

    /** Quiet zone in modules. The spec asks for four; a code with none is often unreadable. */
    private const val MARGIN = 2

    fun render(content: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            // The room code is short, so the highest correction costs almost nothing and buys
            // tolerance for a smeared screen or an off-angle camera.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to MARGIN,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val row = IntArray(sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                row[x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
            bitmap.setPixels(row, 0, sizePx, 0, y, sizePx, 1)
        }
        bitmap
    }.getOrNull()
}
