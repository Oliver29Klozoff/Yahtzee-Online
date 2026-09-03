package com.yahtzee.online.game

import com.yahtzee.online.bot.BotRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Bots in a bracket.
 *
 * Two things have to hold or a tournament with bots in it quietly stops: a bot has to be
 * recognisable from its id alone, since that is all most screens have, and a fixture with a bot on
 * both sides has to be resolvable without anybody sitting down at it.
 */
class TournamentBotTest {

    @Test
    fun `a bot is recognisable from its id`() {
        assertTrue(Tournament.isBot("bot-1"))
        assertFalse(Tournament.isBot("363e2d76-2249-4959-8e6f-7f5219f16859"))
    }

    /** Adding two must not seat the second on top of the first. */
    @Test
    fun `bot seats do not collide`() {
        val taken = mutableSetOf<String>("human")
        repeat(3) { taken += Tournament.nextBotId(taken) }
        assertEquals(setOf("human", "bot-1", "bot-2", "bot-3"), taken)
    }

    /** The gap a bot fills: four people cannot make a bracket of eight on their own. */
    @Test
    fun `bots let a short field fill a bracket`() {
        val field = (0 until 3).map { Entrant(id = "p$it", name = "P$it", seed = it) } +
            Entrant(id = "bot-1", name = "Bot 1", seed = 3)
        val matches = Tournament.draw(field)
        val firstRound = matches.values.filter { it.round == 0 }
        assertEquals(2, firstRound.size)
        // Only the first round is seeded; the rounds above it are drawn empty on purpose, so the
        // "no byes" claim is about round one alone.
        assertTrue("a full field of four needs no byes", firstRound.none { it.bId.isEmpty() })
    }

    /**
     * A bot-versus-bot fixture is playable without a person.
     *
     * Not a strength test — a bot's score varies wildly, and pinning a range would be a flaky test
     * of the dice rather than a real one. What matters is that a full game comes out the other end
     * with a plausible total, because a crash or a zero here strands the round above it forever.
     */
    @Test
    fun `a bot can play a whole game by itself`() {
        val score = BotRun.play(AppSettings.BotSkill.HARD, Random(20260903))
        assertTrue("a completed scorecard cannot total $score", score in 20..1500)
    }

    /** Every skill has to be able to finish, not just the one the search drives. */
    @Test
    fun `every skill finishes a game`() {
        AppSettings.BotSkill.values().forEach { skill ->
            val score = BotRun.play(skill, Random(7))
            assertTrue("$skill produced $score", score > 0)
        }
    }

    /** Two bots drawn together must produce a decided fixture the bracket can move past. */
    @Test
    fun `a bot fixture settles and advances`() {
        val field = listOf(
            Entrant(id = "p0", name = "You", seed = 0),
            Entrant(id = "bot-1", name = "Bot 1", seed = 1),
            Entrant(id = "bot-2", name = "Bot 2", seed = 2),
            Entrant(id = "bot-3", name = "Bot 3", seed = 3)
        )
        val drawn = Tournament.draw(field)
        val botFixture = drawn.values.first {
            Tournament.isBot(it.aId) && Tournament.isBot(it.bId)
        }
        val settled = Tournament.settle(
            drawn, botFixture.id,
            BotRun.play(AppSettings.BotSkill.NORMAL, Random(1)),
            BotRun.play(AppSettings.BotSkill.NORMAL, Random(2))
        ) { id -> field.first { it.id == id }.seed }

        val decided = settled[botFixture.id]!!
        assertTrue(decided.decided)
        val next = settled[Tournament.matchId(1, botFixture.slot / 2)]!!
        assertTrue(
            "the winner was not moved up",
            next.aId == decided.winnerId || next.bId == decided.winnerId
        )
    }
}
