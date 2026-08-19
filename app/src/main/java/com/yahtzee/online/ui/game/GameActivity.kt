package com.yahtzee.online.ui.game

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.net.GameRepository

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_ID = "player_id"
    }

    private val repository = GameRepository()
    private lateinit var roomCode: String
    private lateinit var playerId: String
    private var listener: ValueEventListener? = null
    private lateinit var scorecardAdapter: ScorecardAdapter
    private var lastState: GameState? = null
    private var gameOverShown = false
    private var lastDice: List<Int>? = null
    private var lastRollsUsed = 0
    private lateinit var dice3DView: Dice3DView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        playerId = intent.getStringExtra(EXTRA_PLAYER_ID) ?: ""

        dice3DView = findViewById(R.id.dice3DView)

        scorecardAdapter = ScorecardAdapter(this)
        val scorecardList = findViewById<ListView>(R.id.scorecardList)
        scorecardList.adapter = scorecardAdapter
        scorecardList.setOnItemClickListener { _, _, position, _ ->
            if (scorecardAdapter.isScorable(position)) {
                scorecardAdapter.categoryAt(position)?.let { onScoreCategory(it) }
            }
        }

        findViewById<Button>(R.id.rollButton).setOnClickListener {
            val state = lastState ?: return@setOnClickListener
            if (!state.isMyTurn(playerId) || state.rollsUsed >= MAX_ROLLS_PER_TURN) return@setOnClickListener
            repository.rollDice(roomCode, state.dice, state.held, state.rollsUsed)
        }

        listener = repository.listenToRoom(roomCode) { state ->
            if (state == null) return@listenToRoom
            lastState = state
            render(state)
            if (state.status == GameState.STATUS_FINISHED && !gameOverShown) {
                gameOverShown = true
                showGameOver(state)
            }
        }
    }

    private fun render(state: GameState) {
        val myTurn = state.isMyTurn(playerId)
        val currentPlayerName = state.players[state.currentPlayerId]?.name ?: ""

        findViewById<TextView>(R.id.turnStatusText).text =
            if (myTurn) getString(R.string.your_turn) else getString(R.string.waiting_for_turn, currentPlayerName)

        findViewById<TextView>(R.id.rollsLeftText).text =
            getString(R.string.rolls_left, MAX_ROLLS_PER_TURN - state.rollsUsed)

        val rollButton = findViewById<Button>(R.id.rollButton)
        rollButton.isEnabled = myTurn && state.rollsUsed < MAX_ROLLS_PER_TURN
        rollButton.visibility = if (myTurn) View.VISIBLE else View.GONE

        renderDice(state, myTurn)

        val canScore = myTurn && state.rollsUsed > 0
        scorecardAdapter.update(state, playerId, canScore)

        val player = state.players[playerId]
        val byCategory = player?.scores
            ?.mapNotNull { (name, value) -> runCatching { Category.valueOf(name) to value }.getOrNull() }
            ?.toMap()
            ?: emptyMap()
        val total = Scoring.grandTotal(byCategory, player?.yahtzeeBonusCount ?: 0)
        findViewById<TextView>(R.id.scorecardTotalText).text = getString(R.string.total_score, total)
    }

    private fun renderDice(state: GameState, myTurn: Boolean) {
        val isNewRoll = state.rollsUsed > 0 && state.rollsUsed != lastRollsUsed
        if (isNewRoll) {
            dice3DView.rollTo(state.dice, state.held)
        }
        lastDice = state.dice
        lastRollsUsed = state.rollsUsed

        renderHoldRow(state, myTurn)
    }

    private fun renderHoldRow(state: GameState, myTurn: Boolean) {
        val holdRow = findViewById<LinearLayout>(R.id.holdRow)
        holdRow.removeAllViews()
        state.dice.forEachIndexed { index, value ->
            val chip = Button(this)
            chip.text = value.toString()
            chip.isSelected = state.held.getOrNull(index) == true
            chip.setBackgroundColor(
                if (chip.isSelected) resources.getColor(R.color.die_held, theme)
                else resources.getColor(R.color.die_normal, theme)
            )
            chip.setTextColor(
                if (chip.isSelected) resources.getColor(R.color.background, theme)
                else resources.getColor(R.color.text_dark, theme)
            )
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.marginStart = 8
            params.marginEnd = 8
            chip.layoutParams = params
            chip.setOnClickListener {
                if (myTurn && state.rollsUsed in 1 until MAX_ROLLS_PER_TURN) {
                    repository.toggleHold(roomCode, state.held, index)
                }
            }
            chip.isEnabled = myTurn && state.rollsUsed in 1 until MAX_ROLLS_PER_TURN
            holdRow.addView(chip)
        }
    }

    private fun onScoreCategory(category: Category) {
        val state = lastState ?: return
        if (!state.isMyTurn(playerId) || state.rollsUsed == 0) return
        repository.submitScore(roomCode, state, category, playerId)
    }

    private fun showGameOver(state: GameState) {
        val winnerName = state.players[state.winnerId]?.name ?: "?"
        AlertDialog.Builder(this)
            .setTitle(R.string.game_over)
            .setMessage(getString(R.string.winner_is, winnerName))
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(roomCode, it) }
    }
}
