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
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.scoresForCard
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.QrCode

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

    private companion object {
        /**
         * Camera distance for the TV table. Above 1 pulls back: the pane is short and wide next
         * to a phone's tall strip, and at the phone framing the dice overrun the top of it.
         */
        const val TV_CAMERA_SCALE = 1.35f
    }

    private val repository by lazy { GameRepository(this) }
    private lateinit var dice: Dice3DView

    private var roomCode: String = ""
    private var listener: ValueEventListener? = null

    /** Kept only so [onDestroy] can tell an empty room from one with a game going on in it. */
    private var lastState: GameState? = null

    /** Last dice shown, so a roll is animated once rather than on every unrelated update. */
    private var lastDice: List<Int>? = null
    private var lastRollsUsed = -1

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

        findViewById<TextView>(R.id.tableHint).setText(R.string.tv_waiting)
        openRoom()
    }

    private fun openRoom() {
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
            lastState = state
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(state)
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
                    state.players[state.winnerId]?.name.orEmpty()
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
                turnText.text = getString(
                    R.string.tv_turn_of,
                    state.players[state.currentPlayerId]?.name.orEmpty()
                )
                // Latecomers can still scan in, so the code stays up rather than being replaced
                // by something only useful before the game started.
                hint.setText(R.string.tv_scan_to_join_late)
            }
        }
    }

    private fun renderDice(state: GameState) {
        val activeColor = state.players[state.currentPlayerId]?.diceColor
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
        if (roomCode.isNotEmpty() &&
            state != null &&
            state.status == GameState.STATUS_LOBBY &&
            state.players.isEmpty()
        ) {
            repository.deleteRoom(roomCode)
        }
    }
}
