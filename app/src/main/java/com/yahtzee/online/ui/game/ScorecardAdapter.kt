package com.yahtzee.online.ui.game

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.YahtzeeState
import com.yahtzee.online.game.scoresForCard
import com.yahtzee.online.game.yahtzeeStateFor

private sealed class Row {
    data class Header(val title: String) : Row()
    data class CategoryRow(val category: Category) : Row()
    object BonusRow : Row()
    object YahtzeeBonusRow : Row()
}

/**
 * The paper-style scorecard.
 *
 * With several cards in play every category shows one cell per card, side by side on a single
 * sheet, which is how a printed Triple Yahtzee card works — the choice of where to put a roll
 * is much easier to make when the alternatives are visible next to each other rather than
 * behind a tab. Each cell is tapped directly, so the card is chosen by the same tap that picks
 * the category.
 */
/**
 * How much of the card one adapter is responsible for.
 *
 * [BOTH] is the whole thing in one column, which is what a phone held upright wants. On its side
 * there is no height for seventeen rows and plenty of width for two columns, so the card is split
 * and each half gets its own list — [UPPER] and [LOWER] are those halves.
 */
enum class ScorecardSection { BOTH, UPPER, LOWER }

class ScorecardAdapter(
    private val context: android.content.Context,
    private val section: ScorecardSection = ScorecardSection.BOTH,
    private val onScore: (card: Int, category: Category) -> Unit = { _, _ -> }
) : BaseAdapter() {

    private val rows: List<Row> = buildList {
        if (section != ScorecardSection.LOWER) {
            add(Row.Header("Upper Section"))
            Category.UPPER.forEach { add(Row.CategoryRow(it)) }
            add(Row.BonusRow)
        }
        if (section != ScorecardSection.UPPER) {
            add(Row.Header("Lower Section"))
            Category.LOWER.forEach { add(Row.CategoryRow(it)) }
            add(Row.YahtzeeBonusRow)
        }
    }

    private var state: GameState? = null
    private var playerId: String = ""
    private var canScore = false

    fun update(state: GameState, playerId: String, canScore: Boolean) {
        this.state = state
        this.playerId = playerId
        this.canScore = canScore
        notifyDataSetChanged()
    }

    private fun cardCount(): Int = (state?.cardCount ?: 1).coerceAtLeast(1)

    private fun scoresFor(card: Int): Map<Category, Int> =
        state?.players?.get(playerId)?.scoresForCard(card) ?: emptyMap()

    override fun getCount() = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getViewTypeCount() = 4
    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.CategoryRow -> 1
        Row.BonusRow -> 2
        Row.YahtzeeBonusRow -> 3
    }

    // Cells handle their own taps now, since a row-level tap could not say which card was meant.
    override fun isEnabled(position: Int): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val row = rows[position]) {
            is Row.Header -> bindHeader(row, convertView, parent)
            is Row.CategoryRow -> bindCategory(row.category, convertView, parent)
            Row.BonusRow -> bindBonus(convertView, parent)
            Row.YahtzeeBonusRow -> bindYahtzeeBonus(convertView, parent)
        }
    }

    private fun bindHeader(row: Row.Header, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_section_header, parent, false)
        view.findViewById<TextView>(R.id.sectionTitle).text = row.title

        val subtotalView = view.findViewById<TextView>(R.id.sectionSubtotal)
        if (row.title == "Upper Section" && state != null) {
            // Every card's upper subtotal, in the same order as the columns below it.
            subtotalView.text = (0 until cardCount()).joinToString("   ") { card ->
                Category.UPPER.sumOf { scoresFor(card)[it] ?: 0 }.toString()
            }
            subtotalView.visibility = View.VISIBLE
        } else {
            subtotalView.visibility = View.GONE
        }
        return view
    }

    private fun bindBonus(convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_bonus_row, parent, false)
        // Shorter when the card is in halves: the full wording wraps to two lines in a
        // half-width column, which costs a row's worth of height on a card that has none spare.
        view.findViewById<TextView>(R.id.bonusLabel).text =
            if (section == ScorecardSection.BOTH) "Bonus if 63+ (get 35 pts)" else "Bonus 63+"

        val cells = view.findViewById<LinearLayout>(R.id.bonusCells)
        cells.removeAllViews()
        for (card in 0 until cardCount()) {
            val upperTotal = Category.UPPER.sumOf { scoresFor(card)[it] ?: 0 }
            val earned = upperTotal >= 63
            cells.addView(
                textCell(
                    text = if (earned) "+35" else "$upperTotal/63",
                    color = if (earned) AccentColor.resolve(context) else muted()
                )
            )
        }
        return view
    }

    /**
     * Extra Yahtzees: every five-of-a-kind rolled after the Yahtzee box is already filled with
     * 50 is worth another 100 points. Counted per player rather than per card, so this shows a
     * single figure however many cards are in play.
     */
    private fun bindYahtzeeBonus(convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_bonus_row, parent, false)
        view.findViewById<TextView>(R.id.bonusLabel).text =
            if (section == ScorecardSection.BOTH) "Yahtzee bonus (+100 each)" else "Yahtzee bonus"

        val count = state?.players?.get(playerId)?.yahtzeeBonusCount ?: 0
        // A bonus waiting on the table is called out here too, so the row a player looks at to
        // find their bonus is the one that tells them another is currently on offer.
        val pending = state?.yahtzeeStateFor(playerId) == YahtzeeState.BONUS
        val cells = view.findViewById<LinearLayout>(R.id.bonusCells)
        cells.removeAllViews()
        cells.addView(
            textCell(
                text = when {
                    pending && count > 0 -> "$count × 100  ·  +100 ready"
                    pending -> "+100 ready"
                    count > 0 -> "$count × 100 = ${count * 100}"
                    else -> "–"
                },
                color = if (pending || count > 0) AccentColor.resolve(context) else muted(),
                wide = true
            )
        )
        return view
    }

    private fun bindCategory(category: Category, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_category, parent, false)
        val label = view.findViewById<TextView>(R.id.categoryLabel)
        val hint = view.findViewById<TextView>(R.id.categoryHint)
        val cells = view.findViewById<LinearLayout>(R.id.scoreCells)

        label.text = category.label
        hint.text = category.hint
        // The hint is what makes a row two lines tall, and a split card is split precisely
        // because the height is not there. Whole-card mode keeps it; halves drop it.
        hint.visibility = if (section == ScorecardSection.BOTH) View.VISIBLE else View.GONE

        val currentState = state
        cells.removeAllViews()

        // Struck through only once every card has this category filled — with cards still open
        // the category is not finished with.
        val allFilled = (0 until cardCount()).all { scoresFor(it).containsKey(category) }
        if (allFilled) {
            label.setTextColor(context.resources.getColor(R.color.category_filled_text, context.theme))
            label.paintFlags = label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            label.paintFlags = label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            label.setTextColor(context.resources.getColor(R.color.text_dark, context.theme))
        }

        for (card in 0 until cardCount()) {
            val existing = scoresFor(card)[category]
            val open = existing == null
            val cell = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.score_cell_text))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                // Sized from a dimension so a phone on its side can use a smaller square; the
                // cell is what makes a row as tall as it is.
                val size = context.resources.getDimensionPixelSize(R.dimen.score_cell)
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginStart = (5 * context.resources.displayMetrics.density).toInt()
                }
            }

            when {
                existing != null -> {
                    cell.text = existing.toString()
                    cell.background = badge(context.resources.getColor(R.color.score_badge_filled_bg, context.theme))
                    cell.setTextColor(context.resources.getColor(R.color.score_badge_filled_text, context.theme))
                }
                currentState != null && canScore -> {
                    cell.text = Scoring.score(category, currentState.dice).toString()
                    cell.background = badge(AccentColor.badgeBackground(context))
                    cell.setTextColor(AccentColor.resolve(context))
                    cell.setOnClickListener { onScore(card, category) }
                }
                else -> {
                    cell.text = "–"
                    cell.background = badge(context.resources.getColor(R.color.score_badge_filled_bg, context.theme))
                    cell.setTextColor(context.resources.getColor(R.color.text_muted, context.theme))
                }
            }
            cells.addView(cell)
        }
        return view
    }

    /** Takes a resolved colour rather than a resource, so accent-derived values can be passed. */
    private fun muted() = context.resources.getColor(R.color.text_muted, context.theme)

    private fun badge(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10f * context.resources.displayMetrics.density
        setColor(color)
    }

    private fun textCell(text: String, color: Int, wide: Boolean = false) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.score_cell_text))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(color)
        val density = context.resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            if (wide) LinearLayout.LayoutParams.WRAP_CONTENT
            else context.resources.getDimensionPixelSize(R.dimen.score_cell),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = (5 * density).toInt() }
    }
}
