package com.yahtzee.online.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One player's finished card, as it stood when the game ended. */
data class ArchivedPlayer(
    val id: String,
    val name: String,
    val scores: Map<String, Int>,
    val yahtzeeBonusCount: Int
)

/** A game that has been played out, kept so the card can be looked at again. */
data class ArchivedGame(
    val playedAt: Long,
    val mode: PlayerStats.Mode,
    val cardCount: Int,
    val youId: String,
    val winnerId: String,
    val players: List<ArchivedPlayer>
) {
    val you: ArchivedPlayer? get() = players.firstOrNull { it.id == youId }
    val youWon: Boolean get() = winnerId.isNotEmpty() && winnerId == youId

    fun totalFor(player: ArchivedPlayer): Int =
        Player(
            id = player.id,
            name = player.name,
            scores = player.scores,
            yahtzeeBonusCount = player.yahtzeeBonusCount
        ).grandTotalAllCards(cardCount)

    /** Highest first, which is the order a finished game is read in. */
    val standings: List<ArchivedPlayer> get() = players.sortedByDescending { totalFor(it) }
}

/**
 * The cards of games already played.
 *
 * [PlayerStats] keeps what a game was worth — a score, a mode, whether it was won — and that is
 * all it ever kept. The card itself went nowhere: a solo game held it only in memory, and an
 * online one held it in a room that the daily sweep eventually took away. So the answer to "what
 * did I actually score on Sunday" was a number and nothing behind it, which is the one thing a
 * scorecard is for.
 *
 * Written at the moment a game is declared over, from the state that declared it, so what is kept
 * is what everybody was looking at rather than a reconstruction.
 *
 * On the device, like the rest of the record. A card is a memento rather than a claim about
 * anybody, so there is nothing here that wants sharing or defending.
 */
object GameArchive {

    private const val PREFS = "game_archive"
    private const val KEY_GAMES = "games"

    /**
     * How many finished games are kept.
     *
     * Cards are far larger than the summary lines [PlayerStats] keeps — six cards for four
     * players is over three hundred boxes — so this is a smaller number than it might be, chosen
     * to hold a good few evenings of play rather than a season of it.
     */
    const val MAX_GAMES = 25

    /** Newest first. */
    fun all(context: Context): List<ArchivedGame> {
        val raw = prefs(context).getString(KEY_GAMES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { decode(array.optJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.playedAt }
    }

    fun latest(context: Context): ArchivedGame? = all(context).firstOrNull()

    /**
     * Files a finished game.
     *
     * A game already filed is not filed twice. Both screens can be told a game is over more than
     * once — a late snapshot, a screen rebuilt behind the dialog — and a history full of the same
     * evening repeated is worse than no history.
     */
    fun record(
        context: Context,
        state: GameState,
        youId: String,
        mode: PlayerStats.Mode,
        playedAt: Long = System.currentTimeMillis()
    ) {
        val players = state.playerOrder.mapNotNull { id ->
            state.players[id]?.let {
                ArchivedPlayer(
                    id = it.id,
                    name = it.name,
                    scores = it.scores,
                    yahtzeeBonusCount = it.yahtzeeBonusCount
                )
            }
        }
        if (players.isEmpty()) return

        val game = ArchivedGame(
            playedAt = playedAt,
            mode = mode,
            cardCount = state.cardCount.coerceAtLeast(1),
            youId = youId,
            winnerId = state.decidedWinner()?.id.orEmpty(),
            players = players
        )

        val existing = all(context)
        if (existing.any { it.isSameGameAs(game) }) return

        val kept = (listOf(game) + existing).take(MAX_GAMES)
        prefs(context).edit()
            .putString(KEY_GAMES, JSONArray(kept.map { encode(it) }).toString())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_GAMES).apply()
    }

    /**
     * Whether two records are the same game filed twice.
     *
     * Matched on who played and what they scored rather than on the clock, because the second
     * telling arrives moments after the first with its own timestamp. The same people finishing
     * on the same totals is the same game, not a remarkable coincidence.
     */
    private fun ArchivedGame.isSameGameAs(other: ArchivedGame): Boolean =
        cardCount == other.cardCount &&
            players.map { it.id } == other.players.map { it.id } &&
            players.map { it.scores } == other.players.map { it.scores }

    private fun encode(game: ArchivedGame): JSONObject {
        val players = JSONArray()
        game.players.forEach { player ->
            val scores = JSONObject()
            player.scores.forEach { (key, value) -> scores.put(key, value) }
            players.put(
                JSONObject()
                    .put("id", player.id)
                    .put("name", player.name)
                    .put("scores", scores)
                    .put("yahtzeeBonusCount", player.yahtzeeBonusCount)
            )
        }
        return JSONObject()
            .put("playedAt", game.playedAt)
            .put("mode", game.mode.name)
            .put("cardCount", game.cardCount)
            .put("youId", game.youId)
            .put("winnerId", game.winnerId)
            .put("players", players)
    }

    private fun decode(json: JSONObject?): ArchivedGame? {
        if (json == null) return null
        val playersJson = json.optJSONArray("players") ?: return null
        val players = (0 until playersJson.length()).mapNotNull { i ->
            val item = playersJson.optJSONObject(i) ?: return@mapNotNull null
            val scoresJson = item.optJSONObject("scores") ?: JSONObject()
            ArchivedPlayer(
                id = item.optString("id"),
                name = item.optString("name"),
                scores = scoresJson.keys().asSequence().associateWith { scoresJson.optInt(it) },
                yahtzeeBonusCount = item.optInt("yahtzeeBonusCount")
            )
        }
        if (players.isEmpty()) return null

        return ArchivedGame(
            playedAt = json.optLong("playedAt"),
            mode = runCatching { PlayerStats.Mode.valueOf(json.optString("mode")) }
                .getOrDefault(PlayerStats.Mode.SOLO),
            cardCount = json.optInt("cardCount", 1).coerceAtLeast(1),
            youId = json.optString("youId"),
            winnerId = json.optString("winnerId"),
            players = players
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
