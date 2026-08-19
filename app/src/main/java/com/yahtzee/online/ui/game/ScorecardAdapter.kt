package com.yahtzee.online.ui.game

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Scoring

private sealed class Row {
    data class Header(val title: String) : Row()
    data class CategoryRow(val category: Category) : Row()
    object BonusRow : Row()
}

class ScorecardAdapter(
    private val context: android.content.Context
) : BaseAdapter() {

    private val rows: List<Row> = buildList {
        add(Row.Header("Upper Section"))
        Category.UPPER.forEach { add(Row.CategoryRow(it)) }
        add(Row.BonusRow)
        add(Row.Header("Lower Section"))
        Category.LOWER.forEach { add(Row.CategoryRow(it)) }
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

    fun isScorable(position: Int): Boolean {
        val row = rows[position] as? Row.CategoryRow ?: return false
        val player = state?.players?.get(playerId)
        return canScore && player?.scores?.containsKey(row.category.name) != true
    }

    /** Category represented at [position], or null for header/bonus rows. */
    fun categoryAt(position: Int): Category? = (rows[position] as? Row.CategoryRow)?.category

    override fun getCount() = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getViewTypeCount() = 3
    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.CategoryRow -> 1
        Row.BonusRow -> 2
    }

    override fun isEnabled(position: Int): Boolean = rows[position] is Row.CategoryRow

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val row = rows[position]) {
            is Row.Header -> bindHeader(row, convertView, parent)
            is Row.CategoryRow -> bindCategory(row.category, convertView, parent)
            Row.BonusRow -> bindBonus(convertView, parent)
        }
    }

    private fun bindHeader(row: Row.Header, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_section_header, parent, false)
        view.findViewById<TextView>(R.id.sectionTitle).text = row.title

        val subtotalView = view.findViewById<TextView>(R.id.sectionSubtotal)
        val player = state?.players?.get(playerId)
        if (row.title == "Upper Section" && player != null) {
            val upperTotal = Category.UPPER.sumOf { player.scores[it.name] ?: 0 }
            subtotalView.text = "Subtotal $upperTotal"
            subtotalView.visibility = View.VISIBLE
        } else {
            subtotalView.visibility = View.GONE
        }
        return view
    }

    private fun bindBonus(convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_bonus_row, parent, false)
        val label = view.findViewById<TextView>(R.id.bonusLabel)
        val value = view.findViewById<TextView>(R.id.bonusValue)

        val player = state?.players?.get(playerId)
        val upperTotal = Category.UPPER.sumOf { player?.scores?.get(it.name) ?: 0 }
        val bonusEarned = upperTotal >= 63

        label.text = "Bonus if 63+ (get 35 pts)"
        value.text = if (bonusEarned) "+35 ✓" else "$upperTotal / 63"
        value.setTextColor(
            context.resources.getColor(
                if (bonusEarned) R.color.score_badge_available_text else R.color.text_muted,
                context.theme
            )
        )
        return view
    }

    private fun bindCategory(category: Category, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_category, parent, false)
        val label = view.findViewById<TextView>(R.id.categoryLabel)
        val hint = view.findViewById<TextView>(R.id.categoryHint)
        val score = view.findViewById<TextView>(R.id.categoryScore)

        label.text = category.label
        hint.text = category.hint

        val currentState = state
        val player = currentState?.players?.get(playerId)
        val existingScore = player?.scores?.get(category.name)

        fun badge(radiusDp: Float, colorRes: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(context.resources.getColor(colorRes, context.theme))
        }

        if (existingScore != null) {
            score.text = existingScore.toString()
            score.background = badge(10f, R.color.score_badge_filled_bg)
            score.setTextColor(context.resources.getColor(R.color.score_badge_filled_text, context.theme))
            label.setTextColor(context.resources.getColor(R.color.category_filled_text, context.theme))
            label.paintFlags = label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            label.paintFlags = label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            label.setTextColor(context.resources.getColor(R.color.text_dark, context.theme))
            if (currentState != null && canScore) {
                val preview = Scoring.score(category, currentState.dice)
                score.text = preview.toString()
                score.background = badge(10f, R.color.score_badge_available_bg)
                score.setTextColor(context.resources.getColor(R.color.score_badge_available_text, context.theme))
            } else {
                score.text = "–"
                score.background = badge(10f, R.color.score_badge_filled_bg)
                score.setTextColor(context.resources.getColor(R.color.text_muted, context.theme))
            }
        }

        return view
    }
}
