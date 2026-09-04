package com.yahtzee.online.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A solo game in progress, enough to rebuild it exactly. */
data class SavedSoloGame(
    val humanPlayerId: String,
    val botIds: List<String>,
    val cardCount: Int,
    val botSkill: AppSettings.BotSkill,
    val state: GameState,
    /**
     * The day this is a challenge for, or null for an ordinary solo game. Stored so a daily left
     * open overnight resumes against the tape it was dealt from — picking up today's dice halfway
     * through would score the player against a hand nobody else played.
     */
    val dailyId: String? = null,
    /**
     * The duel this is a round of, or null. Kept for the same reason as [dailyId] — the duel's
     * dice come from its code, so a game resumed without it would silently deal a different hand
     * than the one the opponent is being measured against.
     */
    val duelCode: String? = null,
    /**
     * The tournament this is a fixture in, or null. Together with [matchId] it is what lets a
     * match against a bot be picked up where it was left rather than dealt again — a tournament
     * match is somebody's run at a trophy, and re-dealing it is not a fresh start, it is a loss.
     */
    val tourneyCode: String? = null,
    /** Which fixture in [tourneyCode] this game settles. */
    val matchId: String? = null
)

/**
 * Persists the solo game across the activity being destroyed.
 *
 * Online games live in Firebase and survive on their own, but a solo game only ever existed in
 * [com.yahtzee.online.bot.LocalGameEngine] in memory — so rotating the phone, taking a call, or
 * backing out by accident silently threw away a game that might be forty turns in.
 *
 * Written after every move rather than on a lifecycle callback: the process can be killed
 * without warning, and a save that only happens on the way out is the one that does not happen.
 */
object SoloGameStore {

    private const val PREFS = "solo_game"
    private const val KEY_GAME = "game"

    /**
     * Which slot a game is filed under.
     *
     * A tournament fixture gets its own, one per match, because there is only one ordinary slot
     * and whatever is played next takes it. Backing out of a fixture to check the bracket and
     * playing a quick solo game on the way back used to overwrite the fixture with the solo game
     * — the tournament match was gone before anybody returned to it.
     */
    private fun slotFor(game: SavedSoloGame): String = slotFor(game.tourneyCode, game.matchId)

    private fun slotFor(tourneyCode: String?, matchId: String?): String =
        if (tourneyCode.isNullOrEmpty() || matchId.isNullOrEmpty()) KEY_GAME
        else "match_${tourneyCode}_$matchId"

    fun save(context: Context, game: SavedSoloGame) {
        prefs(context).edit().putString(slotFor(game), encode(game).toString()).apply()
    }

    fun load(context: Context): SavedSoloGame? = load(context, KEY_GAME)

    /** The game saved for a tournament fixture, if that fixture was left part-played. */
    fun loadMatch(context: Context, tourneyCode: String, matchId: String): SavedSoloGame? =
        load(context, slotFor(tourneyCode, matchId))

    fun hasMatch(context: Context, tourneyCode: String, matchId: String): Boolean =
        loadMatch(context, tourneyCode, matchId) != null

    fun clearMatch(context: Context, tourneyCode: String, matchId: String) {
        prefs(context).edit().remove(slotFor(tourneyCode, matchId)).apply()
    }

