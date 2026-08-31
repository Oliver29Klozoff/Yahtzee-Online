package com.yahtzee.online.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.ActiveGamesStore
import com.yahtzee.online.game.DailyChallenge
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.Duel
import com.yahtzee.online.net.DuelRepository
import com.yahtzee.online.ui.duel.DuelActivity
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.Rivalries
import com.yahtzee.online.game.Rivalry
import com.yahtzee.online.game.SoloGameStore
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.net.LeaderboardEntry
import com.yahtzee.online.net.LeaderboardRepository
import com.yahtzee.online.net.RoomCleanup
import com.yahtzee.online.net.TurnCheckWorker
import com.yahtzee.online.net.TurnNotifier
import com.yahtzee.online.ui.bot.SoloGameActivity
import com.yahtzee.online.ui.lobby.LobbyActivity
import com.yahtzee.online.update.UpdateChecker

class MainActivity : ImmersiveActivity() {

    companion object {
        /** Room code from a followed invite link, joined as soon as this screen opens. */
        const val EXTRA_JOIN_ROOM = "join_room"
        const val EXTRA_JOIN_DUEL = "join_duel"

        /**
         * Process-scoped, so the launch check runs once per cold boot. MainActivity is recreated
         * every time the player backs out of a game, and without this the prompt would reappear
         * each time they returned to the menu.
         */
        private var checkedThisLaunch = false

        private const val LEADERBOARD_SIZE = 10
    }

    private val repository by lazy { GameRepository(this) }
    private val leaderboard = LeaderboardRepository()
    private var leaderboardListener: ValueEventListener? = null

    /** The daily board is a separate query, so it needs its own handle to detach. */
    private var dailyQuery: com.google.firebase.database.Query? = null
    private var dailyListener: ValueEventListener? = null

    /** Which board the leaderboard section is currently showing. */
    private var showingDaily = false

