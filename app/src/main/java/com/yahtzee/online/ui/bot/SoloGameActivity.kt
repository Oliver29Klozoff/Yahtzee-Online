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
import com.yahtzee.online.bot.BotReactions
import com.yahtzee.online.bot.LocalGameEngine
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.DailyChallenge
import com.yahtzee.online.game.Duel
import com.yahtzee.online.net.DuelRepository
import com.yahtzee.online.ui.duel.DuelActivity
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.PlayedFormats
import com.yahtzee.online.game.PlayerStats
import com.yahtzee.online.game.Projection
import com.yahtzee.online.game.SavedSoloGame
import com.yahtzee.online.game.SoloGameStore
import com.yahtzee.online.game.decidedWinner
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.YahtzeeState
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.game.yahtzeeStateFor
import com.yahtzee.online.net.LeaderboardRepository
import com.yahtzee.online.net.ProfileRepository
import com.yahtzee.online.game.GameReview
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.LastTurn
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.ReviewActivity
import com.yahtzee.online.ui.game.ScorecardAdapter
import com.yahtzee.online.ui.game.ScorecardSection
import com.yahtzee.online.ui.game.RollOffRow
import com.yahtzee.online.ui.game.ScoreConfirm
import com.yahtzee.online.ui.game.ScorecardTabs
import com.yahtzee.online.ui.game.YahtzeeBanner
import com.yahtzee.online.ui.game.activeDiceColorOf
import com.yahtzee.online.ui.game.GameLayout
import com.yahtzee.online.ui.game.EmojiBurst
import com.yahtzee.online.ui.game.OffTheRip
import com.yahtzee.online.ui.game.ScoreAnnounce
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

        /**
         * The duel code when this is a round of a duel. A duel round is the same shape as a daily
         * challenge — alone, one card, dice off a fixed tape — so it runs through this screen too;
         * the only differences are where the tape's seed comes from and where the score is posted.
         */
        const val EXTRA_DUEL_CODE = "duel_code"

        /** Set when this game settles a tournament fixture against a bot. */
        const val EXTRA_TOURNEY_CODE = "tourney_code"
        const val EXTRA_MATCH_ID = "tourney_match"
        /** The bracket name of the bot in that fixture, so both places call it the same thing. */
        const val EXTRA_BOT_NAME = "bot_name"
        private const val ROLL_SETTLE_DELAY_MS = 1300L

        /** How long the finished roll-off is held before play begins. */
        private const val ROLL_OFF_REVEAL_MS = 4000L
    }

    private lateinit var engine: LocalGameEngine
    private lateinit var dice3DView: Dice3DView
    private val sound by lazy { SoundEngine(this) }
    private lateinit var scorecardAdapter: ScorecardAdapter

    /** The lower half when the card is split in two. Null when one list holds all of it. */
    private var lowerAdapter: ScorecardAdapter? = null

    /** Card count the scorecard is currently built for; -1 until it has been built. */
    private var scorecardCards = -1
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

    /**
     * The state as of the last announcement, so a newly filled box can be spotted.
     *
     * Kept separately from whatever the renderer last drew: the engine reports a change on every
     * roll and every held die too, and comparing against the previous *announcement* means a
     * score is found once rather than re-found on each of the updates that follow it.
     */
    private var lastAnnouncedState: GameState? = null

    /** Non-null when this is a daily challenge, holding the day whose tape is in play. */
    private var dailyId: String? = null

    /** Non-null when this is a round of a duel, holding the code whose tape is in play. */
    private var duelCode: String? = null

    /** The turn just played, and the render it was spotted against. See [LastTurn]. */
    private var lastTurn: LastTurn.Scored? = null
    private var lastRenderedState: GameState? = null

    /** The tournament fixture this game settles, or null for an ordinary solo game. */
    private var tourneyCode: String? = null
    private var matchId: String? = null

    /**
     * True when the dice come off a fixed tape rather than being rolled freely.
     *
     * A taped game is always solo and always one card — there is nobody else at the table and
     * every player of it has to face the identical thirteen turns for the comparison to mean
     * anything.
     */
    private val fixedTape: Boolean get() = dailyId != null || duelCode != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val name = intent.getStringExtra(EXTRA_PLAYER_NAME) ?: "You"
        val botCount = intent.getIntExtra(EXTRA_BOT_COUNT, 1).coerceIn(1, 4)
        val cardCount = intent.getIntExtra(EXTRA_CARD_COUNT, 1).coerceIn(1, 6)
        tourneyCode = intent.getStringExtra(EXTRA_TOURNEY_CODE)?.takeIf { it.isNotEmpty() }
        matchId = intent.getStringExtra(EXTRA_MATCH_ID)?.takeIf { it.isNotEmpty() }

        // Resume a game in progress if there is one, rather than dealing over the top of it.
        //
        // A tournament fixture always resumes, without waiting to be asked. Every other solo game
        // is one the player chose to continue from a button that knows a game is there; a fixture
        // is opened by tapping it in the bracket, which is the same tap whether it has been
        // started or not — so left to the flag it would deal a fresh game over a match already
        // half played, which is exactly the way one gets lost.
        val fixture = tourneyCode?.let { code -> matchId?.let { SoloGameStore.loadMatch(this, code, it) } }
        val saved = fixture
            ?: if (intent.getBooleanExtra(EXTRA_RESUME, false)) SoloGameStore.loadResumable(this) else null
        // A resumed daily keeps its own day, so a game left open overnight finishes against the
        // tape it was started on rather than silently switching to today's dice mid-game.
        dailyId = saved?.dailyId ?: intent.getStringExtra(EXTRA_DAILY_ID)
        duelCode = saved?.duelCode ?: intent.getStringExtra(EXTRA_DUEL_CODE)
        val taped = fixedTape

        engine = LocalGameEngine(
            name,
            if (taped) 0 else saved?.botIds?.size ?: botCount,
            DicePreferences.getColor(this),
            if (taped) 1 else saved?.cardCount ?: cardCount,
            saved?.botSkill ?: AppSettings.botSkill(this),
            saved,
            dailyId?.let { DailyChallenge.tapeFor(it) }
                ?: duelCode?.let { Duel.tapeFor(it) }
,
            listOfNotNull(intent.getStringExtra(EXTRA_BOT_NAME)?.takeIf { it.isNotEmpty() })
        )

        // From here on this screen can be rebuilt from the saved game.
        //
        // That matters now that turning the phone restarts the activity rather than stretching
        // the layout: without this the restart would arrive with no resume flag, deal a brand new
        // game, and write it over the one being played. The game is persisted after every move,
        // so what comes back is exactly what was on screen.
        intent.putExtra(EXTRA_RESUME, true)
        setIntent(intent)

        // Solo games have no turn timer / no timer UI needed.
        findViewById<View>(R.id.turnTimerText).visibility = View.GONE
        findViewById<View>(R.id.turnTimerBar).visibility = View.GONE

        dice3DView = findViewById(R.id.dice3DView)
        GameLayout.fitTableToFontScale(dice3DView)
        dice3DView.setPipStyle(DicePreferences.pipStyle(this))
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

        configureScorecard(engine.state.cardCount)

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
            // A bot's whole turn goes by in a couple of seconds, so what it actually did with the
            // roll is the easiest thing in the game to miss entirely.
            ScoreAnnounce.detect(lastAnnouncedState, engine.state, engine.humanPlayerId)?.let { taken ->
                ScoreAnnounce.show(this, findViewById(R.id.reactionPopup), taken)
            }
            reactToTurn(lastAnnouncedState, engine.state)
            lastAnnouncedState = engine.state
            persistGame()
            render(engine.state)
        }
        lastAnnouncedState = engine.state
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

    /**
     * Scores a box. Lifted out of the adapter's lambda so both halves of a split card can call
     * the same code rather than the landscape half quietly getting a copy that drifts.
     */
    /**
     * Points the scorecard at one list or two, depending on how many cards are in play.
     *
     * Rebuilt rather than reconfigured because an adapter decides which rows it owns when it is
     * created. Guarded on the count so it runs when the answer changes rather than on every
     * engine update, which would rebuild both lists on every held die.
     */
    private fun configureScorecard(cardCount: Int) {
        if (scorecardCards == cardCount) return
        scorecardCards = cardCount

        val lowerList = findViewById<ListView>(R.id.scorecardListLower)
        val split = GameLayout.splitsScorecard(cardCount, GameLayout.isLandscape(lowerList))

        scorecardAdapter = ScorecardAdapter(
            this,
            if (split) ScorecardSection.UPPER else ScorecardSection.BOTH
        ) { card, category -> onScoreCategory(card, category) }
        findViewById<ListView>(R.id.scorecardList).adapter = scorecardAdapter

        lowerList.visibility = if (split) View.VISIBLE else View.GONE
        lowerAdapter = if (split) {
            ScorecardAdapter(this, ScorecardSection.LOWER) { card, category ->
                onScoreCategory(card, category)
            }.also { lowerList.adapter = it }
        } else {
            null
        }
    }

    private fun onScoreCategory(card: Int, category: Category) {
        if (scoreConfirm.consumesTap(card, category)) return
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
        // Read before the write, which resets the dice and the roll count.
        val offTheRip = engine.state.currentPlayerId == engine.humanPlayerId &&
            OffTheRip.qualifies(engine.state.rollsUsed, category, engine.state.dice)
        val ripPoints = Scoring.score(category, engine.state.dice)
        engine.submitScore(category, card)
        if (offTheRip) {
            OffTheRip.show(this, findViewById(R.id.reactionPopup), category, ripPoints)
        }
        if (earnedBonus) announceYahtzeeBonus()
    }

    private fun render(state: GameState) {
        if (renderRollOff(state)) return

        // The finished turn against the one before it. An online room gets its two snapshots
        // from the database; here they are simply consecutive renders, which works because the
        // engine replaces its state rather than editing it in place.
        LastTurn.detect(lastRenderedState, state)?.let { lastTurn = it }
        lastRenderedState = state

        val myTurn = !engine.isBotTurn()

        // Between turns the screen stays on the turn just played: the bot's score, in the bot's
        // colour, under its name, until somebody rolls. Against a bot this is the only chance to
        // see what it did — it takes its turn while you are looking at the table, not at a card.
        val handover = lastTurn?.takeIf { LastTurn.isHandover(state) }
        val shownPlayerId = handover?.playerId ?: state.currentPlayerId
        val shownName = state.players[shownPlayerId]?.name ?: ""

        findViewById<TextView>(R.id.turnStatusText).text = when {
            handover != null && shownPlayerId == engine.humanPlayerId ->
                getString(R.string.turn_you_just_scored)
            handover != null -> getString(R.string.turn_just_scored, shownName)
            myTurn -> getString(R.string.your_turn)
            else -> getString(R.string.waiting_for_turn, shownName)
        }

        renderTurnSummary(state, handover)

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
        // table belongs to you or to a bot. No-op unless the value actually changed. Frozen
        // through the hand-over so the table does not change hands before anything has happened.
        val activeColor = state.players[shownPlayerId]?.diceColor
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
        configureScorecard(state.cardCount)
        scorecardAdapter.update(state, viewing, canScore)
        lowerAdapter?.update(state, viewing, canScore)

        val total = state.players[viewing]?.grandTotalAllCards(state.cardCount) ?: 0
        findViewById<TextView>(R.id.scorecardTotalText).text = getString(R.string.total_score, total)

        val projection = findViewById<TextView>(R.id.projectionText)
        val viewed = state.players[viewing]
        val showProjection = AppSettings.showProjection(this) &&
            viewed != null &&
            state.status == GameState.STATUS_PLAYING
        projection.visibility = if (showProjection) View.VISIBLE else View.GONE
        if (showProjection && viewed != null) {
            projection.text =
                getString(R.string.projected, Projection.forPlayer(viewed, state.cardCount))
        }

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

    /**
     * Lets the bots have a word about the turn that just ended.
     *
     * Straight onto the burst layer rather than through the room: there is no room. A solo game
     * exists only on this device, so the emoji is thrown here the moment it is earned instead of
     * being written somewhere and read back.
     *
     * Their own turns and yours both. A bot applauding a Yahtzee you just rolled is the whole
     * reason for this — playing bots was silent in a way playing people never is.
     */
    private fun reactToTurn(previous: GameState?, current: GameState) {
        val scored = LastTurn.detect(previous, current) ?: return
        val layer = findViewById<android.widget.FrameLayout>(R.id.emojiBurstLayer)

        if (scored.playerId != engine.humanPlayerId) {
            BotReactions.forOwnScore(scored.category, scored.points)
                ?.let { EmojiBurst.spawn(layer, it, scored.playerName) }
            return
        }

        // Yours: the first bot at the table answers for all of them, so a row of opponents does
        // not applaud in chorus.
        val responder = current.playerOrder
            .firstOrNull { it != engine.humanPlayerId }
            ?.let { current.players[it] }
            ?: return
        BotReactions.forOtherScore(scored.category, scored.points)
            ?.let { EmojiBurst.spawn(layer, it, responder.name) }
    }

    /**
     * Puts the finished turn's score where the hold chips normally sit.
     *
     * They swap rather than stack, so nothing below moves as the turn changes hands.
     */
    private fun renderTurnSummary(state: GameState, scored: LastTurn.Scored?) {
        val summary = findViewById<TextView>(R.id.turnSummaryText)
        if (scored == null) {
            summary.visibility = View.GONE
            return
        }
        summary.visibility = View.VISIBLE
        summary.text = getString(R.string.turn_summary_score, scored.label, scored.points)
        summary.setTextColor(
            state.players[scored.playerId]?.diceColor?.takeIf { it != 0 }
                ?: DieTextureAtlas.DEFAULT_COLOR
        )
    }

    private fun renderHoldRow(state: GameState, myTurn: Boolean) {
        val holdRow = findViewById<LinearLayout>(R.id.holdRow)
        // Kept in the layout but hidden mid-throw: the values are already known, and showing
        // them would give the result away before the dice land. INVISIBLE rather than GONE so
        // nothing below shifts as they appear.
        // The summary takes this space between turns, so the two never show at once.
        holdRow.visibility = when {
            lastTurn != null && LastTurn.isHandover(state) -> View.GONE
            diceRolling -> View.INVISIBLE
            else -> View.VISIBLE
        }
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
            clearSaved()
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
                dailyId = dailyId,
                duelCode = duelCode,
                tourneyCode = tourneyCode,
                matchId = matchId
            )
        )
    }

    /** Drops the saved game from whichever slot this one occupies. */
    private fun clearSaved() {
        val code = tourneyCode
        val match = matchId
        if (code != null && match != null) SoloGameStore.clearMatch(this, code, match)
        else SoloGameStore.clear(this)
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
            PlayedFormats.record(this, state.cardCount)
            LeaderboardRepository().submitRankedScore(
                cardCount = state.cardCount,
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
                // would otherwise inflate the win rate with games that had no opponent. The same
                // goes for a duel round: the opponent may not have played yet, and whether this
                // was a win is not knowable here.
                won = !fixedTape && state.winnerId == engine.humanPlayerId,
                opponents = state.playerOrder.size - 1
            )
        }

        reportTournamentResult(score, state)
        ProfileRepository(this).push()

        duelCode?.let {
            showDuelResult(it, score)
            return
        }

        if (day != null) {
            showDailyResult(day, score)
            return
        }

        val winnerName = state.decidedWinner()?.name ?: getString(R.string.nobody)
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

    /**
     * The duel round is over: post the number and hand the player back to the duel.
     *
     * There is deliberately no score shown in a dialog here. The duel screen is where the score
     * means anything — next to whoever else has played — and stopping to announce it in isolation
     * first would be announcing half a result.
     */
    private fun showDuelResult(duel: String, score: Int) {
        SoloGameStore.clear(this)
        DuelRepository(this).submitScore(
            code = duel,
            name = PlayerProfile.getName(this).ifEmpty { "Player" },
            score = score
        )

        startActivity(
            Intent(this, DuelActivity::class.java)
                .putExtra(DuelActivity.EXTRA_DUEL_CODE, duel)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
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

    /**
     * Sends a finished bot match back to the bracket it was played for.
     *
     * A tournament fixture against a bot has no room and no second phone, so it is an ordinary
     * solo game that happens to know which fixture it settles. The seats are read off the match
     * rather than off this game, because the draw decided which side each of them is on and this
     * screen has no idea.
     */
    private fun reportTournamentResult(score: Int, state: com.yahtzee.online.game.GameState) {
        val code = intent.getStringExtra(EXTRA_TOURNEY_CODE).orEmpty()
        val matchId = intent.getStringExtra(EXTRA_MATCH_ID).orEmpty()
        if (code.isEmpty() || matchId.isEmpty()) return

        val botScore = state.players.values
            .firstOrNull { it.id != engine.humanPlayerId }
            ?.grandTotalAllCards(state.cardCount) ?: 0
        val me = com.yahtzee.online.game.PlayerProfile.getId(this)
        com.yahtzee.online.net.TournamentRepository(this).reportFrom(code, matchId) { aId, _ ->
            if (aId == me) score to botScore else botScore to score
        }
    }

}
