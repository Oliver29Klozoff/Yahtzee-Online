package com.yahtzee.online.game

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/**
 * A duel: two or more people play the *same* dice, once each, and the higher score wins.
 *
 * The premise is that luck is removed entirely. Everybody gets the identical thirteen turns of
 * rolls, so the gap between two scores is nothing but judgement — which is the one thing a dice
 * game normally cannot offer, and the reason this is worth more than a shared leaderboard.
 *
 * ## Why a duel is not simply "today's daily dice"
 *
 * The obvious build was to duel over the day's challenge tape, which already exists and is
 * already identical for everyone. It has a hole in it: anyone who played today's daily challenge
 * before the duel arrived already knows the dice. They would know which turn holds the run of
 * sixes and which one is the graveyard, and the whole premise — that judgement is the only
 * variable — is gone, silently, with no way for the opponent to tell it happened.
 *
 * So a duel deals its own tape, seeded from the duel code. Nobody has seen those dice before,
 * every player in the duel sees the same ones, and it costs nothing: the tape machinery does not
 * care where the seed came from. It also means duels are no longer rationed to one a day.
 */
object Duel {

    /** Excludes I, O, 0 and 1: a code gets read aloud and typed in by hand. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 5

    private const val PREFS = "duels"
    private const val KEY_JOINED = "joined"
    private const val KEY_SEEN = "seenFinishers"

    /**
     * The seat the solver plays from.
     *
     * A fixed id rather than a generated one so it is the same seat in every duel, and so a duel
     * can never end up with two of them: a second "add the expert" writes over the first rather
     * than seating a rival clone.
     */
    const val EXPERT_ID = "expert"

    fun isExpert(playerId: String): Boolean = playerId == EXPERT_ID

    fun generateCode(): String =
        (1..CODE_LENGTH).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    /**
     * The duel's dice.
     *
     * Prefixed before hashing so a duel code can never collide with a date string and hand a duel
     * the same tape as some day's challenge — which would reopen the very hole this design exists
     * to close.
     */
    fun tapeFor(code: String): DiceTape = DiceTape(DailyChallenge.seedFor("duel:$code"))

    fun linkFor(code: String): String = "yahtzee://duel/$code"

    /** Duel codes this device has joined, newest first. */
    fun joined(context: Context): List<String> = readJoined(context).keys.toList()

    /** True once this device has posted a score, so the duel cannot be replayed for a better one. */
    fun hasPlayed(context: Context, code: String): Boolean =
        readJoined(context)[code] == true

    fun remember(context: Context, code: String) {
        val current = readJoined(context)
        if (current.containsKey(code)) return
        write(context, linkedMapOf(code to false).apply { putAll(current) })
    }

    fun markPlayed(context: Context, code: String) {
        val current = readJoined(context)
        write(context, LinkedHashMap(current).apply { put(code, true) })
    }

    fun forget(context: Context, code: String) {
        write(context, LinkedHashMap(readJoined(context)).apply { remove(code) })
        prefs(context).edit().remove(seenKey(code)).apply()
    }

    /**
     * How many people had posted a score in this duel the last time it was announced.
     *
     * Counting rather than remembering who is enough here and far cheaper: the only question the
     * background check asks is whether anybody new has finished since it last looked, and a count
     * that has gone up is exactly that. Nobody ever un-finishes — the rules forbid a second write
     * to a score — so the count only ever moves one way.
     */
    fun seenFinishers(context: Context, code: String): Int =
        prefs(context).getInt(seenKey(code), 0)

    fun markSeenFinishers(context: Context, code: String, count: Int) {
        prefs(context).edit().putInt(seenKey(code), count).apply()
    }

    private fun seenKey(code: String) = "$KEY_SEEN:$code"

    /**
     * Code to whether this device has played it, newest first.
     *
     * Insertion order is the whole point of the JSON object here — [JSONObject] preserves the
     * order keys were added, and the list on the start screen reads far better newest-first than
     * in whatever order a hash map felt like.
     */
    private fun readJoined(context: Context): Map<String, Boolean> {
        val raw = prefs(context).getString(KEY_JOINED, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val result = LinkedHashMap<String, Boolean>()
            json.keys().forEach { key -> result[key] = json.optBoolean(key, false) }
            result
        }.getOrDefault(emptyMap())
    }

    private fun write(context: Context, entries: Map<String, Boolean>) {
        val json = JSONObject()
        entries.forEach { (code, played) -> json.put(code, played) }
        prefs(context).edit().putString(KEY_JOINED, json.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** One player's standing in a duel. */
data class DuelPlayer(
    val id: String,
    val name: String,
    val score: Int?,
    val finishedAt: Long
) {
    val hasPlayed: Boolean get() = score != null
}

/** A duel as it currently stands. */
data class DuelState(
    val code: String,
    val createdBy: String,
    val players: List<DuelPlayer>
) {
    /** Everyone who has posted, best first. Unfinished players are not ranked. */
    val standings: List<DuelPlayer>
        get() = players.filter { it.hasPlayed }.sortedByDescending { it.score ?: 0 }

    val waiting: List<DuelPlayer> get() = players.filterNot { it.hasPlayed }

    /**
     * The winner, but only once nobody is still playing.
     *
     * Declaring a leader while someone has turns left would announce a result that is not one —
     * and on a duel of two, the first to finish would always be shown as winning.
     */
    val winner: DuelPlayer?
        get() = if (waiting.isEmpty() && players.size > 1) standings.firstOrNull() else null

    val isSettled: Boolean get() = waiting.isEmpty() && players.count { it.hasPlayed } > 1
}
