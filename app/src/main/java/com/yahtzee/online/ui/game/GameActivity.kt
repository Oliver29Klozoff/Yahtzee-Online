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
import com.yahtzee.online.game.NudgeSeen
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.PlayedFormats
import com.yahtzee.online.game.PlayerStats
import com.yahtzee.online.game.Projection
import com.yahtzee.online.game.Rivalries
import com.yahtzee.online.game.RivalryResult
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

        /** How long before the same player can be nudged again from this device. */
        private const val NUDGE_COOLDOWN_MS = 60_000L
    }

    private val repository by lazy { GameRepository(this) }
    private lateinit var roomCode: String
    private lateinit var playerId: String
    private var listener: ValueEventListener? = null
    private lateinit var scorecardAdapter: ScorecardAdapter

    /** The lower half when the card is split in two. Null when one list holds all of it. */
    private var lowerAdapter: ScorecardAdapter? = null

    /** Card count the scorecard is currently built for; -1 until it has been built. */
    private var scorecardCards = -1
    private var viewingPlayerId: String? = null

    private val scoreConfirm by lazy { ScoreConfirm(this) }
    private var lastTurnPlayerId: String? = null
    private var lastState: GameState? = null
    private var gameOverShown = false
    private var lastDice: List<Int>? = null
    private var lastRollsUsed = 0

    /** Newest reaction already shown, so an unrelated room update does not replay it. */
    private var lastReactionAt = -1L

    private val chatSheet by lazy { ChatSheet(this) }

    /**
     * When this screen last showed the chat. Messages after it are unread.
     *
     * Starts at the current time rather than at zero: opening a game you have been playing for
     * days should not greet you with a badge counting every word ever said in it.
     */
    private var chatSeenAt = System.currentTimeMillis()

    /** Throttles outgoing nudges, and tracks the newest one aimed at this device. */
    private var lastNudgeSentAt = 0L
    private var lastNudgeSeenAt = 0L
    private var nudgeAdopted = false

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
                lastState?.let { render(it) }
            }
        }

        // Built with one card assumed, and rebuilt if the room turns out to be playing more.
        configureScorecard(1)

        // Button or fling, both reach the same call under the same guard.
        fun rollIfAllowed() {
            val state = lastState ?: return
            if (!state.isMyTurn(playerId) || state.rollsUsed >= MAX_ROLLS_PER_TURN) return
            repository.rollDice(roomCode, state.dice, state.held, state.rollsUsed, state.turnMillis)
        }
        findViewById<Button>(R.id.rollButton).setOnClickListener { rollIfAllowed() }
        dice3DView.setOnThrowListener { rollIfAllowed() }

        // Reactions and chat only exist where there is somebody on the other end.
        findViewById<View>(R.id.socialRow).visibility = View.VISIBLE
        Reactions.buildRow(
            this,
            findViewById(R.id.reactionRow),
            findViewById(R.id.emojiBurstLayer)
        ) { emoji ->
            repository.sendReaction(roomCode, emoji)
        }
        findViewById<Button>(R.id.chatButton).setOnClickListener { openChat() }
        findViewById<Button>(R.id.nudgeButton).setOnClickListener { sendNudge() }
        findViewById<Button>(R.id.offTheRipButton).setOnClickListener {
            // Shown here before the room answers, for the same reason a tapped emoji is: the
            // press should look like it did something without waiting on a round trip.
            OffTheRip.showCall(this, findViewById(R.id.reactionPopup), PlayerProfile.getName(this))
            repository.sendReaction(roomCode, Reactions.OFF_THE_RIP)
        }

        listener = repository.listenToRoom(roomCode) { state ->
            if (state == null) return@listenToRoom
            // Captured before it is replaced: what somebody just scored is the difference between
            // the room as it was and the room as it now is.
            val previous = lastState
            lastState = state
            ScoreAnnounce.detect(previous, state, playerId)?.let { taken ->
                ScoreAnnounce.show(this, findViewById(R.id.reactionPopup), taken)
            }
            lastReactionAt = Reactions.render(
                findViewById(R.id.emojiBurstLayer), state, playerId, lastReactionAt,
                onShout = { name ->
                    OffTheRip.showCall(this, findViewById(R.id.reactionPopup), name)
                }
            )
            renderChat(state)
            renderNudge(state)
            renderIncomingNudge(state)
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
        configureScorecard(state.cardCount)
        scorecardAdapter.update(state, viewing, canScore)
        lowerAdapter?.update(state, viewing, canScore)

        // The header shows the player's total across every card, so it stays a comparable
        // figure regardless of which card is currently open.
        val total = state.players[viewing]?.grandTotalAllCards(state.cardCount) ?: 0
        findViewById<TextView>(R.id.scorecardTotalText).text = getString(R.string.total_score, total)
        renderProjection(state, viewing)
    }

    /**
     * Where the card on show is heading, if the player asked to be told.
     *
     * Hidden once the game is over: a projection of a finished card is just its own total said
     * twice, and the one number that matters then is the one on the board.
     */
    private fun renderProjection(state: GameState, viewingId: String?) {
        val label = findViewById<TextView>(R.id.projectionText)
        val player = state.players[viewingId]
        val show = AppSettings.showProjection(this) &&
            player != null &&
            state.status == GameState.STATUS_PLAYING

        label.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || player == null) return
        label.text = getString(R.string.projected, Projection.forPlayer(player, state.cardCount))
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

    /**
     * Keeps the chat button and any open sheet in step with the room.
     *
     * Your own messages never count as unread — you were there when they were sent, and a badge
     * that goes up when you speak is just wrong.
     */
    private fun renderChat(state: GameState) {
        chatSheet.update(state.chat, playerId)
        if (chatSheet.isShowing) chatSeenAt = System.currentTimeMillis()

        val unread = state.chat.count { it.at > chatSeenAt && it.senderId != playerId }
        findViewById<Button>(R.id.chatButton).text =
            if (unread > 0) getString(R.string.chat_unread, unread) else getString(R.string.chat_open)
    }

    private fun openChat() {
        val state = lastState ?: return
        chatSeenAt = System.currentTimeMillis()
        chatSheet.show(
            state.chat,
            playerId,
            onSend = { text -> repository.sendChat(roomCode, text) },
            onDelete = { message -> repository.deleteChat(roomCode, message) }
        )
        findViewById<Button>(R.id.chatButton).setText(R.string.chat_open)
    }

    /**
     * Offers the nudge only while the room is genuinely waiting on somebody else.
     *
     * Not during the roll-off, not once the game is over, and never on your own turn — the answer
     * to "why is nothing happening" there is you.
     */
    private fun renderNudge(state: GameState) {
        val current = state.currentPlayerId
        val waiting = state.status == GameState.STATUS_PLAYING &&
            current != null &&
            current != playerId
        findViewById<Button>(R.id.nudgeButton).visibility =
            if (waiting) View.VISIBLE else View.GONE
    }

    private fun sendNudge() {
        val state = lastState ?: return
        val target = state.currentPlayerId ?: return
        if (target == playerId) return

        // A prod that can be sent twenty times in ten seconds is not a prod, it is a hammer on
        // somebody's notification shade. One a minute is plenty to make the point.
        val now = System.currentTimeMillis()
        if (now - lastNudgeSentAt < NUDGE_COOLDOWN_MS) {
            Toast.makeText(this, R.string.nudge_wait, Toast.LENGTH_SHORT).show()
            return
        }
        lastNudgeSentAt = now

        repository.sendNudge(roomCode, target)
        val name = state.players[target]?.name.orEmpty()
        Toast.makeText(this, getString(R.string.nudge_sent, name), Toast.LENGTH_SHORT).show()
    }

    /**
     * Reacts to a nudge aimed at this device.
     *
     * Announced on screen rather than as a notification, because to see this the app is already
     * open in front of the player — a notification would be telling somebody something they are
     * currently looking at. The background check raises the notification for the other case.
     */
    private fun renderIncomingNudge(state: GameState) {
        // The first snapshot is the room as it already was, so whatever nudge it carries is
        // history and is adopted silently.
        //
        // Adopted on the first *snapshot*, not on the first nudge. Keyed to the first nudge, a
        // room that was clean when you opened it would swallow the next one to arrive — so the
        // first time anybody nudged you in a session, nothing happened at all, which is the one
        // occasion it most needed to.
        if (!nudgeAdopted) {
            nudgeAdopted = true
            lastNudgeSeenAt = state.nudge?.at ?: 0L
            return
        }

        val nudge = state.nudge ?: return
        if (nudge.toPlayerId != playerId) return
        if (nudge.at <= lastNudgeSeenAt) return
        lastNudgeSeenAt = nudge.at
        // Shared with the background check and the start screen, so the same prod cannot arrive
        // twice by two different routes.
        NudgeSeen.mark(this, roomCode, nudge.at)

        sound.play(SoundEngine.Sound.SCORE)
        Toast.makeText(
            this,
            getString(R.string.nudge_toast, nudge.byName.ifEmpty { "Someone" }),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Points the scorecard at one list or two, depending on how many cards are in play.
     *
     * Rebuilt rather than reconfigured because an adapter decides which rows it owns when it is
     * created. Guarded on the count so this runs when the answer changes and not on every update
     * the room sends, which would rebuild both lists several times a turn.
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
        // Checked before the write, which clears the dice and the roll count out from under it.
        val offTheRip = OffTheRip.qualifies(state.rollsUsed, category, state.dice)
        val ripPoints = Scoring.score(category, state.dice)
        repository.submitScore(roomCode, state, category, playerId, card)
        if (offTheRip) {
            OffTheRip.show(this, findViewById(R.id.reactionPopup), category, ripPoints)
        }
        if (earnedBonus) {
            sound.play(SoundEngine.Sound.WIN)
            Toast.makeText(this, R.string.yahtzee_bonus_awarded, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Files this game against everyone else who was in it.
     *
     * Head to head rather than by who won the table: in a four-player game you beat the two you
     * outscored and lost to the one who outscored you, and a record that only counted outright
     * victories would say nothing at all about three of those four games.
     */
    private fun recordRivalries(state: GameState) {
        val mine = state.players[playerId]?.grandTotalAllCards(state.cardCount) ?: return
        val at = System.currentTimeMillis()

        state.players.values
            .filterNot { it.id == playerId }
            .forEach { opponent ->
                val theirs = opponent.grandTotalAllCards(state.cardCount)
                Rivalries.record(
                    context = this,
                    opponentId = opponent.id,
                    name = opponent.name,
                    result = when {
                        mine > theirs -> RivalryResult.WIN
                        mine < theirs -> RivalryResult.LOSS
                        else -> RivalryResult.DRAW
                    },
                    at = at
                )
            }
    }

    private fun showGameOver(state: GameState) {
        sound.play(SoundEngine.Sound.WIN)
        submitToLeaderboard(state)
        recordRivalries(state)
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
        PlayedFormats.record(this, state.cardCount)
        LeaderboardRepository().submitRankedScore(
            cardCount = state.cardCount,
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
