package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The date arithmetic behind the streak.
 *
 * Doing it on the string would have been simpler and wrong at every month end, and silently so —
 * a run broken on the first of the month is exactly the kind of bug nobody reports because they
 * assume they forgot to play.
 */
class DailyStreakTest {

    @Test
    fun `an ordinary day steps back by one`() {
        assertEquals("2026-08-30", DailyChallenge.dayBefore("2026-08-31"))
        assertEquals("2026-08-14", DailyChallenge.dayBefore("2026-08-15"))
    }

    @Test
    fun `the first of a month steps into the last of the previous one`() {
        assertEquals("2026-07-31", DailyChallenge.dayBefore("2026-08-01"))
        assertEquals("2026-04-30", DailyChallenge.dayBefore("2026-05-01"))
    }

    @Test
    fun `new year steps back across the year boundary`() {
        assertEquals("2025-12-31", DailyChallenge.dayBefore("2026-01-01"))
    }

    /** 2028 is a leap year, so the day before the first of March is the 29th. */
    @Test
    fun `a leap day is not skipped`() {
        assertEquals("2028-02-29", DailyChallenge.dayBefore("2028-03-01"))
    }

    /** 2026 is not, so the same date steps to the 28th. */
    @Test
    fun `a non leap year has no twenty ninth`() {
        assertEquals("2026-02-28", DailyChallenge.dayBefore("2026-03-01"))
    }

    /** Unparseable input breaks the run rather than inventing a day that continues it. */
    @Test
    fun `nonsense comes back unchanged`() {
        assertEquals("not-a-date", DailyChallenge.dayBefore("not-a-date"))
    }
}
