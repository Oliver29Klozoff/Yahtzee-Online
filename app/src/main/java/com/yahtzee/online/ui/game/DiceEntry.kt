package com.yahtzee.online.ui.game

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import com.yahtzee.online.game.AccentColor

/**
 * Five dice, typed in rather than thrown.
 *
 * What a scorepad room needs instead of a roll button: the player has real dice in front of them
 * and the app only has to be told what came up.
 *
 * Steppers rather than a keyboard. The value is always one to six, and a keyboard for a single
 * digit — five times a turn, thirteen turns — is the sort of thing that makes people go back to
 * paper. Tapping a die cycles it, which takes on average three taps and never needs a keyboard to
 * appear or be dismissed.
 */
object DiceEntry {

    /** Where the row starts each turn. Ones, so every die needs the same number of taps to reach. */
    private val START = List(5) { 1 }

    /**
     * Builds the row into [row] and reports every change through [onChange].
     *
     * Rebuilt rather than updated, because it is five buttons and the alternative is holding
     * references to them somewhere for the sake of nothing.
     */
    fun build(context: Context, row: LinearLayout, values: MutableList<Int>, onChange: () -> Unit) {
        row.removeAllViews()
        val density = context.resources.displayMetrics.density

        values.indices.forEach { index ->
            val die = AppCompatButton(context).apply {
                text = values[index].toString()
                minWidth = 0
                minimumWidth = 0
                gravity = Gravity.CENTER
                // The same tile the hold row uses, so a die you are typing in and a die you are
                // keeping look like the same object rather than two different controls.
                styleHoldChip(this, held = false, diceColor = AccentColor.resolve(context))
                setOnClickListener {
                    // Round rather than clamp: a die tapped past six is far more likely to be
                    // somebody overshooting a two than wanting a six for ever.
                    values[index] = if (values[index] >= 6) 1 else values[index] + 1
                    text = values[index].toString()
                    onChange()
                }
            }
            row.addView(
                die,
                LinearLayout.LayoutParams(
                    0,
                    (52 * density).toInt(),
                    1f
                ).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
            )
        }
    }

    fun freshValues(): MutableList<Int> = START.toMutableList()
}
