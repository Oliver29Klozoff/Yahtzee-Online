package com.yahtzee.online.ui.game

import android.view.View

/**
 * Keeps the game screen usable when the phone is set to a large system font.
 *
 * Everything on that screen except the table scales with the font: the turn line, the keep/reroll
 * tiles, the roll button, the rolls-left line, the scorecard's own header and its rows. The
 * scorecard is the one element with a weight, so it absorbs every one of those increases — at a
 * 1.5x font it was squeezed down to two and a half visible rows with the last one cut off by the
 * bottom of the screen, on a screen whose whole purpose is choosing a row.
 *
 * The table is the only thing that can afford to give the space back. It has no text in it, it
 * has no fixed content to lose, and dice a little smaller are still perfectly readable — whereas
 * a scorecard you cannot reach is not a scorecard. So its height is divided by the font scale,
 * which hands roughly the space the enlarged text took straight back to the rows.
 *
 * There is no resource qualifier for font scale, so this cannot be expressed in the layout.
 */
object GameLayout {

    /**
     * Whether the scorecard should be shown as two columns rather than one.
     *
     * One column of seventeen rows does not fit the height either orientation has left over, at
     * any size the numbers can still be read at. Two columns of eight and nine do — so long as a
     * row's cells and its category name both fit half the width available.
     *
     * That is where the orientation matters, and it is why this is not one number. Every card
     * adds a cell to every row. Half a portrait screen leaves about 61dp for the name once three
     * cards have taken their cells, and "Small Straight" wants around 82 — so portrait splits for
     * the classic single card only. Landscape has half again as much width and leaves about
     * 104dp, so three cards fit there comfortably. Five and six fit neither, and keep the full
     * width and the scroll they have always had.
     */
    fun splitsScorecard(cardCount: Int, landscape: Boolean): Boolean =
        if (landscape) cardCount <= 3 else cardCount <= 1

    /** True when the screen is on its side, which is what decides how much width a column has. */
    fun isLandscape(view: View): Boolean =
        view.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** The height the table has in the layout, and would keep at a normal font. */
    private const val BASE_HEIGHT_DP = 215f

    /** Never shrink past this: below it the throw stops reading as dice crossing a table. */
    private const val MIN_HEIGHT_DP = 165f

    /** Ignore the rounding error around 1.0 rather than resizing for nothing. */
    private const val THRESHOLD = 1.05f

    fun fitTableToFontScale(table: View) {
        val resources = table.resources

        // Portrait only. On its side the table is a weighted child that already takes whatever
        // height is left over after the controls, so it shrinks by itself — pinning it to a
        // fixed number here would undo that and hand the layout the very problem this exists to
        // solve, in the orientation with least height to spare.
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return
        }

        val fontScale = resources.configuration.fontScale
        if (fontScale <= THRESHOLD) return

        val density = resources.displayMetrics.density
        val target = (BASE_HEIGHT_DP / fontScale).coerceAtLeast(MIN_HEIGHT_DP)
        table.layoutParams = table.layoutParams.apply { height = (target * density).toInt() }
    }
}
