package com.yahtzee.online.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.ArchivedGame
import com.yahtzee.online.game.ArchivedPlayer
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameArchive
import com.yahtzee.online.game.PlayerStats
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Scoring
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Games already played, and the cards they were played on.
 *
 * The list first, a card when one is chosen. Both live on one screen because the card is the whole
 * point: a list of results is what the stats screen already shows, and what was missing was being
 * able to open one.
 *
 * Back steps from a card to the list before it leaves, which is what pressing it there means —
 * finished looking at that game, not at the history.
 */
class GameHistoryActivity : ImmersiveActivity() {

    private val dayFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    /** The game being looked at, or null while the list is up. */
    private var open: ArchivedGame? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_history)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { goBack() }
        renderList()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (open != null) {
            goBack()
            return
        }
        super.onBackPressed()
    }

    private fun goBack() {
        if (open != null) {
            open = null
            renderList()
        } else {
            finish()
        }
    }

    private fun renderList() {
        val body = findViewById<LinearLayout>(R.id.historyBody)
        body.removeAllViews()
        findViewById<TextView>(R.id.historyTitle).setText(R.string.history_title)

        val games = GameArchive.all(this)
        findViewById<TextView>(R.id.historyEmpty).visibility =
            if (games.isEmpty()) View.VISIBLE else View.GONE

        games.forEach { game ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setBackgroundResource(selectableBackground())
                setOnClickListener {
                    open = game
                    renderCard(game)
                }
            }

            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(TextView(this).apply {
                text = game.standings.joinToString(", ") { it.name }
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(resources.getColor(R.color.text_dark, theme))
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            heading.addView(TextView(this).apply {
                text = game.you?.let { game.totalFor(it).toString() }.orEmpty()
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                // Accent when you won it, which is the one thing worth picking out of a list.
                setTextColor(
                    if (game.youWon) AccentColor.resolve(this@GameHistoryActivity)
                    else resources.getColor(R.color.text_dark, theme)
                )
            })
            row.addView(heading)

            row.addView(TextView(this).apply {
                text = subtitle(game)
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_muted, theme))
            })
            body.addView(row)
        }
    }

    private fun subtitle(game: ArchivedGame): String {
        val mode = when (game.mode) {
            PlayerStats.Mode.ONLINE -> getString(R.string.mode_online)
            PlayerStats.Mode.DAILY -> getString(R.string.daily_challenge)
            PlayerStats.Mode.SOLO -> getString(R.string.mode_solo)
        }
        val cards = if (game.cardCount > 1) getString(R.string.n_cards, game.cardCount) else null
        return listOfNotNull(dayFormat.format(Date(game.playedAt)), mode, cards).joinToString(" · ")
    }

    /**
     * One finished card, in full.
     *
     * A column per player and a row per box, which is the shape of the paper it stands in for.
     * With several cards in play each gets its own table rather than being summed away — the whole
     * reason for keeping the card is that the total was never the interesting part.
     */
    private fun renderCard(game: ArchivedGame) {
        val body = findViewById<LinearLayout>(R.id.historyBody)
        body.removeAllViews()
        findViewById<TextView>(R.id.historyEmpty).visibility = View.GONE
        findViewById<TextView>(R.id.historyTitle).text = subtitle(game)

        val players = game.standings

        repeat(game.cardCount) { card ->
            if (game.cardCount > 1) body.addView(heading(getString(R.string.history_card, card + 1)))
            body.addView(rowOf(getString(R.string.players_label), players.map { it.name }, bold = true))

            Category.UPPER.forEach { category ->
                body.addView(rowOf(category.label, players.map { boxText(it, card, category) }))
            }
            body.addView(
                rowOf(
                    getString(R.string.history_upper_bonus),
                    players.map { Scoring.upperBonus(scoresOn(it, card)).toString() },
                    muted = true
                )
            )
            Category.LOWER.forEach { category ->
                body.addView(rowOf(category.label, players.map { boxText(it, card, category) }))
            }
        }

        // Only when one was actually earned: a row of zeroes for the rarest thing in the game
        // reads as a broken feature rather than as something that did not happen.
        if (game.players.any { it.yahtzeeBonusCount > 0 }) {
            body.addView(
                rowOf(
                    getString(R.string.history_yahtzee_bonus),
                    players.map { (it.yahtzeeBonusCount * 100).toString() },
                    muted = true
                )
            )
        }

        body.addView(
            rowOf(getString(R.string.history_total), players.map { game.totalFor(it).toString() }, bold = true)
        )
    }

    private fun scoresOn(player: ArchivedPlayer, card: Int): Map<Category, Int> =
        player.scores.mapNotNull { (key, value) ->
            if (ScoreKey.cardOf(key) != card) null
            else ScoreKey.categoryOf(key)?.let { it to value }
        }.toMap()

    /** A dash rather than a zero for a box never filled: the two mean different things. */
    private fun boxText(player: ArchivedPlayer, card: Int, category: Category): String =
        player.scores[ScoreKey.of(card, category)]?.toString() ?: "–"

    private fun rowOf(
        label: String,
        cells: List<String>,
        bold: Boolean = false,
        muted: Boolean = false
    ): LinearLayout {
        val colour = resources.getColor(if (muted) R.color.text_muted else R.color.text_dark, theme)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(colour)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        })
        cells.forEach { cell ->
            row.addView(TextView(this).apply {
                text = cell
                textSize = 14f
                gravity = Gravity.END
                maxLines = 1
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(colour)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
        }
        return row
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(resources.getColor(R.color.text_dark, theme))
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun selectableBackground(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
