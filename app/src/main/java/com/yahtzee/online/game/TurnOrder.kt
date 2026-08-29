package com.yahtzee.online.game

/**
 * Who plays after whom, once the roll-off has decided who plays first.
 *
 * Seating is a ring, not a queue. Everyone sat down in some order, and winning the toss should
 * change where play *starts*, not who is sitting next to whom — exactly as it works at a table,
 * where the winner goes first and play carries on to their left with nobody swapping chairs.
 */
object TurnOrder {

    /**
     * [order] rotated so that [firstPlayerId] leads, with everyone else keeping their places
     * around the ring.
     *
     * The old version built the list as "the winner, then everybody else from the top of the
     * seating". That is not a rotation, and with three or more players it quietly warps the table:
     * whoever happened to sit first was promoted to second on every single roll-off they did not
     * win. With seating [A, B, C] and B winning it produced [B, A, C] — A jumping the queue ahead
     * of C — where the ring gives [B, C, A].
     *
     * Two players cannot tell the difference, which is why it survived so long.
     */
    fun startingWith(order: List<String>, firstPlayerId: String): List<String> {
        val start = order.indexOf(firstPlayerId)
        // A winner who is not seated should not be able to empty the table. Falling back to
        // putting them in front keeps the game playable rather than dropping everyone else.
        if (start < 0) return listOf(firstPlayerId) + order.filterNot { it == firstPlayerId }
        return List(order.size) { offset -> order[(start + offset) % order.size] }
    }
}
