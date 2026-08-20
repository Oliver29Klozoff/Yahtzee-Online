package com.yahtzee.online.ui.lobby

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.game.GameActivity

class LobbyActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_ID = "player_id"
        const val EXTRA_PLAYER_NAME = "player_name"

        /** How long the finished roll-off stays on screen before the game opens. */
        private const val ROLL_OFF_REVEAL_MS = 5000L
    }

    private val repository = GameRepository()
    private lateinit var roomCode: String
    private lateinit var playerId: String
    private var listener: ValueEventListener? = null
    private var gameStarted = false

    /** Players whose roll has already been tumbled, so each result animates exactly once. */
    private val animatedRolls = mutableSetOf<String>()

    /** Last seen opening rolls, retained because the winning write clears them immediately. */
    private var revealRolls: Map<String, Int> = emptyMap()
    private var revealOrder: List<String> = emptyList()

    /** True while the result is being held on screen, so state updates leave the views alone. */
    private var revealing = false
    private val revealHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        playerId = intent.getStringExtra(EXTRA_PLAYER_ID) ?: repository.localPlayerId

        findViewById<TextView>(R.id.roomCodeText).text = roomCode

        // The roll-off is a single die, not a hand of five.
        findViewById<Dice3DView>(R.id.rollOffDice).setDieCount(1)

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

            // Hold on to the last set of opening rolls. resolveRollOff wipes them the instant it
            // picks a winner, so without this the dice would disappear at exactly the moment
            // everyone wants to look at them.
            if (state.openingRolls.isNotEmpty()) {
                revealRolls = state.openingRolls
                revealOrder = state.playerOrder
            }

            val isHost = state.hostId == playerId
            val inLobby = state.status == GameState.STATUS_LOBBY
            startButton.visibility = if (isHost && inLobby && state.players.size >= 1) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.waitingText).visibility =
                if (isHost && inLobby) View.GONE else if (inLobby) View.VISIBLE else View.GONE

            renderRollOff(state)

            if (state.status == GameState.STATUS_PLAYING && !gameStarted) {
                gameStarted = true
                if (revealRolls.isNotEmpty()) revealWinnerThenStart(state) else openGame()
            }
        }
    }

    private var lastState: GameState? = null

    private fun renderRollOff(state: GameState) {
        // Once the reveal is up it owns these views until it finishes. Resolving the roll-off
        // writes nine separate values, and every one of them fires this listener again — without
        // this guard the next write would immediately hide the result, which is why the dice
        // vanished almost as soon as they appeared.
        if (revealing) return

        val statusText = findViewById<TextView>(R.id.rollOffStatusText)
        val rollButton = findViewById<Button>(R.id.rollForFirstButton)
        val rollScroll = findViewById<View>(R.id.rollOffScroll)

        if (state.status != GameState.STATUS_ROLL_OFF) {
            statusText.visibility = View.GONE
            rollButton.visibility = View.GONE
            rollScroll.visibility = View.GONE
            findViewById<View>(R.id.rollOffDice).visibility = View.GONE
            return
        }

        rollScroll.visibility = View.VISIBLE
        findViewById<View>(R.id.rollOffDice).visibility = View.VISIBLE
        animateNewRolls(state)
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
     * Holds the finished roll-off on screen for a few seconds before the game opens, so everyone
     * gets to see what was rolled and who won rather than the results flashing past.
     *
     * Rendered from the retained snapshot, because the winning write clears the live rolls.
     */
    private fun revealWinnerThenStart(state: GameState) {
        revealing = true
        findViewById<View>(R.id.rollOffDice).visibility = View.VISIBLE
        findViewById<View>(R.id.rollOffScroll).visibility = View.VISIBLE
        findViewById<Button>(R.id.rollForFirstButton).visibility = View.GONE

        val firstPlayerId = state.playerOrder.firstOrNull()
        val firstName = state.players[firstPlayerId]?.name.orEmpty()
        findViewById<TextView>(R.id.rollOffStatusText).apply {
            visibility = View.VISIBLE
            text = if (firstPlayerId == playerId) {
                getString(R.string.you_go_first)
            } else {
                getString(R.string.goes_first, firstName)
            }
        }

        renderRollOffDice(state, revealRolls, revealOrder, winnerId = firstPlayerId)
        revealHandler.postDelayed({
            revealing = false
            openGame()
        }, ROLL_OFF_REVEAL_MS)
    }

    /**
     * Tumbles the shared die whenever someone's roll lands, in that player's own colour, so the
     * roll-off plays out as actual rolls rather than numbers appearing.
     *
     * Only one die is on screen, so if several results arrive in the same update the newest is
     * animated and the rest are simply recorded — the row below shows every value regardless.
     * A tie wipes the stored rolls, which resets this so the re-rolls animate too.
     */
    private fun animateNewRolls(state: GameState) {
        if (state.openingRolls.isEmpty()) {
            animatedRolls.clear()
            return
        }
        val fresh = state.playerOrder.filter { it in state.openingRolls.keys && it !in animatedRolls }
        if (fresh.isEmpty()) return
        animatedRolls.addAll(fresh)

        val newest = fresh.last()
        val value = state.openingRolls[newest] ?: return
        val color = state.players[newest]?.diceColor?.takeIf { it != 0 } ?: DieTextureAtlas.DEFAULT_COLOR

        val dice = findViewById<Dice3DView>(R.id.rollOffDice)
        dice.setDiceColor(color)
        dice.rollTo(listOf(value), listOf(false), state.seatAngle(playerId, newest))
    }

    /**
     * Everyone's opening roll, each shown as a real die face in that player's own colour, so
     * the roll-off is legible at a glance instead of only reporting your own number in text.
     *
     * Players still to roll show a dimmed placeholder. Note that a tie clears every stored roll
     * (see GameRepository.resolveRollOff), so during a re-roll everyone shows a placeholder —
     * the tied players are highlighted instead, since they are the only ones who roll again.
     */
    private fun renderRollOffDice(
        state: GameState,
        rolls: Map<String, Int> = state.openingRolls,
        order: List<String> = state.playerOrder,
        winnerId: String? = null
    ) {
        val row = findViewById<LinearLayout>(R.id.rollOffRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val dieSize = (winnerId?.let { 64 } ?: 54) * density
        val tied = state.openingRollTied.toSet()
        val highest = rolls.values.maxOrNull()

        order.forEach { id ->
            val player = state.players[id] ?: return@forEach
            val roll = rolls[id]
            val color = player.diceColor.takeIf { it != 0 } ?: DieTextureAtlas.DEFAULT_COLOR

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }

            // During the reveal the winner's die is shown at full strength and the rest dimmed,
            // so the result is obvious at a glance.
            val dieView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dieSize.toInt(), dieSize.toInt())
                setImageBitmap(DieTextureAtlas.face(color, roll ?: 1))
                alpha = when {
                    roll == null -> 0.18f
                    winnerId != null && id != winnerId -> 0.45f
                    else -> 1f
                }
            }

            val awaitingReroll = id in tied
            val isLeader = if (winnerId != null) id == winnerId else roll != null && roll == highest

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

    override fun onResume() {
        super.onResume()
        findViewById<Dice3DView>(R.id.rollOffDice).onResume()
    }

    override fun onPause() {
        super.onPause()
        // GLSurfaceView keeps a render thread alive otherwise, even once the lobby is gone.
        findViewById<Dice3DView>(R.id.rollOffDice).onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(roomCode, it) }
        revealHandler.removeCallbacksAndMessages(null)
    }
}
