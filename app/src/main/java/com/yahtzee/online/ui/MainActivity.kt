package com.yahtzee.online.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.game.DailyChallenge
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.SoloGameStore
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.net.LeaderboardEntry
import com.yahtzee.online.net.LeaderboardRepository
import com.yahtzee.online.ui.bot.SoloGameActivity
import com.yahtzee.online.ui.lobby.LobbyActivity
import com.yahtzee.online.update.UpdateChecker

class MainActivity : ImmersiveActivity() {

    private companion object {
        /**
         * Process-scoped, so the launch check runs once per cold boot. MainActivity is recreated
         * every time the player backs out of a game, and without this the prompt would reappear
         * each time they returned to the menu.
         */
        var checkedThisLaunch = false

        const val LEADERBOARD_SIZE = 10
    }

    private val repository = GameRepository()
    private val leaderboard = LeaderboardRepository()
    private var leaderboardListener: ValueEventListener? = null

    /** The daily board is a separate query, so it needs its own handle to detach. */
    private var dailyQuery: com.google.firebase.database.Query? = null
    private var dailyListener: ValueEventListener? = null

    /** Which board the leaderboard section is currently showing. */
    private var showingDaily = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            joinButton.isEnabled = false
            repository.joinRoom(code, playerName(), DicePreferences.getColor(this)) { success ->
                joinButton.isEnabled = true
                if (success) {
                    openLobby(code)
                } else {
                    Toast.makeText(this, "Room not found", Toast.LENGTH_SHORT).show()
                }
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
        // Re-read on resume so a name changed on the name page shows immediately on return.
        findViewById<TextView>(R.id.greetingText).text =
            getString(R.string.greeting, playerName())

        renderDailyCard()
        observeLeaderboard()
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
                resources.getColor(R.color.brand_primary, theme)
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
