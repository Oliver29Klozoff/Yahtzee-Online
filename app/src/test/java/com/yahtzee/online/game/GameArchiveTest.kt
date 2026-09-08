package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an archived game knows about itself.
 *
 * The store needs a device, so what is pinned here is the reading of a card — the totals, the
 * order, and the difference between a box left empty and a box scored as zero, which is the whole
 * reason for keeping the card rather than the number.
 */
class GameArchiveTest {

    private fun player(id: String, name: String, scores: Map<Category, Int>, bonuses: Int = 0) =
        ArchivedPlayer(
            id = id,
            name = name,
            scores = scores.mapKeys { ScoreKey.of(0, it.key) },
            yahtzeeBonusCount = bonuses
        )

    private fun game(vararg players: ArchivedPlayer, winner: String = "", cards: Int = 1) =
        ArchivedGame(
            playedAt = 1_000L,
            mode = PlayerStats.Mode.ONLINE,
            cardCount = cards,
            youId = "me",
            winnerId = winner,
            players = players.toList()
        )

    @Test
    fun `a total counts both halves and the bonus`() {
        // 63 in the top half earns the 35 bonus, plus 50 in the Yahtzee box.
        val scores = Category.UPPER.associateWith { (Category.UPPER.indexOf(it) + 1) * 3 } +
            mapOf(Category.YAHTZEE to 50)
        val archived = game(player("me", "You", scores))
        assertEquals(63 + 35 + 50, archived.totalFor(archived.players.first()))
    }

    @Test
    fun `extra Yahtzees are worth a hundred each`() {
        val plain = game(player("me", "You", mapOf(Category.CHANCE to 20)))
        val withBonus = game(player("me", "You", mapOf(Category.CHANCE to 20), bonuses = 2))
        assertEquals(
            plain.totalFor(plain.players.first()) + 200,
            withBonus.totalFor(withBonus.players.first())
        )
    }

    /** A finished game is read highest first, whatever order people sat in. */
    @Test
    fun `standings are ordered by score`() {
        val archived = game(
            player("me", "You", mapOf(Category.CHANCE to 12)),
            player("them", "Ada", mapOf(Category.CHANCE to 25))
        )
        assertEquals(listOf("Ada", "You"), archived.standings.map { it.name })
    }

    @Test
    fun `it knows whether you won`() {
        val you = player("me", "You", mapOf(Category.CHANCE to 25))
        val them = player("them", "Ada", mapOf(Category.CHANCE to 12))
        assertTrue(game(you, them, winner = "me").youWon)
        assertFalse(game(you, them, winner = "them").youWon)
        // An undecided game is not a win for anybody.
        assertFalse(game(you, them).youWon)
    }

    @Test
    fun `you are found among the players`() {
        val archived = game(
            player("them", "Ada", mapOf(Category.CHANCE to 12)),
            player("me", "You", mapOf(Category.CHANCE to 25))
        )
        assertEquals("You", archived.you?.name)
    }

    @Test
    fun `a game you were not in has no you`() {
        val archived = ArchivedGame(
            playedAt = 1L,
            mode = PlayerStats.Mode.SOLO,
            cardCount = 1,
            youId = "nobody",
            winnerId = "",
            players = listOf(player("them", "Ada", mapOf(Category.CHANCE to 12)))
        )
        assertNull(archived.you)
    }

    /**
     * An empty box and a box scored zero are different things.
     *
     * Zero is a decision somebody made — a roll thrown away to protect something else — and a
     * card that showed it as blank would lose the most interesting mark on it.
     */
    @Test
    fun `a zero is kept as a zero`() {
        val archived = game(player("me", "You", mapOf(Category.YAHTZEE to 0)))
        val scores = archived.players.first().scores
        assertTrue(scores.containsKey(ScoreKey.of(0, Category.YAHTZEE)))
        assertEquals(0, scores[ScoreKey.of(0, Category.YAHTZEE)])
        assertFalse("an untouched box is absent, not zero", scores.containsKey(ScoreKey.of(0, Category.ONES)))
    }

    /** Multi-card games keep each card separately rather than summing them away. */
    @Test
    fun `cards are kept apart`() {
        val scores = mapOf(
            ScoreKey.of(0, Category.SIXES) to 18,
            ScoreKey.of(1, Category.SIXES) to 6
        )
        val archived = ArchivedGame(
            playedAt = 1L,
            mode = PlayerStats.Mode.SOLO,
            cardCount = 2,
            youId = "me",
            winnerId = "me",
            players = listOf(ArchivedPlayer("me", "You", scores, 0))
        )
        assertEquals(18, archived.players.first().scores[ScoreKey.of(0, Category.SIXES)])
        assertEquals(6, archived.players.first().scores[ScoreKey.of(1, Category.SIXES)])
        assertEquals(24, archived.totalFor(archived.players.first()))
    }

    /** Enough evenings to be worth opening, few enough that the cards do not pile up. */
    @Test
    fun `the archive is capped`() {
        assertTrue(GameArchive.MAX_GAMES in 10..50)
    }
}
