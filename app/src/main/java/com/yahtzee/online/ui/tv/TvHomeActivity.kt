package com.yahtzee.online.ui.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.yahtzee.online.R
import com.yahtzee.online.ui.ImmersiveActivity

/**
 * What the television offers when it is switched on: a table, or a tournament.
 *
 * The TV used to go straight to the table, which was right while that was the only thing it could
 * do. A chooser is the smallest thing that adds the second without taking anything away — the
 * table is focused when the screen opens, so the old behaviour is still one press of the remote.
 *
 * Deliberately two buttons and no more. Everything else about playing happens on a phone, and a
 * television menu is the wrong place to grow features into: it is operated with four arrows and a
 * select key by somebody sitting across a room.
 */
class TvHomeActivity : ImmersiveActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_home)

        val table = findViewById<Button>(R.id.tvOpenTable)
        table.setOnClickListener {
            startActivity(Intent(this, TableActivity::class.java))
        }
        findViewById<Button>(R.id.tvOpenTournament).setOnClickListener {
            startActivity(Intent(this, TvTournamentActivity::class.java))
        }

        table.requestFocus()
    }
}
