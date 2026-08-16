package com.yahtzee.online.ui.game

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
    private val context: android.content.Context,
    private val onCategoryClick: (Category) -> Unit
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

        if (existingScore != null) {
            score.text = existingScore.toString()
            view.isEnabled = false
            view.alpha = 0.6f
        } else if (currentState != null && canScore) {
            val preview = Scoring.score(category, currentState.dice)
            score.text = preview.toString()
            view.alpha = 1.0f
            view.isEnabled = true
            view.setOnClickListener { onCategoryClick(category) }
        } else {
            score.text = "-"
            view.alpha = 0.4f
            view.isEnabled = false
            view.setOnClickListener(null)
        }

        return view
    }
}
