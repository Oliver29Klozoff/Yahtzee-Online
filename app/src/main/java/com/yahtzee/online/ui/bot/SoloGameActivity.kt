package com.yahtzee.online.ui.bot

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.yahtzee.online.R
import com.yahtzee.online.audio.SoundEngine
import com.yahtzee.online.bot.LocalGameEngine
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.DailyChallenge
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.PlayerStats
import com.yahtzee.online.game.SavedSoloGame
import com.yahtzee.online.game.SoloGameStore
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.YahtzeeState
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.game.yahtzeeStateFor
import com.yahtzee.online.net.LeaderboardRepository
import com.yahtzee.online.game.GameReview
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.ReviewActivity
import com.yahtzee.online.ui.game.ScorecardAdapter
import com.yahtzee.online.ui.game.RollOffRow
import com.yahtzee.online.ui.game.ScoreConfirm
import com.yahtzee.online.ui.game.ScorecardTabs
import com.yahtzee.online.ui.game.YahtzeeBanner
import com.yahtzee.online.ui.game.activeDiceColorOf
import com.yahtzee.online.ui.game.styleHoldChip

class SoloGameActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_BOT_COUNT = "bot_count"
        const val EXTRA_CARD_COUNT = "card_count"
        const val EXTRA_RESUME = "resume"

        /**
         * The day id when this is a daily challenge. Daily games run through this same screen:
         * they are a solo game with no opponents, one card, and the day's tape supplying the
         * dice, so everything else here — the scorecard, the hold row, the 3D dice — is already
         * exactly what is wanted.
         */
        const val EXTRA_DAILY_ID = "daily_id"
        private const val ROLL_SETTLE_DELAY_MS = 1300L

        /** How long the finished roll-off is held before play begins. */
        private const val ROLL_OFF_REVEAL_MS = 4000L
    }

    private lateinit var engine: LocalGameEngine
    private lateinit var dice3DView: Dice3DView
    private val sound by lazy { SoundEngine(this) }
    private lateinit var scorecardAdapter: ScorecardAdapter
    private var viewingPlayerId: String? = null

    private var lastTurnPlayerId: String? = null
    private val botHandler = Handler(Looper.getMainLooper())
    private var lastRollsUsed = 0

    /** True while the dice are mid-throw, so the values are not revealed before they land. */
    private var diceRolling = false
    private var gameOverShown = false
    private var botTurnScheduled = false
    private val scoreConfirm by lazy { ScoreConfirm(this) }
    private var rollOffDiceShown = false
    private var botRollOffScheduled = false
    private var rollOffFinishScheduled = false

    /** Non-null when this is a daily challenge, holding the day whose tape is in play. */
    private var dailyId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val name = intent.getStringExtra(EXTRA_PLAYER_NAME) ?: "You"
        val botCount = intent.getIntExtra(EXTRA_BOT_COUNT, 1).coerceIn(1, 4)
        val cardCount = intent.getIntExtra(EXTRA_CARD_COUNT, 1).coerceIn(1, 6)
        // Resume a game in progress if there is one, rather than dealing over the top of it.
        val saved = if (intent.getBooleanExtra(EXTRA_RESUME, false)) SoloGameStore.loadResumable(this) else null
        // A resumed daily keeps its own day, so a game left open overnight finishes against the
        // tape it was started on rather than silently switching to today's dice mid-game.
        dailyId = saved?.dailyId ?: intent.getStringExtra(EXTRA_DAILY_ID)
        val daily = dailyId != null

        engine = LocalGameEngine(
            name,
            if (daily) 0 else saved?.botIds?.size ?: botCount,
            DicePreferences.getColor(this),
            if (daily) 1 else saved?.cardCount ?: cardCount,
            saved?.botSkill ?: AppSettings.botSkill(this),
            saved,
            dailyId?.let { DailyChallenge.tapeFor(it) }
        )

        // Solo games have no turn timer / no timer UI needed.
        findViewById<View>(R.id.turnTimerText).visibility = View.GONE
        findViewById<View>(R.id.turnTimerBar).visibility = View.GONE

        dice3DView = findViewById(R.id.dice3DView)
        dice3DView.setTableColor(AppSettings.tableColor(this))
        dice3DView.setTableLogo(TableLogoStore.mode(this))
        dice3DView.setMotionScale(AppSettings.diceMotion(this).durationScale)
        // The dice report their own landing, so the knock lands with the visual, not the throw.
        dice3DView.setOnSettledListener {
            sound.play(SoundEngine.Sound.LAND)
            // Redraw so the values appear only once the dice have actually come to rest.
            if (diceRolling) {
                diceRolling = false
                render(engine.state)
            }
        }
        if (AppSettings.keepScreenOn(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        scorecardAdapter = ScorecardAdapter(this) { card, category ->
            if (scoreConfirm.consumesTap(card, category)) return@ScorecardAdapter
            sound.play(SoundEngine.Sound.SCORE)
            // Read before submitting: scoring is what consumes the Yahtzee, so afterwards there
            // is no longer anything on the table to tell the player what they just earned.
            val earnedBonus = engine.state.yahtzeeStateFor(engine.humanPlayerId) == YahtzeeState.BONUS &&
                category != Category.YAHTZEE
            // Noted before submitting: what made the choice a choice is the boxes that were still
            // open, and scoring closes one of them.
            if (engine.state.currentPlayerId == engine.humanPlayerId) {
                GameReview.record(this, engine.state, engine.humanPlayerId, card, category)
            }
            engine.submitScore(category, card)
            if (earnedBonus) announceYahtzeeBonus()
        }
        findViewById<ListView>(R.id.scorecardList).adapter = scorecardAdapter

        // Two ways to roll the same roll: the button, and flinging the dice across the table.
        // The gesture is a shortcut to the same call rather than a second path into the game, so
        // neither can do anything the other could not.
        fun rollIfAllowed() {
            val state = engine.state
            if (engine.isBotTurn() || state.rollsUsed >= MAX_ROLLS_PER_TURN) return
            engine.rollDice()
        }
        findViewById<Button>(R.id.rollButton).setOnClickListener { rollIfAllowed() }
        dice3DView.setOnThrowListener { rollIfAllowed() }

        // A resumed game keeps the record it has already built; a new one starts empty.
        if (saved == null) GameReview.begin(this)

        engine.setOnChangeListener {
            persistGame()
            render(engine.state)
        }
        persistGame()
        render(engine.state)
    }

    /**
     * The pre-game roll for turn order. The player rolls, then each bot follows on a delay so
     * their dice are watched arriving one at a time rather than all appearing at once.
     *
     * The dice view drops to a single die for this and goes back to five when play starts, and
     * the scoring UI stays hidden throughout — there is nothing to score yet.
     */
    private fun renderRollOff(state: GameState): Boolean {
        val inRollOff = state.status == GameState.STATUS_ROLL_OFF
        // The hold row is idle during the roll-off, so it carries everyone's dice instead.
        findViewById<View>(R.id.holdRow).visibility = View.VISIBLE
        findViewById<View>(R.id.scorecardList).visibility = if (inRollOff) View.GONE else View.VISIBLE
        if (!inRollOff) {
            if (rollOffDiceShown) {
                rollOffDiceShown = false
                dice3DView.setDieCount(5)
            }
            return false
        }

        if (!rollOffDiceShown) {
            rollOffDiceShown = true
            dice3DView.setDieCount(1)
        }

        val pending = engine.rollOffPending()
        val myRoll = state.openingRolls[engine.humanPlayerId]
        val tieNotice = if (state.openingRollTied.isNotEmpty()) {
            getString(R.string.roll_off_tied) + " "
        } else ""
        findViewById<TextView>(R.id.turnStatusText).text = tieNotice + getString(R.string.roll_off_title)
        findViewById<TextView>(R.id.rollsLeftText).text = ""
        RollOffRow.render(
            context = this,
            row = findViewById(R.id.holdRow),
            state = state,
            localPlayerId = engine.humanPlayerId
        )

        val rollButton = findViewById<Button>(R.id.rollButton)
        val myTurnToRoll = engine.humanPlayerId in pending
        rollButton.visibility = if (myTurnToRoll) View.VISIBLE else View.GONE
        rollButton.isEnabled = myTurnToRoll
        rollButton.text = getString(R.string.roll_for_first)
        rollButton.setOnClickListener {
            val value = engine.rollForFirst(engine.humanPlayerId) ?: return@setOnClickListener
            animateRollOffDie(engine.humanPlayerId, value)
            scheduleBotRollOff()
        }

        // Covers the case where the player rolled last: nothing is left to schedule, so the
        // hold has to be started from here.
        if (engine.rollOffReady()) scheduleRollOffFinish()

        // Bots roll on their own once the player has, or straight away if the tie left them
        // rolling without the player.
        if (myRoll != null || !myTurnToRoll) scheduleBotRollOff()
        return true
    }

    private fun scheduleBotRollOff() {
        if (botRollOffScheduled) return
        val next = engine.rollOffPending().firstOrNull { it != engine.humanPlayerId }
        if (next == null) {
            scheduleRollOffFinish()
            return
        }
        botRollOffScheduled = true
        botHandler.postDelayed({
            botRollOffScheduled = false
            val value = engine.rollForFirst(next) ?: return@postDelayed
            animateRollOffDie(next, value)
            scheduleBotRollOff()
        }, ROLL_SETTLE_DELAY_MS)
    }

    /**
     * Holds the completed row on screen before play begins, so the last roll — almost always a
     * bot's — is actually seen rather than flashing past as the game opens.
     */
    private fun scheduleRollOffFinish() {
        if (!engine.rollOffReady() || rollOffFinishScheduled) return
        rollOffFinishScheduled = true
        botHandler.postDelayed({
            rollOffFinishScheduled = false
            engine.finishRollOff()
        }, ROLL_OFF_REVEAL_MS)
    }

    private fun animateRollOffDie(playerId: String, value: Int) {
        val color = engine.state.players[playerId]?.diceColor?.takeIf { it != 0 }
            ?: DieTextureAtlas.DEFAULT_COLOR
        dice3DView.setDiceColor(color)
        dice3DView.rollTo(
            listOf(value),
            listOf(false),
            engine.state.seatAngle(engine.humanPlayerId, playerId)
        )
    }

    private fun render(state: GameState) {
        if (renderRollOff(state)) return

        val myTurn = !engine.isBotTurn()
        val currentPlayerName = state.players[state.currentPlayerId]?.name ?: ""

        findViewById<TextView>(R.id.turnStatusText).text =
            if (myTurn) getString(R.string.your_turn) else getString(R.string.waiting_for_turn, currentPlayerName)

        findViewById<TextView>(R.id.rollsLeftText).text =
            getString(R.string.rolls_left, MAX_ROLLS_PER_TURN - state.rollsUsed)

        val rollButton = findViewById<Button>(R.id.rollButton)
        rollButton.isEnabled = myTurn && state.rollsUsed < MAX_ROLLS_PER_TURN
        rollButton.visibility = if (myTurn) View.VISIBLE else View.GONE
        // Reclaim the button from the roll-off, which borrows it for its own handler.
        rollButton.text = getString(R.string.roll_dice)
        rollButton.setOnClickListener {
            if (engine.isBotTurn() || engine.state.rollsUsed >= MAX_ROLLS_PER_TURN) return@setOnClickListener
            engine.rollDice()
        }

        // Dice take the colour of whoever is rolling, so you can tell at a glance whether the
        // table belongs to you or to a bot. No-op unless the value actually changed.
        val activeColor = state.players[state.currentPlayerId]?.diceColor
            ?.takeIf { it != 0 }
            ?: DieTextureAtlas.DEFAULT_COLOR
        dice3DView.setDiceColor(activeColor)

        renderDice(state)
        renderHoldRow(state, myTurn)
        YahtzeeBanner.render(
            context = this,
            banner = findViewById(R.id.yahtzeeBanner),
            state = state,
            playerId = engine.humanPlayerId,
            isMyTurn = myTurn,
            suppress = diceRolling
        )

        // The scorecard follows whoever's turn it is, so you watch each bot's card fill in as
        // it plays. Tapping a tab overrides that for the rest of the current turn; the override
        // clears on the next turn change so focus returns to the new active player.
        if (state.currentPlayerId != lastTurnPlayerId) {
            lastTurnPlayerId = state.currentPlayerId
            viewingPlayerId = null
            // A half-finished confirmation must not survive into someone else's turn.
            scoreConfirm.reset()
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
            diceRolling = true
            scoreConfirm.reset()
            sound.play(SoundEngine.Sound.ROLL)
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
                    engine.toggleHold(index)
                }
            }
            chip.isEnabled = myTurn && state.rollsUsed in 1 until MAX_ROLLS_PER_TURN
            holdRow.addView(chip)
        }
    }

    /**
     * Starts a fresh game with the same opponents and format. Relaunching the activity is
     * simpler than resetting the engine in place: every piece of per-game state here — the
     * viewed card, whose scorecard is showing, the pending bot turn — would otherwise have to be
     * unwound by hand, and missing one leaves the next game subtly wrong.
     */
    private fun restartSoloGame() {
        botHandler.removeCallbacksAndMessages(null)
        startActivity(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Writes the game out after every move. Saving on a lifecycle callback instead would miss
     * the case that matters most — the process being killed in the background with no warning.
     */
    private fun persistGame() {
        val state = engine.state
        if (state.status == GameState.STATUS_FINISHED) {
            SoloGameStore.clear(this)
            return
        }
        SoloGameStore.save(
            this,
            SavedSoloGame(
                humanPlayerId = engine.humanPlayerId,
                botIds = state.playerOrder.filterNot { it == engine.humanPlayerId },
                cardCount = state.cardCount,
                botSkill = AppSettings.botSkill(this),
                state = state,
                dailyId = dailyId
            )
        )
    }

    /**
     * Called out with the win sound rather than the ordinary scoring click: a hundred points is
     * the largest single thing that happens in a game of Yahtzee and used to happen in silence.
     */
    private fun announceYahtzeeBonus() {
        sound.play(SoundEngine.Sound.WIN)
        Toast.makeText(this, R.string.yahtzee_bonus_awarded, Toast.LENGTH_LONG).show()
    }

    private fun showGameOver(state: GameState) {
        sound.play(SoundEngine.Sound.WIN)
        val me = state.players[engine.humanPlayerId]
        val score = me?.grandTotalAllCards(state.cardCount) ?: 0
        val day = dailyId

        if (me != null) {
            // Solo results count too — the board ranks people, not game modes.
            LeaderboardRepository().submitScore(
                playerId = PlayerProfile.getId(this),
                name = PlayerProfile.getName(this).ifEmpty { me.name },
                score = score
            )
            PlayerStats.record(
                context = this,
                player = me,
                cardCount = state.cardCount,
                mode = if (day != null) PlayerStats.Mode.DAILY else PlayerStats.Mode.SOLO,
                // A daily challenge has nobody to beat, so it is never counted as a win — it
                // would otherwise inflate the win rate with games that had no opponent.
                won = day == null && state.winnerId == engine.humanPlayerId,
                opponents = state.playerOrder.size - 1
            )
        }

        if (day != null) {
            showDailyResult(day, score)
            return
        }

        val winnerName = state.players[state.winnerId]?.name ?: "?"
        AlertDialog.Builder(this)
            .setTitle(R.string.game_over)
            .setMessage(getString(R.string.winner_is, winnerName))
            .setPositiveButton(R.string.play_again) { _, _ -> restartSoloGame() }
            .setNeutralButton(R.string.see_review) { _, _ -> openReview() }
            .setNegativeButton(R.string.leave_game) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * Leaves the finished board behind rather than sitting under the review: coming back from it
     * would land on a game that is over, with a game-over box already dismissed.
     */
    private fun openReview() {
        startActivity(Intent(this, ReviewActivity::class.java))
        finish()
    }

    /**
     * The daily result: posted to the day's board, marked played so it cannot be re-rolled for a
     * better number, and offered as something to share. No "play again" — that is the whole point.
     */
    private fun showDailyResult(day: String, score: Int) {
        DailyChallenge.recordToday(this, score)
        SoloGameStore.clear(this)
        LeaderboardRepository().submitDailyScore(
            dayId = day,
            playerId = PlayerProfile.getId(this),
            name = PlayerProfile.getName(this).ifEmpty { "Player" },
            score = score
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.daily_complete)
            .setMessage(getString(R.string.daily_result, score, day))
            .setPositiveButton(R.string.share_result) { _, _ -> shareDailyResult(day, score) }
            // Worth more on the daily than anywhere else: everyone played the same dice, so the
            // gap between your score and someone else's is entirely in these decisions.
            .setNeutralButton(R.string.see_review) { _, _ -> openReview() }
            .setNegativeButton(R.string.done) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun shareDailyResult(day: String, score: Int) {
        val text = getString(R.string.daily_share_text, day, score)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text),
                getString(R.string.share_result)
            )
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        botHandler.removeCallbacksAndMessages(null)
        sound.release()
        botHandler.removeCallbacksAndMessages(null)
    }
}
