package com.yahtzee.online.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * What is printed on the felt: the app artwork, a picture the player chose, or nothing.
 *
 * A chosen picture is copied into the app's own storage rather than kept as a content Uri. The
 * Uri handed back by the photo picker is a temporary grant that does not survive a reboot, and
 * the renderer needs to decode this on every surface creation for the rest of the install.
 */
object TableLogoStore {

    private const val PREFS = "table_logo"
    private const val KEY_MODE = "mode"
    private const val CUSTOM_FILE = "table_logo.png"

    /**
     * Stored square, matching how the felt maps it, and no larger than it can usefully be shown
     * — the artwork sits at a slant under rolling dice, so detail beyond this is invisible.
     */
    private const val MAX_EDGE = 512

    enum class Mode(val label: String) {
        ARTWORK("App artwork"),
        CUSTOM("Your picture"),
        NONE("None")
    }

    fun mode(context: Context): Mode {
        val stored = prefs(context).getString(KEY_MODE, Mode.ARTWORK.name)
        val mode = runCatching { Mode.valueOf(stored!!) }.getOrDefault(Mode.ARTWORK)
        // Falling back rather than showing blank felt: the file can go missing if storage is
        // cleared, and the setting would otherwise point at nothing with no way to tell.
        return if (mode == Mode.CUSTOM && !customFile(context).exists()) Mode.ARTWORK else mode
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun customFile(context: Context): File = File(context.filesDir, CUSTOM_FILE)

    fun hasCustom(context: Context): Boolean = customFile(context).exists()

    /**
     * Copies the picked image in, cropped square from the centre and scaled down.
     *
     * Cropped rather than letterboxed because the felt maps a square region: fitting a
     * rectangular photo into it would either stretch faces sideways or leave bars that, under
     * the additive blend, would show as a bright band across the table.
     *
     * Returns false if the image could not be read, leaving any previous choice untouched.
     */
    fun saveCustom(context: Context, uri: Uri): Boolean = runCatching {
        val source = context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return false

        val edge = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - edge) / 2,
            (source.height - edge) / 2,
            edge,
            edge
        )
        val scaled = if (edge > MAX_EDGE) {
            Bitmap.createScaledBitmap(cropped, MAX_EDGE, MAX_EDGE, true)
        } else {
            cropped
        }

        customFile(context).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        if (scaled !== cropped) scaled.recycle()
        if (cropped !== source) cropped.recycle()
        source.recycle()

        setMode(context, Mode.CUSTOM)
        true
    }.getOrDefault(false)

    fun clearCustom(context: Context) {
        customFile(context).delete()
        setMode(context, Mode.ARTWORK)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
