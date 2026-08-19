package com.yahtzee.online.ui.game

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

class ScorecardAdapter(
    private val context: android.content.Context
) : BaseAdapter() {

    private val categories = Category.values().toList()
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
        val category = categories[position]
        val player = state?.players?.get(playerId)
        return canScore && player?.scores?.containsKey(category.name) != true
    }

    override fun getCount() = categories.size
    override fun getItem(position: Int) = categories[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_category, parent, false)
        val category = categories[position]
        val label = view.findViewById<TextView>(R.id.categoryLabel)
        val score = view.findViewById<TextView>(R.id.categoryScore)

        label.text = category.label

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
            score.background = badge(9f, R.color.score_badge_filled_bg)
            score.setTextColor(context.resources.getColor(R.color.score_badge_filled_text, context.theme))
            label.setTextColor(context.resources.getColor(R.color.category_filled_text, context.theme))
            label.paintFlags = label.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            label.paintFlags = label.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            label.setTextColor(context.resources.getColor(R.color.text_dark, context.theme))
            if (currentState != null && canScore) {
                val preview = Scoring.score(category, currentState.dice)
                score.text = preview.toString()
                score.background = badge(9f, R.color.score_badge_available_bg)
                score.setTextColor(context.resources.getColor(R.color.score_badge_available_text, context.theme))
            } else {
                score.text = "–"
                score.background = badge(9f, R.color.score_badge_filled_bg)
                score.setTextColor(context.resources.getColor(R.color.text_muted, context.theme))
            }
        }

        return view
    }
}
