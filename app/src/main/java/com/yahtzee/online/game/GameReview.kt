package com.yahtzee.online.game

import android.content.Context
import com.yahtzee.online.bot.ExpertStrategy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps a record of where the player put each roll, so a finished game can be looked back over.
 *
 * A scorecard says what happened but not what else was on offer, which is where a game is
 * actually lost: the turn that went into Chance for 20 with Fives sitting open at 25 leaves no
 * trace afterwards. Every scoring decision is noted as it is made, together with what was still
 * open at that moment, because that is what makes the choice judgeable later.
 *
 * Only the local player's turns are recorded. Reviewing an opponent's game would be reading over
 * their shoulder, and there is nothing to learn from a bot's card.
 */
object GameReview {

    private const val PREFS = "game_review"
    private const val KEY_TURNS = "turns"

    /** One scoring decision, with enough context to say what else could have been done. */
    data class Turn(
        val dice: List<Int>,
        val card: Int,
        val chosen: Category,
        val points: Int,
        val open: Set<Category>,
        val upperTotal: Int
    ) {
        /**
         * Where a strong player would have put this roll. Uses the same valuation the Expert bot
         * plays by, so the advice is one a real opponent would follow rather than a rule of thumb
         * invented for the review screen.
         */
        val better: Category get() = ExpertStrategy.chooseCategory(dice, open, upperTotal)

        val betterPoints: Int get() = Scoring.score(better, dice)

        /** Whether a strong player would have used a different box at all. */
        val differs: Boolean get() = better != chosen

        /**
         * Points forgone.
         *
         * Zero when the box chosen was the right one, and also zero when a lower score was the
         * right call — protecting a box is not a mistake and counting it as one would teach the
         * opposite of the lesson. It is also zero for a swap that costs nothing now but spends
         * the wrong box, which is why [differs] is tracked separately: that is a real mistake
         * with no immediate price, and reporting it as points would be wrong in the other
         * direction.
         */
        val missed: Int get() = if (!differs) 0 else maxOf(0, betterPoints - points)
    }

    data class Summary(
        val turns: List<Turn>,
        val missedPoints: Int,
        val differed: Int,
        /** The single turn that cost the most, if any did. */
        val worst: Turn?
    ) {
        val hasTurns: Boolean get() = turns.isNotEmpty()
    }

    /** Starts a fresh record. Called when a game begins, so the last one is not appended to. */
    fun begin(context: Context) {
        prefs(context).edit().remove(KEY_TURNS).apply()
    }

    /**
     * Notes one decision. Takes the state as it was *before* scoring, since the open categories
     * are the whole point and submitting closes one of them.
     */
    fun record(context: Context, state: GameState, playerId: String, card: Int, category: Category) {
        val player = state.players[playerId] ?: return
        val forCard = player.scoresForCard(card)
        val open = Category.values().filterNot { forCard.containsKey(it) }.toSet()
        if (open.isEmpty()) return

        val turn = Turn(
            dice = state.dice,
            card = card,
            chosen = category,
            points = Scoring.score(category, state.dice),
            open = open,
            upperTotal = Category.UPPER.sumOf { forCard[it] ?: 0 }
        )
        write(context, turns(context) + turn)
    }

    fun turns(context: Context): List<Turn> {
        val raw = prefs(context).getString(KEY_TURNS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val dice = item.optJSONArray("dice")?.let { d ->
                    (0 until d.length()).map { d.getInt(it) }
                } ?: return@mapNotNull null
                val open = item.optJSONArray("open")?.let { o ->
                    (0 until o.length()).mapNotNull { index ->
                        runCatching { Category.valueOf(o.getString(index)) }.getOrNull()
                    }.toSet()
                } ?: return@mapNotNull null
                val chosen = runCatching { Category.valueOf(item.optString("chosen")) }.getOrNull()
                    ?: return@mapNotNull null
                Turn(
                    dice = dice,
                    card = item.optInt("card"),
                    chosen = chosen,
                    points = item.optInt("points"),
                    open = open,
                    upperTotal = item.optInt("upperTotal")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun summary(context: Context): Summary {
        val turns = turns(context)
        return Summary(
            turns = turns,
            missedPoints = turns.sumOf { it.missed },
            differed = turns.count { it.differs },
            worst = turns.filter { it.missed > 0 }.maxByOrNull { it.missed }
        )
    }

    private fun write(context: Context, turns: List<Turn>) {
        val array = JSONArray()
        turns.forEach { turn ->
            array.put(
                JSONObject()
                    .put("dice", JSONArray(turn.dice))
                    .put("card", turn.card)
                    .put("chosen", turn.chosen.name)
                    .put("points", turn.points)
                    .put("open", JSONArray(turn.open.map { it.name }))
                    .put("upperTotal", turn.upperTotal)
            )
        }
        prefs(context).edit().putString(KEY_TURNS, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
