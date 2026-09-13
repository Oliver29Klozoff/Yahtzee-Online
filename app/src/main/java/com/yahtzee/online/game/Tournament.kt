package com.yahtzee.online.game

/** Somebody entered in a tournament. [seed] is their position in the draw, from zero. */
data class Entrant(
    val id: String = "",
    val name: String = "",
    val joinedAt: Long = 0L,
    val seed: Int = 0
)

/**
 * One fixture. [aId] and [bId] are empty until the round before has been decided, and an empty
 * [bId] in the first round is a bye rather than a fixture waiting to be filled.
 */
data class Match(
    val round: Int = 0,
    val slot: Int = 0,
    val aId: String = "",
    val bId: String = "",
    val aScore: Int = 0,
    val bScore: Int = 0,
    val winnerId: String = "",
    val roomCode: String = "",
    val status: String = Tournament.MATCH_PENDING,
    /**
     * Games won so far, for a fixture played as a series.
     *
     * Both stay at zero in a single-game tournament, where [winnerId] is set by the one result
     * and nothing ever reads these. [aScore] and [bScore] remain the scores of the most recent
     * game rather than a running total — a series is won by taking games, not by aggregate, and
     * showing a sum would suggest otherwise.
     */
    val aWins: Int = 0,
    val bWins: Int = 0,
    /**
     * The room whose result was last counted into the series.
     *
     * Both players report the same finished game, and in a series neither report finds the
     * fixture decided — so without this the one game would be counted twice and a fixture could
     * be won by a player who took a single game. Keyed on the room because that is what a game
     * is: the scores cannot tell two games apart, and a series can easily contain two alike.
     */
    val lastRoom: String = ""
) {
    val id: String get() = Tournament.matchId(round, slot)
    val decided: Boolean get() = winnerId.isNotEmpty()

    /** How the series stands, for a fixture that needs more than one game. */
    val seriesLine: String get() = "$aWins–$bWins"

    /** True once any game of a series has been played but the fixture is still open. */
    val seriesUnderway: Boolean get() = !decided && (aWins > 0 || bWins > 0)

    /** Both seats filled, so somebody can actually play it. */
    val ready: Boolean get() = aId.isNotEmpty() && bId.isNotEmpty()

    fun involves(playerId: String): Boolean = playerId == aId || playerId == bId

    fun opponentOf(playerId: String): String = if (playerId == aId) bId else aId
}

data class TournamentState(
    val code: String = "",
    val name: String = "",
    val hostId: String = "",
    val status: String = Tournament.OPEN,
    val cardCount: Int = 1,
    /**
     * Games needed to take a fixture, as the length of the series: 1 or 3.
     *
     * Stored on the tournament rather than per match so every fixture in a draw is the same
     * length — a bracket where the semi-final is longer than the final would need explaining, and
     * a knockout is already understood without it.
     */
    val bestOf: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val players: Map<String, Entrant> = emptyMap(),
    val matches: Map<String, Match> = emptyMap()
) {
    val entrants: List<Entrant> get() = players.values.sortedBy { it.seed }

    val rounds: Int get() = matches.values.maxOfOrNull { it.round }?.plus(1) ?: 0

    fun matchesIn(round: Int): List<Match> =
        matches.values.filter { it.round == round }.sortedBy { it.slot }

    /** The one this player should be looking at: their next undecided fixture. */
    fun nextMatchFor(playerId: String): Match? =
        matches.values
            .filter { it.involves(playerId) && !it.decided }
            .minByOrNull { it.round }

    val champion: String
        get() = matches.values.maxByOrNull { it.round }?.winnerId.orEmpty()
}

/**
 * A single-elimination draw, and the rules for moving through it.
 *
 * Single elimination rather than a league because of what a tournament between friends actually
 * has to survive: somebody losing interest. A league needs every fixture played before the table
 * means anything, so one person who stops playing strands everyone. A bracket only ever waits on
 * the two people in a live match, and half the field is done after one round.
 *
 * The draw is built once, in full, including the rounds nobody can be placed in yet. A bracket
 * that grew a round at a time could not be drawn on screen before it had been played, and seeing
 * who you might meet in the final is most of the appeal of being in one.
 */
