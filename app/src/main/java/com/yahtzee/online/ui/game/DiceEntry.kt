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
 * A keypad, not steppers. Tapping a die to cycle it through one to six was three taps a die on
 * average — fifteen a turn, near two hundred over a game — for the sake of avoiding a keyboard.
 * Six face buttons cost one tap a die instead, because the faces are named directly rather than
 * counted up to.
 *
 * What makes that work is that a hand of dice has no order. Every category scores off how many of
 * each face are showing, so 3-3-5-2-6 and 2-3-3-5-6 are the same hand — which means the keypad can
 * simply fill the next slot along and a player can read their dice out in whatever order they lie
 * on the table. Five taps and the hand is in.
 *
 * The slot being filled is marked, and tapping any slot moves there, so fixing one die that was
 * read wrong is two taps rather than a lap through six faces.
 */
object DiceEntry {

    /** Where the row starts each turn. */
    private val START = List(5) { 1 }

    private const val DICE = 5
    private const val FACES = 6

    /**
     * Builds the entry into [slotRow] and [keypadRow], reporting changes through [onChange].
     *
     * The buttons are held for the life of the build so a tap can repaint one slot rather than
     * rebuild the row — a rebuilt row loses which slot was being filled, and the cursor is the
     * whole reason this is quicker than what it replaced.
     */
    fun build(
        context: Context,
        slotRow: LinearLayout,
        keypadRow: LinearLayout,
        values: MutableList<Int>,
        onChange: () -> Unit
    ) {
        slotRow.removeAllViews()
        keypadRow.removeAllViews()
        val density = context.resources.displayMetrics.density
        val accent = AccentColor.resolve(context)

        val slots = ArrayList<AppCompatButton>(DICE)
        var cursor = 0

        fun paint() {
            slots.forEachIndexed { index, button ->
                button.text = values[index].toString()
                // The marked slot borrows the "held" look rather than inventing a second kind of
                // highlight, so a die about to be set reads like a die being kept.
                styleHoldChip(button, held = index == cursor, diceColor = accent)
            }
        }

        values.indices.forEach { index ->
            val die = AppCompatButton(context).apply {
                minWidth = 0
                minimumWidth = 0
                gravity = Gravity.CENTER
                setOnClickListener {
                    cursor = index
                    paint()
                }
            }
            slots.add(die)
            slotRow.addView(
                die,
                LinearLayout.LayoutParams(0, (52 * density).toInt(), 1f).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
            )
        }

        (1..FACES).forEach { face ->
            val key = AppCompatButton(context).apply {
                text = face.toString()
                minWidth = 0
                minimumWidth = 0
                gravity = Gravity.CENTER
                styleHoldChip(this, held = false, diceColor = accent)
                setOnClickListener {
                    values[cursor] = face
                    // On to the next die, wrapping, so five taps enters a whole hand and a sixth
                    // starts it again rather than doing nothing.
                    cursor = (cursor + 1) % DICE
                    paint()
                    onChange()
                }
            }
            keypadRow.addView(
                key,
                LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
            )
        }

        paint()
    }

    fun freshValues(): MutableList<Int> = START.toMutableList()
}
