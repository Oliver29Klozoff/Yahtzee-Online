package com.yahtzee.online.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.PlayerProfile
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val roomCodeInput = findViewById<EditText>(R.id.roomCodeInput)
        val createButton = findViewById<Button>(R.id.createRoomButton)
        val joinButton = findViewById<Button>(R.id.joinRoomButton)
        val playVsBotsButton = findViewById<Button>(R.id.playVsBotsButton)
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

        // Cold boot: sweep any APK left behind by a previous update, then quietly look for a new
        // release. Only runs on a genuinely fresh start, not on every return to the menu from a
        // game, which would re-prompt mid-session.
        val updateChecker = UpdateChecker(this)
        updateChecker.cleanupStaleApk()
        if (savedInstanceState == null && !checkedThisLaunch) {
            checkedThisLaunch = true
            updateChecker.checkOnLaunch()
        }

        playVsBotsButton.setOnClickListener {
            val botOptions = arrayOf("1 bot", "2 bots", "3 bots", "4 bots")
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_bot_count)
                .setItems(botOptions) { _, which ->
                    val intent = Intent(this, SoloGameActivity::class.java)
                    intent.putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, playerName())
                    intent.putExtra(SoloGameActivity.EXTRA_BOT_COUNT, which + 1)
                    startActivity(intent)
                }
                .show()
        }

        createButton.setOnClickListener {
            // The host picks the format up front, so everyone who joins the room plays it.
            val labels = GameState.CARD_OPTIONS.map { count ->
                if (count == 1) getString(R.string.one_card) else getString(R.string.n_cards, count)
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_card_count)
                .setItems(labels) { _, which ->
                    val cardCount = GameState.CARD_OPTIONS[which]
                    createButton.isEnabled = false
                    repository.createRoom(playerName(), DicePreferences.getColor(this), cardCount) { code ->
                        createButton.isEnabled = true
                        openLobby(code)
                    }
                }
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
        // Re-read on resume so a name changed on the name page shows immediately on return.
        findViewById<TextView>(R.id.greetingText).text =
            getString(R.string.greeting, playerName())

        leaderboardListener = leaderboard.observeTop(LEADERBOARD_SIZE) { entries ->
            runOnUiThread { renderLeaderboard(entries) }
        }
    }

    override fun onPause() {
        super.onPause()
        leaderboardListener?.let { leaderboard.removeListener(it) }
        leaderboardListener = null
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