object Tournament {

    const val OPEN = "OPEN"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"

    const val MATCH_PENDING = "PENDING"
    const val MATCH_PLAYING = "PLAYING"
    const val MATCH_DONE = "DONE"

    /** Fewest that make a bracket, and most that fit one nobody has to scroll for a week. */
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 16

    /** Stable and derivable, so a match can be found without carrying its key around. */
    fun matchId(round: Int, slot: Int): String = "r${round}s$slot"

    /**
     * Entrants who are not people.
     *
     * Marked in the id rather than by a flag beside it, so every screen that already has an id can
     * answer the question without going and fetching the entrant. Four people cannot make a
     * bracket of eight and should not have to find four more; a couple of bots fill it out, and a
     * bot drawn against a bot plays itself out rather than stalling the round above.
     */
    const val BOT_PREFIX = "bot-"

    fun isBot(id: String): Boolean = id.startsWith(BOT_PREFIX)

    /** The next free bot seat, so adding one twice does not overwrite the first. */
    fun nextBotId(taken: Set<String>): String {
        var n = 1
        while ("$BOT_PREFIX$n" in taken) n++
        return "$BOT_PREFIX$n"
    }

    /** The bracket size a field of [players] is drawn into: the next power of two. */
    fun bracketSize(players: Int): Int {
        var size = 1
        while (size < players) size *= 2
        return size.coerceAtLeast(2)
    }

    fun roundCount(players: Int): Int {
        var size = bracketSize(players)
        var rounds = 0
        while (size > 1) {
            size /= 2
            rounds++
        }
        return rounds
    }

    /**
     * What a round is called, counting down to the final rather than up from the first.
     *
     * "Round 1 of 4" tells you nothing about how far along you are; "Quarter-final" tells you
     * immediately. Rounds further out than a quarter-final have no name worth having, so those
     * are numbered.
     */
    fun roundName(round: Int, totalRounds: Int): Int = when (totalRounds - round) {
        1 -> com.yahtzee.online.R.string.tourney_round_final
        2 -> com.yahtzee.online.R.string.tourney_round_semi
        3 -> com.yahtzee.online.R.string.tourney_round_quarter
        else -> com.yahtzee.online.R.string.tourney_round_n
    }

    /**
     * The whole draw for [entrants], first round seeded and the rest left empty.
     *
     * Top seed meets bottom seed, second meets second-from-bottom, and so on — the standard draw,
     * which keeps the two strongest apart until the final rather than pairing them in round one.
     * Seeds here are join order, since there is nothing else to go on the first time a group
     * plays; it is still better than pairing everyone in the order they happened to arrive.
     *
     * A field that is not a power of two is padded with empty seats. Anyone drawn against one has
     * a bye: their match is decided the moment the draw is made, so they appear in round two
     * already through rather than waiting on a fixture that can never be played.
     */
    fun draw(entrants: List<Entrant>): Map<String, Match> {
        if (entrants.size < MIN_PLAYERS) return emptyMap()

        val size = bracketSize(entrants.size)
        val rounds = roundCount(entrants.size)
        val bySeed = entrants.sortedBy { it.seed }
        val matches = linkedMapOf<String, Match>()

        // The first round, paired strongest against weakest.
        for (slot in 0 until size / 2) {
            val a = bySeed.getOrNull(slot)
            val b = bySeed.getOrNull(size - 1 - slot)
            val bye = a != null && b == null
            matches[matchId(0, slot)] = Match(
                round = 0,
                slot = slot,
                aId = a?.id.orEmpty(),
                bId = b?.id.orEmpty(),
                // A bye is not a game anybody plays, so it is settled here and now.
                winnerId = if (bye) a!!.id else "",
                status = if (bye) MATCH_DONE else MATCH_PENDING
            )
        }

        // Everything after it, empty but drawn, so the shape of the thing can be seen from the start.
        for (round in 1 until rounds) {
            for (slot in 0 until (size shr (round + 1))) {
                matches[matchId(round, slot)] = Match(round = round, slot = slot)
            }
        }

        // Byes are already through, so put them into the second round straight away.
        return advanceAll(matches)
    }

