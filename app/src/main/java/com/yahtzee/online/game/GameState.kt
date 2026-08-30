package com.yahtzee.online.game

data class Player(
    val id: String = "",
    val name: String = "",
    val joinedAt: Long = 0L,
    val scores: Map<String, Int> = emptyMap(),
    val yahtzeeBonusCount: Int = 0,
    /**
     * The player's chosen dice colour as an ARGB int, synced so everyone at the table sees
     * whoever is rolling in their own colour. 0 means "not set" — players on older builds have
     * no value here — and callers fall back to the default cobalt.
     */
    val diceColor: Int = 0
)

data class GameState(
    val roomCode: String = "",
    val hostId: String = "",
    val status: String = "LOBBY",
    val playerOrder: List<String> = emptyList(),
    val players: Map<String, Player> = emptyMap(),
    val currentTurnIndex: Int = 0,
    val rollsUsed: Int = 0,
    val dice: List<Int> = listOf(1, 1, 1, 1, 1),
    val held: List<Boolean> = listOf(false, false, false, false, false),
    val winnerId: String = "",
    val turnDeadline: Long = 0L,
    val openingRolls: Map<String, Int> = emptyMap(),
    val openingRollTied: List<String> = emptyList(),
    /**
     * How many scorecards each player fills in this room, chosen by the host when creating it.
     * More than one is Triple-Yahtzee style: a single shared roll per turn, and the player picks
     * which card it goes on, so the game runs cardCount x 13 turns.
     */
    val cardCount: Int = 1,
    /**
     * Seconds a player gets per roll before their turn is played for them, chosen by the host.
     * Unlike the display preferences this cannot be per-device — every player has to be counting
     * down the same clock. 0 means no limit.
     */
    val turnSeconds: Int = 30,
    /**
     * The latest reaction from each player, as emoji to when it was sent. One slot per player:
     * a reaction is a moment rather than a record, and the timestamp is what lets the same emoji
     * twice read as two reactions instead of one.
     */
    val reactions: Map<String, Pair<String, Long>> = emptyMap(),

    /**
     * What has been said in the room, oldest first.
     *
     * Unlike a reaction, a message is a record: it is read after the fact by whoever was not
     * looking at their phone when it arrived, which in a game played a turn a day is everybody.
     * So these accumulate rather than being one slot per player — bounded, because the room is
     * re-read whole on every roll.
     */
    val chat: List<ChatMessage> = emptyList()
) {
    companion object {
        const val STATUS_LOBBY = "LOBBY"
        const val STATUS_ROLL_OFF = "ROLL_OFF"
        const val STATUS_PLAYING = "PLAYING"
        const val STATUS_FINISHED = "FINISHED"
        const val TURN_TIME_MILLIS = 30_000L

        /** Card counts the host can choose from. */
        val CARD_OPTIONS = listOf(1, 3, 5, 6)

        /** Turn lengths the host can choose from, in seconds; 0 is no limit. */
        val TURN_SECOND_OPTIONS = listOf(30, 60, 90, 0)
    }

    val currentPlayerId: String?
        get() = playerOrder.getOrNull(currentTurnIndex)

    fun isMyTurn(playerId: String): Boolean = currentPlayerId == playerId

    /** This room's turn limit in milliseconds, or 0 when the host turned the clock off. */
    val turnMillis: Long
        get() = turnSeconds.coerceAtLeast(0) * 1000L

    /** Total score slots each player must fill before the game ends. */
    val totalSlots: Int
        get() = Category.values().size * cardCount.coerceAtLeast(1)

    fun isGameOver(): Boolean =
        players.isNotEmpty() && players.values.all { it.scores.size == totalSlots }
}

/**
 * Score-map keys. With multiple cards a player holds several scores per category, so keys are
 * "card:CATEGORY" — for example "2:ONES".
 *
 * Single-card rooms written by older builds stored the bare category name. Those parse cleanly
 * here: a key with no separator yields the whole string as the category and card 0, so old
 * rooms keep working without migration.
 */
object ScoreKey {
    fun of(card: Int, category: Category): String = "$card:${category.name}"

    fun cardOf(key: String): Int = key.substringBefore(':', "").toIntOrNull() ?: 0

