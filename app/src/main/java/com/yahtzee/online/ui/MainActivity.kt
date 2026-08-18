package com.yahtzee.online.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.yahtzee.online.R
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.ui.lobby.LobbyActivity

class MainActivity : AppCompatActivity() {

    private val repository = GameRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val roomCodeInput = findViewById<EditText>(R.id.roomCodeInput)
        val createButton = findViewById<Button>(R.id.createRoomButton)
        val joinButton = findViewById<Button>(R.id.joinRoomButton)

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