    /** Asked at most once per launch, so declining does not re-prompt on every return here. */
    private var askedForNotifications = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) TurnCheckWorker.schedule(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Housekeeping for a database with no server behind it: every client that reaches the
        // start screen takes a turn at clearing out dead rooms, at most once a day.
        RoomCleanup.maybeSweep(this)

        val roomCodeInput = findViewById<EditText>(R.id.roomCodeInput)
        val createButton = findViewById<Button>(R.id.createRoomButton)
        val joinButton = findViewById<Button>(R.id.joinRoomButton)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val greetingText = findViewById<TextView>(R.id.greetingText)

        greetingText.setOnClickListener {
            startActivity(
                Intent(this, NameActivity::class.java)
                    .putExtra(NameActivity.EXTRA_EDIT_MODE, true)
            )
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.statsButton).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        findViewById<View>(R.id.dailyCard).setOnClickListener { startDailyChallenge() }
        findViewById<View>(R.id.duelCard).setOnClickListener { startDuel() }
        findViewById<Button>(R.id.scanCodeButton).setOnClickListener { scanRoomCode() }

        // Followed an invite link: join straight away rather than making them retype the code
        // that was in the link they just tapped.
        intent.getStringExtra(EXTRA_JOIN_ROOM)?.let { code ->
            intent.removeExtra(EXTRA_JOIN_ROOM)
            joinRoomByCode(code)
        }
        intent.getStringExtra(EXTRA_JOIN_DUEL)?.let { code ->
            intent.removeExtra(EXTRA_JOIN_DUEL)
            openDuel(code, join = true)
        }

        findViewById<Button>(R.id.leaderboardToggle).setOnClickListener {
            showingDaily = !showingDaily
            observeLeaderboard()
        }

        // Cold boot: sweep any APK left behind by a previous update, then quietly look for a new
        // release. Only runs on a genuinely fresh start, not on every return to the menu from a
        // game, which would re-prompt mid-session.
        val updateChecker = UpdateChecker(this)
        updateChecker.cleanupStaleApk()
        if (savedInstanceState == null && !checkedThisLaunch) {
            checkedThisLaunch = true
            updateChecker.checkOnLaunch()
        }

        createButton.setOnClickListener {
            // The host picks the format up front, so everyone who joins the room plays it.
            val labels = GameState.CARD_OPTIONS.map { count ->
                if (count == 1) getString(R.string.one_card) else getString(R.string.n_cards, count)
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_card_count)
                .setItems(labels) { _, which -> chooseTurnLength(GameState.CARD_OPTIONS[which]) }
                .show()
        }

        joinButton.setOnClickListener {
            val code = roomCodeInput.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(this, R.string.room_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            joinRoomByCode(code)
        }
    }

    /**
     * Joins by pointing the camera at the host's screen.
     *
     * Google's scanner is used rather than a camera preview of our own, because it runs in its
     * own process and so needs no camera permission from this app. Asking for the camera is a
     * large thing to ask in exchange for not typing six characters, and a permission refused
     * once tends to stay refused.
     */
    private fun scanRoomCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                // One scan button for both kinds of invite. Nobody looking at a QR code on a
                // screen knows or cares whether it leads to a room or a duel, and making them
                // pick the right button first would be asking them to know.
                val target = inviteFrom(barcode.rawValue)
                when (target?.first) {
                    "join" -> joinRoomByCode(target.second)
                    "duel" -> openDuel(target.second, join = true)
                    else -> Toast.makeText(this, R.string.scan_not_a_room, Toast.LENGTH_LONG).show()
                }
            }
            // Cancelling is not a failure and should say nothing; only a scanner that could not
            // run at all is worth reporting.
            .addOnCanceledListener { }
            .addOnFailureListener {
                Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * What a scanned invite points at: "join" or "duel", and the code.
     *
     * Parsed here rather than handed to the system as a link: the app's own scheme is not one a
     * general scanner would open, and this way a code scanned in-app works regardless of what
     * the phone would otherwise do with it.
     */
    private fun inviteFrom(raw: String?): Pair<String, String>? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return null
        if (uri.scheme != "yahtzee") return null
        val host = uri.host ?: return null
        if (host != "join" && host != "duel") return null
        val code = uri.lastPathSegment?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return host to code
    }

    private fun joinRoomByCode(code: String) {
        val joinButton = findViewById<Button>(R.id.joinRoomButton)
        joinButton.isEnabled = false
        repository.joinRoom(code, playerName(), DicePreferences.getColor(this)) { success ->
            joinButton.isEnabled = true
            if (success) {
                trackGame(code)
                openLobby(code)
            } else {
                Toast.makeText(this, R.string.room_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Offered on return as well as on launch, so finishing a game or backing out of one
        // updates the button rather than leaving a stale offer to resume something that is over.
        val continueButton = findViewById<Button>(R.id.continueGameButton)
        val resumable = SoloGameStore.loadResumable(this)
        continueButton.visibility = if (resumable != null) Button.VISIBLE else Button.GONE
        continueButton.setOnClickListener {
            startActivity(
                Intent(this, SoloGameActivity::class.java)
                    .putExtra(SoloGameActivity.EXTRA_RESUME, true)
                    .putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, playerName())
            )
        }
        // A saved solo game otherwise sits here until it is played out — including one abandoned
        // ten turns in that the player has no intention of going back to.
        continueButton.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_saved_game)
                .setMessage(R.string.clear_saved_game_warning)
                .setPositiveButton(R.string.clear_game) { _, _ ->
                    SoloGameStore.clear(this)
                    continueButton.visibility = Button.GONE
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
        // Re-read on resume so a name changed on the name page shows immediately on return.
        findViewById<TextView>(R.id.greetingText).text =
            getString(R.string.greeting, playerName())

        renderDailyCard()
        renderDuelCard()
        renderRivals()
        renderActiveGames()
        observeLeaderboard()
    }

    /**
     * Standing records against the people this device has actually played.
     *
     * The whole point of keeping them is that they are a way back to a person. Tapping one opens
     * a room and invites them to it — the invite path already exists and the background check on
     * their phone turns it into a tracked game and a notification, so a rivalry is a button that
     * starts the next game rather than a scoreboard to look at.
     */
    private fun renderRivals() {
        val list = findViewById<LinearLayout>(R.id.rivalsList)
        val section = findViewById<View>(R.id.rivalsSection)
        val rivals = Rivalries.all(this)

        section.visibility = if (rivals.isEmpty()) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (rivals.isEmpty()) return

        val density = resources.displayMetrics.density
        val accent = AccentColor.resolve(this)

        rivals.take(6).forEach { rival ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (14 * density).toInt(), (10 * density).toInt(),
                    (14 * density).toInt(), (10 * density).toInt()
                )
                background = getDrawable(R.drawable.scorecard_card_background)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
                setOnClickListener { playAgain(rival) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(getString(R.string.rivals_remove, rival.name))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            Rivalries.forget(this@MainActivity, rival.opponentId)
                            renderRivals()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }

            row.addView(
                TextView(this).apply {
                    text = rival.name
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.text_dark))
                }
            )
            row.addView(
                TextView(this).apply {
                    text = if (rival.draws > 0) {
                        getString(
                            R.string.rivals_line_drawn,
                            rival.wins, rival.name, rival.losses, rival.draws
                        )
                    } else {
                        getString(R.string.rivals_line, rival.wins, rival.name, rival.losses)
                    }
                    textSize = 13f
                    // Behind gets the accent, which is the state worth doing something about.
                    setTextColor(if (rival.trailing) accent else getColor(R.color.text_muted))
                }
            )
            list.addView(row)
        }
    }

    /** Opens a room and invites this rival straight into it. */
    private fun playAgain(rival: Rivalry) {
        repository.createRoom(
            hostName = playerName(),
            diceColor = DicePreferences.getColor(this),
            cardCount = 1
        ) { code ->
            if (isFinishing || isDestroyed) return@createRoom
            repository.invitePlayer(rival.opponentId, code, playerName())
            trackGame(code)
            Toast.makeText(
                this,
                getString(R.string.rivals_invited, code, rival.name),
                Toast.LENGTH_LONG
            ).show()
            openLobby(code)
        }
    }

    /**
     * The duel card, plus a line per duel this device is in.
     *
     * The rows matter more than they look: a duel is asynchronous by nature — you play your round
     * and then wait, possibly for a day — so without somewhere to come back to, a duel invited by
     * someone else would exist only in a message thread the player has to go and find again.
     */
    private fun renderDuelCard() {
        val list = findViewById<LinearLayout>(R.id.duelList)
        list.removeAllViews()

        val codes = Duel.joined(this)
        findViewById<TextView>(R.id.duelStatus).setText(
            if (codes.isEmpty()) R.string.duel_subtitle else R.string.duel_subtitle_active
        )

        val density = resources.displayMetrics.density
        codes.take(4).forEach { code ->
            val played = Duel.hasPlayed(this, code)
            val row = TextView(this).apply {
                text = getString(
                    if (played) R.string.duel_row_played else R.string.duel_row_waiting,
                    code
                )
                textSize = 13f
                setTextColor(AccentColor.resolve(this@MainActivity))
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener { openDuel(code) }
                // A duel that has run its course, or one the other person never took up, should
                // not sit on the start screen for ever.
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.duel_title)
                        .setMessage(getString(R.string.duel_remove_confirm, code))
                        .setPositiveButton(R.string.clear_game) { _, _ ->
                            Duel.forget(this@MainActivity, code)
                            renderDuelCard()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
            list.addView(row)
        }
    }

    /** Opens a fresh duel. Joining an existing one arrives by link or scan instead. */
    private fun startDuel() {
        DuelRepository(this).createDuel(playerName()) { code ->
            if (isFinishing || isDestroyed) return@createDuel
            openDuel(code)
        }
    }

    private fun openDuel(code: String, join: Boolean = false) {
        startActivity(
            Intent(this, DuelActivity::class.java)
                .putExtra(DuelActivity.EXTRA_DUEL_CODE, code)
                .putExtra(DuelActivity.EXTRA_JOIN, join)
        )
    }

    /**
     * Starts watching for turns, asking for notification permission the first time there is
     * actually a game to watch — rather than on first launch, when the request would arrive with
     * nothing to justify it and be refused out of hand.
     */
    private fun trackGame(code: String) {
        ActiveGamesStore.track(this, code)
        TurnNotifier.ensureChannel(this)
        TurnCheckWorker.schedule(this)
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (TurnNotifier.canNotify(this)) return
        if (askedForNotifications) return
        askedForNotifications = true

        AlertDialog.Builder(this)
            .setMessage(R.string.notify_permission_rationale)
            .setPositiveButton(R.string.notify_permission_allow) { _, _ ->
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    /**
     * The games this device is sitting in, each showing whose move it is. Rooms are read one at a
     * time and the row is filled in as each answers, so a slow room does not hold up the rest.
     */
    private fun renderActiveGames() {
        val section = findViewById<View>(R.id.activeGamesSection)
        val list = findViewById<LinearLayout>(R.id.activeGamesList)
        val tracked = ActiveGamesStore.all(this)

        section.visibility = if (tracked.isEmpty()) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (tracked.isEmpty()) return

        // Any tracked game means the watch should be running: it stands itself down when the
        // list empties, so this is what starts it again after that.
        TurnCheckWorker.schedule(this)

        val density = resources.displayMetrics.density
        tracked.forEach { game ->
            val row = TextView(this).apply {
                text = game.roomCode
                textSize = 15f
                setTextColor(resources.getColor(R.color.text_dark, theme))
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                background = resources.getDrawable(
                    android.R.drawable.list_selector_background, theme
                )
                setOnClickListener { openLobby(game.roomCode) }
                setOnLongClickListener {
                    // Long press rather than a delete button on every row: clearing a game is
                    // rare next to opening one, and a row of crosses would make the list read as
                    // something to tidy up rather than something to play.
                    promptClearGame(game.roomCode)
                    true
                }
            }
            list.addView(row)

            repository.readRoomOnce(game.roomCode) { state ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (state == null || state.status == GameState.STATUS_FINISHED) {
                        ActiveGamesStore.untrack(this, game.roomCode)
                        row.visibility = View.GONE
                        return@runOnUiThread
                    }
                    val myTurn = state.currentPlayerId == repository.localPlayerId
                    val status = when {
                        state.status != GameState.STATUS_PLAYING -> getString(R.string.game_in_lobby)
                        myTurn -> getString(R.string.game_waiting_on_you)
                        else -> getString(
                            R.string.game_waiting_on_other,
                            state.players[state.currentPlayerId]?.name.orEmpty()
                        )
                    }
                    row.text = "${game.roomCode}   ·   $status"
                    row.setTextColor(
                        if (myTurn) AccentColor.resolve(this)
                        else resources.getColor(R.color.text_muted, theme)
                    )
                    // The player is looking at the game right now, so a pending notification
                    // about it is already answered.
                    if (myTurn) TurnNotifier.clear(this, game.roomCode)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        detachLeaderboard()
    }

    /**
     * The daily card doubles as its own status line: before playing it explains the format, and
     * afterwards it reports the score and stops offering a game, since there is only one attempt.
     */
    private fun renderDailyCard() {
        val played = DailyChallenge.todayScore(this)
        val card = findViewById<View>(R.id.dailyCard)
        findViewById<TextView>(R.id.dailyStatus).text = when (played) {
            null -> getString(R.string.daily_subtitle)
            else -> getString(R.string.daily_played, played)
        }
        card.isEnabled = played == null
        card.alpha = if (played == null) 1f else 0.55f

        // The outline is a stroke inside a drawable, which the accent walk cannot reach — it only
        // looks at text and tint, and a shape's stroke colour cannot even be read back. Set here
        // instead, or the card keeps the nearest preset while the rest of the screen takes the
        // exact colour. Mutated first, since a drawable inflated from a resource shares its state
        // with every other view using it.
        (card.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.setStroke(
            (resources.displayMetrics.density).toInt().coerceAtLeast(1),
            AccentColor.resolve(this)
        )
    }

    /**
     * Opens today's challenge, unless a different solo game is already saved — that game would be
     * overwritten by the daily's own save, and losing a game in progress to a menu tap is exactly
     * the complaint that put the continue button there in the first place.
     */
    private fun startDailyChallenge() {
        if (DailyChallenge.playedToday(this)) return

        val saved = SoloGameStore.loadResumable(this)
        if (saved != null && saved.dailyId == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.daily_challenge)
                .setMessage(R.string.daily_replaces_game)
                .setPositiveButton(R.string.daily_start_anyway) { _, _ ->
                    SoloGameStore.clear(this)
                    launchDaily()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        launchDaily()
    }

    private fun launchDaily() {
        startActivity(
            Intent(this, SoloGameActivity::class.java)
                .putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, playerName())
                .putExtra(SoloGameActivity.EXTRA_DAILY_ID, DailyChallenge.todayId())
                // A daily left half-played resumes rather than restarting, so the tape is not
                // replayed from the top with the boxes already filled.
                .putExtra(SoloGameActivity.EXTRA_RESUME, true)
        )
    }

    private fun observeLeaderboard() {
        detachLeaderboard()
        findViewById<TextView>(R.id.leaderboardHeading).setText(
            if (showingDaily) R.string.daily_board else R.string.leaderboard
        )
        findViewById<Button>(R.id.leaderboardToggle).setText(
            if (showingDaily) R.string.leaderboard else R.string.daily_board
        )
        findViewById<TextView>(R.id.leaderboardEmpty).setText(
            if (showingDaily) R.string.daily_board_empty else R.string.leaderboard_empty
        )

        if (showingDaily) {
            val (query, listener) = leaderboard.observeDailyTop(
                DailyChallenge.todayId(),
                LEADERBOARD_SIZE
            ) { entries -> runOnUiThread { renderLeaderboard(entries) } }
            dailyQuery = query
            dailyListener = listener
        } else {
            leaderboardListener = leaderboard.observeTop(LEADERBOARD_SIZE) { entries ->
                runOnUiThread { renderLeaderboard(entries) }
            }
        }
    }

    /**
     * Drops a game from the list.
     *
     * What that means depends on where the game has got to. Before it starts, the seat can be
     * given up properly, so the others stop waiting on a player who is not coming. Once it is
     * under way the seat has to stay — turn order is positional, and removing a player mid-game
     * would hand their turn to someone else — so this only stops the phone tracking it, and the
     * wording says so rather than implying the game has been left.
     */
    private fun promptClearGame(code: String) {
        repository.readRoomOnce(code) { state ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val started = state != null && state.status != GameState.STATUS_LOBBY &&
                    state.status != GameState.STATUS_FINISHED

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.clear_game_title, code))
                    .setMessage(if (started) R.string.clear_game_started else R.string.clear_game_lobby)
                    .setPositiveButton(R.string.clear_game) { _, _ ->
                        ActiveGamesStore.untrack(this, code)
                        TurnNotifier.clear(this, code)
                        if (!started) repository.leaveRoom(code)
                        renderActiveGames()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun detachLeaderboard() {
        leaderboardListener?.let { leaderboard.removeListener(it) }
        leaderboardListener = null
        // Removed from the query it was added to, not from the board root — a listener attached
        // to an ordered query is not detached by clearing the parent reference.
        dailyListener?.let { dailyQuery?.removeEventListener(it) }
        dailyListener = null
        dailyQuery = null
    }

    /**
     * Turn length is set by the host with the room's format, not in Settings: every player has
     * to be counting down the same clock, so it cannot be a per-device preference.
     */
    private fun chooseTurnLength(cardCount: Int) {
        val createButton = findViewById<Button>(R.id.createRoomButton)
        val labels = GameState.TURN_SECOND_OPTIONS.map { seconds ->
            when (seconds) {
                0 -> getString(R.string.no_time_limit)
                90 -> getString(R.string.turn_minutes_seconds, 1, 30)
                60 -> getString(R.string.turn_minutes, 1)
                else -> getString(R.string.turn_seconds, seconds)
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_turn_length)
            .setItems(labels) { _, which ->
                createButton.isEnabled = false
                repository.createRoom(
                    playerName(),
                    DicePreferences.getColor(this),
                    cardCount,
                    GameState.TURN_SECOND_OPTIONS[which]
                ) { code ->
                    createButton.isEnabled = true
                    trackGame(code)
                    openLobby(code)
                }
            }
            .show()
    }

    private fun playerName(): String = PlayerProfile.getName(this).ifEmpty { "Player" }

    private fun renderLeaderboard(entries: List<LeaderboardEntry>) {
        val list = findViewById<LinearLayout>(R.id.leaderboardList)
        val empty = findViewById<TextView>(R.id.leaderboardEmpty)
        list.removeAllViews()

        empty.visibility = if (entries.isEmpty()) TextView.VISIBLE else TextView.GONE
        if (entries.isEmpty()) return

        val density = resources.displayMetrics.density
        val myId = PlayerProfile.getId(this)

        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
            }
            val isMe = entry.playerId == myId
            val nameColor = if (isMe) {
                AccentColor.resolve(this)
            } else {
                resources.getColor(R.color.text_dark, theme)
            }

            row.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_muted, theme))
                layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = entry.name
                textSize = 15f
                maxLines = 1
                setTextColor(nameColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = entry.bestScore.toString()
                textSize = 15f
                setTextColor(nameColor)
            })

            list.addView(row)
        }
    }

    private fun openLobby(code: String) {
        val intent = Intent(this, LobbyActivity::class.java)
        intent.putExtra(LobbyActivity.EXTRA_ROOM_CODE, code)
        intent.putExtra(LobbyActivity.EXTRA_PLAYER_ID, repository.localPlayerId)
        intent.putExtra(LobbyActivity.EXTRA_PLAYER_NAME, playerName())
        startActivity(intent)
    }
}