    /**
     * The draw with every decided match's winner written into the round above.
     *
     * Applied to the whole bracket rather than to one result, so a bye in round one lands in round
     * two without anybody having to remember to push it there, and so a result arriving twice
     * cannot advance the same player twice.
     */
    fun advanceAll(matches: Map<String, Match>): Map<String, Match> {
        val result = matches.toMutableMap()
        val lastRound = matches.values.maxOfOrNull { it.round } ?: return result

        for (round in 0 until lastRound) {
            result.values
                .filter { it.round == round && it.decided }
                .forEach { decided ->
                    val nextId = matchId(round + 1, decided.slot / 2)
                    val next = result[nextId] ?: return@forEach
                    // Even slots feed the top seat of the match above, odd ones the bottom.
                    result[nextId] =
                        if (decided.slot % 2 == 0) next.copy(aId = decided.winnerId)
                        else next.copy(bId = decided.winnerId)
                }
        }
        return result
    }

    /**
     * [match] settled by the two scores, plus the whole bracket moved on.
     *
     * A draw goes to the player already further up the draw — the higher seed. Yahtzee ties are
     * rare but they do happen, and a bracket cannot hold two people in one seat; deciding it on
     * seed is arbitrary but at least it is known in advance, which "replay it" is not.
     */
    /** Games one side must win to take a fixture: one of one, two of three. */
    fun gamesToWin(bestOf: Int): Int = bestOf / 2 + 1

    /**
     * Records one finished game.
     *
     * In a single-game tournament this decides the fixture, as it always did. In a series it adds
     * a game to whoever won it and only decides the fixture once somebody has enough of them.
     *
     * A fixture that is not yet decided has its room cleared. Each game of a series is played in
     * its own room — the old one is a finished game that cannot be reopened, and leaving the code
     * on the match would send both players back to a board with no turns left in it.
     */
    fun settle(
        matches: Map<String, Match>,
        matchId: String,
        aScore: Int,
        bScore: Int,
        bestOf: Int = 1,
        room: String = "",
        seedOf: (String) -> Int
    ): Map<String, Match> {
        val match = matches[matchId] ?: return matches
        if (!match.ready) return matches
        if (match.decided) return matches

        // The same game, reported by its other player. Counting it again would hand somebody a
        // series they had won half of.
        if (room.isNotEmpty() && room == match.lastRoom) return matches

        // A drawn game still has to go to somebody, and the better seed is the only thing on hand
        // to break it with. Unchanged from the single-game rule, applied per game of a series.
        val gameWinner = when {
            aScore > bScore -> match.aId
            bScore > aScore -> match.bId
            seedOf(match.aId) <= seedOf(match.bId) -> match.aId
            else -> match.bId
        }

        val aWins = match.aWins + if (gameWinner == match.aId) 1 else 0
        val bWins = match.bWins + if (gameWinner == match.bId) 1 else 0
        val needed = gamesToWin(bestOf)

        val seriesWinner = when {
            aWins >= needed -> match.aId
            bWins >= needed -> match.bId
            else -> ""
        }

        val settled = match.copy(
            aScore = aScore,
            bScore = bScore,
            aWins = aWins,
            bWins = bWins,
            winnerId = seriesWinner,
            lastRoom = room.ifEmpty { match.lastRoom },
            roomCode = if (seriesWinner.isEmpty()) "" else match.roomCode,
            status = if (seriesWinner.isEmpty()) MATCH_PENDING else MATCH_DONE
        )
        return advanceAll(matches + (matchId to settled))
    }
}
