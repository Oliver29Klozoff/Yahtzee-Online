package com.yahtzee.online.ui.lobby

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.GameState
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.game.GameActivity

class LobbyActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_ID = "player_id"
        const val EXTRA_PLAYER_NAME = "player_name"
    }

    private val repository = GameRepository()
    private lateinit var roomCode: String
    private lateinit var playerId: String
    private var listener: ValueEventListener? = null
    private var gameStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        playerId = intent.getStringExtra(EXTRA_PLAYER_ID) ?: repository.localPlayerId

        findViewById<TextView>(R.id.roomCodeText).text = roomCode

        val startButton = findViewById<Button>(R.id.startGameButton)
        startButton.setOnClickListener {
            repository.startGame(roomCode)
        }

        val rollForFirstButton = findViewById<Button>(R.id.rollForFirstButton)
        rollForFirstButton.setOnClickListener {
            val state = lastState ?: return@setOnClickListener
            repository.rollForFirst(roomCode, state, playerId)
        }

        listener = repository.listenToRoom(roomCode) { state ->
            if (state == null) return@listenToRoom
            lastState = state
            renderPlayers(state)

            val isHost = state.hostId == playerId
            val inLobby = state.status == GameState.STATUS_LOBBY
            startButton.visibility = if (isHost && inLobby && state.players.size >= 1) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.waitingText).visibility =
                if (isHost && inLobby) View.GONE else if (inLobby) View.VISIBLE else View.GONE

            renderRollOff(state)

            if (state.status == GameState.STATUS_PLAYING && !gameStarted) {
                gameStarted = true
                openGame()
            }
        }
    }

    private var lastState: GameState? = null

    private fun renderRollOff(state: GameState) {
        val statusText = findViewById<TextView>(R.id.rollOffStatusText)
        val rollButton = findViewById<Button>(R.id.rollForFirstButton)
        val rollScroll = findViewById<View>(R.id.rollOffScroll)

        if (state.status != GameState.STATUS_ROLL_OFF) {
            statusText.visibility = View.GONE
            rollButton.visibility = View.GONE
            rollScroll.visibility = View.GONE
            return
        }

        rollScroll.visibility = View.VISIBLE
        renderRollOffDice(state)

        val eligible = state.openingRollTied.ifEmpty { state.playerOrder }
        val myRoll = state.openingRolls[playerId]

        statusText.visibility = View.VISIBLE
        rollButton.visibility = View.VISIBLE

        val tieNotice = if (state.openingRollTied.isNotEmpty()) getString(R.string.roll_off_tied) + " " else ""
        statusText.text = tieNotice + getString(R.string.roll_off_title)

        // The dice row already reports every roll including your own, so the status line keeps
        // showing what the group is waiting on rather than repeating your number back at you.
        rollButton.isEnabled = myRoll == null && playerId in eligible
    }

    /**
     * Everyone's opening roll, each shown as a real die face in that player's own colour, so
     * the roll-off is legible at a glance instead of only reporting your own number in text.
     *
     * Players still to roll show a dimmed placeholder. Note that a tie clears every stored roll
     * (see GameRepository.resolveRollOff), so during a re-roll everyone shows a placeholder —
     * the tied players are highlighted instead, since they are the only ones who roll again.
     */
    private fun renderRollOffDice(state: GameState) {
        val row = findViewById<LinearLayout>(R.id.rollOffRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val dieSize = (54 * density).toInt()
        val tied = state.openingRollTied.toSet()
        val highest = state.openingRolls.values.maxOrNull()

        state.playerOrder.forEach { id ->
            val player = state.players[id] ?: return@forEach
            val roll = state.openingRolls[id]
            val color = player.diceColor.takeIf { it != 0 } ?: DieTextureAtlas.DEFAULT_COLOR

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }

            val dieView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dieSize, dieSize)
                setImageBitmap(DieTextureAtlas.face(color, roll ?: 1))
                alpha = if (roll != null) 1f else 0.18f
            }

            val awaitingReroll = id in tied
            val isLeader = roll != null && roll == highest

            val label = TextView(this).apply {
                text = if (id == playerId) getString(R.string.you_label) else player.name
                textSize = 12f
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, (5 * density).toInt(), 0, 0)
                setTextColor(
                    when {
                        awaitingReroll -> resources.getColor(R.color.timer_warn, theme)
                        isLeader -> color
                        else -> resources.getColor(R.color.text_muted, theme)
                    }
                )
            }

            cell.addView(dieView)
            cell.addView(label)
            row.addView(cell)
        }
    }

    private fun renderPlayers(state: GameState) {
        val names = state.playerOrder.mapNotNull { state.players[it]?.name }
        val adapter = ArrayAdapter(this, R.layout.item_player, names)
        findViewById<ListView>(R.id.playersList).adapter = adapter
    }

    private fun openGame() {
        val gameIntent = Intent(this, GameActivity::class.java)
        gameIntent.putExtra(GameActivity.EXTRA_ROOM_CODE, roomCode)
        gameIntent.putExtra(GameActivity.EXTRA_PLAYER_ID, playerId)
        startActivity(gameIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(roomCode, it) }
    }
}
