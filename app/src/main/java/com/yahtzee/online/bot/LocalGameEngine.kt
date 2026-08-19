package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DiceRoller
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.Scoring
import java.util.UUID

/**
 * Runs a full Yahtzee game entirely on-device (no Firebase) between the human player and
 * N bot opponents. Mirrors the shape of the online GameState/GameRepository flow so the UI
 * layer can treat it almost identically, but every mutation happens locally and bot turns
 * play themselves out automatically.
 */
class LocalGameEngine(humanName: String, botCount: Int) {

    val humanPlayerId: String = UUID.randomUUID().toString()
    private val botIds: List<String> = List(botCount) { UUID.randomUUID().toString() }
    private val roller = DiceRoller()

    var state: GameState = run {
        val human = Player(id = humanPlayerId, name = humanName, joinedAt = System.currentTimeMillis())
        val bots = botIds.mapIndexed { i, id ->
            id to Player(id = id, name = "Bot ${i + 1}", joinedAt = System.currentTimeMillis())
        }
        val order = listOf(humanPlayerId) + botIds
        GameState(
            roomCode = "SOLO",
            hostId = humanPlayerId,
            status = GameState.STATUS_PLAYING,
            playerOrder = order,
            players = (listOf(humanPlayerId to human) + bots).toMap(),
            dice = List(5) { 1 },
            held = List(5) { false },
            rollsUsed = 0
        )
    }
        private set

    private var onChange: (() -> Unit)? = null

    fun setOnChangeListener(listener: () -> Unit) {
        onChange = listener
    }

    fun isBotTurn(): Boolean = state.currentPlayerId in botIds

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

    fun submitScore(category: Category) {
        val playerId = state.currentPlayerId ?: return
        val player = state.players[playerId] ?: return
        if (player.scores.containsKey(category.name)) return

        val points = Scoring.score(category, state.dice)
        val alreadyHadYahtzee = player.scores[Category.YAHTZEE.name] == 50
        val bonusEarned = category != Category.YAHTZEE &&
            state.dice.groupBy { it }.values.any { it.size == 5 } && alreadyHadYahtzee

        val updatedPlayer = player.copy(
            scores = player.scores + (category.name to points),
            yahtzeeBonusCount = player.yahtzeeBonusCount + if (bonusEarned) 1 else 0
        )
        val updatedPlayers = state.players + (playerId to updatedPlayer)

        val allDone = updatedPlayers.values.all { it.scores.size == Category.values().size }
        val nextIndex = (state.currentTurnIndex + 1) % state.playerOrder.size

        state = if (allDone) {
            val winner = updatedPlayers.values.maxByOrNull { p ->
                val byCategory = p.scores.mapNotNull { (name, value) ->
                    runCatching { Category.valueOf(name) to value }.getOrNull()
                }.toMap()
                Scoring.grandTotal(byCategory, p.yahtzeeBonusCount)
            }
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

    /** Plays out the current bot's entire turn synchronously (roll/hold decisions, then score). */
    fun playBotTurn() {
        val playerId = state.currentPlayerId ?: return
        if (playerId !in botIds) return
        val player = state.players[playerId] ?: return
        val openCategories = Category.values().filter { !player.scores.containsKey(it.name) }.toSet()
        if (openCategories.isEmpty()) return

        // First roll: everything unheld.
        state = state.copy(held = List(5) { false })
        rollDiceForBot()

        while (state.rollsUsed < MAX_ROLLS_PER_TURN) {
            val rollsLeft = MAX_ROLLS_PER_TURN - state.rollsUsed
            val holdIndices = BotStrategy.chooseHolds(state.dice, openCategories, rollsLeft)
            if (holdIndices.size == 5) break // bot is happy with current dice, stop rolling early
            state = state.copy(held = List(5) { it in holdIndices })
            rollDiceForBot()
        }

        val upperTotal = Category.UPPER.sumOf { player.scores[it.name] ?: 0 }
        val chosen = BotStrategy.chooseCategory(state.dice, openCategories, upperTotal)
        submitScore(chosen)
    }

    private fun rollDiceForBot() {
        val heldSet = state.held.mapIndexedNotNull { i, isHeld -> if (isHeld) i else null }.toSet()
        val newDice = roller.reroll(state.dice, heldSet)
        state = state.copy(dice = newDice, rollsUsed = state.rollsUsed + 1)
        onChange?.invoke()
    }
}
