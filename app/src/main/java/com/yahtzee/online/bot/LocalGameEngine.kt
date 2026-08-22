package com.yahtzee.online.bot

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DiceRoller
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.SavedSoloGame
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.scoresForCard
import java.util.UUID
import kotlin.random.Random

/**
 * Runs a full Yahtzee game entirely on-device (no Firebase) between the human player and
 * N bot opponents. Mirrors the shape of the online GameState/GameRepository flow so the UI
 * layer can treat it almost identically, but every mutation happens locally and bot turns
 * play themselves out automatically.
 */
class LocalGameEngine(
    humanName: String,
    botCount: Int,
    humanColor: Int = 0,
    private val cardCount: Int = 1,
    private val botSkill: AppSettings.BotSkill = AppSettings.BotSkill.HARD,
    /** A game in progress to rebuild instead of dealing a new one. */
    restored: SavedSoloGame? = null
) {

    private companion object {
        /** Short, easily told apart at a glance on the scorecard tabs. */
        val BOT_NAMES = listOf(
            "Ada", "Bruno", "Cleo", "Dexter", "Etta", "Felix",
            "Greta", "Hugo", "Iris", "Jonas", "Kira", "Lorne",
            "Mabel", "Nico", "Opal", "Piper", "Quinn", "Rufus",
            "Sable", "Theo", "Uma", "Vera", "Wilder", "Zaia"
        )
    }

    /**
     * Dice colours for the bots: hues spread evenly around the wheel starting from the player's
     * own, so every bot is distinct both from the player and from each other.
     *
     * Picking from the fixed palette and filtering out the player's colour looked equivalent but
     * was not. That filter compares colours exactly, and a colour chosen with the custom sliders
     * almost never equals a palette entry — so a player using a custom blue would still be given
     * a bot in palette blue, and two players' dice would be near-indistinguishable. Deriving from
     * the player's hue keeps them apart whatever they picked.
     */
    private fun botColoursAvoiding(humanColor: Int, count: Int): List<Int> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(humanColor, hsv)
        val step = 360f / (count + 1)
        return (1..count).map { i ->
            android.graphics.Color.HSVToColor(
                floatArrayOf((hsv[0] + step * i) % 360f, 0.78f, 0.95f)
            )
        }
    }

    val humanPlayerId: String = restored?.humanPlayerId ?: UUID.randomUUID().toString()
    private val botIds: List<String> = restored?.botIds ?: List(botCount) { UUID.randomUUID().toString() }
    private val roller = DiceRoller()

    var state: GameState = restored?.state ?: run {
        val human = Player(
            id = humanPlayerId,
            name = humanName,
            joinedAt = System.currentTimeMillis(),
            diceColor = humanColor
        )
        val botColors = botColoursAvoiding(humanColor, botCount)
        // Names are drawn without replacement so no two opponents share one, and shuffled per
        // game so the same three bots are not sitting there every time.
        val names = BOT_NAMES.shuffled().toMutableList()
        val bots = botIds.mapIndexed { i, id ->
            id to Player(
                id = id,
                name = names.removeFirstOrNull() ?: "Bot ${i + 1}",
                joinedAt = System.currentTimeMillis(),
                diceColor = botColors[i]
            )
        }
        val order = listOf(humanPlayerId) + botIds
        GameState(
            roomCode = "SOLO",
            hostId = humanPlayerId,
            status = GameState.STATUS_ROLL_OFF,
            playerOrder = order,
            players = (listOf(humanPlayerId to human) + bots).toMap(),
            dice = List(5) { 1 },
            held = List(5) { false },
            rollsUsed = 0,
            cardCount = cardCount
        )
    }
        private set

    private var onChange: (() -> Unit)? = null

    fun setOnChangeListener(listener: () -> Unit) {
        onChange = listener
    }

    fun isBotTurn(): Boolean = state.currentPlayerId in botIds

    /**
     * Players who still owe an opening roll. After a tie this narrows to just the tied players,
     * mirroring the online rules — everyone else keeps the placing they already earned.
     */
    fun rollOffPending(): List<String> {
        val eligible = state.openingRollTied.ifEmpty { state.playerOrder }
        return eligible.filterNot { state.openingRolls.containsKey(it) }
    }

    /**
     * Rolls one die for [playerId] to decide turn order, resolving once everyone eligible has
     * rolled. Returns the value rolled, or null if that player was not owed a roll.
     */
    fun rollForFirst(playerId: String): Int? {
        if (state.status != GameState.STATUS_ROLL_OFF) return null
        if (playerId !in rollOffPending()) return null

        val value = Random.nextInt(1, 7)
        state = state.copy(openingRolls = state.openingRolls + (playerId to value))
        onChange?.invoke()
        return value
    }

    /** True once everyone eligible has rolled and the result is only waiting to be applied. */
    fun rollOffReady(): Boolean =
        state.status == GameState.STATUS_ROLL_OFF && rollOffPending().isEmpty()

    /**
     * Applies the roll-off result. Deliberately separate from [rollForFirst] rather than
     * happening inside it: resolving on the same call that recorded the final roll flipped the
     * game into play in the same breath, so the last die — usually a bot's — was never seen.
     * The caller now holds the finished row on screen first and then calls this.
     */
    fun finishRollOff() {
        if (!rollOffReady()) return
        resolveRollOff()
    }

    private fun resolveRollOff() {
        val eligible = state.openingRollTied.ifEmpty { state.playerOrder }
        val highest = eligible.maxOf { state.openingRolls[it] ?: 0 }
        val winners = eligible.filter { state.openingRolls[it] == highest }

        state = if (winners.size > 1) {
            // Tie: only those players roll again, and their earlier rolls are cleared so the
            // pending list is recomputed correctly.
            state.copy(openingRollTied = winners, openingRolls = emptyMap())
        } else {
            val first = winners.first()
            state.copy(
                playerOrder = listOf(first) + state.playerOrder.filterNot { it == first },
                currentTurnIndex = 0,
                status = GameState.STATUS_PLAYING,
                openingRolls = emptyMap(),
                openingRollTied = emptyList()
            )
        }
        onChange?.invoke()
    }

    fun rollDice() {
        if (state.rollsUsed >= MAX_ROLLS_PER_TURN) return
        val heldSet = state.held.mapIndexedNotNull { i, isHeld -> if (isHeld) i else null }.toSet()
        val newDice = roller.reroll(state.dice, heldSet)
        state = state.copy(dice = newDice, rollsUsed = state.rollsUsed + 1)
        onChange?.invoke()
    }

    fun toggleHold(index: Int) {
        val updated = state.held.toMutableList()
        updated[index] = !updated[index]
        state = state.copy(held = updated)
        onChange?.invoke()
    }

    fun submitScore(category: Category, cardIndex: Int = 0) {
        val playerId = state.currentPlayerId ?: return
        val player = state.players[playerId] ?: return
        val key = ScoreKey.of(cardIndex, category)
        if (player.scores.containsKey(key)) return

        val points = Scoring.score(category, state.dice)
        // With several cards in play the qualifying Yahtzee may sit on any of them.
        val alreadyHadYahtzee = (0 until cardCount).any {
            player.scores[ScoreKey.of(it, Category.YAHTZEE)] == 50
        }
        val bonusEarned = category != Category.YAHTZEE &&
            state.dice.groupBy { it }.values.any { it.size == 5 } && alreadyHadYahtzee

        val updatedPlayer = player.copy(
            scores = player.scores + (key to points),
            yahtzeeBonusCount = player.yahtzeeBonusCount + if (bonusEarned) 1 else 0
        )
        val updatedPlayers = state.players + (playerId to updatedPlayer)

        val allDone = updatedPlayers.values.all { it.scores.size == state.totalSlots }
        val nextIndex = (state.currentTurnIndex + 1) % state.playerOrder.size

        state = if (allDone) {
            val winner = updatedPlayers.values.maxByOrNull { it.grandTotalAllCards(cardCount) }
            state.copy(
                players = updatedPlayers,
                status = GameState.STATUS_FINISHED,
                winnerId = winner?.id ?: ""
            )
        } else {
            state.copy(
                players = updatedPlayers,
                currentTurnIndex = nextIndex,
                held = List(5) { false },
                rollsUsed = 0
            )
        }
        onChange?.invoke()
    }

    /**
     * True once the bot has nothing left to do this turn but score (either it used all its
     * rolls, or [BotStrategy] chose to hold all five dice early). Drives [SoloGameActivity]'s
     * step-by-step pacing: the UI calls [stepBotRoll] once per visible roll, waiting for the
     * dice animation between calls, instead of the whole turn resolving in one synchronous burst.
     */
    /** Open categories per card for [playerId], excluding cards that are already complete. */
    private fun openByCard(playerId: String): Map<Int, Set<Category>> {
        val player = state.players[playerId] ?: return emptyMap()
        return (0 until cardCount)
            .associateWith { card ->
                Category.values().filter { !player.scores.containsKey(ScoreKey.of(card, it)) }.toSet()
            }
            .filterValues { it.isNotEmpty() }
    }

    fun isBotDoneRolling(): Boolean {
        val playerId = state.currentPlayerId ?: return true
        if (playerId !in botIds) return true
        val open = openByCard(playerId).values.flatten().toSet()
        if (open.isEmpty()) return true
        if (state.rollsUsed == 0) return false
        if (state.rollsUsed >= MAX_ROLLS_PER_TURN) return true
        val rollsLeft = MAX_ROLLS_PER_TURN - state.rollsUsed
        return BotSkillPlay.chooseHolds(botSkill, state.dice, open, rollsLeft).size == 5
    }

    /**
     * Performs exactly one roll of the current bot's turn — the first roll (everything unheld)
     * if this is the start of the turn, otherwise applies [BotStrategy]'s hold decision first.
     * Call repeatedly (checking [isBotDoneRolling] between calls) to play out a full bot turn
     * with each intermediate roll visible, then call [finishBotTurn] to submit the score.
     */
    fun stepBotRoll() {
        val playerId = state.currentPlayerId ?: return
        if (playerId !in botIds) return

        if (state.rollsUsed == 0) {
            state = state.copy(held = List(5) { false })
        } else {
            // Hold whatever serves any card still open, since the bot has yet to commit to one.
            val open = openByCard(playerId).values.flatten().toSet()
            val rollsLeft = MAX_ROLLS_PER_TURN - state.rollsUsed
            val holdIndices = BotSkillPlay.chooseHolds(botSkill, state.dice, open, rollsLeft)
            state = state.copy(held = List(5) { it in holdIndices })
        }
        rollDiceForBot()
    }

    /**
     * Submits the bot's final score once [isBotDoneRolling] is true. With several cards it picks
     * the best category on each, then plays whichever of those scores highest — so a poor roll
     * lands on a card the bot cares less about rather than burning a valuable box.
     */
    fun finishBotTurn() {
        val playerId = state.currentPlayerId ?: return
        if (playerId !in botIds) return
        val player = state.players[playerId] ?: return

        val best = openByCard(playerId).entries
            .map { (card, open) ->
                val upperTotal = Category.UPPER.sumOf { player.scoresForCard(card)[it] ?: 0 }
                val category = BotSkillPlay.chooseCategory(botSkill, state.dice, open, upperTotal)
                Triple(card, category, Scoring.score(category, state.dice))
            }
            .maxByOrNull { it.third }
            ?: return
        submitScore(best.second, best.first)
    }

    private fun rollDiceForBot() {
        val heldSet = state.held.mapIndexedNotNull { i, isHeld -> if (isHeld) i else null }.toSet()
        val newDice = roller.reroll(state.dice, heldSet)
        state = state.copy(dice = newDice, rollsUsed = state.rollsUsed + 1)
        onChange?.invoke()
    }
}
