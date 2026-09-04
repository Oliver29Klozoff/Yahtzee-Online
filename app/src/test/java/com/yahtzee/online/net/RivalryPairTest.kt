package com.yahtzee.online.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key two players share.
 *
 * This is the whole feature in one function. If the two phones derive different keys they each
 * write to their own private record, nothing errors, and the shared tally silently becomes the
 * per-device tally it exists to replace — which is exactly the bug being fixed, reintroduced in a
 * form that looks like it is working.
 */
class RivalryPairTest {

    private val alice = "363e2d76-2249-4959-8e6f-7f5219f16859"
    private val bob = "fdd14504-6679-44c4-aac1-2e447c644721"

    /** Both phones must land on the same record whichever way round they hold the pair. */
    @Test
    fun `the key does not depend on who is asking`() {
        assertEquals(
            RivalryRepository.pairId(alice, bob),
            RivalryRepository.pairId(bob, alice)
        )
    }

    @Test
    fun `different pairs get different records`() {
        val carol = "aaaaaaaa-0000-0000-0000-000000000000"
        assertNotEquals(
            RivalryRepository.pairId(alice, bob),
            RivalryRepository.pairId(alice, carol)
        )
    }

    /**
     * Firebase refuses a key containing any of . $ # [ ] / — a write to one fails outright, so a
     * key built from ids has to stay clear of them.
     */
    @Test
    fun `the key is legal as a database key`() {
        val key = RivalryRepository.pairId(alice, bob)
        listOf('.', '$', '#', '[', ']', '/').forEach {
            assertTrue("a key containing '$it' is rejected by Firebase", !key.contains(it))
        }
        assertTrue("keys are capped at 768 bytes", key.toByteArray().size <= 768)
    }

    /** Both ids have to be in it, or two different pairs could collide onto one record. */
    @Test
    fun `both players are named in the key`() {
        val key = RivalryRepository.pairId(alice, bob)
        assertTrue(key.contains(alice))
        assertTrue(key.contains(bob))
    }
}
