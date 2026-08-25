package com.yahtzee.online.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.yahtzee.online.R
import com.yahtzee.online.game.PlayerProfile

/**
 * Asks for the player's name. Shown once on first launch, from [SplashActivity], and reachable
 * afterwards by tapping the greeting on the start screen to change it.
 */
class NameActivity : ImmersiveActivity() {

    companion object {
        /**
         * Set when opened to edit an existing name, in which case this returns to the caller
         * instead of continuing on to the start screen.
         */
        const val EXTRA_EDIT_MODE = "edit_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_name)

        val editMode = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val continueButton = findViewById<Button>(R.id.continueButton)

        nameInput.setText(PlayerProfile.getName(this))
        nameInput.setSelection(nameInput.text.length)

        continueButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PlayerProfile.setName(this, name)
            if (!editMode) {
                // Carry an invite through the name page.
                //
                // Someone following a challenge on a phone that has never run the app lands here
                // first, and the code they tapped was being dropped on the floor — they arrived at
                // the start screen with no idea they had been invited to anything, which is
                // precisely the person the invite most needed to work for.
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        intent.getStringExtra(MainActivity.EXTRA_JOIN_ROOM)
                            ?.let { putExtra(MainActivity.EXTRA_JOIN_ROOM, it) }
                        intent.getStringExtra(MainActivity.EXTRA_JOIN_DUEL)
                            ?.let { putExtra(MainActivity.EXTRA_JOIN_DUEL, it) }
                    }
                )
            }
            finish()
        }
    }
}
