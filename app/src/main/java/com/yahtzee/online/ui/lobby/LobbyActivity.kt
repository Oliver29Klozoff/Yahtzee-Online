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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.RecentPlayersStore
import com.yahtzee.online.game.seatAngle
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.QrCode
import com.yahtzee.online.ui.bot.SoloGameActivity
import com.yahtzee.online.ui.game.GameActivity
import com.yahtzee.online.ui.game.RollOffRow

class LobbyActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_ID = "player_id"
        const val EXTRA_PLAYER_NAME = "player_name"

        /** How long the finished roll-off stays on screen before the game opens. */
        private const val ROLL_OFF_REVEAL_MS = 5000L

        /**
         * A room needs two people before it can start.
         *
         * An online room is a game against somebody. One player is not a short field, it is a
         * solo game — and the app already has one of those, on its own button, keeping its own
         * records. Anybody who would rather not wait has that and the bots to fall back on.
         *
         * Taken from the repository so the button and the write it guards cannot disagree.
         */
        private const val MIN_PLAYERS_TO_START = GameRepository.MIN_PLAYERS_TO_START

        /**
         * Camera distance for the roll-off, as a fraction of the game's framing — about 1.4x
         * larger on screen. Not pushed closer than this because dice are thrown in from about
         * y=2.3, and a tighter frame clips the top of the arc so the die pops into view
         * mid-flight instead of being seen to land.
         */
        private const val ROLL_OFF_CAMERA_SCALE = 0.70f

        /** How long the host sits alone before being offered a game against bots. */
        private const val NOBODY_JOINED_PROMPT_MS = 30_000L
    }

    private val repository by lazy { GameRepository(this) }
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

    private val botPromptHandler = Handler(Looper.getMainLooper())
    private var botPromptScheduled = false
    private var botPromptShown = false
    private val revealHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        playerId = intent.getStringExtra(EXTRA_PLAYER_ID) ?: repository.localPlayerId

        findViewById<TextView>(R.id.roomCodeText).text = roomCode

        // The roll-off is a single die, not a hand of five, and it is framed much closer so that
        // one die reads clearly from across a room.
        findViewById<Dice3DView>(R.id.rollOffDice).apply {
            setDieCount(1)
            setPipStyle(DicePreferences.pipStyle(this@LobbyActivity))
            setCameraScale(ROLL_OFF_CAMERA_SCALE)
            setTableColor(AppSettings.tableColor(this@LobbyActivity))
            setTableLogo(TableLogoStore.mode(this@LobbyActivity))
            setMotionScale(AppSettings.diceMotion(this@LobbyActivity).durationScale)
        }

        val startButton = findViewById<Button>(R.id.startGameButton)
        startButton.setOnClickListener {
            // Checked again off the latest state rather than trusting the button's own enabled
            // flag: the last player can leave between a render and a tap.
            val state = lastState
            if (state != null && state.players.size < MIN_PLAYERS_TO_START) {
                Toast.makeText(this, R.string.start_game_needs_player, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repository.startGame(roomCode)
        }

        findViewById<Button>(R.id.playBotsInsteadButton).setOnClickListener { startBotGame() }
        findViewById<Button>(R.id.passControlButton).setOnClickListener { promptPassControl() }
        findViewById<Button>(R.id.shareInviteButton).setOnClickListener { shareInvite() }
        findViewById<Button>(R.id.inviteRecentButton).setOnClickListener { inviteRecent() }

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

            // A rematch sends the room back to the roll-off while this lobby is still on the
            // stack behind the finished game. Without clearing these latches the lobby would
            // refuse to open the next game, having already recorded that one had started.
            if (state.status == GameState.STATUS_ROLL_OFF || state.status == GameState.STATUS_LOBBY) {
                if (gameStarted) {
                    gameStarted = false
                    revealing = false
                    revealRolls = emptyMap()
                    revealOrder = emptyList()
                    animatedRolls.clear()
                }
            }

            val isHost = state.hostId == playerId
            val inLobby = state.status == GameState.STATUS_LOBBY
            renderBotFallback(state, isHost, inLobby)

            // Inviting is only useful while there is still a seat to fill.
            findViewById<View>(R.id.inviteRow).visibility = if (inLobby) View.VISIBLE else View.GONE
            renderRoomQr(inLobby)
            // Everyone who actually sat down, recorded once play begins rather than on joining,
            // so a room someone glanced at and left does not put them in the list.
            if (state.status != GameState.STATUS_LOBBY) {
                RecentPlayersStore.remember(this, state.players.values, playerId)
            }
            // Starting needs somebody to start against.
            //
            // The host used to be offered this the moment the room existed, so a start tapped
            // before anyone arrived opened a game of one — the roll-off is uncontested, the host
            // wins it, and they play a whole scorecard alone while the other person is still
            // reading the invite. Shown rather than hidden while the seat is empty, so the reason
            // it will not start is on screen instead of the button simply not being there.
            val enoughToStart = state.players.size >= MIN_PLAYERS_TO_START
            startButton.visibility = if (isHost && inLobby) View.VISIBLE else View.GONE
            startButton.isEnabled = enoughToStart
            startButton.alpha = if (enoughToStart) 1f else 0.5f
            startButton.text = getString(
                if (enoughToStart) R.string.start_game else R.string.start_game_needs_player
            )
            // Nothing to hand over unless you hold the room and somebody else is in it.
            findViewById<Button>(R.id.passControlButton).visibility =
                if (isHost && inLobby && state.players.size > 1) View.VISIBLE else View.GONE
            // The host sees this too while the room is empty — they are waiting on someone just
            // as much as a guest is, and used to be the only one not told so.
            findViewById<TextView>(R.id.waitingText).visibility =
                if (!inLobby) View.GONE
                else if (isHost && enoughToStart) View.GONE
                else View.VISIBLE

            renderRollOff(state)

            if (state.status == GameState.STATUS_PLAYING && !gameStarted) {
                gameStarted = true
                if (revealRolls.isNotEmpty()) revealWinnerThenStart(state) else openGame()
            }
        }
    }

    private var lastState: GameState? = null

    /** Drawn once — the code cannot change while this lobby is open. */
    private var qrDrawn = false

    /**
     * The room's invite link as a QR. Shown only while the room is still open: a code for a game
     * already under way would let somebody scan their way into a seat that no longer exists.
     */
    private fun renderRoomQr(inLobby: Boolean) {
        val image = findViewById<ImageView>(R.id.roomQr)
        val caption = findViewById<View>(R.id.roomQrCaption)
        val visible = inLobby && roomCode.isNotEmpty()

        image.visibility = if (visible) View.VISIBLE else View.GONE
        caption.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || qrDrawn) return

        val size = (180 * resources.displayMetrics.density).toInt()
        QrCode.render(inviteLink(), size)?.let {
            image.setImageBitmap(it)
            qrDrawn = true
        }
    }

    /** The one link both the QR and the share sheet carry, so they cannot disagree. */
    private fun inviteLink(): String = "yahtzee://join/$roomCode"

    /**
     * Hands the room to another player.
     *
     * Whoever scanned in first ends up holding it, which is an accident of who was quickest with
     * a camera rather than a decision anyone made — so it can be given away. Once passed, the
     * start button simply appears on their phone and disappears from this one; there is nothing
     * for them to accept.
     */
    private fun promptPassControl() {
        val state = lastState ?: return
        val others = state.playerOrder
            .mapNotNull { state.players[it] }
            .filterNot { it.id == playerId }
        if (others.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.pass_control)
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                val chosen = others[which]
                repository.transferHost(roomCode, chosen.id)
                Toast.makeText(
                    this,
                    getString(R.string.pass_control_done, chosen.name),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Shares an invite as text.
     *
     * The link uses the app's own scheme rather than an https address: making https open the app
     * directly needs a website serving an assetlinks file to vouch for it, and without one
     * Android would show a browser chooser instead. The room code is spelled out alongside it so
     * the message still works wherever the link is not tappable — which, for a custom scheme, is
     * plenty of places.
     */
    private fun shareInvite() {
        val text = getString(R.string.invite_share_text, roomCode, inviteLink())
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text),
                getString(R.string.share_invite)
            )
        )
    }

    /**
     * Invites someone this device has played before. The invite is left in Firebase for their own
     * app to find on its next turn check, so it reaches them without a server to push it — at the
     * cost of arriving on that job's schedule rather than instantly.
     */
    private fun inviteRecent() {
        val recent = RecentPlayersStore.all(this)
            .filterNot { it.id in (lastState?.players?.keys ?: emptySet()) }

        if (recent.isEmpty()) {
            Toast.makeText(this, R.string.invite_recent_empty, Toast.LENGTH_LONG).show()
            return
        }

        val names = recent.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.invite_recent)
            .setItems(names) { _, which ->
                val player = recent[which]
                repository.invitePlayer(
                    player.id,
                    roomCode,
                    PlayerProfile.getName(this).ifEmpty { "A player" }
                )
                Toast.makeText(
                    this,
                    getString(R.string.invite_sent, player.name),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
     * Offers a bot game while the host is sitting alone in a lobby, and prompts unprompted if
     * nobody has turned up after a while, so a room nobody joins is not a dead end.
     *
     * The bots are the local engine rather than networked players: with nobody else in the room
     * there is no one to synchronise with, so running them over Firebase would add moving parts
     * for no observable difference.
     */
    private fun renderBotFallback(state: GameState, isHost: Boolean, inLobby: Boolean) {
        val alone = isHost && inLobby && state.players.size <= 1
        findViewById<Button>(R.id.playBotsInsteadButton).visibility =
            if (alone) View.VISIBLE else View.GONE

        if (!alone) {
            botPromptHandler.removeCallbacksAndMessages(null)
            botPromptScheduled = false
            return
        }
        if (botPromptScheduled || botPromptShown) return
        botPromptScheduled = true
        botPromptHandler.postDelayed({
            val current = lastState ?: return@postDelayed
            if (current.status != GameState.STATUS_LOBBY || current.players.size > 1) return@postDelayed
            botPromptShown = true
            AlertDialog.Builder(this)
                .setTitle(R.string.nobody_joined_title)
                .setMessage(getString(R.string.nobody_joined_message, roomCode))
                .setPositiveButton(R.string.play_vs_bots) { _, _ -> startBotGame() }
                .setNegativeButton(R.string.keep_waiting, null)
                .show()
        }, NOBODY_JOINED_PROMPT_MS)
    }

    /** Abandons the empty room and starts a local game against bots instead. */
    private fun startBotGame() {
        val name = state()?.players?.get(playerId)?.name ?: PlayerProfile.getName(this)
        val labels = arrayOf("1 bot", "2 bots", "3 bots", "4 bots")
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_bot_count)
            .setItems(labels) { _, which ->
                // The room is only ours and nobody joined, so tear it down rather than leaving
                // an empty lobby sitting in the database holding its code.
                repository.deleteRoom(roomCode)
                startActivity(
                    Intent(this, SoloGameActivity::class.java)
                        .putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, name.ifEmpty { "You" })
                        .putExtra(SoloGameActivity.EXTRA_BOT_COUNT, which + 1)
                        // Keep the format the host set up for the room they are abandoning.
                        .putExtra(SoloGameActivity.EXTRA_CARD_COUNT, state()?.cardCount ?: 1)
                )
                finish()
            }
            .show()
    }

    private fun state(): GameState? = lastState

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
        RollOffRow.render(
            context = this,
            row = findViewById(R.id.rollOffRow),
            state = state,
            localPlayerId = playerId,
            rolls = rolls,
            order = order,
            winnerId = winnerId
        )
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
        botPromptHandler.removeCallbacksAndMessages(null)
    }
}
