package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnOrderTest {

    private val table = listOf("A", "B", "C", "D")

    @Test
    fun `the winner leads`() {
        table.forEach { winner ->
            assertEquals(winner, TurnOrder.startingWith(table, winner).first())
        }
    }

    /**
     * The bug this replaced: "winner, then everyone from the top of the list" promoted whoever
     * sat first to second place on every roll-off they lost. On this table, B winning used to
     * give [B, A, C, D] — A jumping ahead of both C and D.
     */
    @Test
    fun `play carries on around the table from the winner`() {
        assertEquals(listOf("B", "C", "D", "A"), TurnOrder.startingWith(table, "B"))
        assertEquals(listOf("C", "D", "A", "B"), TurnOrder.startingWith(table, "C"))
        assertEquals(listOf("D", "A", "B", "C"), TurnOrder.startingWith(table, "D"))
    }

    @Test
    fun `the winner leading already changes nothing`() {
        assertEquals(table, TurnOrder.startingWith(table, "A"))
    }

    /** Nobody changes seats — the ring is the same ring, only entered at a different point. */
    @Test
    fun `neighbours are preserved`() {
        table.forEach { winner ->
            val rotated = TurnOrder.startingWith(table, winner)
            rotated.indices.forEach { i ->
                val next = rotated[(i + 1) % rotated.size]
                val originallyNext = table[(table.indexOf(rotated[i]) + 1) % table.size]
                assertEquals(originallyNext, next)
            }
        }
    }

    @Test
    fun `everyone keeps a seat and nobody is duplicated`() {
        table.forEach { winner ->
            val rotated = TurnOrder.startingWith(table, winner)
            assertEquals(table.size, rotated.size)
            assertEquals(table.toSet(), rotated.toSet())
        }
    }

    /** Two players is the case that hid the bug: a rotation and the old code agree. */
    @Test
    fun `two players simply swap`() {
        assertEquals(listOf("B", "A"), TurnOrder.startingWith(listOf("A", "B"), "B"))
        assertEquals(listOf("A", "B"), TurnOrder.startingWith(listOf("A", "B"), "A"))
    }

    /** A winner with no seat must not be able to empty the table. */
    @Test
    fun `an unseated winner is put in front without losing anyone`() {
        val result = TurnOrder.startingWith(table, "Z")
        assertEquals("Z", result.first())
        assertEquals(listOf("Z", "A", "B", "C", "D"), result)
    }

    @Test
    fun `a single player is left alone`() {
        assertEquals(listOf("A"), TurnOrder.startingWith(listOf("A"), "A"))
    }
}
