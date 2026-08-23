package com.yahtzee.online.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A lifetime record of how this player actually plays: totals, a per-category breakdown, and the
 * last few results.
 *
 * Kept on the device rather than in Firebase. The leaderboard already answers "who scored
 * highest", which is a claim about everyone and has to be shared; this answers "how do I play",
 * which is nobody else's business and is worth having with no connection. Recording is
 * deliberately driven from the finished [Player] rather than accumulated turn by turn, so a game
 * abandoned halfway leaves the record untouched instead of half-written.
 */
object PlayerStats {

    private const val PREFS = "player_stats"
    private const val KEY_TOTALS = "totals"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_RECENT = "recent"

    /** Enough history to show a trend without the preference growing without bound. */
    private const val MAX_RECENT = 25

    enum class Mode { SOLO, ONLINE, DAILY }

    /** How one category has gone across every game and every card. */
    data class CategoryRecord(val filled: Int = 0, val points: Int = 0, val zeroed: Int = 0) {
        val average: Float get() = if (filled == 0) 0f else points.toFloat() / filled
        val zeroRate: Int get() = if (filled == 0) 0 else zeroed * 100 / filled
    }

    data class GameRecord(
        val playedAt: Long,
        val mode: Mode,
        val score: Int,
        val won: Boolean,
        val cardCount: Int,
        val opponents: Int
    )

    data class Totals(
        val played: Int = 0,
        val won: Int = 0,
        val bestScore: Int = 0,
        val totalScore: Long = 0L,
        val yahtzees: Int = 0,
        val yahtzeeBonuses: Int = 0,
        val upperBonuses: Int = 0,
        /**
         * Upper sections completed, counted per card rather than per game — the denominator for
         * [upperBonusRate], which would otherwise be wrong in a multi-card game where one game
         * offers six chances at the bonus.
         */
        val upperSections: Int = 0
    ) {
        val averageScore: Int get() = if (played == 0) 0 else (totalScore / played).toInt()
        val winRate: Int get() = if (played == 0) 0 else won * 100 / played
        val upperBonusRate: Int get() = if (upperSections == 0) 0 else upperBonuses * 100 / upperSections
        val hasPlayed: Boolean get() = played > 0
    }

    /**
     * Folds one finished game into the record.
     *
     * [won] is passed in rather than derived: solo games against bots and online games decide a
     * winner differently, and a daily challenge has no opponent to beat at all.
     */
    fun record(
        context: Context,
        player: Player,
        cardCount: Int,
        mode: Mode,
        won: Boolean,
        opponents: Int
    ) {
        val cards = cardCount.coerceAtLeast(1)
        val score = player.grandTotalAllCards(cards)

        val previous = totals(context)
        var yahtzees = 0
        var upperBonuses = 0
        val categories = categoryRecords(context).toMutableMap()

        for (card in 0 until cards) {
            val forCard = player.scoresForCard(card)
            if (forCard.isEmpty()) continue
            if (Scoring.upperBonus(forCard) > 0) upperBonuses++
            forCard.forEach { (category, points) ->
                if (category == Category.YAHTZEE && points > 0) yahtzees++
                val existing = categories[category] ?: CategoryRecord()
                categories[category] = existing.copy(
                    filled = existing.filled + 1,
                    points = existing.points + points,
                    zeroed = existing.zeroed + if (points == 0) 1 else 0
                )
            }
        }

        writeTotals(
            context,
            previous.copy(
                played = previous.played + 1,
                won = previous.won + if (won) 1 else 0,
                bestScore = maxOf(previous.bestScore, score),
                totalScore = previous.totalScore + score,
                yahtzees = previous.yahtzees + yahtzees,
                yahtzeeBonuses = previous.yahtzeeBonuses + player.yahtzeeBonusCount,
                upperBonuses = previous.upperBonuses + upperBonuses,
                upperSections = previous.upperSections + cards
            )
        )
        writeCategories(context, categories)
        writeRecent(
            context,
            (recent(context) + GameRecord(
                playedAt = System.currentTimeMillis(),
                mode = mode,
                score = score,
                won = won,
                cardCount = cards,
                opponents = opponents
            )).takeLast(MAX_RECENT)
        )
    }

    fun totals(context: Context): Totals {
        val raw = prefs(context).getString(KEY_TOTALS, null) ?: return Totals()
        return runCatching {
            val json = JSONObject(raw)
            Totals(
                played = json.optInt("played"),
                won = json.optInt("won"),
                bestScore = json.optInt("bestScore"),
                totalScore = json.optLong("totalScore"),
                yahtzees = json.optInt("yahtzees"),
                yahtzeeBonuses = json.optInt("yahtzeeBonuses"),
                upperBonuses = json.optInt("upperBonuses"),
                upperSections = json.optInt("upperSections")
            )
        }.getOrDefault(Totals())
    }

    fun categoryRecords(context: Context): Map<Category, CategoryRecord> {
        val raw = prefs(context).getString(KEY_CATEGORIES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            Category.values().mapNotNull { category ->
                val item = json.optJSONObject(category.name) ?: return@mapNotNull null
                category to CategoryRecord(
                    filled = item.optInt("filled"),
                    points = item.optInt("points"),
                    zeroed = item.optInt("zeroed")
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Most recent last. */
    fun recent(context: Context): List<GameRecord> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                GameRecord(
                    playedAt = item.optLong("playedAt"),
                    mode = runCatching { Mode.valueOf(item.optString("mode")) }.getOrDefault(Mode.SOLO),
                    score = item.optInt("score"),
                    won = item.optBoolean("won"),
                    cardCount = item.optInt("cardCount", 1),
                    opponents = item.optInt("opponents")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun writeTotals(context: Context, totals: Totals) {
        val json = JSONObject()
            .put("played", totals.played)
            .put("won", totals.won)
            .put("bestScore", totals.bestScore)
            .put("totalScore", totals.totalScore)
            .put("yahtzees", totals.yahtzees)
            .put("yahtzeeBonuses", totals.yahtzeeBonuses)
            .put("upperBonuses", totals.upperBonuses)
            .put("upperSections", totals.upperSections)
        prefs(context).edit().putString(KEY_TOTALS, json.toString()).apply()
    }

    private fun writeCategories(context: Context, records: Map<Category, CategoryRecord>) {
        val json = JSONObject()
        records.forEach { (category, record) ->
            json.put(
                category.name,
                JSONObject()
                    .put("filled", record.filled)
                    .put("points", record.points)
                    .put("zeroed", record.zeroed)
            )
        }
        prefs(context).edit().putString(KEY_CATEGORIES, json.toString()).apply()
    }

    private fun writeRecent(context: Context, games: List<GameRecord>) {
        val array = JSONArray()
        games.forEach {
            array.put(
                JSONObject()
                    .put("playedAt", it.playedAt)
                    .put("mode", it.mode.name)
                    .put("score", it.score)
                    .put("won", it.won)
                    .put("cardCount", it.cardCount)
                    .put("opponents", it.opponents)
            )
        }
        prefs(context).edit().putString(KEY_RECENT, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
