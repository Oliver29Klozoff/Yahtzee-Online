package com.yahtzee.online.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.yahtzee.online.R
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.bot.SoloGameActivity
import com.yahtzee.online.ui.lobby.LobbyActivity
import com.yahtzee.online.update.UpdateChecker

class MainActivity : ImmersiveActivity() {

    private val repository = GameRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val roomCodeInput = findViewById<EditText>(R.id.roomCodeInput)
        val createButton = findViewById<Button>(R.id.createRoomButton)
        val joinButton = findViewById<Button>(R.id.joinRoomButton)
        val playVsBotsButton = findViewById<Button>(R.id.playVsBotsButton)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        UpdateChecker(this).cleanupStaleApk()

        playVsBotsButton.setOnClickListener {
            val name = nameInput.text.toString().trim().ifEmpty { "You" }
            val botOptions = arrayOf("1 bot", "2 bots", "3 bots", "4 bots")
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_bot_count)
                .setItems(botOptions) { _, which ->
                    val botCount = which + 1
                    val intent = Intent(this, SoloGameActivity::class.java)
                    intent.putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, name)
                    intent.putExtra(SoloGameActivity.EXTRA_BOT_COUNT, botCount)
                    startActivity(intent)
                }
                .show()
        }

        createButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.your_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createButton.isEnabled = false
            repository.createRoom(name) { code ->
                createButton.isEnabled = true
                openLobby(code, name)
            }
        }

        joinButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val code = roomCodeInput.text.toString().trim().uppercase()
            if (name.isEmpty() || code.isEmpty()) {
                Toast.makeText(this, R.string.room_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            joinButton.isEnabled = false
            repository.joinRoom(code, name) { success ->
                joinButton.isEnabled = true
                if (success) {
                    openLobby(code, name)
                } else {
                    Toast.makeText(this, "Room not found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openLobby(code: String, name: String) {
        val intent = Intent(this, LobbyActivity::class.java)
        intent.putExtra(LobbyActivity.EXTRA_ROOM_CODE, code)
        intent.putExtra(LobbyActivity.EXTRA_PLAYER_ID, repository.localPlayerId)
        intent.putExtra(LobbyActivity.EXTRA_PLAYER_NAME, name)
        startActivity(intent)
    }
}
