package com.yahtzee.online.ui.duel

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.Duel
import com.yahtzee.online.game.DuelState
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.SoloGameStore
import com.yahtzee.online.net.DuelRepository
import com.yahtzee.online.net.TurnCheckWorker
import com.yahtzee.online.net.TurnNotifier
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.QrCode
import com.yahtzee.online.ui.bot.SoloGameActivity

/**
 * One duel: how to invite people into it, and how everyone did.
 *
 * Both halves live on one screen because they are the same screen at different moments. Before
 * anyone has played it is an invitation; once they have it is a result; in between it is both,
 * and splitting them would mean a player who came back to check a score landed on a page about
 * sharing a code instead.
 */
class DuelActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_DUEL_CODE = "duel_code"

        /** Set when arriving from a link or a scan, so the seat is taken on the way in. */
        const val EXTRA_JOIN = "duel_join"
    }

    private lateinit var repository: DuelRepository
    private var code: String = ""
    private var listener: ValueEventListener? = null
    private var lastState: DuelState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_duel)
        repository = DuelRepository(this)

        code = intent.getStringExtra(EXTRA_DUEL_CODE).orEmpty().uppercase()
        if (code.isEmpty()) {
            finish()
            return
        }

        // A duel is answered whenever the other person gets round to it, so the background check
        // needs to be running for the result to arrive on its own.
        TurnNotifier.ensureChannel(this)
        TurnCheckWorker.schedule(this)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.shareDuelButton).setOnClickListener { shareDuel() }
        findViewById<Button>(R.id.playDuelButton).setOnClickListener { play() }

        renderInvite()

        if (intent.getBooleanExtra(EXTRA_JOIN, false)) {
            intent.removeExtra(EXTRA_JOIN)
            repository.joinDuel(code, playerName()) { joined ->
                if (isFinishing || isDestroyed) return@joinDuel
                if (!joined) {
                    Toast.makeText(this, R.string.duel_not_found, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        listener = repository.listen(code) { state ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastState = state
                if (state == null) {
                    // Swept up, or deleted by whoever opened it. Nothing to show and no way back
                    // into it, so say so rather than leaving an empty page.
                    Toast.makeText(this, R.string.duel_not_found, Toast.LENGTH_LONG).show()
                    Duel.forget(this, code)
                    finish()
                    return@runOnUiThread
                }
                render(state)
            }
        }
    }

    private fun renderInvite() {
        findViewById<TextView>(R.id.duelCodeText).text = code
        val size = (resources.displayMetrics.density * 180).toInt()
        QrCode.render(Duel.linkFor(code), size)?.let {
            findViewById<ImageView>(R.id.duelQr).setImageBitmap(it)
        }
    }

    private fun render(state: DuelState) {
        renderPlayers(state)
        renderVerdict(state)

        // Once every seat has posted there is nothing left to invite anyone to.
        findViewById<View>(R.id.inviteSection).visibility =
            if (state.isSettled) View.GONE else View.VISIBLE

        val playButton = findViewById<Button>(R.id.playDuelButton)
        val played = Duel.hasPlayed(this, code) ||
            state.players.any { it.id == repository.localPlayerId && it.hasPlayed }
        playButton.isEnabled = !played
        playButton.alpha = if (played) 0.5f else 1f
        playButton.setText(if (played) R.string.duel_played else R.string.duel_play)
    }

    private fun renderPlayers(state: DuelState) {
        val list = findViewById<LinearLayout>(R.id.duelPlayers)
        list.removeAllViews()
        val density = resources.displayMetrics.density
        val accent = AccentColor.resolve(this)

        // Finished players first in score order, then whoever is still going. A duel is read as a
        // ranking, and someone who has not played yet has no place in one.
        val ordered = state.standings + state.waiting
        if (ordered.isEmpty()) {
            list.addView(lineFor(getString(R.string.duel_nobody_yet), null, false, accent, density))
            return
        }

        ordered.forEachIndexed { index, player ->
            val isMe = player.id == repository.localPlayerId
            val name = if (isMe) getString(R.string.duel_you, player.name) else player.name
            val label = when {
                !player.hasPlayed -> getString(R.string.duel_still_playing)
                else -> player.score.toString()
            }
            val leading = player.hasPlayed && index == 0 && state.standings.size > 1
            list.addView(lineFor(name, label, leading, accent, density))
        }
    }

    private fun lineFor(
        name: String,
        score: String?,
        highlight: Boolean,
        accent: Int,
        density: Float
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * density).toInt(), (12 * density).toInt(),
                (14 * density).toInt(), (12 * density).toInt()
            )
            background = getDrawable(R.drawable.scorecard_card_background)
        }
        row.addView(
            TextView(this).apply {
                text = name
                textSize = 15f
                setTextColor(getColor(R.color.text_dark))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (score != null) {
            row.addView(
                TextView(this).apply {
                    text = score
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (highlight) accent else getColor(R.color.text_dark))
                }
            )
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * density).toInt() }
        row.layoutParams = params
        return row
    }

    private fun renderVerdict(state: DuelState) {
        val verdict = findViewById<TextView>(R.id.duelVerdict)
        val winner = state.winner
        if (winner == null) {
            verdict.visibility = View.GONE
            return
        }

        // A draw is a real outcome on identical dice and deserves saying out loud, rather than
        // quietly handing the win to whoever happened to sort first.
        val best = state.standings.filter { it.score == winner.score }
        verdict.visibility = View.VISIBLE
        verdict.text = when {
            best.size > 1 -> getString(R.string.duel_draw, winner.score ?: 0)
            winner.id == repository.localPlayerId -> getString(R.string.duel_you_won, winner.score ?: 0)
            else -> getString(R.string.duel_they_won, winner.name, winner.score ?: 0)
        }
    }

    /**
     * Opens the duel's own game.
     *
     * Guarded against overwriting an unrelated solo game in progress for the same reason the daily
     * challenge is: the duel saves through the same slot, and losing a game forty turns in to a
     * menu tap is exactly the complaint the continue button exists to answer.
     */
    private fun play() {
        val saved = SoloGameStore.loadResumable(this)
        if (saved != null && saved.dailyId == null && saved.duelCode != code) {
            AlertDialog.Builder(this)
                .setTitle(R.string.duel_title)
                .setMessage(R.string.daily_replaces_game)
                .setPositiveButton(R.string.daily_start_anyway) { _, _ ->
                    SoloGameStore.clear(this)
                    launch()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        launch()
    }

    private fun launch() {
        startActivity(
            Intent(this, SoloGameActivity::class.java)
                .putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, playerName())
                .putExtra(SoloGameActivity.EXTRA_DUEL_CODE, code)
        )
    }

    private fun shareDuel() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(
                        Intent.EXTRA_TEXT,
                        getString(R.string.duel_share_text, code, Duel.linkFor(code))
                    ),
                getString(R.string.duel_share)
            )
        )
    }

    private fun playerName(): String = PlayerProfile.getName(this).ifEmpty { "Player" }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(code, it) }
    }
}
