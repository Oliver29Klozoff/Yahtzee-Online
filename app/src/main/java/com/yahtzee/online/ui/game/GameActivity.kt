package com.yahtzee.online.ui.game

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.audio.SoundEngine
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.ActiveGamesStore
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameReview
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.PlayerStats
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.scoresForCard
import com.yahtzee.online.game.YahtzeeState
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.game.yahtzeeStateFor
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.net.LeaderboardRepository
import com.yahtzee.online.net.TurnNotifier
import com.yahtzee.online.ui.ImmersiveActivity

class GameActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_ID = "player_id"

        /** Gap between automatic actions, long enough for the dice to finish landing. */
        private const val AUTO_ACTION_INTERVAL_MS = 1500L
    }

    private val repository by lazy { GameRepository(this) }
    private lateinit var roomCode: String
    private lateinit var playerId: String
    private var listener: ValueEventListener? = null
    private lateinit var scorecardAdapter: ScorecardAdapter
    private var viewingPlayerId: String? = null

    private val scoreConfirm by lazy { ScoreConfirm(this) }
    private var lastTurnPlayerId: String? = null
    private var lastState: GameState? = null
    private var gameOverShown = false
    private var lastDice: List<Int>? = null
    private var lastRollsUsed = 0

    /** True while the dice are mid-throw, so the values are not revealed before they land. */
    private var diceRolling = false
    private lateinit var dice3DView: Dice3DView
    private val sound by lazy { SoundEngine(this) }
    private val timerHandler = Handler(Looper.getMainLooper())
    /** Earliest time the next automatic roll/score may fire, pacing an abandoned turn. */
    private var nextAutoActionAt = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimerDisplay()
            timerHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        playerId = intent.getStringExtra(EXTRA_PLAYER_ID) ?: ""

        // The board is open, so any standing "it's your turn" nudge for it has served its purpose.
        TurnNotifier.clear(this, roomCode)

        applyDisplaySettings()

        dice3DView = findViewById(R.id.dice3DView)
        dice3DView.setPipStyle(DicePreferences.pipStyle(this))
        dice3DView.setDiceFinish(DicePreferences.diceFinish(this))
        dice3DView.setTableColor(AppSettings.tableColor(this))
        dice3DView.setTableLogo(TableLogoStore.mode(this))
        dice3DView.setMotionScale(AppSettings.diceMotion(this).durationScale)
        // The dice report their own landing, so the knock lands with the visual, not the throw.
        dice3DView.setOnSettledListener {
            sound.play(SoundEngine.Sound.LAND)
            // Redraw so the values appear only once the dice have actually come to rest.
            if (diceRolling) {
                diceRolling = false
                lastState?.let { render(it) }
            }
        }

        scorecardAdapter = ScorecardAdapter(this) { card, category -> onScoreCategory(card, category) }
        findViewById<ListView>(R.id.scorecardList).adapter = scorecardAdapter

        findViewById<Button>(R.id.rollButton).setOnClickListener {
            val state = lastState ?: return@setOnClickListener
            if (!state.isMyTurn(playerId) || state.rollsUsed >= MAX_ROLLS_PER_TURN) return@setOnClickListener
            repository.rollDice(roomCode, state.dice, state.held, state.rollsUsed, state.turnMillis)
        }

        listener = repository.listenToRoom(roomCode) { state ->
            if (state == null) return@listenToRoom
            lastState = state
            render(state)
            if (state.status == GameState.STATUS_FINISHED && !gameOverShown) {
                gameOverShown = true
                showGameOver(state)
            }

            // Someone else called a rematch: the room has already reset, so close this finished
            // board and drop back to the lobby, which is waiting behind us to run the roll-off.
            if (gameOverShown && state.status == GameState.STATUS_ROLL_OFF) {
                finish()
            }
        }

        timerHandler.post(timerTick)
    }

    private fun updateTimerDisplay() {
        val state = lastState ?: return
        val timerText = findViewById<TextView>(R.id.turnTimerText)
        val timerBar = findViewById<ProgressBar>(R.id.turnTimerBar)
        if (state.status != GameState.STATUS_PLAYING || state.turnDeadline == 0L) {
            timerText.visibility = View.GONE
            timerBar.visibility = View.GONE
            return
        }

        val remainingMillis = (state.turnDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
        val remainingSeconds = remainingMillis / 1000f

        // Colour ramps with urgency, so the timer is readable at a glance without having to
        // parse the number.
        // Warning and urgent stay amber and red whatever the accent — they mean something, and a
        // player who picked a red accent should not have a calm timer that already looks urgent.
        val color = when {
            remainingSeconds <= 5f -> resources.getColor(R.color.timer_urgent, theme)
            remainingSeconds <= 10f -> resources.getColor(R.color.timer_warn, theme)
            else -> AccentColor.resolve(this)
        }

        timerText.visibility = View.VISIBLE
        timerText.text = getString(R.string.turn_timer, remainingSeconds.toInt() + 1)
        timerText.setTextColor(color)
        timerText.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 9f * resources.displayMetrics.density
            setColor(resources.getColor(R.color.surface, theme))
            setStroke((1.5f * resources.displayMetrics.density).toInt(), color)
        }

        // The bar drains smoothly rather than stepping once a second: the tick runs every
        // 250ms and the range is in milliseconds, so the movement stays continuous.
        timerBar.visibility = View.VISIBLE
        timerBar.max = state.turnMillis.toInt().coerceAtLeast(1)
        timerBar.progress = remainingMillis.toInt()
        timerBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
        timerBar.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(resources.getColor(R.color.timer_track, theme))

        // Auto-play steps repeatedly until the turn actually ends, rather than firing once.
        // Rolling does not extend the deadline, so a single-shot trigger left the turn stalled
        // forever whenever the clock ran out with rolls still in hand: it rolled once, the
        // deadline stayed in the past, and nothing ever scored. Spacing the steps out gives the
        // dice time to land so the roll is still watchable.
        if (remainingMillis <= 0L && state.isMyTurn(playerId)) {
            val now = System.currentTimeMillis()
            if (now >= nextAutoActionAt) {
                nextAutoActionAt = now + AUTO_ACTION_INTERVAL_MS
                repository.autoPlayTurn(roomCode, state, playerId)
            }
        } else {
            nextAutoActionAt = 0L
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

        // Dice take the colour of whoever is rolling, so a glance at the table tells you whose
        // turn it is. Players on older builds have no colour stored, hence the fallback.
        // setDiceColor is a no-op unless the value actually changed, so this is cheap per frame.
        val activeColor = state.players[state.currentPlayerId]?.diceColor
            ?.takeIf { it != 0 }
            ?: DieTextureAtlas.DEFAULT_COLOR
        dice3DView.setDiceColor(activeColor)

        renderDice(state, myTurn)

        // The scorecard follows whoever's turn it is, so the active player's card is always in
        // view by default. Tapping a tab overrides that for the rest of the current turn; the
        // override clears on the next turn change so focus returns to the new active player.
        if (state.currentPlayerId != lastTurnPlayerId) {
            lastTurnPlayerId = state.currentPlayerId
            viewingPlayerId = null
            // A half-finished confirmation must not survive into someone else's turn.
            scoreConfirm.reset()
        }
        val viewing = viewingPlayerId?.takeIf { state.players.containsKey(it) }
            ?: state.currentPlayerId?.takeIf { state.players.containsKey(it) }
            ?: playerId
        ScorecardTabs.render(
            context = this,
            row = findViewById(R.id.playerTabsRow),
            state = state,
            localPlayerId = playerId,
            viewingPlayerId = viewing
        ) { selectedId ->
            viewingPlayerId = selectedId
            render(state)
        }

        val canScore = myTurn && state.rollsUsed > 0 && viewing == playerId
        scorecardAdapter.update(state, viewing, canScore)

        // The header shows the player's total across every card, so it stays a comparable
        // figure regardless of which card is currently open.
        val total = state.players[viewing]?.grandTotalAllCards(state.cardCount) ?: 0
        findViewById<TextView>(R.id.scorecardTotalText).text = getString(R.string.total_score, total)
    }

    /**
     * Card selector, shown only in multi-card rooms. Each tab reports how many of its 13
     * categories the viewed player has filled, which is the information you need when deciding
     * where a roll should go. Tabs are for choosing a card to view and score into, so they are
     * not reset by turn changes the way the player tabs are.
     */
    private fun renderDice(state: GameState, myTurn: Boolean) {
        val isNewRoll = state.rollsUsed > 0 && state.rollsUsed != lastRollsUsed
        if (isNewRoll) {
            diceRolling = true
            scoreConfirm.reset()
            sound.play(SoundEngine.Sound.ROLL)
            // Dice arrive from wherever the roller is sitting relative to you — your own throws
            // always come from your right, opponents' from their seat around the table.
            dice3DView.rollTo(
                state.dice,
                state.held,
                state.seatAngle(playerId, state.currentPlayerId)
            )
        }
        lastDice = state.dice
        lastRollsUsed = state.rollsUsed

        renderHoldRow(state, myTurn)
        YahtzeeBanner.render(
            context = this,
            banner = findViewById(R.id.yahtzeeBanner),
            state = state,
            playerId = playerId,
            isMyTurn = myTurn,
            suppress = diceRolling
        )
    }

    private fun renderHoldRow(state: GameState, myTurn: Boolean) {
        val holdRow = findViewById<LinearLayout>(R.id.holdRow)
        // Kept in the layout but hidden mid-throw: the values are already known, and showing
        // them would give the result away before the dice land. INVISIBLE rather than GONE so
        // nothing below shifts as they appear.
        holdRow.visibility = if (diceRolling) View.INVISIBLE else View.VISIBLE
        holdRow.removeAllViews()
        state.dice.forEachIndexed { index, value ->
            val chip = Button(this)
            chip.text = value.toString()
            chip.isSelected = state.held.getOrNull(index) == true
            styleHoldChip(chip, chip.isSelected, activeDiceColorOf(state))
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

    /**
     * Keeps the display awake through other players' turns, since a game can sit idle for a
     * while without anyone touching this device.
     */
    private fun applyDisplaySettings() {
        if (AppSettings.keepScreenOn(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun onScoreCategory(card: Int, category: Category) {
        val state = lastState ?: return
        if (!state.isMyTurn(playerId) || state.rollsUsed == 0) return

        // Scoring cannot be undone, so an optional second tap guards against a mis-tap.
        if (scoreConfirm.consumesTap(card, category)) return
        sound.play(SoundEngine.Sound.SCORE)
        // Read before submitting: scoring consumes the Yahtzee, so once the write lands there is
        // nothing left on the table to say what it was worth.
        GameReview.record(this, state, playerId, card, category)
        val earnedBonus = state.yahtzeeStateFor(playerId) == YahtzeeState.BONUS &&
            category != Category.YAHTZEE
        repository.submitScore(roomCode, state, category, playerId, card)
        if (earnedBonus) {
            sound.play(SoundEngine.Sound.WIN)
            Toast.makeText(this, R.string.yahtzee_bonus_awarded, Toast.LENGTH_LONG).show()
        }
    }

    private fun showGameOver(state: GameState) {
        sound.play(SoundEngine.Sound.WIN)
        submitToLeaderboard(state)
        val winnerName = state.players[state.winnerId]?.name ?: "?"
        AlertDialog.Builder(this)
            .setTitle(R.string.game_over)
            .setMessage(getString(R.string.winner_is, winnerName))
            .setPositiveButton(R.string.play_again) { _, _ ->
                // Resetting the room sends every client back to the roll-off, so this returns to
                // the lobby to follow it rather than sitting on a finished board.
                repository.rematch(roomCode, state)
                finish()
            }
            .setNeutralButton(R.string.see_review) { _, _ ->
                startActivity(android.content.Intent(this, com.yahtzee.online.ui.ReviewActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.leave_game) { _, _ ->
                // Only leaving stops the watch. A rematch keeps the same room going, so the
                // game stays tracked and the next turn still raises a notification.
                ActiveGamesStore.untrack(this, roomCode)
                TurnNotifier.clear(this, roomCode)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /** Records this player's own final score on the global board — never an opponent's. */
    private fun submitToLeaderboard(state: GameState) {
        val me = state.players[playerId] ?: return
        // grandTotalAllCards rather than reading the score map directly: the keys are
        // "card:CATEGORY", so parsing them as bare category names — as this did — matched
        // nothing, and every online game posted a total of zero (silently dropped by the
        // repository's own score <= 0 guard) or, with a Yahtzee bonus, just the bonus.
        val total = me.grandTotalAllCards(state.cardCount)
        LeaderboardRepository().submitScore(
            playerId = PlayerProfile.getId(this),
            name = PlayerProfile.getName(this).ifEmpty { me.name },
            score = total
        )
        PlayerStats.record(
            context = this,
            player = me,
            cardCount = state.cardCount,
            mode = PlayerStats.Mode.ONLINE,
            won = state.winnerId == playerId,
            opponents = state.playerOrder.size - 1
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
        listener?.let { repository.stopListening(roomCode, it) }
        timerHandler.removeCallbacks(timerTick)
    }
}