    fun categoryOf(key: String): Category? =
        runCatching { Category.valueOf(key.substringAfter(':')) }.getOrNull()
}

/**
 * Where [ofPlayerId] is sitting, as seen by [viewerId], in radians around the table.
 *
 * Zero is the viewer's own seat, and seats are spaced evenly by turn order, so on every screen
 * you sit in the same place and opponents fan out around you in the order they play. Four
 * players land on the quarters; five or more fall onto intermediate angles for free.
 */
fun GameState.seatAngle(viewerId: String, ofPlayerId: String?): Float {
    if (ofPlayerId == null || playerOrder.isEmpty()) return 0f
    val mine = playerOrder.indexOf(viewerId)
    val theirs = playerOrder.indexOf(ofPlayerId)
    if (mine < 0 || theirs < 0) return 0f
    val seatsAway = ((theirs - mine) % playerOrder.size + playerOrder.size) % playerOrder.size
    return (2.0 * Math.PI * seatsAway / playerOrder.size).toFloat()
}

/** This player's filled categories on one card. */
fun Player.scoresForCard(card: Int): Map<Category, Int> =
    scores.entries.mapNotNull { (key, value) ->
        if (ScoreKey.cardOf(key) != card) return@mapNotNull null
        ScoreKey.categoryOf(key)?.let { it to value }
    }.toMap()

/** True once this player has a Yahtzee banked as a Yahtzee — 50 in the box, on any card. */
fun Player.hasScoredYahtzee(cardCount: Int): Boolean =
    (0 until cardCount.coerceAtLeast(1)).any {
        scores[ScoreKey.of(it, Category.YAHTZEE)] == 50
    }

/** Whether the dice currently on the table are five of a kind. */
fun GameState.diceAreYahtzee(): Boolean =
    dice.size == DICE_COUNT && dice.groupBy { it }.values.any { it.size == DICE_COUNT }

/**
 * What a Yahtzee sitting on the table is worth to [playerId] right now.
 *
 * The bonus is applied automatically when the roll is scored, so without something saying it is
 * there a player has no way to know: scoring five 3s into Sixes shows a 0 in the cell while a
 * hundred points go into the total unannounced. Worse, the bonus depends on a decision made
 * turns earlier — it is only ever payable if the Yahtzee box already holds 50 — so a player who
 * spent their first Yahtzee as a Chance can roll four more and never earn a thing.
 */
enum class YahtzeeState {
    /** Nothing special on the table. */
    NONE,

    /** A Yahtzee, with the box still open — taking 50 here is what unlocks later bonuses. */
    FIRST,

    /** A Yahtzee with 50 already banked: worth +100 in whichever box it is scored. */
    BONUS,

    /**
     * A Yahtzee, but the box holds a zero rather than 50, so no bonus can ever be earned. Called
     * out rather than passed over silently, since the alternative is a player waiting all game
     * for a bonus that cannot arrive.
     */
    FORFEITED
}

/**
 * How the table currently stands for [playerId]. Rolls not yet taken this turn read as [NONE]:
 * the dice keep the previous turn's faces until the first roll, and a Yahtzee left showing from
 * the last turn is not one this player has rolled.
 */
fun GameState.yahtzeeStateFor(playerId: String): YahtzeeState {
    val player = players[playerId] ?: return YahtzeeState.NONE
    if (rollsUsed == 0 || !diceAreYahtzee()) return YahtzeeState.NONE
    if (player.hasScoredYahtzee(cardCount)) return YahtzeeState.BONUS

    val cards = cardCount.coerceAtLeast(1)
    val everyBoxFilled = (0 until cards).all { player.scores.containsKey(ScoreKey.of(it, Category.YAHTZEE)) }
    return if (everyBoxFilled) YahtzeeState.FORFEITED else YahtzeeState.FIRST
}

/**
 * Score across every card, including upper-section bonuses earned per card. The Yahtzee bonus is
 * tracked once per player rather than per card, so it is added a single time here.
 */
fun Player.grandTotalAllCards(cardCount: Int): Int {
    val cards = (0 until cardCount.coerceAtLeast(1))
        .sumOf { Scoring.grandTotal(scoresForCard(it), yahtzeeBonusCount = 0) }
    return cards + yahtzeeBonusCount * 100
}
