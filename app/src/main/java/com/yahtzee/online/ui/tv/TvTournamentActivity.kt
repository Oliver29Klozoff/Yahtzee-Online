package com.yahtzee.online.ui.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.game.Tournament
import com.yahtzee.online.game.TournamentState
import com.yahtzee.online.net.FirebaseSignIn
import com.yahtzee.online.net.TournamentRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.QrCode
import com.yahtzee.online.ui.tournament.BracketView

/**
 * A tournament on the television.
 *
 * The same division of labour the TV table already uses: the screen holds the draw and shows it,
 * every entrant is a phone, and nothing here is ever pressed. It opens a tournament nobody has
 * joined yet and puts the code up; people scan in from the sofa; the host's phone starts the
 * draw; and from then on this is the bracket everybody looks up at between matches.
 *
 * When a fixture is actually being played it steps aside for it — [TableActivity] takes the
 * screen for that room and hands it back when the game ends, so the big screen is showing
 * whichever of the two is currently worth watching.
 */
class TvTournamentActivity : ImmersiveActivity() {

    private val repository by lazy { TournamentRepository(this) }

    private var code: String = ""
    private var listener: ValueEventListener? = null
    private var lastState: TournamentState? = null

    /**
     * The fixture currently on screen as a table, so the hand-off happens once.
     *
     * Cleared when that screen gives the television back, which is what lets the next match take
     * it — and what stops a finished match being reopened the moment its room reports FINISHED.
     */
    private var showingMatch: String? = null

    /** Matches already shown, so one that has been watched is not opened a second time. */
    private val shown = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_tournament)
        findViewById<TextView>(R.id.tvTourneyHint).setText(R.string.tv_tourney_scan)
        openTournament()
    }

    /**
     * Opens the draw this screen is showing.
     *
     * Behind [FirebaseSignIn.awaitReady] for the same reason the table is: a television reaches
     * this on launch rather than through a menu, so on a device that has never run the app the
     * write would go out before there was a session to sign it with and be refused, leaving a
     * screen with no code on it and nothing saying why.
     */
    private fun openTournament() {
        FirebaseSignIn.awaitReady {
            repository.createSpectator(getString(R.string.tv_tourney_name), cardCount = 1) { made ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (made.isEmpty()) {
                        findViewById<TextView>(R.id.tvTourneyHint)
                            .setText(R.string.tv_tourney_failed)
                        return@runOnUiThread
                    }
                    code = made
                    findViewById<TextView>(R.id.tvTourneyCode).text = made
                    renderQr(made)
                    watch(made)
                }
            }
        }
    }

    private fun renderQr(code: String) {
        val size = (230 * resources.displayMetrics.density).toInt()
        QrCode.render("yahtzee://tourney/$code", size)?.let {
            findViewById<ImageView>(R.id.tvTourneyQr).setImageBitmap(it)
        }
    }

    private fun watch(code: String) {
        listener = repository.listen(code, onMissing = { finish() }) { state ->
            if (state == null) return@listen
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastState = state
                render(state)
                openLiveMatch(state)
            }
        }
    }

    private fun render(state: TournamentState) {
        findViewById<TextView>(R.id.tvTourneyTitle).text =
            state.name.ifEmpty { getString(R.string.tv_tourney_name) }

        val entrants = state.entrants
        findViewById<TextView>(R.id.tvTourneyStatus).text = when {
            state.status == Tournament.DONE ->
                getString(R.string.tv_tourney_won, championName(state))
            state.status == Tournament.OPEN ->
                resources.getQuantityString(R.plurals.tv_tourney_entered, entrants.size, entrants.size)
            else -> getString(R.string.tv_tourney_running)
        }

        // The code comes down once the draw is made: a bracket is a fixed field, and a screen
        // still inviting people into one that has started would be inviting them into nothing.
        findViewById<View>(R.id.tvJoinColumn).visibility =
            if (state.status == Tournament.OPEN) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.tvTourneyEntrants).text =
            entrants.joinToString("\n") { it.name }

        // Nobody is seated at a television, so the bracket is drawn from no player's point of
        // view — nothing on it is highlighted as "yours".
        findViewById<BracketView>(R.id.tvBracket).setBracket(state, localPlayerId = "")
    }

    private fun championName(state: TournamentState): String {
        val final = state.matches.values.filter { it.round == state.rounds - 1 }
        val winner = final.firstOrNull { it.decided }?.winnerId.orEmpty()
        return state.players[winner]?.name.orEmpty()
    }

    /**
     * Puts a fixture on the screen once it has a room to show.
     *
     * Only when exactly one is live. Two matches being played at once is an ordinary thing in a
     * first round, and there is no sensible way to show both on one television — so the bracket
     * stays up, which is at least the honest picture of a round in progress.
     */
    private fun openLiveMatch(state: TournamentState) {
        if (showingMatch != null) return

        val live = state.matches.values.filter {
            it.roomCode.isNotEmpty() && !it.decided && it.id !in shown
        }
        val only = live.singleOrNull() ?: return

        showingMatch = only.id
        shown.add(only.id)
        startActivity(
            Intent(this, TableActivity::class.java)
                .putExtra(TableActivity.EXTRA_ROOM_CODE, only.roomCode)
        )
    }

    override fun onResume() {
        super.onResume()
        // The table has handed the screen back. Clearing this here rather than when the match
        // finished is what makes the hand-off symmetrical: the next fixture can only take the
        // television once the last one has actually given it up.
        showingMatch = null
        lastState?.let { openLiveMatch(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(code, it) }
    }
}
