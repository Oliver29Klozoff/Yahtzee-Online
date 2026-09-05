package com.yahtzee.online.ui.tv

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.LastTurn
import com.yahtzee.online.game.isClosedToNewPlayers
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.diceAreYahtzee
import com.yahtzee.online.game.decidedWinner
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.scoresForCard
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.net.FirebaseSignIn
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.QrCode
import com.yahtzee.online.ui.game.EmojiBurst
import com.yahtzee.online.ui.game.EmojiPop
import com.yahtzee.online.ui.game.OffTheRip
import com.yahtzee.online.ui.game.Reactions
import com.yahtzee.online.ui.game.ScoreAnnounce
import com.yahtzee.online.ui.game.YahtzeeShout

/**
 * The television's view of a game in progress.
 *
 * The TV is the table, not a player. It opens a room, shows the code big enough to scan from a
 * sofa, and then does nothing but display: whose turn it is, the dice as they land, and where
 * everyone stands. Every decision is made on a phone.
 *
 * That division is what makes this worth having at all. The alternative — driving the phone
 * screens with a remote — means one person playing while everyone else watches a cursor move.
 * Here the shared screen shows the shared thing, and each player's private choices stay in their
 * own hand, which is how the game works at a real table.
 */
class TableActivity : ImmersiveActivity() {

    companion object {
        /**
         * A room to show instead of opening one, for a tournament fixture on the big screen.
         */
        const val EXTRA_ROOM_CODE = "room_code"

        /**
         * Camera distance for the TV table. Above 1 pulls back: the pane is short and wide next
         * to a phone's tall strip, and at the phone framing the dice overrun the top of it.
         */
        const val TV_CAMERA_SCALE = 1.35f

        /** Messages kept on screen. Enough to follow, few enough not to crowd the scores. */
        const val CHAT_LINES = 4

        /** Matches the phone's bar for calling a hand worth shouting about. */
        const val OFF_THE_RIP_POINTS = 20

        /** How long a finished fixture stays up before the bracket comes back. */
        const val MATCH_RESULT_MS = 6000L
    }

    private val repository by lazy { GameRepository(this) }
    private lateinit var dice: Dice3DView

    private var roomCode: String = ""

    /** False when the room was handed to us, in which case it is not ours to delete. */
    private var ownsRoom = true

    /** Set once the hand-back to the bracket is scheduled, so it is scheduled once. */
    private var returning = false
    private var listener: ValueEventListener? = null

    /**
     * The room as it last stood.
     *
     * Two jobs: [onDestroy] uses it to tell an empty room from one with a game in it, and the
     * room events are worked out by comparing it against whatever arrives next.
     */
    private var lastState: GameState? = null

    /**
     * The turn just played, kept on screen until the next player rolls.
     *
     * A television is watched from across a room, and the moment people look up is the moment a
     * turn ends. Moving straight on to the next name and colour threw that away.
     */
    private var lastTurn: LastTurn.Scored? = null

    /**
     * How far each player's clock had got when this screen last looked, so an unrelated update
     * does not replay a reaction.
     *
     * Per player rather than one mark for the room, because the timestamps come from different
     * phones and no two agree; see [Reactions.arrivalsSince].
     *
     * Starts null, which the renderer reads as "this screen has not seen the room yet" and adopts
     * whatever is already there silently. A television switched on halfway through a game should
     * not open with a flurry of everything anybody reacted with while it was off.
     */
    private var lastReactionAt: Map<String, Long>? = null

    /** Last dice shown, so a roll is animated once rather than on every unrelated update. */
    private var lastDice: List<Int>? = null
    private var lastRollsUsed = -1

