package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RivalryTest {

    private val dave = "dave-id"

    @Test
    fun `a first game creates the record`() {
        val record = Rivalries.merge(null, dave, "Dave", RivalryResult.WIN, at = 100L)
        assertEquals(dave, record.opponentId)
        assertEquals("Dave", record.name)
        assertEquals(1, record.wins)
        assertEquals(0, record.losses)
        assertEquals(1, record.played)
        assertEquals(100L, record.lastPlayedAt)
    }

    @Test
    fun `results accumulate`() {
        var record = Rivalries.merge(null, dave, "Dave", RivalryResult.WIN, 1L)
        record = Rivalries.merge(record, dave, "Dave", RivalryResult.WIN, 2L)
        record = Rivalries.merge(record, dave, "Dave", RivalryResult.LOSS, 3L)
        record = Rivalries.merge(record, dave, "Dave", RivalryResult.DRAW, 4L)

        assertEquals(2, record.wins)
        assertEquals(1, record.losses)
        assertEquals(1, record.draws)
        assertEquals(4, record.played)
        assertEquals(4L, record.lastPlayedAt)
    }

    /** People rename themselves; a record against a name nobody uses is a record against nobody. */
    @Test
    fun `a rename carries into the record`() {
        val first = Rivalries.merge(null, dave, "Dave", RivalryResult.WIN, 1L)
        val second = Rivalries.merge(first, dave, "David", RivalryResult.LOSS, 2L)
        assertEquals("David", second.name)
        assertEquals(1, second.wins)
        assertEquals(1, second.losses)
    }

    /** An empty name is missing data, not a rename to nothing. */
    @Test
    fun `an empty name leaves the existing one alone`() {
        val first = Rivalries.merge(null, dave, "Dave", RivalryResult.WIN, 1L)
        val second = Rivalries.merge(first, dave, "", RivalryResult.WIN, 2L)
        assertEquals("Dave", second.name)
    }

    /** Games can be filed out of order after a spell offline; the record should not go backwards. */
    @Test
    fun `an older game does not rewind the last played time`() {
        val first = Rivalries.merge(null, dave, "Dave", RivalryResult.WIN, 500L)
        val second = Rivalries.merge(first, dave, "Dave", RivalryResult.WIN, 100L)
        assertEquals(500L, second.lastPlayedAt)
    }

    @Test
    fun `trailing is only true while behind`() {
        var record = Rivalries.merge(null, dave, "Dave", RivalryResult.LOSS, 1L)
        assertTrue(record.trailing)

        record = Rivalries.merge(record, dave, "Dave", RivalryResult.WIN, 2L)
        assertFalse("level is not behind", record.trailing)

        record = Rivalries.merge(record, dave, "Dave", RivalryResult.WIN, 3L)
        assertFalse(record.trailing)
    }

    /** Draws are their own outcome and must not quietly count as either side winning. */
    @Test
    fun `a record of nothing but draws is level`() {
        var record = Rivalries.merge(null, dave, "Dave", RivalryResult.DRAW, 1L)
        record = Rivalries.merge(record, dave, "Dave", RivalryResult.DRAW, 2L)
        assertEquals(0, record.wins)
        assertEquals(0, record.losses)
        assertEquals(2, record.draws)
        assertEquals(2, record.played)
        assertFalse(record.trailing)
    }
}
