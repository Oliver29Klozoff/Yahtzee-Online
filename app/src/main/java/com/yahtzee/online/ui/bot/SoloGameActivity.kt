package com.yahtzee.online.ui.bot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.yahtzee.online.R
import com.yahtzee.online.bot.LocalGameEngine
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.net.LeaderboardRepository
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.game.ScorecardAdapter
import com.yahtzee.online.ui.game.ScorecardTabs

class SoloGameActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_BOT_COUNT = "bot_count"
        private const val ROLL_SETTLE_DELAY_MS = 1300L
    }

    private lateinit var engine: LocalGameEngine
    private lateinit var dice3DView: Dice3DView
    private lateinit var scorecardAdapter: ScorecardAdapter
    private var viewingPlayerId: String? = null
    private var lastTurnPlayerId: String? = null
    private val botHandler = Handler(Looper.getMainLooper())
    private var lastRollsUsed = 0
    private var gameOverShown = false
    private var botTurnScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val name = intent.getStringExtra(EXTRA_PLAYER_NAME) ?: "You"
        val botCount = intent.getIntExtra(EXTRA_BOT_COUNT, 1).coerceIn(1, 4)
        engine = LocalGameEngine(name, botCount, DicePreferences.getColor(this))

        // Solo games have no turn timer / no timer UI needed.
        findViewById<View>(R.id.turnTimerText).visibility = View.GONE
        findViewById<View>(R.id.turnTimerBar).visibility = View.GONE

        dice3DView = findViewById(R.id.dice3DView)

        scorecardAdapter = ScorecardAdapter(this)
        val scorecardList = findViewById<ListView>(R.id.scorecardList)
        scorecardList.adapter = scorecardAdapter
        scorecardList.setOnItemClickListener { _, _, position, _ ->
            if (scorecardAdapter.isScorable(position)) {
                scorecardAdapter.categoryAt(position)?.let { engine.submitScore(it) }
            }
        }

        findViewById<Button>(R.id.rollButton).setOnClickListener {
            val state = engine.state
            if (engine.isBotTurn() || state.rollsUsed >= MAX_ROLLS_PER_TURN) return@setOnClickListener
            engine.rollDice()
        }

        engine.setOnChangeListener { render(engine.state) }
        render(engine.state)
    }

    private fun render(state: GameState) {
        val myTurn = !engine.isBotTurn()
        val currentPlayerName = state.players[state.currentPlayerId]?.name ?: ""

        findViewById<TextView>(R.id.turnStatusText).text =
            if (myTurn) getString(R.string.your_turn) else getString(R.string.waiting_for_turn, currentPlayerName)

        findViewById<TextView>(R.id.rollsLeftText).text =
            getString(R.string.rolls_left, MAX_ROLLS_PER_TURN - state.rollsUsed)

        val rollButton = findViewById<Button>(R.id.rollButton)
        rollButton.isEnabled = myTurn && state.rollsUsed < MAX_ROLLS_PER_TURN
        rollButton.visibility = if (myTurn) View.VISIBLE else View.GONE

        // Dice take the colour of whoever is rolling, so you can tell at a glance whether the
        // table belongs to you or to a bot. No-op unless the value actually changed.
        val activeColor = state.players[state.currentPlayerId]?.diceColor
            ?.takeIf { it != 0 }
            ?: DieTextureAtlas.DEFAULT_COLOR
        dice3DView.setDiceColor(activeColor)

        renderDice(state)
        renderHoldRow(state, myTurn)

        // The scorecard follows whoever's turn it is, so you watch each bot's card fill in as
        // it plays. Tapping a tab overrides that for the rest of the current turn; the override
        // clears on the next turn change so focus returns to the new active player.
        if (state.currentPlayerId != lastTurnPlayerId) {
            lastTurnPlayerId = state.currentPlayerId
            viewingPlayerId = null
        }
        val viewing = viewingPlayerId?.takeIf { state.players.containsKey(it) }
            ?: state.currentPlayerId?.takeIf { state.players.containsKey(it) }
            ?: engine.humanPlayerId
        ScorecardTabs.render(
            context = this,
            row = findViewById(R.id.playerTabsRow),
            state = state,
            localPlayerId = engine.humanPlayerId,
            viewingPlayerId = viewing
        ) { selectedId ->
            viewingPlayerId = selectedId
            render(state)
        }

        val canScore = myTurn && state.rollsUsed > 0 && viewing == engine.humanPlayerId
        scorecardAdapter.update(state, viewing, canScore)

        val total = state.players[viewing]?.grandTotalAllCards(state.cardCount) ?: 0
        findViewById<TextView>(R.id.scorecardTotalText).text = getString(R.string.total_score, total)

        if (state.status == GameState.STATUS_FINISHED && !gameOverShown) {
            gameOverShown = true
            showGameOver(state)
        } else if (engine.isBotTurn() && !botTurnScheduled && state.status == GameState.STATUS_PLAYING) {
            botTurnScheduled = true
            botHandler.postDelayed({ stepBotTurn() }, 900)
        }
    }

    /**
     * Plays one bot roll at a time with a pause between each — long enough for the 3D dice to
     * finish tumbling ([Dice3DView]'s roll animation runs ~700-1200ms) — so every intermediate
     * roll and hold decision is visible on screen instead of the whole turn resolving instantly.
     */
    private fun stepBotTurn() {
        engine.stepBotRoll()
        if (engine.isBotDoneRolling()) {
            botHandler.postDelayed({
                botTurnScheduled = false
                engine.finishBotTurn()
            }, ROLL_SETTLE_DELAY_MS)
        } else {
            botHandler.postDelayed({ stepBotTurn() }, ROLL_SETTLE_DELAY_MS)
        }
    }

    private fun renderDice(state: GameState) {
        val isNewRoll = state.rollsUsed > 0 && state.rollsUsed != lastRollsUsed
        if (isNewRoll) {
            // Bots throw from their own seat around the table; yours always come from the right.
            dice3DView.rollTo(
                state.dice,
                state.held,
                state.seatAngle(engine.humanPlayerId, state.currentPlayerId)
            )
        }
        lastRollsUsed = state.rollsUsed
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
                    engine.toggleHold(index)
                }
            }
            chip.isEnabled = myTurn && state.rollsUsed in 1 until MAX_ROLLS_PER_TURN
            holdRow.addView(chip)
        }
    }

    private fun showGameOver(state: GameState) {
        // Solo results count too — the board ranks people, not game modes.
        state.players[engine.humanPlayerId]?.let { me ->
            LeaderboardRepository().submitScore(
                playerId = PlayerProfile.getId(this),
                name = PlayerProfile.getName(this).ifEmpty { me.name },
                score = me.grandTotalAllCards(state.cardCount)
            )
        }
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
        botHandler.removeCallbacksAndMessages(null)
    }
}
