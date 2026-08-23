package com.yahtzee.online.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.PlayerStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The player's own record: how they score, which boxes they waste, and how the last few games
 * went. Read-only and entirely local — see [PlayerStats] for why none of this is synced.
 */
class StatsActivity : ImmersiveActivity() {

    private val dayFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.resetStatsButton).setOnClickListener { confirmReset() }
        render()
    }

    private fun render() {
        val totals = PlayerStats.totals(this)
        val hasGames = totals.hasPlayed

        findViewById<View>(R.id.statsEmpty).visibility = if (hasGames) View.GONE else View.VISIBLE
        listOf(R.id.categoryHeading, R.id.recentHeading, R.id.resetStatsButton).forEach {
            findViewById<View>(it).visibility = if (hasGames) View.VISIBLE else View.GONE
        }
        if (!hasGames) {
            findViewById<LinearLayout>(R.id.statsSummary).removeAllViews()
            return
        }

        renderSummary(totals)
        renderCategories()
        renderRecent()
    }

    private fun renderSummary(totals: PlayerStats.Totals) {
        val container = findViewById<LinearLayout>(R.id.statsSummary)
        container.removeAllViews()

        // Win rate is meaningless without opponents, so it is only offered once some game has
        // actually had one — otherwise a player who only does daily challenges sees a hard 0%.
        val contested = PlayerStats.recent(this).any { it.opponents > 0 }
        val cells = buildList {
            add(getString(R.string.stat_best) to totals.bestScore.toString())
            add(getString(R.string.stat_average) to totals.averageScore.toString())
            add(getString(R.string.stat_played) to totals.played.toString())
            if (contested) add(getString(R.string.stat_win_rate) to "${totals.winRate}%")
            add(getString(R.string.stat_yahtzees) to totals.yahtzees.toString())
            add(getString(R.string.stat_upper_bonus) to "${totals.upperBonusRate}%")
            // Only once one has been earned — a permanent zero here reads as a broken feature
            // rather than as a rare event that has not happened yet.
            if (totals.yahtzeeBonuses > 0) {
                add(getString(R.string.stat_yahtzee_bonuses) to totals.yahtzeeBonuses.toString())
            }
        }

        // Two to a row, so the numbers stay large enough to read at a glance on a phone.
        cells.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            pair.forEach { (label, value) -> row.addView(summaryCell(label, value)) }
            // An odd count leaves a hole; a weightless filler keeps the last cell half-width
            // instead of stretching it across the row.
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            container.addView(row)
        }
    }

    private fun summaryCell(label: String, value: String): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

            addView(TextView(this@StatsActivity).apply {
                text = value
                textSize = 26f
                setTextColor(resources.getColor(R.color.text_dark, theme))
            })
            addView(TextView(this@StatsActivity).apply {
                text = label
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_muted, theme))
            })
        }
    }

    /**
     * Every category, ordered by how much it is costing the player: the ones scratched most often
     * float to the top, since that is the list worth acting on.
     */
    private fun renderCategories() {
        val list = findViewById<LinearLayout>(R.id.categoryList)
        list.removeAllViews()

        val records = PlayerStats.categoryRecords(this)
        val density = resources.displayMetrics.density

        Category.values()
            .mapNotNull { category -> records[category]?.let { category to it } }
            .filter { it.second.filled > 0 }
            .sortedByDescending { it.second.zeroRate }
            .forEach { (category, record) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                }
                row.addView(TextView(this).apply {
                    text = category.label
                    textSize = 15f
                    maxLines = 1
                    setTextColor(resources.getColor(R.color.text_dark, theme))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = getString(R.string.stat_avg_value, record.average)
                    textSize = 15f
                    gravity = Gravity.END
                    setTextColor(resources.getColor(R.color.text_dark, theme))
                    layoutParams = LinearLayout.LayoutParams((64 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                row.addView(TextView(this).apply {
                    text = getString(R.string.stat_zero_value, record.zeroRate)
                    textSize = 13f
                    gravity = Gravity.END
                    // Amber once a category is being thrown away in a quarter of games — the
                    // point of the list is to make that visible without reading the numbers.
                    setTextColor(
                        if (record.zeroRate >= 25) resources.getColor(R.color.timer_warn, theme)
                        else resources.getColor(R.color.text_muted, theme)
                    )
                    layoutParams = LinearLayout.LayoutParams((72 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                list.addView(row)
            }
    }

    private fun renderRecent() {
        val list = findViewById<LinearLayout>(R.id.recentList)
        list.removeAllViews()

        val density = resources.displayMetrics.density
        PlayerStats.recent(this).reversed().forEach { game ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
            }
            row.addView(TextView(this).apply {
                text = dayFormat.format(Date(game.playedAt))
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_muted, theme))
                layoutParams = LinearLayout.LayoutParams((58 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = describe(game)
                textSize = 14f
                maxLines = 1
                setTextColor(resources.getColor(R.color.text_dark, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = game.score.toString()
                textSize = 15f
                setTextColor(
                    if (game.won) AccentColor.resolve(this@StatsActivity)
                    else resources.getColor(R.color.text_dark, theme)
                )
            })
            list.addView(row)
        }
    }

    private fun describe(game: PlayerStats.GameRecord): String {
        val mode = when (game.mode) {
            PlayerStats.Mode.DAILY -> getString(R.string.daily_challenge)
            PlayerStats.Mode.ONLINE -> getString(R.string.mode_online)
            PlayerStats.Mode.SOLO -> getString(R.string.mode_solo)
        }
        val cards = if (game.cardCount > 1) getString(R.string.n_cards, game.cardCount) else null
        return listOfNotNull(mode, cards).joinToString(" · ")
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stats_reset)
            .setMessage(R.string.stats_reset_confirm)
            .setPositiveButton(R.string.stats_reset) { _, _ ->
                PlayerStats.clear(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
