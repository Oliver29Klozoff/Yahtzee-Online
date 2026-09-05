package com.yahtzee.online.net

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Tournament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a bot seated in an online room does with its turn.
 *
 * The driver around this is a Handler and a Firebase reference; this is the part that decides,
 * and it is the part that can go wrong quietly. A bot that never scores stalls the whole room —
 * there is no clock on a television game, so nothing would ever move it on.
 */
class BotSeatDriverTest {

    private val botId = "${Tournament.BOT_PREFIX}1"

    private fun room(
        dice: List<Int>,
        rollsUsed: Int,
        scores: Map<String, Int> = emptyMap(),
        cardCount: Int = 1
    ): GameState {
        val bot = Player(id = botId, name = "Ada", scores = scores)
        return GameState(
            roomCode = "TESTR",
            hostId = "human",
            status = GameState.STATUS_PLAYING,
            playerOrder = listOf(botId, "human"),
            players = mapOf(botId to bot, "human" to Player(id = "human", name = "You")),
            currentTurnIndex = 0,
            rollsUsed = rollsUsed,
            dice = dice,
            cardCount = cardCount
        )
    }

    private fun decide(state: GameState) = BotSeatDriver.decide(
        state, BotSeatDriver.openByCard(state, botId), AppSettings.BotSkill.EXPERT
    )

    @Test
    fun `an untouched turn rolls`() {
        val action = decide(room(listOf(1, 1, 1, 1, 1), rollsUsed = 0))
        assertTrue(action is BotSeatDriver.Companion.Action.Roll)
    }

    /** The third roll is the last one, so the turn has to end in a score. */
    @Test
    fun `the last roll always scores`() {
        val action = decide(room(listOf(2, 4, 6, 1, 3), rollsUsed = 3))
        assertTrue("a bot that does not score stalls the room", action is BotSeatDriver.Companion.Action.Score)
    }

    /** A Yahtzee in hand is not something to reroll. */
    @Test
    fun `five of a kind is kept and scored`() {
        val action = decide(room(listOf(5, 5, 5, 5, 5), rollsUsed = 1))
        assertTrue(action is BotSeatDriver.Companion.Action.Score)
        assertEquals(Category.YAHTZEE, (action as BotSeatDriver.Companion.Action.Score).category)
    }

    /** A hand worth nothing mid-turn should be improved, not banked. */
    @Test
    fun `a poor hand rerolls rather than scoring`() {
        val action = decide(room(listOf(1, 2, 3, 6, 6), rollsUsed = 1))
        assertTrue(action is BotSeatDriver.Companion.Action.Reroll)
        val held = (action as BotSeatDriver.Companion.Action.Reroll).held
        assertEquals(5, held.size)
        assertTrue("a reroll that holds everything is not a reroll", held.any { !it })
    }

    /** With Yahtzee already filled, the same five dice have to go somewhere else. */
    @Test
    fun `a filled box is never scored twice`() {
        val state = room(
            listOf(5, 5, 5, 5, 5),
            rollsUsed = 3,
            scores = mapOf(ScoreKey.of(0, Category.YAHTZEE) to 50)
        )
        val action = decide(state)
        assertTrue(action is BotSeatDriver.Companion.Action.Score)
        val chosen = (action as BotSeatDriver.Companion.Action.Score).category
        assertTrue("picked a box that was already filled", chosen != Category.YAHTZEE)
    }

    /** A full card leaves nothing to decide, and must not pick a box anyway. */
    @Test
    fun `a full card offers no categories`() {
        val filled = Category.values().associate { ScoreKey.of(0, it) to 0 }
        val state = room(listOf(1, 2, 3, 4, 5), rollsUsed = 3, scores = filled)
        assertTrue(BotSeatDriver.openByCard(state, botId).isEmpty())
    }

    /** Multi-card rooms: the bot may score on any card that still has room. */
    @Test
    fun `a second card is still open when the first is full`() {
        val filled = Category.values().associate { ScoreKey.of(0, it) to 0 }
        val state = room(listOf(6, 6, 6, 6, 6), rollsUsed = 3, scores = filled, cardCount = 2)
        val open = BotSeatDriver.openByCard(state, botId)
        assertEquals(setOf(1), open.keys)

        val action = decide(state)
        assertTrue(action is BotSeatDriver.Companion.Action.Score)
        assertEquals(1, (action as BotSeatDriver.Companion.Action.Score).card)
    }

    /** Every skill has to produce a legal move — a lesser bot must not stall the room either. */
    @Test
    fun `every skill scores on the last roll`() {
        for (skill in AppSettings.BotSkill.values()) {
            val state = room(listOf(3, 3, 5, 2, 1), rollsUsed = 3)
            val action = BotSeatDriver.decide(state, BotSeatDriver.openByCard(state, botId), skill)
            assertTrue("$skill did not score", action is BotSeatDriver.Companion.Action.Score)
        }
    }
}
