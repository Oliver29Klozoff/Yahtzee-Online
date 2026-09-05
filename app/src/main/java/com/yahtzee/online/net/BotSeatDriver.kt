package com.yahtzee.online.net

import android.os.Handler
import android.os.Looper
import com.yahtzee.online.bot.BotReactions
import com.yahtzee.online.bot.BotSkillPlay
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.LastTurn
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
     * Steps this driver has already taken, and will never take again.
     *
     * Firebase re-delivers the room on every field that changes, so one bot turn arrives as a
     * stream of snapshots all saying the same thing: it is still the bot's go. Without this each
     * one would start another roll on top of the last.
     *
     * Marked done and left that way, rather than released once the work finishes. Releasing was
     * the first attempt and it rolled the dice twice: a reroll is two writes — the held dice,
     * then the dice and the roll count — and the snapshot that lands between them still carries
     * the old roll count. Against a guard that had just been released, that snapshot read as a
     * step not yet taken, and the bot rolled again on the same one.
     *
     * The cost of never releasing is that a write which silently fails is not retried. That is
     * the better failure: a stalled bot is visible and survives reopening the room, where a
     * bot that rolls twice quietly rewrites a turn nobody can get back.
     */
    private val handled = mutableSetOf<String>()

    private var stopped = false

    /**
     * The room as of the most recent snapshot.
     *
     * A bot's move is decided off the main thread and written a second or so later, deliberately,
     * so the table has something to watch. The room can move underneath that pause — and a write
     * aimed at a bot's turn that lands after the turn has passed is a roll taken on somebody
     * else's go, which from the other side of the table looks exactly like the app playing your
     * turn for you. Checked against this immediately before writing.
     */
    private var latest: GameState? = null

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
        val previous = latest
        latest = state
        if (state.hostId != myPlayerId) return
        if (state.status != phase) return

        if (state.status == GameState.STATUS_PLAYING) applaud(code, previous, state)

        when (state.status) {
            GameState.STATUS_ROLL_OFF -> driveRollOff(code, state)
            GameState.STATUS_PLAYING -> driveTurn(code, state)
        }
    }

    /**
     * Lets a bot respond to somebody else's turn.
     *
     * One bot, not all of them: a table of bots applauding in unison is a machine, not a room. The
     * first seated is as good a choice as any and is at least consistent, so the same one is
     * always the one who says something.
     *
     * A bot's own score is answered where it is scored, not here — this is only for reading the
     * table, which is the half that makes them feel like they are at it.
     */
    private fun applaud(code: String, previous: GameState?, state: GameState) {
        val scored = LastTurn.detect(previous, state) ?: return
        if (Tournament.isBot(scored.playerId)) return

        val responder = state.playerOrder.firstOrNull { Tournament.isBot(it) } ?: return
        BotReactions.forOtherScore(scored.category, scored.points)
            ?.let { repository.sendReactionAs(code, responder, it) }
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

        // The tied list identifies the round, so a tie that clears the rolls and asks again is a
        // new piece of work rather than one already marked done.
        val key = "rolloff:$waiting:${state.openingRollTied.joinToString(",")}"
        if (!handled.add(key)) return

        main.postDelayed({
            if (!stopped) repository.rollForFirst(code, state, waiting)
        }, ROLL_OFF_DELAY_MS)
    }

    /** Plays one step of a bot's turn: a roll, or the score that ends it. */
    private fun driveTurn(code: String, state: GameState) {
        val botId = state.currentPlayerId?.takeIf { Tournament.isBot(it) } ?: return
        if (!state.players.containsKey(botId)) return

        // Identifies one step of one turn, and stays identified afterwards.
        //
        // How many boxes the bot has filled rather than the turn index: the index comes round
        // again every lap of the table, so keys built on it would repeat and a later turn would
        // be mistaken for one already played. Filled boxes only ever go up.
        val filled = state.players[botId]?.scores?.size ?: 0
        val key = "turn:$botId:$filled:${state.rollsUsed}"
        if (!handled.add(key)) return

        val skill = AppSettings.botSkill(context)
        val open = openByCard(state, botId)
        if (open.isEmpty()) return

        Thread {
            val action = runCatching { decide(state, open, skill) }.getOrNull()
            main.postDelayed({
                // Re-checked here, not at decision time: the pause is the whole point of the
                // delay, and the room is free to move during it.
                when {
                    stopped || action == null -> Unit
                    stillDue(botId, state) -> apply(code, state, botId, action)
                    // Decided for a moment that has passed. Give the key back rather than
                    // leaving it spent: the step it names may still be genuinely due later, and
                    // a key burned on a decision that was never applied is a bot that reaches
                    // that step and is refused — which looks like it hanging on its own score.
                    else -> handled.remove(key)
                }
            }, if (state.rollsUsed == 0) FIRST_ROLL_DELAY_MS else STEP_DELAY_MS)
        }.start()
    }

    /**
     * Whether the move decided a moment ago is still the move the room is waiting for.
     *
     * The turn must still be this bot's, and still on the same roll. Anything else means the
     * room moved on during the pause before the write, and the write would land on a turn that
     * is no longer the one it was decided for.
     */
    private fun stillDue(botId: String, decidedAt: GameState): Boolean {
        val now = latest ?: return false
        return now.status == GameState.STATUS_PLAYING &&
            now.currentPlayerId == botId &&
            now.rollsUsed == decidedAt.rollsUsed
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

            is Action.Score -> {
                repository.submitScore(code, state, action.category, botId, action.card)
                // Sent after the score, so the emoji arrives with the news rather than ahead of
                // it. Silence most of the time is the point; see BotReactions.
                BotReactions.forOwnScore(
                    action.category,
                    Scoring.score(action.category, state.dice)
                )?.let { repository.sendReactionAs(code, botId, it) }
            }
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
