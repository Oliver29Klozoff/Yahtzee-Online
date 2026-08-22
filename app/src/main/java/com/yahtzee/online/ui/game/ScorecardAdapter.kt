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
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.scoresForCard

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
class ScorecardAdapter(
    private val context: android.content.Context,
    private val onScore: (card: Int, category: Category) -> Unit = { _, _ -> }
) : BaseAdapter() {

    private val rows: List<Row> = buildList {
        add(Row.Header("Upper Section"))
        Category.UPPER.forEach { add(Row.CategoryRow(it)) }
        add(Row.BonusRow)
        add(Row.Header("Lower Section"))
        Category.LOWER.forEach { add(Row.CategoryRow(it)) }
        add(Row.YahtzeeBonusRow)
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
        view.findViewById<TextView>(R.id.bonusLabel).text = "Bonus if 63+ (get 35 pts)"

        val cells = view.findViewById<LinearLayout>(R.id.bonusCells)
        cells.removeAllViews()
        for (card in 0 until cardCount()) {
            val upperTotal = Category.UPPER.sumOf { scoresFor(card)[it] ?: 0 }
            val earned = upperTotal >= 63
            cells.addView(
                textCell(
                    text = if (earned) "+35" else "$upperTotal/63",
                    colorRes = if (earned) R.color.score_badge_available_text else R.color.text_muted
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
        view.findViewById<TextView>(R.id.bonusLabel).text = "Yahtzee bonus (+100 each)"

        val count = state?.players?.get(playerId)?.yahtzeeBonusCount ?: 0
        val cells = view.findViewById<LinearLayout>(R.id.bonusCells)
        cells.removeAllViews()
        cells.addView(
            textCell(
                text = if (count > 0) "$count × 100 = ${count * 100}" else "–",
                colorRes = if (count > 0) R.color.score_badge_available_text else R.color.text_muted,
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
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val size = (34 * context.resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginStart = (5 * context.resources.displayMetrics.density).toInt()
                }
            }

            when {
                existing != null -> {
                    cell.text = existing.toString()
                    cell.background = badge(R.color.score_badge_filled_bg)
                    cell.setTextColor(context.resources.getColor(R.color.score_badge_filled_text, context.theme))
                }
                currentState != null && canScore -> {
                    cell.text = Scoring.score(category, currentState.dice).toString()
                    cell.background = badge(R.color.score_badge_available_bg)
                    cell.setTextColor(context.resources.getColor(R.color.score_badge_available_text, context.theme))
                    cell.setOnClickListener { onScore(card, category) }
                }
                else -> {
                    cell.text = "–"
                    cell.background = badge(R.color.score_badge_filled_bg)
                    cell.setTextColor(context.resources.getColor(R.color.text_muted, context.theme))
                }
            }
            cells.addView(cell)
        }
        return view
    }

    private fun badge(colorRes: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10f * context.resources.displayMetrics.density
        setColor(context.resources.getColor(colorRes, context.theme))
    }

    private fun textCell(text: String, colorRes: Int, wide: Boolean = false) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(context.resources.getColor(colorRes, context.theme))
        val density = context.resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            if (wide) LinearLayout.LayoutParams.WRAP_CONTENT else (34 * density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = (5 * density).toInt() }
    }
}
