package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The recovery code is the only way back to an identity, so a defect here does not show up as a
 * visible bug — it shows up as a player who can never reach their own leaderboard row or their
 * seat in a game again. These cover the round trip and, as importantly, the cases that must be
 * refused rather than quietly accepted.
 */
class ProfileRecoveryTest {

    @Test
    fun `round trips every id it is given`() {
        repeat(500) {
            val id = UUID.randomUUID().toString()
            assertEquals(id, ProfileRecovery.decode(ProfileRecovery.encode(id)))
        }
    }

    @Test
    fun `round trips the extremes a random sample would miss`() {
        listOf(
            UUID(0L, 0L),
            UUID(-1L, -1L),
            UUID(Long.MIN_VALUE, Long.MAX_VALUE),
            UUID(Long.MAX_VALUE, Long.MIN_VALUE)
        ).forEach { uuid ->
            val id = uuid.toString()
            assertEquals(id, ProfileRecovery.decode(ProfileRecovery.encode(id)))
        }
    }

    @Test
    fun `codes are a fixed length and stay inside the alphabet`() {
        repeat(200) {
            val code = ProfileRecovery.encode(UUID.randomUUID().toString())
            assertEquals(28, code.length)
            assertTrue(code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        }
    }

    @Test
    fun `dashes and spacing are presentation only`() {
        val id = UUID.randomUUID().toString()
        val raw = ProfileRecovery.encode(id)
        val grouped = raw.chunked(4).joinToString("-")
        assertEquals(id, ProfileRecovery.decode(grouped))
        assertEquals(id, ProfileRecovery.decode(raw.chunked(4).joinToString(" ")))
        assertEquals(id, ProfileRecovery.decode(grouped.lowercase()))
    }

    @Test
    fun `letters that are misread as digits are accepted as those digits`() {
        // The alphabet has no O, I or L, so a code read off a screen and typed by hand should
        // still land rather than being rejected for a character it could never have contained.
        val id = UUID.randomUUID().toString()
        val code = ProfileRecovery.encode(id)
        val mistyped = code.replace('0', 'O').replace('1', 'I')
        assertEquals(id, ProfileRecovery.decode(mistyped))
    }

    @Test
    fun `a single wrong character is refused`() {
        // Without the checksum this is the dangerous case: a code that decodes to a valid-looking
        // id belonging to nobody, stranding the player somewhere no game can reach.
        var caught = 0
        var tested = 0
        repeat(200) {
            val code = ProfileRecovery.encode(UUID.randomUUID().toString())
            val index = code.indices.random()
            val replacement = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".first { it != code[index] }
            val broken = code.substring(0, index) + replacement + code.substring(index + 1)
            tested++
            if (ProfileRecovery.decode(broken) == null) caught++
        }
        // A one-byte checksum cannot catch everything, but it must catch the overwhelming
        // majority; anything less and the guarantee is not worth offering.
        assertTrue("caught $caught of $tested single-character errors", caught >= tested * 9 / 10)
    }

    @Test
    fun `transposed characters are refused`() {
        var caught = 0
        var tested = 0
        repeat(200) {
            val code = ProfileRecovery.encode(UUID.randomUUID().toString())
            val index = (0 until code.length - 1).random()
            if (code[index] == code[index + 1]) return@repeat
            val swapped = code.substring(0, index) +
                code[index + 1] + code[index] +
                code.substring(index + 2)
            tested++
            if (ProfileRecovery.decode(swapped) == null) caught++
        }
        assertTrue("caught $caught of $tested transpositions", caught >= tested * 9 / 10)
    }

    @Test
    fun `malformed input is refused rather than guessed at`() {
        assertNull(ProfileRecovery.decode(""))
        assertNull(ProfileRecovery.decode("not a code"))
        assertNull(ProfileRecovery.decode("ABCD-EFGH"))
        // Right length, but U is deliberately absent from the alphabet.
        assertNull(ProfileRecovery.decode("U".repeat(28)))
        // One character short and one character long.
        val code = ProfileRecovery.encode(UUID.randomUUID().toString())
        assertNull(ProfileRecovery.decode(code.dropLast(1)))
        assertNull(ProfileRecovery.decode(code + "Z"))
    }

    @Test
    fun `an unparseable id yields no code rather than a misleading one`() {
        assertEquals("", ProfileRecovery.encode("definitely-not-a-uuid"))
    }

    @Test
    fun `different ids give different codes`() {
        val codes = (1..500).map { ProfileRecovery.encode(UUID.randomUUID().toString()) }
        assertEquals(codes.size, codes.toSet().size)
        assertNotEquals(codes[0], codes[1])
    }
}
