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
    val cardCount: Int = 1
) {
    companion object {
        const val STATUS_LOBBY = "LOBBY"
        const val STATUS_ROLL_OFF = "ROLL_OFF"
        const val STATUS_PLAYING = "PLAYING"
        const val STATUS_FINISHED = "FINISHED"
        const val TURN_TIME_MILLIS = 30_000L

        /** Card counts the host can choose from. */
        val CARD_OPTIONS = listOf(1, 3, 5, 6)
    }

    val currentPlayerId: String?
        get() = playerOrder.getOrNull(currentTurnIndex)

    fun isMyTurn(playerId: String): Boolean = currentPlayerId == playerId

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

/**
 * Score across every card, including upper-section bonuses earned per card. The Yahtzee bonus is
 * tracked once per player rather than per card, so it is added a single time here.
 */
fun Player.grandTotalAllCards(cardCount: Int): Int {
    val cards = (0 until cardCount.coerceAtLeast(1))
        .sumOf { Scoring.grandTotal(scoresForCard(it), yahtzeeBonusCount = 0) }
    return cards + yahtzeeBonusCount * 100
}
