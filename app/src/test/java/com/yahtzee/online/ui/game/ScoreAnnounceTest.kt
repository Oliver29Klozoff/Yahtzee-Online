package com.yahtzee.online.ui.game

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.ScoreKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreAnnounceTest {

    private val me = "me"
    private val them = "them"

    private fun state(vararg players: Player) = GameState(
        roomCode = "TEST",
        hostId = me,
        status = GameState.STATUS_PLAYING,
        playerOrder = players.map { it.id },
        players = players.associateBy { it.id }
    )

    private fun player(id: String, name: String, scores: Map<Category, Int>) = Player(
        id = id,
        name = name,
        joinedAt = 0L,
        scores = scores.mapKeys { (category, _) -> ScoreKey.of(0, category) }
    )

    @Test
    fun `a box somebody else fills is announced`() {
        val before = state(player(me, "Me", emptyMap()), player(them, "Dave", emptyMap()))
        val after = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", mapOf(Category.LARGE_STRAIGHT to 40))
        )

        val taken = ScoreAnnounce.detect(before, after, me)
        assertEquals("Dave", taken?.playerName)
        assertEquals(40, taken?.points)
        assertEquals(Category.LARGE_STRAIGHT.label, taken?.label)
    }

    /** You chose it a moment ago; being told about it is noise. */
    @Test
    fun `your own score is not announced`() {
        val before = state(player(me, "Me", emptyMap()), player(them, "Dave", emptyMap()))
        val after = state(
            player(me, "Me", mapOf(Category.YAHTZEE to 50)),
            player(them, "Dave", emptyMap())
        )
        assertNull(ScoreAnnounce.detect(before, after, me))
    }

    /** A zero is a real and often significant outcome — scratching a Yahtzee, say. */
    @Test
    fun `taking a zero is still announced`() {
        val before = state(player(me, "Me", emptyMap()), player(them, "Dave", emptyMap()))
        val after = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", mapOf(Category.YAHTZEE to 0))
        )

        val taken = ScoreAnnounce.detect(before, after, me)
        assertEquals(0, taken?.points)
        assertEquals(Category.YAHTZEE.label, taken?.label)
    }

    /** The room updates constantly — on rolls and held dice — with no score involved. */
    @Test
    fun `nothing is announced when no box was filled`() {
        val before = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", mapOf(Category.ONES to 3))
        )
        val after = before.copy(rollsUsed = 2, dice = listOf(6, 6, 6, 5, 4))
        assertNull(ScoreAnnounce.detect(before, after, me))
    }

    /** Nothing to compare against on the very first snapshot of a room. */
    @Test
    fun `the first sight of a room announces nothing`() {
        val current = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", mapOf(Category.SIXES to 24))
        )
        assertNull(ScoreAnnounce.detect(null, current, me))
    }

    /** Catching up after being away should not queue up a run of stale announcements. */
    @Test
    fun `several at once report the biggest`() {
        val before = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", emptyMap()),
            player("third", "Sam", emptyMap())
        )
        val after = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", mapOf(Category.THREES to 9)),
            player("third", "Sam", mapOf(Category.FULL_HOUSE to 25))
        )

        val taken = ScoreAnnounce.detect(before, after, me)
        assertEquals("Sam", taken?.playerName)
        assertEquals(25, taken?.points)
    }

    /** A player who was not in the previous snapshot has nothing to diff against. */
    @Test
    fun `a player who has just joined announces nothing`() {
        val before = state(player(me, "Me", emptyMap()))
        val after = state(
            player(me, "Me", emptyMap()),
            player(them, "Dave", mapOf(Category.FOURS to 12))
        )
        assertNull(ScoreAnnounce.detect(before, after, me))
    }
}
