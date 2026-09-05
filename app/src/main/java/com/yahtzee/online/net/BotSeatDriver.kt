package com.yahtzee.online.net

import android.os.Handler
import android.os.Looper
import com.yahtzee.online.bot.BotSkillPlay
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.Tournament
import com.yahtzee.online.game.scoresForCard

/**
 * Plays the turns of any bot sitting in an online room.
 *
 * A bot seat is an ordinary player in the database — the television draws it, the scorecard
 * counts it, the room waits on it — but it has no phone behind it, so somebody's client has to
 * move it. That somebody is the host, and only the host: every screen watching the room sees the
 * same bot turn arrive, and if they all played it the dice would be rolled several times over and
 * the score written several times over.
 *
 * Two things this deliberately does not do. It does not play instantly — a bot that scores the
 * moment its turn opens reads as the app skipping a player rather than as somebody taking a turn,
 * and on a shared screen there would be nothing to watch. And it does not run on the main thread:
 * the expert search costs a few hundred thousand array reads, which is nothing in a loop and an
 * ANR on the UI thread — the lesson the tournament bracket taught.
 */
class BotSeatDriver(
    private val context: android.content.Context,
    private val repository: GameRepository,
    /**
     * Which phase of the room this instance is responsible for.
     *
     * Not decoration. The lobby does not go away when the game opens on top of it — its listener
     * is still live, which is how a rematch finds its way back — so a driver in each screen that
     * both answered to PLAYING would be two drivers on one device rolling the same bot's dice
     * twice. The roll-off belongs to the lobby, the turns belong to the game, and neither
     * answers for the other.
     */
    private val phase: String
) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Turns already being played, keyed by what makes a turn unique.
     *
     * Firebase re-delivers the room on every field that changes, so a single bot turn arrives as
     * a stream of snapshots that all say the same thing: it is still the bot's go. Without this
     * each one would start another roll on top of the last.
     */
    private val working = mutableSetOf<String>()

    private var stopped = false

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
    }

    /**
     * Called with every snapshot of the room. Does nothing unless this device is the host and the
     * room is genuinely waiting on a bot.
     */
    fun onState(code: String, state: GameState, myPlayerId: String) {
        if (stopped) return
        if (state.hostId != myPlayerId) return
        if (state.status != phase) return

        when (state.status) {
            GameState.STATUS_ROLL_OFF -> driveRollOff(code, state)
            GameState.STATUS_PLAYING -> driveTurn(code, state)
        }
    }

    /**
     * Rolls the opening die for any bot that has not rolled one.
     *
     * Bots are in the draw for first like everybody else. Left out of it the roll-off would never
     * complete — it waits for every eligible player — and the room would sit on the dice forever.
     */
    private fun driveRollOff(code: String, state: GameState) {
        val eligible =
            if (state.openingRollTied.isNotEmpty()) state.openingRollTied else state.playerOrder
        val waiting = eligible.firstOrNull {
            Tournament.isBot(it) && !state.openingRolls.containsKey(it)
        } ?: return

        val key = "rolloff:${state.roomCode}:$waiting:${state.openingRolls.size}"
        if (!working.add(key)) return

        main.postDelayed({
            if (!stopped) repository.rollForFirst(code, state, waiting)
            working.remove(key)
        }, ROLL_OFF_DELAY_MS)
    }

    /** Plays one step of a bot's turn: a roll, or the score that ends it. */
    private fun driveTurn(code: String, state: GameState) {
        val botId = state.currentPlayerId?.takeIf { Tournament.isBot(it) } ?: return
        if (!state.players.containsKey(botId)) return

        // Keyed on the roll count as well as the seat, so the three steps of one turn are three
        // different pieces of work rather than one that is refused twice.
        val key = "turn:${state.roomCode}:$botId:${state.currentTurnIndex}:${state.rollsUsed}"
        if (!working.add(key)) return

        val skill = AppSettings.botSkill(context)
        val open = openByCard(state, botId)
        if (open.isEmpty()) {
            working.remove(key)
            return
        }

        Thread {
            val action = runCatching { decide(state, open, skill) }.getOrNull()
            main.postDelayed({
                if (!stopped && action != null) apply(code, state, botId, action)
                working.remove(key)
            }, if (state.rollsUsed == 0) FIRST_ROLL_DELAY_MS else STEP_DELAY_MS)
        }.start()
    }

    private fun apply(code: String, state: GameState, botId: String, action: Action) {
        when (action) {
            is Action.Roll ->
                repository.rollDice(code, state.dice, List(5) { false }, 0, state.turnMillis, resetTimer = false)

            is Action.Reroll -> {
                // Written before the roll so the table can see what the bot decided to keep.
                repository.setHeld(code, action.held)
                repository.rollDice(
                    code, state.dice, action.held, state.rollsUsed, state.turnMillis, resetTimer = false
                )
            }

            is Action.Score ->
                repository.submitScore(code, state, action.category, botId, action.card)
        }
    }

    /**
     * The decision half, kept free of Android and of the network.
     *
     * Here rather than inline in the driver so it can be exercised directly: what a bot does with
     * a hand is the part worth being sure of, and the rest of this class is a Handler, a Firebase
     * reference and a set of keys.
     */
    companion object {
        /** Long enough that the table sees whose turn it is before the dice move. */
        private const val FIRST_ROLL_DELAY_MS = 1200L
        private const val STEP_DELAY_MS = 1600L
        private const val ROLL_OFF_DELAY_MS = 900L

        /** What the bot wants to do, worked out off the UI thread. */
        sealed interface Action {
            object Roll : Action
            data class Reroll(val held: List<Boolean>) : Action
            data class Score(val card: Int, val category: Category) : Action
        }

        /** The open categories on each card the bot still has room on. */
        fun openByCard(state: GameState, botId: String): Map<Int, Set<Category>> {
            val player = state.players[botId] ?: return emptyMap()
            return (0 until state.cardCount.coerceAtLeast(1))
                .associateWith { card ->
                    Category.values()
                        .filter { !player.scores.containsKey(ScoreKey.of(card, it)) }
                        .toSet()
                }
                .filterValues { it.isNotEmpty() }
        }

        fun decide(
            state: GameState,
            openByCard: Map<Int, Set<Category>>,
            skill: AppSettings.BotSkill
        ): Action {
            if (state.rollsUsed == 0) return Action.Roll

            val open = openByCard.values.flatten().toSet()
            val player = state.players[state.currentPlayerId] ?: return Action.Roll
            // The best upper total across the cards in play — the one the bonus is closest on.
            val upperBest = openByCard.keys.maxOfOrNull { card ->
                Category.UPPER.sumOf { player.scoresForCard(card)[it] ?: 0 }
            } ?: 0

            if (state.rollsUsed < MAX_ROLLS_PER_TURN) {
                val holds = BotSkillPlay.chooseHolds(
                    skill, state.dice, open, MAX_ROLLS_PER_TURN - state.rollsUsed, upperBest
                )
                // Keeping all five is the bot saying it is content, not a reroll of nothing.
                if (holds.size < 5) return Action.Reroll(List(5) { it in holds })
            }

            val best = openByCard.entries
                .map { (card, cardOpen) ->
                    val upperTotal = Category.UPPER.sumOf { player.scoresForCard(card)[it] ?: 0 }
                    val category = BotSkillPlay.chooseCategory(skill, state.dice, cardOpen, upperTotal)
                    Triple(card, category, Scoring.score(category, state.dice))
                }
                .maxByOrNull { it.third }
                ?: return Action.Roll
            return Action.Score(best.first, best.second)
        }
    }
}
