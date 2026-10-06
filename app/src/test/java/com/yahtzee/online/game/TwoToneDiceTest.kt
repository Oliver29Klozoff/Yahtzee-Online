package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ends of a die, and the presets that pair them.
 *
 * What is worth pinning here is the fallback: every screen that draws somebody else's dice reads
 * both ends off the player, and a player written by an older build has no second end at all. If
 * that read returned 0 rather than the colour they do have, every die on the table would fade
 * into black for everyone still on the previous version.
 */
class TwoToneDiceTest {

    @Test
    fun `a player with no second colour reads as plain`() {
        val player = Player(id = "p1", name = "Ada", diceColor = 0xFF3D7FFF.toInt())
        val (first, second) = player.diceColors
        assertEquals(0xFF3D7FFF.toInt(), first)
        assertEquals("the far end falls back to the near one", first, second)
    }

    @Test
    fun `a two-tone player keeps both ends`() {
        val player = Player(
            id = "p1",
            name = "Ada",
            diceColor = 0xFF3D7FFF.toInt(),
            diceColorB = 0xFF9B5DE5.toInt()
        )
        assertEquals(0xFF3D7FFF.toInt() to 0xFF9B5DE5.toInt(), player.diceColors)
    }

    @Test
    fun `every preset is a real gradient with its own name`() {
        val names = DicePreferences.GRADIENTS.map { it.first }
        assertEquals("names are unique", names.size, names.distinct().size)
        DicePreferences.GRADIENTS.forEach { (name, first, second) ->
            assertNotEquals("$name is two colours, not one twice", first, second)
            // Shifted by hand rather than through Color.alpha: the android.jar on the unit-test
            // classpath is stubs, and every call into it throws.
            assertTrue("$name is fully opaque", (first ushr 24) == 255)
            assertTrue("$name is fully opaque", (second ushr 24) == 255)
        }
    }
}
