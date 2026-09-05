package com.yahtzee.online.ui.tournament

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
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.R
import com.yahtzee.online.bot.BotRun
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.Match
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.Tournament
import com.yahtzee.online.game.TournamentState
import com.yahtzee.online.game.TournamentStore
import com.yahtzee.online.net.GameRepository
import com.yahtzee.online.net.TournamentRepository
import com.yahtzee.online.ui.ImmersiveActivity
import com.yahtzee.online.ui.game.GameActivity
import com.yahtzee.online.ui.bot.SoloGameActivity

/**
 * A knockout tournament: the lobby before the draw, the bracket after it.
 *
 * Each match is an ordinary room, created when the first of its two players asks to play and
 * joined by the second from the code the match then carries. That is what keeps a tournament from
 * being a second game engine — everything about actually playing is the game that already exists,
 * and this screen only decides who plays whom and what it meant.
 */
class TournamentActivity : ImmersiveActivity() {

    companion object {
        const val EXTRA_CODE = "tourney_code"
    }

    private val repository by lazy { TournamentRepository(this) }
    private val games by lazy { GameRepository(this) }

    private var code: String = ""
    private var listener: ValueEventListener? = null
    private var state: TournamentState? = null

    /**
     * Bot fixtures already being played out, so a redraw does not start them again.
     *
     * The bracket redraws on every update to the tournament, and reporting a result is itself an
     * update — so without this each bot fixture kicked off another pair of games on every echo of
     * its own result.
     */
    private val resolving = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tournament)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.createTourneyButton).setOnClickListener { create() }
        findViewById<Button>(R.id.joinTourneyButton).setOnClickListener { joinTyped() }
        findViewById<Button>(R.id.startDrawButton).setOnClickListener {
            state?.let { repository.start(it) }
        }
        findViewById<Button>(R.id.addBotButton).setOnClickListener {
            state?.let { repository.addBot(it.code) }
        }

        findViewById<Button>(R.id.leaveTourneyButton).setOnClickListener { leave() }

        // Whatever this device is already in, unless the intent names something else. Backing out
        // of a bracket should be leaving the room, not leaving the tournament.
        val opening = intent.getStringExtra(EXTRA_CODE)?.takeIf { it.isNotEmpty() }
            ?: TournamentStore.current(this).takeIf { it.isNotEmpty() }
        opening?.let { open(it) }
    }

    /** Steps out of this tournament so a different one can be made or joined. */
    private fun leave() {
        listener?.let { repository.stopListening(code, it) }
        listener = null
        state = null
        code = ""
        resolving.clear()
        TournamentStore.forget(this)

        findViewById<View>(R.id.tourneyEntry).visibility = View.VISIBLE
        listOf(
            R.id.tourneyCode, R.id.tourneyStatus, R.id.championLine,
            R.id.startDrawButton, R.id.addBotButton, R.id.leaveTourneyButton, R.id.bracketScroll
        ).forEach { findViewById<View>(it).visibility = View.GONE }
        findViewById<LinearLayout>(R.id.tourneyBody).removeAllViews()
        findViewById<TextView>(R.id.tourneyTitle).setText(R.string.tourney_title)
    }

    private fun create() {
        val typed = findViewById<EditText>(R.id.tourneyNameInput).text.toString().trim()
        val name = typed.ifEmpty { getString(R.string.tourney_default_name) }
        repository.create(name, PlayerProfile.getName(this), cardCount = 1) { created ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (created.isEmpty()) {
                    Toast.makeText(this, R.string.tourney_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    open(created)
                }
            }
        }
    }

    private fun joinTyped() {
        val typed = findViewById<EditText>(R.id.tourneyCodeInput).text.toString().trim().uppercase()
        if (typed.isEmpty()) return
        repository.join(typed, PlayerProfile.getName(this)) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    TournamentRepository.JOIN_OK -> open(typed)
                    TournamentRepository.JOIN_FULL ->
                        Toast.makeText(this, R.string.tourney_full, Toast.LENGTH_SHORT).show()
                    TournamentRepository.JOIN_STARTED ->
                        Toast.makeText(this, R.string.tourney_started, Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(this, R.string.tourney_not_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun open(tourneyCode: String) {
        code = tourneyCode
        TournamentStore.remember(this, tourneyCode)
        findViewById<Button>(R.id.leaveTourneyButton).visibility = View.VISIBLE
        findViewById<View>(R.id.tourneyEntry).visibility = View.GONE
        findViewById<TextView>(R.id.tourneyCode).apply {
            text = tourneyCode
            visibility = View.VISIBLE
        }
        listener?.let { repository.stopListening(code, it) }
        listener = repository.listen(
            code,
            onMissing = {
                // Finished, swept, or deleted. Remembering it would leave this screen showing a
                // code and nothing else for ever, with no hint that the thing behind it is gone.
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.tourney_gone, Toast.LENGTH_LONG).show()
                    leave()
                }
            }
        ) { fresh ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                state = fresh
                fresh?.let { render(it) }
            }
        }
    }

    private fun render(state: TournamentState) {
        findViewById<TextView>(R.id.tourneyTitle).text =
            state.name.ifEmpty { getString(R.string.tourney_title) }

        val isHost = state.hostId == repository.localPlayerId
        val canStart = isHost && state.status == Tournament.OPEN &&
            state.players.size >= Tournament.MIN_PLAYERS
        findViewById<Button>(R.id.startDrawButton).visibility =
            if (canStart) View.VISIBLE else View.GONE

        val status = findViewById<TextView>(R.id.tourneyStatus)
        status.visibility = View.VISIBLE
        status.text = when {
            state.status != Tournament.OPEN -> getString(R.string.tourney_share, state.code)
            state.players.size < Tournament.MIN_PLAYERS -> getString(R.string.tourney_need_players)
            isHost -> getString(R.string.tourney_share, state.code)
            else -> getString(R.string.tourney_waiting)
        }

        val champion = state.champion
        findViewById<TextView>(R.id.championLine).apply {
            if (state.status == Tournament.DONE && champion.isNotEmpty()) {
                text = getString(
                    R.string.tourney_champion,
                    state.players[champion]?.name.orEmpty(),
                    state.name.ifEmpty { getString(R.string.tourney_title) }
                )
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        findViewById<Button>(R.id.addBotButton).visibility =
            if (isHost && state.status == Tournament.OPEN &&
                state.players.size < Tournament.MAX_PLAYERS
            ) View.VISIBLE else View.GONE

        if (state.status == Tournament.OPEN) renderEntrants(state) else {
            renderBracket(state)
            resolveBotMatches(state)
        }
    }

    /**
     * Plays out any fixture with a bot on both sides.
     *
     * Nobody is going to sit down at one, and an unplayed match stalls every round above it. They
     * are settled the moment a bracket with one in it is looked at — by whoever is looking, which
     * is a little arbitrary but needs no coordination and cannot deadlock. The repository ignores
     * a result for a match already decided, so two people opening the bracket at once is harmless.
     */
    private fun resolveBotMatches(state: TournamentState) {
        val skill = AppSettings.botSkill(this)
        val due = state.matches.values.filter {
            it.ready && !it.decided &&
                Tournament.isBot(it.aId) && Tournament.isBot(it.bId) &&
                resolving.add(it.id)
        }
        if (due.isEmpty()) return

        // Off the main thread, and this is not a nicety.
        //
        // At Expert the bot's decisions come from an exact search over every distinct hand, and
        // two whole games of that is seconds of work. Run inline it froze the screen for long
        // enough that Android offered to close the app — which from the sofa is indistinguishable
        // from a crash, and is what this looked like.
        Thread {
            due.forEach { match ->
                val a = BotRun.play(skill)
                val b = BotRun.play(skill)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    repository.report(state.code, match.id, a, b)
                }
            }
        }.start()
    }

    private fun renderEntrants(state: TournamentState) {
        val body = findViewById<LinearLayout>(R.id.tourneyBody)
        body.removeAllViews()
        findViewById<View>(R.id.bracketScroll).visibility = View.GONE
        body.addView(heading(getString(R.string.tourney_entrants)))
        state.entrants.forEach { entrant ->
            body.addView(TextView(this).apply {
                text = entrant.name
                textSize = 16f
                setPadding(0, pad(7), 0, pad(7))
                setTextColor(resources.getColor(R.color.text_dark, theme))
            })
        }
    }

    private fun renderBracket(state: TournamentState) {
        findViewById<LinearLayout>(R.id.tourneyBody).removeAllViews()
        findViewById<View>(R.id.bracketScroll).visibility = View.VISIBLE
        findViewById<BracketView>(R.id.bracketView).apply {
            onPlay = { match -> playMatch(state, match) }
            setBracket(state, repository.localPlayerId)
        }
    }

    /**
     * One fixture.
     *
     * A match you are in and could play right now gets the button; everything else is a line of
     * text. The button is on the row rather than in one place at the top because a bracket is read
     * by finding your own name in it, and the thing to do next should be where you found it.
     */
    private fun matchRow(state: TournamentState, match: Match, isMine: Boolean): View {
        val nameOf = { id: String -> state.players[id]?.name.orEmpty() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(8), 0, pad(8))
        }

        val text = when {
            match.decided && match.bId.isEmpty() -> getString(R.string.tourney_bye, nameOf(match.aId))
            match.decided -> getString(
                R.string.tourney_result,
                nameOf(match.aId), match.aScore, match.bScore, nameOf(match.bId)
            )
            match.ready -> getString(R.string.tourney_vs, nameOf(match.aId), nameOf(match.bId))
            else -> getString(R.string.tourney_awaiting)
        }

        row.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(
                when {
                    isMine -> AccentColor.resolve(this@TournamentActivity)
                    match.ready || match.decided -> resources.getColor(R.color.text_dark, theme)
                    else -> resources.getColor(R.color.text_muted, theme)
                }
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (isMine && match.ready && !match.decided) {
            row.addView(Button(this).apply {
                setText(R.string.tourney_play)
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                setPadding(pad(12), 0, pad(12), 0)
                setOnClickListener { playMatch(state, match) }
            })
        }
        return row
    }

    /**
     * Puts the two of you in a room for this fixture.
     *
     * Whoever asks first creates the room and writes its code onto the match; whoever asks second
     * finds the code already there and joins it. No coordination beyond the match itself, and no
     * new kind of game — from the moment both are in, it is an ordinary two-player room that
     * happens to know which fixture it settles.
     */
    private fun playMatch(state: TournamentState, match: Match) {
        val name = PlayerProfile.getName(this)
        val colour = DicePreferences.getColor(this)

        // A bot has no phone to join a room from, so a fixture against one is played on this
        // device as an ordinary solo game that happens to know which fixture it settles.
        if (Tournament.isBot(match.opponentOf(repository.localPlayerId))) {
            startActivity(
                Intent(this, SoloGameActivity::class.java)
                    .putExtra(SoloGameActivity.EXTRA_PLAYER_NAME, name)
                    .putExtra(SoloGameActivity.EXTRA_BOT_COUNT, 1)
                    .putExtra(
                        SoloGameActivity.EXTRA_BOT_NAME,
                        state.players[match.opponentOf(repository.localPlayerId)]?.name.orEmpty()
                    )
                    .putExtra(SoloGameActivity.EXTRA_CARD_COUNT, state.cardCount)
                    .putExtra(SoloGameActivity.EXTRA_TOURNEY_CODE, state.code)
                    .putExtra(SoloGameActivity.EXTRA_MATCH_ID, match.id)
            )
            return
        }

        if (match.roomCode.isNotEmpty()) {
            games.joinRoom(match.roomCode, name, colour) { joined ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (joined == GameRepository.JOIN_OK) enterGame(match.roomCode, state, match)
                    else Toast.makeText(this, R.string.tourney_not_found, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        games.createRoom(name, colour, cardCount = state.cardCount, turnSeconds = 0) { room ->
            repository.claimRoom(state.code, match.id, room)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                enterGame(room, state, match)
            }
        }
    }

    private fun enterGame(roomCode: String, state: TournamentState, match: Match) {
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_ROOM_CODE, roomCode)
                .putExtra(GameActivity.EXTRA_PLAYER_ID, repository.localPlayerId)
                .putExtra(GameActivity.EXTRA_TOURNEY_CODE, state.code)
                .putExtra(GameActivity.EXTRA_MATCH_ID, match.id)
        )
    }

    private fun heading(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, pad(18), 0, pad(6))
        setTextColor(resources.getColor(R.color.text_muted, theme))
    }

    private fun pad(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { repository.stopListening(code, it) }
    }
}
