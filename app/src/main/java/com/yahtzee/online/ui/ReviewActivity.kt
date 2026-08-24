package com.yahtzee.online.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.GameReview

/**
 * A look back over the game just played, turn by turn.
 *
 * The scorecard already says what was scored; what it cannot say is what else was on offer at the
 * time, which is where games are actually lost. Each turn is measured against the choice the
 * Expert bot would have made from the same dice and the same open boxes — the same valuation it
 * plays by, so the advice is what a real opponent would have done rather than a rule invented for
 * this screen.
 */
class ReviewActivity : ImmersiveActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        val summary = GameReview.summary(this)
        val headline = findViewById<TextView>(R.id.reviewHeadline)
        val subhead = findViewById<TextView>(R.id.reviewSubhead)

        if (!summary.hasTurns) {
            headline.setText(R.string.review_empty_title)
            subhead.setText(R.string.review_empty)
            findViewById<View>(R.id.reviewListHeading).visibility = View.GONE
            findViewById<View>(R.id.reviewCaveat).visibility = View.GONE
            return
        }

        if (summary.missedPoints == 0) {
            headline.setText(R.string.review_perfect_title)
            subhead.setText(R.string.review_perfect)
        } else {
            headline.text = getString(R.string.review_missed_title, summary.missedPoints)
            val worst = summary.worst
            subhead.text = if (worst == null) {
                getString(R.string.review_missed, summary.differed)
            } else {
                getString(
                    R.string.review_missed_with_worst,
                    summary.differed,
                    worst.dice.sorted().joinToString("-"),
                    worst.chosen.label,
                    worst.points,
                    worst.better.label,
                    worst.betterPoints
                )
            }
        }

        renderTurns(summary)
    }

    private fun renderTurns(summary: GameReview.Summary) {
        val list = findViewById<LinearLayout>(R.id.reviewList)
        list.removeAllViews()
        val density = resources.displayMetrics.density

        summary.turns.forEachIndexed { index, turn ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (9 * density).toInt(), 0, (9 * density).toInt())
            }

            row.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_muted, theme))
                layoutParams = LinearLayout.LayoutParams((26 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            // Sorted, because a hand is a set of faces — the order they landed in is noise here.
            row.addView(TextView(this).apply {
                text = turn.dice.sorted().joinToString(" ")
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(resources.getColor(R.color.text_dark, theme))
                layoutParams = LinearLayout.LayoutParams((92 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            val missed = turn.missed
            row.addView(TextView(this).apply {
                text = when {
                    turn.differs -> getString(
                        R.string.review_row_better,
                        turn.chosen.label, turn.points, turn.better.label, turn.betterPoints
                    )
                    else -> getString(R.string.review_row_fine, turn.chosen.label, turn.points)
                }
                textSize = 13f
                setTextColor(
                    // Three states, not two. Amber is a turn that cost points; a swap that cost
                    // none but spent the wrong box is still worth seeing, so it is shown without
                    // being dressed up as damage.
                    when {
                        missed > 0 -> resources.getColor(R.color.timer_warn, theme)
                        turn.differs -> resources.getColor(R.color.text_dark, theme)
                        else -> resources.getColor(R.color.text_muted, theme)
                    }
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (missed > 0) {
                row.addView(TextView(this).apply {
                    text = getString(R.string.review_row_cost, missed)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.END
                    setTextColor(AccentColor.resolve(this@ReviewActivity))
                })
            }

            list.addView(row)
        }
    }
}
