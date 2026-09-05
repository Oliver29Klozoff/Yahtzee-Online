package com.yahtzee.online.game

/**
 * The turn that has just been played, held on screen until somebody moves the game on.
 *
 * A turn used to vanish the instant it ended. The score appeared somewhere in a scorecard nobody
 * was looking at, the dice changed to the next player's colour, the name at the top changed, and
 * the only thing that said what had happened was a popup already on its way out. On a shared
 * screen especially, the moment everyone looks up is the moment after a turn ends — and by then
 * there was nothing left to look at.
 *
 * So the screen stays on the finished turn: what was scored, in whose colour, under whose name,
 * until the next player actually rolls. The hand-over is not a moment, it is a state, and
 * [isHandover] is how the room knows it is in it — `rollsUsed` returning to zero is precisely
 * "the turn is over and the next one has not begun", which is the whole window this needs.
 */
object LastTurn {

    /** A finished turn: who played it and what they took for it. */
    data class Scored(
        val playerId: String,
        val playerName: String,
        val category: Category,
        val label: String,
        val points: Int
    )

    /**
     * Whether the room is between turns — a turn has ended and the next player has yet to roll.
     *
     * Only during play. A finished game is not waiting on a roll, and the roll-off has its own
     * screen.
     */
    fun isHandover(state: GameState): Boolean =
        state.status == GameState.STATUS_PLAYING && state.rollsUsed == 0

    /**
     * The box that has just been filled, or null if nothing changed between these two snapshots.
     *
     * Every player, including the one looking. [com.yahtzee.online.ui.game.ScoreAnnounce] leaves
     * your own score out because announcing it back at you is noise; this is not an announcement
     * but the state of the table, and the table shows your finished turn the same as anyone's.
     *
     * Read off the scorecards rather than from a field on the room, for the reason the announcer
     * uses: a box that was empty and now is not is a score somebody has just taken, so there is
     * nothing to write, no rule to change, and no way for this to disagree with the card it came
     * from.
     */
    fun detect(previous: GameState?, current: GameState): Scored? {
        if (previous == null) return null

        return current.players.values
            .mapNotNull { player ->
                val before = previous.players[player.id]?.scores ?: return@mapNotNull null
                val fresh = player.scores.keys - before.keys
                if (fresh.isEmpty()) return@mapNotNull null

                // Several at once means a client catching up rather than a turn being played;
                // the biggest is the one worth showing.
                val key = fresh.maxByOrNull { player.scores[it] ?: 0 } ?: return@mapNotNull null
                val category = ScoreKey.categoryOf(key) ?: return@mapNotNull null
                Scored(
                    playerId = player.id,
                    playerName = player.name,
                    category = category,
                    label = category.label,
                    points = player.scores[key] ?: 0
                )
            }
            .maxByOrNull { it.points }
    }
}
