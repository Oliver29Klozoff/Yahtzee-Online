package com.yahtzee.online.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring

/**
 * The rules, for somebody who has never played.
 *
 * Written for a person holding the phone with no idea what a full house is, so it explains the
 * game before it explains the app: what a turn is, what the boxes mean, why you would ever take
 * a zero. The parts specific to this app come last, because they are no use until the game makes
 * sense.
 *
 * The scoring table is generated from [Category] rather than typed out. A box described here in a
 * way the game does not actually play would be worse than no help at all, and a hand-written
 * table is exactly the thing that goes quietly out of date — so the names come from the same
 * enum the scorecard is built from, and the fixed scores are read from [Scoring] itself.
 */
class HowToPlayActivity : ImmersiveActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_how_to_play)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val body = findViewById<LinearLayout>(R.id.howToBody)

        paragraph(body, getString(R.string.how_to_intro))

        heading(body, getString(R.string.how_to_turn_title))
        paragraph(body, getString(R.string.how_to_turn_body))

        heading(body, getString(R.string.how_to_box_title))
        paragraph(body, getString(R.string.how_to_box_body))

        heading(body, getString(R.string.how_to_upper_title))
        paragraph(body, getString(R.string.how_to_upper_body))
        Category.UPPER.forEach { row(body, it) }
        note(body, getString(R.string.how_to_upper_bonus, UPPER_TARGET, upperBonusPoints()))

        heading(body, getString(R.string.how_to_lower_title))
        paragraph(body, getString(R.string.how_to_lower_body))
        Category.LOWER.forEach { row(body, it) }

        heading(body, getString(R.string.how_to_extra_yahtzee_title))
        paragraph(body, getString(R.string.how_to_extra_yahtzee_body, EXTRA_YAHTZEE_POINTS))

        heading(body, getString(R.string.how_to_winning_title))
        paragraph(body, getString(R.string.how_to_winning_body))

        heading(body, getString(R.string.how_to_together_title))
        paragraph(body, getString(R.string.how_to_together_body))
    }

    /**
     * What the upper bonus is actually worth, asked of the scorer rather than asserted here.
     *
     * An upper section sitting exactly on the target, handed to the same function the scorecard
     * uses. If the bonus ever changes, this page changes with it.
     */
    private fun upperBonusPoints(): Int =
        Scoring.upperBonus(mapOf(Category.SIXES to UPPER_TARGET))

    /** One box: its name, what it takes, and what it pays. */
    private fun row(parent: LinearLayout, category: Category) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val name = TextView(this).apply {
            text = category.label
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_dark, theme))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        }

        val what = TextView(this).apply {
            text = describe(category)
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_muted, theme))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 5f)
        }

        // Straight from the enum, so the number here is the number the scorecard shows.
        val pays = TextView(this).apply {
            text = category.hint
            textSize = 14f
            gravity = Gravity.END
            setTextColor(resources.getColor(R.color.text_muted, theme))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        }

        line.addView(name)
        line.addView(what)
        line.addView(pays)
        parent.addView(line)
    }

    /** Plain English for what a box needs. The enum's own hint says what it pays. */
    private fun describe(category: Category): String = when (category) {
        Category.ONES -> getString(R.string.how_to_face, 1)
        Category.TWOS -> getString(R.string.how_to_face, 2)
        Category.THREES -> getString(R.string.how_to_face, 3)
        Category.FOURS -> getString(R.string.how_to_face, 4)
        Category.FIVES -> getString(R.string.how_to_face, 5)
        Category.SIXES -> getString(R.string.how_to_face, 6)
        Category.THREE_OF_A_KIND -> getString(R.string.how_to_three_kind)
        Category.FOUR_OF_A_KIND -> getString(R.string.how_to_four_kind)
        Category.FULL_HOUSE -> getString(R.string.how_to_full_house)
        Category.SMALL_STRAIGHT -> getString(R.string.how_to_small_straight)
        Category.LARGE_STRAIGHT -> getString(R.string.how_to_large_straight)
        Category.YAHTZEE -> getString(R.string.how_to_yahtzee)
        Category.CHANCE -> getString(R.string.how_to_chance)
    }

    private fun heading(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_dark, theme))
            setPadding(0, dp(26), 0, dp(8))
        })
    }

    private fun paragraph(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(resources.getColor(R.color.text_muted, theme))
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(resources.getColor(R.color.text_dark, theme))
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** The upper total the bonus is paid at. */
        const val UPPER_TARGET = 63

        /** What each Yahtzee after the first is worth. */
        const val EXTRA_YAHTZEE_POINTS = 100
    }
}
