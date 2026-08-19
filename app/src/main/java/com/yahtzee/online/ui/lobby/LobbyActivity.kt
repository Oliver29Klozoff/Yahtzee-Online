package com.yahtzee.online.ui.lobby

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
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

        if (state.status != GameState.STATUS_ROLL_OFF) {
            statusText.visibility = View.GONE
            rollButton.visibility = View.GONE
            return
        }

        val eligible = state.openingRollTied.ifEmpty { state.playerOrder }
        val myRoll = state.openingRolls[playerId]

        statusText.visibility = View.VISIBLE
        rollButton.visibility = View.VISIBLE

        val tieNotice = if (state.openingRollTied.isNotEmpty()) getString(R.string.roll_off_tied) + " " else ""
        statusText.text = tieNotice + getString(R.string.roll_off_title)

        if (myRoll != null) {
            statusText.text = getString(R.string.you_rolled, myRoll)
            rollButton.isEnabled = false
        } else {
            rollButton.isEnabled = playerId in eligible
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