    private fun load(context: Context, slot: String): SavedSoloGame? {
        val raw = prefs(context).getString(slot, null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    /**
     * The saved game, but only if it is still worth resuming.
     *
     * A daily challenge left unfinished from an earlier day is not: its board has closed, and
     * finishing it now would post yesterday's puzzle against today, or count today as played
     * without today's dice having been touched. Discarded outright rather than offered, so the
     * continue button never opens a game that cannot be scored.
     */
    fun loadResumable(context: Context): SavedSoloGame? {
        val saved = load(context) ?: return null
        val stale = saved.dailyId != null && saved.dailyId != DailyChallenge.todayId()
        if (stale) {
            clear(context)
            return null
        }
        return saved
    }

    fun hasGame(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_GAME).apply()
    }

    private fun encode(game: SavedSoloGame): JSONObject {
        val state = game.state
        val players = JSONObject()
        state.players.forEach { (id, player) ->
            val scores = JSONObject()
            player.scores.forEach { (key, value) -> scores.put(key, value) }
            players.put(
                id,
                JSONObject()
                    .put("id", player.id)
                    .put("name", player.name)
                    .put("scores", scores)
                    .put("yahtzeeBonusCount", player.yahtzeeBonusCount)
                    .put("diceColor", player.diceColor)
            )
        }
        return JSONObject()
            .put("humanPlayerId", game.humanPlayerId)
            .put("botIds", JSONArray(game.botIds))
            .put("cardCount", game.cardCount)
            .put("botSkill", game.botSkill.name)
            .put("dailyId", game.dailyId)
            .put("duelCode", game.duelCode)
            .put("tourneyCode", game.tourneyCode)
            .put("matchId", game.matchId)
            .put("status", state.status)
            .put("playerOrder", JSONArray(state.playerOrder))
            .put("players", players)
            .put("currentTurnIndex", state.currentTurnIndex)
            .put("rollsUsed", state.rollsUsed)
            .put("dice", JSONArray(state.dice))
            .put("held", JSONArray(state.held))
            .put("winnerId", state.winnerId)
    }

    private fun decode(json: JSONObject): SavedSoloGame? {
        val humanPlayerId = json.optString("humanPlayerId").ifEmpty { return null }
        val botIds = json.optJSONArray("botIds")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        } ?: return null
        val order = json.optJSONArray("playerOrder")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        } ?: return null

        val playersJson = json.optJSONObject("players") ?: return null
        val players = playersJson.keys().asSequence().mapNotNull { id ->
            val item = playersJson.optJSONObject(id) ?: return@mapNotNull null
            val scoresJson = item.optJSONObject("scores") ?: JSONObject()
            val scores = scoresJson.keys().asSequence()
                .associateWith { scoresJson.optInt(it) }
            id to Player(
                id = item.optString("id", id),
                name = item.optString("name"),
                scores = scores,
                yahtzeeBonusCount = item.optInt("yahtzeeBonusCount"),
                diceColor = item.optInt("diceColor")
            )
        }.toMap()

        val dice = json.optJSONArray("dice")?.let { array ->
            (0 until array.length()).map { array.getInt(it) }
        } ?: List(5) { 1 }
        val held = json.optJSONArray("held")?.let { array ->
            (0 until array.length()).map { array.getBoolean(it) }
        } ?: List(5) { false }

        val state = GameState(
            roomCode = "SOLO",
            hostId = humanPlayerId,
            status = json.optString("status", GameState.STATUS_PLAYING),
            playerOrder = order,
            players = players,
            currentTurnIndex = json.optInt("currentTurnIndex"),
            rollsUsed = json.optInt("rollsUsed"),
            dice = dice,
            held = held,
            winnerId = json.optString("winnerId"),
            cardCount = json.optInt("cardCount", 1)
        )

        // A finished game is not worth resuming — it would open straight onto the game-over box.
        if (state.status == GameState.STATUS_FINISHED) return null

        return SavedSoloGame(
            humanPlayerId = humanPlayerId,
            botIds = botIds,
            cardCount = json.optInt("cardCount", 1),
            botSkill = runCatching {
                AppSettings.BotSkill.valueOf(json.optString("botSkill"))
            }.getOrDefault(AppSettings.BotSkill.HARD),
            state = state,
            dailyId = json.optString("dailyId").takeIf { it.isNotEmpty() && it != "null" },
            duelCode = json.optString("duelCode").takeIf { it.isNotEmpty() && it != "null" },
            tourneyCode = json.optString("tourneyCode").takeIf { it.isNotEmpty() && it != "null" },
            matchId = json.optString("matchId").takeIf { it.isNotEmpty() && it != "null" }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
