package com.yahtzee.online.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A standing record against one person.
 *
 * Every online game and every duel already knew who was in it and who came out on top, and then
 * threw all of it away the moment the box was dismissed. Each game evaporated on its own, which
 * is what made them feel like separate events rather than an ongoing thing — losing to somebody
 * is a different matter at seven to five than it is as a one-off.
 */
data class Rivalry(
    val opponentId: String,
    val name: String,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val lastPlayedAt: Long = 0L
) {
    val played: Int get() = wins + losses + draws

    /** True while you are behind, which is the more motivating thing to be told. */
    val trailing: Boolean get() = losses > wins
}

/** How one game turned out against one opponent, from this device's point of view. */
enum class RivalryResult { WIN, LOSS, DRAW }

object Rivalries {

    private const val PREFS = "rivalries"
    private const val KEY_LIST = "records"

    /**
     * Records are kept for as long as they are used, oldest dropping off past this. A list nobody
     * can read the bottom of is not a list.
     */
    private const val MAX_KEPT = 40

    /**
     * [existing] with one more game folded into it.
     *
     * Pure, and separate from the storage, because this is the part with a decision in it and the
     * part worth testing: the counting, the name following a rename, and the fact that a record
     * is created on first meeting rather than needing to be set up.
     */
    fun merge(
        existing: Rivalry?,
        opponentId: String,
        name: String,
        result: RivalryResult,
        at: Long
    ): Rivalry {
        val base = existing ?: Rivalry(opponentId = opponentId, name = name)
        return base.copy(
            // Their current name wins: people rename themselves, and a record against a name
            // nobody uses any more is a record against a stranger.
            name = name.ifEmpty { base.name },
            wins = base.wins + if (result == RivalryResult.WIN) 1 else 0,
            losses = base.losses + if (result == RivalryResult.LOSS) 1 else 0,
            draws = base.draws + if (result == RivalryResult.DRAW) 1 else 0,
            lastPlayedAt = maxOf(base.lastPlayedAt, at)
        )
    }

    /** Everyone this device has a record against, most recently played first. */
    fun all(context: Context): List<Rivalry> = read(context).sortedByDescending { it.lastPlayedAt }

    fun record(
        context: Context,
        opponentId: String,
        name: String,
        result: RivalryResult,
        at: Long = System.currentTimeMillis()
    ) {
        if (opponentId.isEmpty()) return
        val current = read(context)
        val updated = merge(current.firstOrNull { it.opponentId == opponentId }, opponentId, name, result, at)
        write(
            context,
            (current.filterNot { it.opponentId == opponentId } + updated)
                .sortedByDescending { it.lastPlayedAt }
                .take(MAX_KEPT)
        )
    }

    fun forget(context: Context, opponentId: String) {
        write(context, read(context).filterNot { it.opponentId == opponentId })
    }

    private fun read(context: Context): List<Rivalry> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("opponentId").ifEmpty { return@mapNotNull null }
                Rivalry(
                    opponentId = id,
                    name = item.optString("name"),
                    wins = item.optInt("wins"),
                    losses = item.optInt("losses"),
                    draws = item.optInt("draws"),
                    lastPlayedAt = item.optLong("lastPlayedAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, records: List<Rivalry>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("opponentId", record.opponentId)
                    .put("name", record.name)
                    .put("wins", record.wins)
                    .put("losses", record.losses)
                    .put("draws", record.draws)
                    .put("lastPlayedAt", record.lastPlayedAt)
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