    /** Who rolled five of a kind, waiting for the dice to stop before it is announced. */
    private var pendingYahtzee: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table)

        dice = findViewById(R.id.tableDice)
        dice.setPipStyle(DicePreferences.pipStyle(this))
        dice.setTableColor(AppSettings.tableColor(this))
        // Plain felt on a television, whatever the phones are set to. Artwork printed on the
        // table reads as a picture of dice sitting behind the real ones at this size, and the
        // shared screen is the one place where nothing should compete with the roll.
        dice.setTableLogo(TableLogoStore.Mode.NONE)
        dice.setMotionScale(AppSettings.diceMotion(this).durationScale)
        // A television pane is far wider than the phone strip the camera was framed for, so the
        // table needs pulling back to sit inside it rather than running off the top.
        dice.setCameraScale(TV_CAMERA_SCALE)
        // The shout waits for the dice to stop, so the room reads the news off the table rather
        // than off a banner that beat the dice to it.
        dice.setOnSettledListener {
            val who = pendingYahtzee ?: return@setOnSettledListener
            pendingYahtzee = null
            YahtzeeShout.show(this, findViewById(R.id.yahtzeeShout), who, isYou = false)
        }

        findViewById<TextView>(R.id.tableHint).setText(R.string.tv_waiting)
        openRoom()
    }

    /**
     * Creates the room this screen is showing, once there is a session to create it with.
     *
     * Held behind [FirebaseSignIn.awaitReady] because of what a television does that a phone does
     * not: it goes straight here on launch. Every phone path reaches the database through a menu,
     * minutes of a first run, or at the very least a splash screen — by which time the first-ever
     * anonymous sign-in has long finished. This screen asks for a room in `onCreate`, so on a
     * device that has never run the app the write went out before there was any session to sign it
     * with and the rules refused it. What the room saw was a television sitting on the join screen
     * with no code and no QR on it and nothing saying why, and the only way through was to launch
     * it a second time. Waiting costs a fraction of a second on that one launch and nothing at all
     * on every launch after, since the session is restored from disk.
     */
    private fun openRoom() {
        // Handed a room rather than opening one: a tournament fixture being shown on the big
        // screen. The bracket owns that room and will still be there when the match ends, so
        // this screen only watches it — it neither made it nor gets to tidy it away.
        val given = intent.getStringExtra(EXTRA_ROOM_CODE)?.takeIf { it.isNotEmpty() }
        if (given != null) {
            ownsRoom = false
            roomCode = given
            findViewById<TextView>(R.id.tableRoomCode).text = given
            renderQr(given)
            watchRoom(given)
            return
        }

        FirebaseSignIn.awaitReady {
            repository.createSpectatorRoom { code ->
                roomCode = code
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    findViewById<TextView>(R.id.tableRoomCode).text = code
                    renderQr(code)
                    watchRoom(code)
                }
            }
        }
    }

    private fun renderQr(code: String) {
        val image = findViewById<ImageView>(R.id.tableQr)
        val size = (260 * resources.displayMetrics.density).toInt()
        QrCode.render("yahtzee://join/$code", size)?.let { image.setImageBitmap(it) }

        // The same code again, small, for the corner it keeps during play.
        val small = (88 * resources.displayMetrics.density).toInt()
        QrCode.render("yahtzee://join/$code", small)?.let {
            findViewById<ImageView>(R.id.tableQrSmall).setImageBitmap(it)
        }
        findViewById<TextView>(R.id.tableRoomCodeSmall).text = code
    }

    private fun watchRoom(code: String) {
        listener = repository.listenToRoom(code) { state ->
            if (state == null) return@listenToRoom
            // Held before it is replaced: what somebody just scored, and how many rolls it took
            // them, are both differences between this snapshot and the one before it.
            val previous = lastState
            lastState = state
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                LastTurn.detect(previous, state)?.let { lastTurn = it }
                renderRoomEvents(previous, state)
                render(state)

                // A fixture shown for a bracket hands the screen back when it is over, after long
                // enough for the table to read the result. Only for a room somebody else owns:
                // an ordinary television table has nothing to go back to and stays put.
                if (!ownsRoom && state.status == GameState.STATUS_FINISHED && !returning) {
                    returning = true
                    findViewById<View>(R.id.tableHint).postDelayed({
                        if (!isFinishing && !isDestroyed) finish()
                    }, MATCH_RESULT_MS)
                }
            }
        }
    }

    private fun render(state: GameState) {
        renderPanels(state)
        renderTurn(state)
        renderDice(state)
        renderHeld(state)
        renderScorecards(state)
        renderScores(state)
        renderChat(state)
    }

    /**
     * The things a room says to itself, shown on the screen the room is facing.
     *
     * A television is nobody's seat, and that is what makes it the right place for all of this.
     * Both helpers take the viewer's own id so a phone does not announce things back at the
     * player who did them; here there is no such player, so an empty id is passed and everything
     * that happens is shown — which is exactly what a spectator screen is for.
     */
    private fun renderRoomEvents(previous: GameState?, state: GameState) {
        // What somebody just scored, and whether they did it straight off the opening roll.
        //
        // Off the rip is worked out from the pair of snapshots rather than from a field: the roll
        // count is reset the instant a box is filled, so the only place the "how many rolls did
        // that take" answer survives is in the state as it was a moment ago.
        ScoreAnnounce.detect(previous, state, localPlayerId = "")?.let { taken ->
            val popup = findViewById<TextView>(R.id.reactionPopup)
            if (previous != null && previous.rollsUsed == 1 && taken.points >= OFF_THE_RIP_POINTS) {
                EmojiPop.show(
                    popup,
                    getString(R.string.tv_off_the_rip, taken.playerName, taken.label, taken.points),
                    ScoreAnnounce.SHOW_MILLIS
                )
            } else {
                ScoreAnnounce.show(this, popup, taken)
            }
        }

        lastReactionAt = Reactions.render(
            findViewById(R.id.emojiBurstLayer),
            state,
            localPlayerId = "",
            lastSeen = lastReactionAt,
            captionSp = EmojiBurst.TV_CAPTION_SP,
            onShout = { name ->
                OffTheRip.showCall(this, findViewById(R.id.reactionPopup), name)
            }
        )
    }

    /** The last few messages, oldest first, so the newest sits nearest the eye at the bottom. */
    private fun renderChat(state: GameState) {
        val list = findViewById<LinearLayout>(R.id.tableChat)
        val recent = state.chat.takeLast(CHAT_LINES)
        list.removeAllViews()
        list.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE

        recent.forEach { message ->
            list.addView(
                androidx.appcompat.widget.AppCompatTextView(this).apply {
                    text = getString(R.string.tv_chat_line, message.senderName, message.text)
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_muted))
                    setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
                }
            )
        }
    }

    /**
     * Swaps the left panel between joining and playing.
     *
     * Before the game the only useful thing a screen can offer is a way in; afterwards it is the
     * cards. The code survives the swap at a fraction of the size, because a room still open to
     * latecomers that nobody can find is no use to them.
     */
    private fun renderPanels(state: GameState) {
        val playing = state.status != GameState.STATUS_LOBBY && state.players.isNotEmpty()
        findViewById<View>(R.id.joinPanel).visibility = if (playing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.cardPanel).visibility = if (playing) View.VISIBLE else View.GONE
        // The corner code goes when the room stops taking people. Leaving it up would have
        // somebody walk over, scan it, and be told no by a phone — having been invited by a
        // television.
        findViewById<View>(R.id.tableQrCorner).visibility =
            if (state.isClosedToNewPlayers()) View.GONE else View.VISIBLE
    }

    /**
     * The five dice as the current player is holding them.
     *
     * The 3D table shows what was rolled but not what is being kept, and keeping is the whole
     * decision — a table watching someone deliberate has nothing to watch otherwise. Held dice
     * stand at full strength against dimmed ones, which is the same language the phones use.
     */
    private fun renderHeld(state: GameState) {
        val row = findViewById<LinearLayout>(R.id.tableHeldRow)
        val label = findViewById<TextView>(R.id.tableHeldLabel)
        row.removeAllViews()

        val anyHeld = state.held.any { it }
        val showing = state.status == GameState.STATUS_PLAYING && state.rollsUsed > 0
        label.visibility = if (showing && anyHeld) View.VISIBLE else View.GONE
        if (!showing) return

        val colour = state.players[state.currentPlayerId]?.diceColor?.takeIf { it != 0 }
            ?: DieTextureAtlas.DEFAULT_COLOR
        val dark = DicePreferences.pipStyle(this).darkFor(colour)
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        state.dice.forEachIndexed { index, value ->
            val held = state.held.getOrElse(index) { false }
            row.addView(
                ImageView(this).apply {
                    setImageBitmap(DieTextureAtlas.face(colour, value, dark))
                    // Dimming rather than hiding: the roll is still five dice, and which ones
                    // are going back in matters as much as which are staying.
                    alpha = if (held) 1f else 0.28f
                    layoutParams = LinearLayout.LayoutParams(size, size).also {
                        it.marginEnd = (8 * density).toInt()
                    }
                }
            )
        }
        label.setText(R.string.tv_keeping)
    }

    private fun renderTurn(state: GameState) {
        val turnText = findViewById<TextView>(R.id.tableTurnText)
        val hint = findViewById<TextView>(R.id.tableHint)

        when {
            state.status == GameState.STATUS_FINISHED -> {
                turnText.text = getString(
                    R.string.winner_is,
                    state.decidedWinner()?.name.orEmpty()
                )
                hint.setText(R.string.tv_finished)
            }
            state.players.isEmpty() -> {
                turnText.text = ""
                hint.setText(R.string.tv_waiting)
            }
            state.status == GameState.STATUS_LOBBY -> {
                turnText.text = resources.getQuantityString(
                    R.plurals.tv_players_joined, state.players.size, state.players.size
                )
                hint.setText(R.string.tv_start_on_phone)
            }
            else -> {
                // Between turns the table still belongs to whoever just played: their name stays
                // up, in their colour, next to what they took for it, until the next player
                // rolls. From across a room that hand-over is the only chance anyone gets to see
                // what happened.
                val handover = lastTurn?.takeIf { LastTurn.isHandover(state) }
                turnText.text = if (handover != null) {
                    getString(
                        R.string.tv_just_scored,
                        handover.playerName, handover.label, handover.points
                    )
                } else {
                    getString(
                        R.string.tv_turn_of,
                        state.players[state.currentPlayerId]?.name.orEmpty()
                    )
                }
                // Latecomers can scan in until everybody has had a turn, so the code stays up
                // until then rather than being replaced by something only useful before the game
                // started. Once the room closes the invitation has to go with it — a code on a
                // television that no longer admits anybody is worse than no code at all.
                hint.setText(
                    if (state.isClosedToNewPlayers()) R.string.tv_in_progress
                    else R.string.tv_scan_to_join_late
                )
            }
        }
    }

    private fun renderDice(state: GameState) {
        // The dice keep the colour of the player who rolled them until the next one rolls, so
        // the table does not change hands before anything has actually happened.
        val shownPlayerId = lastTurn?.takeIf { LastTurn.isHandover(state) }?.playerId
            ?: state.currentPlayerId
        val activeColor = state.players[shownPlayerId]?.diceColor
            ?.takeIf { it != 0 }
            ?: DieTextureAtlas.DEFAULT_COLOR
        dice.setDiceColor(activeColor)

        // Only a genuine roll is tumbled. The room's state changes for all sorts of reasons — a
        // score submitted, someone joining — and re-throwing on each would have the dice in
        // permanent motion.
        val rolled = state.rollsUsed != lastRollsUsed || state.dice != lastDice
        lastDice = state.dice
        lastRollsUsed = state.rollsUsed
        if (!rolled || state.rollsUsed == 0) return

        // Five of a kind, on the screen the whole room is already facing — which is the one place
        // it most wants saying. Held until the dice land, so the throw is not given away.
        if (state.diceAreYahtzee()) {
            pendingYahtzee = state.players[state.currentPlayerId]?.name.orEmpty()
        }

        // Thrown from the seat of whoever is rolling, so the dice arrive from their side of the
        // table. The TV has no seat of its own, so the first player stands in as the viewpoint.
        val viewer = state.playerOrder.firstOrNull().orEmpty()
        dice.rollTo(state.dice, state.held, state.seatAngle(viewer, state.currentPlayerId))
    }

    /**
     * Everyone's card as one grid: categories down the side, a column per player.
     *
     * Laid out as a printed scorecard rather than one card per player, because the interesting
     * thing at a table is the comparison — who still has Yahtzee open, who has burned their
     * sixes — and that is only readable when the same row can be run across.
     *
     * Card zero only. A television room is dealt a single card, since several cards each is a
     * format for people looking closely at their own sheet rather than for a shared screen.
     */
    private fun renderScorecards(state: GameState) {
        val grid = findViewById<LinearLayout>(R.id.scorecardGrid)
        grid.removeAllViews()
        if (state.players.isEmpty()) return

        val players = state.playerOrder.mapNotNull { state.players[it] }
        if (players.isEmpty()) return

        grid.addView(
            gridRow(
                label = "",
                cells = players.map { it.name.take(6) },
                header = true,
                highlight = players.map { it.id == state.currentPlayerId }
            )
        )

        Category.values().forEach { category ->
            grid.addView(
                gridRow(
                    label = category.label,
                    cells = players.map { player ->
                        player.scoresForCard(0)[category]?.toString() ?: "–"
                    },
                    highlight = players.map { it.id == state.currentPlayerId }
                )
            )
        }

        grid.addView(
            gridRow(
                label = getString(R.string.tv_total),
                cells = players.map { it.grandTotalAllCards(state.cardCount).toString() },
                header = true,
                highlight = players.map { it.id == state.currentPlayerId }
            )
        )
    }

    private fun gridRow(
        label: String,
        cells: List<String>,
        header: Boolean = false,
        highlight: List<Boolean> = emptyList()
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
        }

        row.addView(TextView(this).apply {
            text = label
            textSize = if (header) 15f else 14f
            maxLines = 1
            setTextColor(resources.getColor(R.color.text_muted, theme))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        })

        cells.forEachIndexed { index, value ->
            row.addView(TextView(this).apply {
                text = value
                textSize = if (header) 15f else 14f
                maxLines = 1
                gravity = Gravity.CENTER
                if (header) setTypeface(typeface, android.graphics.Typeface.BOLD)
                // The player whose turn it is has their whole column lifted, so a glance finds
                // the card being filled in without hunting for a marker.
                setTextColor(
                    when {
                        highlight.getOrElse(index) { false } ->
                            resources.getColor(R.color.text_dark, theme)
                        value == "–" -> resources.getColor(R.color.category_filled_text, theme)
                        else -> resources.getColor(R.color.text_muted, theme)
                    }
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    private fun renderScores(state: GameState) {
        val list = findViewById<LinearLayout>(R.id.tableScores)
        list.removeAllViews()

        // Only while waiting. Once the cards are up they carry the names and the totals already,
        // and saying it twice on one screen wastes the room the dice want.
        val inLobby = state.status == GameState.STATUS_LOBBY
        list.visibility = if (inLobby) View.VISIBLE else View.GONE
        if (!inLobby) return

        val density = resources.displayMetrics.density

        state.playerOrder.mapNotNull { state.players[it] }.forEach { player ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val isTurn = player.id == state.currentPlayerId

            row.addView(TextView(this).apply {
                // Before the game starts, the room says who is holding it — the whole table can
                // then see which phone has the Start button rather than guessing at it.
                text = if (state.status == GameState.STATUS_LOBBY && player.id == state.hostId) {
                    getString(R.string.tv_host_marker, player.name)
                } else {
                    player.name
                }
                textSize = 20f
                maxLines = 1
                // Whose turn it is, said in their own dice colour, so the table matches the
                // player without needing a label.
                setTextColor(
                    if (isTurn) player.diceColor.takeIf { it != 0 } ?: DieTextureAtlas.DEFAULT_COLOR
                    else resources.getColor(R.color.text_muted, theme)
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = player.grandTotalAllCards(state.cardCount).toString()
                textSize = 20f
                setTextColor(resources.getColor(R.color.text_dark, theme))
            })
            list.addView(row)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(roomCode, it) }

        // The television opens a brand new room every single time it is switched on, and the vast
        // majority of them are never scanned into — someone opens the app on the TV, looks at it,
        // and backs out. Those are rubbish the instant the screen closes, so it takes them with
        // it instead of leaving a trail for the daily sweep to find hours later.
        //
        // Strictly guarded: only a lobby, only with nobody seated. A room with players in it
        // belongs to their phones now, and switching the TV off must never end their game.
        val state = lastState
        if (ownsRoom &&
            roomCode.isNotEmpty() &&
            state != null &&
            state.status == GameState.STATUS_LOBBY &&
            state.players.isEmpty()
        ) {
            repository.deleteRoom(roomCode)
        }
    }
}
