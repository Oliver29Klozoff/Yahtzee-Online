package com.yahtzee.online.game

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * One shared puzzle a day: every player in the world gets the same dice, plays a single card
 * alone, and is ranked on the day's board. No opponents and no clock — the only variable is how
 * well the hand was played.
 *
 * The date is the player's own local date rather than UTC, so the puzzle turns over at their
 * midnight instead of at some arbitrary hour of their afternoon. That does let a player further
 * east start a given day's puzzle first, which is the same trade every daily puzzle game makes
 * and is not worth handing everyone else a puzzle that changes mid-morning.
 */
object DailyChallenge {

    private const val PREFS = "daily_challenge"
    private const val KEY_DAY = "day"
    private const val KEY_SCORE = "score"
    private const val KEY_STREAK = "streak"

    /** Turns in a game — one per category, on a single card. */
    const val TURNS = 13

    /** Locale-independent so the id is the same string on every device. */
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun todayId(): String = formatter().format(Date())

    /**
     * The day's seed.
     *
     * Hashed with FNV-1a and finished with a SplitMix64 mixer rather than [String.hashCode]: the
     * ids for consecutive days differ by a character or two, and a weak hash would hand
     * neighbouring days near-identical seeds — and so, visibly, near-identical dice. Written in
     * plain Long arithmetic so it produces the same seed on every device and every Android
     * version, which platform hashes do not promise.
     */
    fun seedFor(dayId: String): Long {
        var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
        for (character in dayId) {
            hash = hash xor character.code.toLong()
            hash *= 1099511628211L // FNV prime
        }
        var mixed = hash
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        return mixed xor (mixed ushr 31)
    }

    fun tapeFor(dayId: String): DiceTape = DiceTape(seedFor(dayId))

    /** The last day this device completed, or null if today's is still open. */
    fun lastCompletedDay(context: Context): String? =
        prefs(context).getString(KEY_DAY, null)

    fun playedToday(context: Context): Boolean = lastCompletedDay(context) == todayId()

    /** The score posted today, or null if today has not been played. */
    fun todayScore(context: Context): Int? =
        if (playedToday(context)) prefs(context).getInt(KEY_SCORE, 0) else null

    /**
     * How many days in a row have been played, counting today.
     *
     * Zero once a day has been missed. Kept because a daily puzzle with no memory is thirteen
     * turns and nothing else — the run is the reason to come back on a day you were not going
     * to, and it is the only part of it that cannot be rebuilt after the fact.
     */
    fun streak(context: Context): Int {
        val last = lastCompletedDay(context) ?: return 0
        val stored = prefs(context).getInt(KEY_STREAK, 0)
        // A run only survives while it is current: finished today, or finished yesterday and
        // still live until midnight. Anything older is a run that has already been broken, and
        // reporting it would be claiming a streak that ended days ago.
        return if (last == todayId() || last == dayBefore(todayId())) stored else 0
    }

    fun recordToday(context: Context, score: Int) {
        val today = todayId()
        val previous = lastCompletedDay(context)
        val continued = previous == dayBefore(today)
        val streak = when {
            previous == today -> prefs(context).getInt(KEY_STREAK, 1)
            continued -> prefs(context).getInt(KEY_STREAK, 0) + 1
            else -> 1
        }

        prefs(context).edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_SCORE, score)
            .putInt(KEY_STREAK, streak)
            .apply()
    }

    /**
     * The day before [dayId], as another id.
     *
     * Done by parsing and subtracting a day rather than by arithmetic on the string, so months,
     * year ends and leap days all take care of themselves. Returns [dayId] unchanged if it
     * cannot be read, which breaks the streak rather than inventing one.
     */
    fun dayBefore(dayId: String): String {
        val parsed = runCatching { formatter().parse(dayId) }.getOrNull() ?: return dayId
        val calendar = java.util.Calendar.getInstance().apply {
            time = parsed
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return formatter().format(calendar.time)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The fixed sequence of dice behind a daily challenge.
 *
 * Seeding an ordinary roller with the day's seed would *not* give two players the same puzzle.
 * A roller hands out numbers in the order they are asked for, so a player who holds three dice
 * and rerolls two consumes two values where a player who rerolls all five consumes five — and
 * from the second turn onwards the two are playing entirely different games.
 *
 * So the day's dice are drawn up front as a fixed tape instead, and a die takes its value from
 * its own position in that tape: turn, roll number, and which of the five it is. Holding a die
 * keeps the value it has, rerolling it yields the value the tape already had waiting at that
 * slot, and every player's decisions are measured against exactly the same dice.
 */
class DiceTape(seed: Long) {

    private val rolls: List<List<Int>> = Random(seed).let { random ->
        List(DailyChallenge.TURNS * MAX_ROLLS_PER_TURN) {
            List(DICE_COUNT) { random.nextInt(1, 7) }
        }
    }

    /**
     * The tape's values for one roll. Out-of-range turns fall back to the last slot rather than
     * throwing, so a game that somehow runs long degrades to repeated dice instead of crashing.
     */
    fun valuesAt(turn: Int, rollIndex: Int): List<Int> {
        val slot = (turn * MAX_ROLLS_PER_TURN + rollIndex).coerceIn(0, rolls.lastIndex)
        return rolls[slot]
    }

    /** [current] with every unheld die replaced by the tape's value for this roll. */
    fun apply(turn: Int, rollIndex: Int, current: List<Int>, held: Set<Int>): List<Int> {
        val values = valuesAt(turn, rollIndex)
        return current.mapIndexed { index, value ->
            if (index in held) value else values.getOrElse(index) { value }
        }
    }
}
