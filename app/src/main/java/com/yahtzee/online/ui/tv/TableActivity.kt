package com.yahtzee.online.ui.tv

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.grandTotalAllCards
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

    private val repository by lazy { GameRepository(this) }
    private lateinit var dice: Dice3DView

    private var roomCode: String = ""
    private var listener: ValueEventListener? = null

    /** Last dice shown, so a roll is animated once rather than on every unrelated update. */
    private var lastDice: List<Int>? = null
    private var lastRollsUsed = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table)

        dice = findViewById(R.id.tableDice)
        dice.setPipStyle(DicePreferences.pipStyle(this))
        dice.setTableColor(AppSettings.tableColor(this))
        dice.setTableLogo(TableLogoStore.mode(this))
        dice.setMotionScale(AppSettings.diceMotion(this).durationScale)

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
    }

    private fun watchRoom(code: String) {
        listener = repository.listenToRoom(code) { state ->
            if (state == null) return@listenToRoom
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(state)
            }
        }
    }

    private fun render(state: GameState) {
        renderTurn(state)
        renderDice(state)
        renderScores(state)
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

    private fun renderScores(state: GameState) {
        val list = findViewById<LinearLayout>(R.id.tableScores)
        list.removeAllViews()
        val density = resources.displayMetrics.density

        state.playerOrder.mapNotNull { state.players[it] }.forEach { player ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val isTurn = player.id == state.currentPlayerId

            row.addView(TextView(this).apply {
                text = player.name
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
    }
}
