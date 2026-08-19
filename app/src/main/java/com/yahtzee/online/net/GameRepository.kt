package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.bot.BotStrategy
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DiceRoller
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.scoresForCard
import java.util.UUID
import kotlin.random.Random

class GameRepository {

    private val db = FirebaseDatabase.getInstance()
    private val roller = DiceRoller()
    val localPlayerId: String = UUID.randomUUID().toString()

    private fun roomRef(code: String) = db.getReference("games").child(code)

    fun createRoom(hostName: String, diceColor: Int, cardCount: Int = 1, onResult: (String) -> Unit) {
        val code = generateRoomCode()
        val ref = roomRef(code)
        val host = Player(
            id = localPlayerId,
            name = hostName,
            joinedAt = System.currentTimeMillis(),
            diceColor = diceColor
        )
        val state = GameState(
            roomCode = code,
            hostId = localPlayerId,
            status = GameState.STATUS_LOBBY,
            playerOrder = listOf(localPlayerId),
            players = mapOf(localPlayerId to host),
            cardCount = cardCount
        )
        ref.setValue(state.toMap()).addOnSuccessListener { onResult(code) }
    }

    fun joinRoom(code: String, playerName: String, diceColor: Int, onResult: (Boolean) -> Unit) {
        val ref = roomRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onResult(false)
                return@addOnSuccessListener
            }
            val player = Player(
                id = localPlayerId,
                name = playerName,
                joinedAt = System.currentTimeMillis(),
                diceColor = diceColor
            )
            ref.child("players").child(localPlayerId).setValue(player)
            ref.child("playerOrder").get().addOnSuccessListener { orderSnap ->
                val order = orderSnap.children.mapNotNull { it.getValue(String::class.java) }.toMutableList()
                if (localPlayerId !in order) {
                    order.add(localPlayerId)
                    ref.child("playerOrder").setValue(order)
                }
                onResult(true)
            }
        }.addOnFailureListener { onResult(false) }
    }

    fun listenToRoom(code: String, onUpdate: (GameState?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onUpdate(snapshot.toGameState())
            }
            override fun onCancelled(error: DatabaseError) {
                onUpdate(null)
            }
        }
        roomRef(code).addValueEventListener(listener)
        return listener
    }

    fun stopListening(code: String, listener: ValueEventListener) {
        roomRef(code).removeEventListener(listener)
    }

    fun startGame(code: String) {
        val ref = roomRef(code)
        ref.child("status").setValue(GameState.STATUS_ROLL_OFF)
        ref.child("openingRolls").setValue(null)
        ref.child("openingRollTied").setValue(null)
    }

    /** Rolls one die for [playerId] during the pre-game roll-off to decide turn order. */
    fun rollForFirst(code: String, state: GameState, playerId: String) {
        val eligible = if (state.openingRollTied.isNotEmpty()) state.openingRollTied else state.playerOrder
        if (playerId !in eligible) return
        if (state.openingRolls.containsKey(playerId)) return

        val ref = roomRef(code)
        val value = Random.nextInt(1, 7)
        ref.child("openingRolls").child(playerId).setValue(value)

        val updatedRolls = state.openingRolls + (playerId to value)
        if (eligible.all { updatedRolls.containsKey(it) }) {
            resolveRollOff(code, state, updatedRolls, eligible)
        }
    }

    private fun resolveRollOff(code: String, state: GameState, rolls: Map<String, Int>, eligible: List<String>) {
        val ref = roomRef(code)
        val highest = eligible.maxOf { rolls[it] ?: 0 }
        val winners = eligible.filter { rolls[it] == highest }

        if (winners.size > 1) {
            // Tie: reset and let only the tied players roll again.
            ref.child("openingRollTied").setValue(winners)
            ref.child("openingRolls").setValue(null)
            return
        }

        val firstPlayerId = winners.first()
        val reordered = listOf(firstPlayerId) + state.playerOrder.filter { it != firstPlayerId }
        ref.child("playerOrder").setValue(reordered)
        ref.child("currentTurnIndex").setValue(0)
        ref.child("status").setValue(GameState.STATUS_PLAYING)
        ref.child("dice").setValue(List(5) { 1 })
        ref.child("held").setValue(List(5) { false })
        ref.child("rollsUsed").setValue(0)
        ref.child("turnDeadline").setValue(System.currentTimeMillis() + GameState.TURN_TIME_MILLIS)
        ref.child("openingRolls").setValue(null)
        ref.child("openingRollTied").setValue(null)
    }

    fun rollDice(code: String, currentDice: List<Int>, held: List<Boolean>, rollsUsed: Int) {
        if (rollsUsed >= 3) return
        val heldSet = held.mapIndexedNotNull { i, isHeld -> if (isHeld) i else null }.toSet()
        val newDice = roller.reroll(currentDice, heldSet)
        val ref = roomRef(code)
        ref.child("dice").setValue(newDice)
        ref.child("rollsUsed").setValue(rollsUsed + 1)
    }

    fun toggleHold(code: String, held: List<Boolean>, index: Int) {
        val updated = held.toMutableList()
        updated[index] = !updated[index]
        roomRef(code).child("held").setValue(updated)
    }

    fun submitScore(
        code: String,
        state: GameState,
        category: Category,
        playerId: String,
        cardIndex: Int = 0
    ) {
        val player = state.players[playerId] ?: return
        val key = ScoreKey.of(cardIndex, category)
        if (player.scores.containsKey(key)) return

        val points = Scoring.score(category, state.dice)
        val ref = roomRef(code)
        // A Yahtzee bonus is earned only if a Yahtzee has already been scored as one somewhere;
        // with several cards in play that can be on any of them.
        val alreadyHadYahtzee = (0 until state.cardCount.coerceAtLeast(1)).any { card ->
            player.scores[ScoreKey.of(card, Category.YAHTZEE)] == 50
        }
        ref.child("players").child(playerId).child("scores").child(key).setValue(points)

        // Every extra Yahtzee after the box holds 50 is worth another 100 points.
        val earnedBonus = category != Category.YAHTZEE &&
            state.dice.groupBy { it }.values.any { it.size == 5 } &&
            alreadyHadYahtzee
        if (earnedBonus) {
            ref.child("players").child(playerId).child("yahtzeeBonusCount")
                .setValue(player.yahtzeeBonusCount + 1)
        }

        val nextIndex = (state.currentTurnIndex + 1) % state.playerOrder.size
        ref.child("currentTurnIndex").setValue(nextIndex)
        // Leave `dice` as whatever they last showed (per-player preference) — only reset
        // held/rollsUsed so the next player starts a fresh turn, but the dice visually stay
        // put until someone actually rolls again.
        ref.child("held").setValue(List(5) { false })
        ref.child("rollsUsed").setValue(0)
        ref.child("turnDeadline").setValue(System.currentTimeMillis() + GameState.TURN_TIME_MILLIS)

        val allDone = state.players.values.all {
            val scores = if (it.id == playerId) it.scores + (key to points) else it.scores
            scores.size == state.totalSlots
        }
        if (allDone) {
            val winner = state.players.values.maxByOrNull {
                // Include a bonus earned on this very play: the write above has not round-tripped
                // through Firebase yet, so `state` still holds the pre-bonus count and a
                // game-winning extra Yahtzee would otherwise be left out of the comparison.
                val withLatest = if (it.id == playerId) {
                    it.copy(
                        scores = it.scores + (key to points),
                        yahtzeeBonusCount = it.yahtzeeBonusCount + if (earnedBonus) 1 else 0
                    )
                } else it
                withLatest.grandTotalAllCards(state.cardCount)
            }
            ref.child("status").setValue(GameState.STATUS_FINISHED)
            ref.child("winnerId").setValue(winner?.id ?: "")
        }
    }

    /**
     * Called by the current player's own client when their turn timer expires. Rolls for
     * them if they still have rolls left, otherwise scores their best available category
     * automatically so the game keeps moving instead of stalling on an inactive player.
     */
    fun autoPlayTurn(code: String, state: GameState, playerId: String) {
        if (!state.isMyTurn(playerId)) return
        val player = state.players[playerId] ?: return
        val cards = state.cardCount.coerceAtLeast(1)

        val openByCard = (0 until cards)
            .associateWith { card ->
                Category.values().filter { !player.scores.containsKey(ScoreKey.of(card, it)) }.toSet()
            }
            .filterValues { it.isNotEmpty() }
        if (openByCard.isEmpty()) return

        // Use the remaining rolls before settling, keeping whatever best serves a still-open
        // category. This reuses the bot's own strategy rather than a naive reroll, so an
        // abandoned turn still plays a sensible hand instead of scoring the first thing rolled.
        if (state.rollsUsed < MAX_ROLLS_PER_TURN) {
            if (state.rollsUsed == 0) {
                rollDice(code, state.dice, List(5) { false }, 0)
                return
            }
            val rollsLeft = MAX_ROLLS_PER_TURN - state.rollsUsed
            val holds = BotStrategy.chooseHolds(state.dice, openByCard.values.flatten().toSet(), rollsLeft)
            if (holds.size < 5) {
                val heldFlags = List(5) { it in holds }
                roomRef(code).child("held").setValue(heldFlags)
                rollDice(code, state.dice, heldFlags, state.rollsUsed)
                return
            }
            // Holding all five means the strategy is content; fall through and score.
        }

        // Score the best slot available, judged per card by the same category logic the bots use
        // so high-value boxes are not burned on a poor roll.
        val best = openByCard.entries
            .map { (card, open) ->
                val upperTotal = Category.UPPER.sumOf { player.scoresForCard(card)[it] ?: 0 }
                val category = BotStrategy.chooseCategory(state.dice, open, upperTotal)
                Triple(card, category, Scoring.score(category, state.dice))
            }
            .maxByOrNull { it.third }
            ?: return
        submitScore(code, state, best.second, playerId, best.first)
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}

private fun GameState.toMap(): Map<String, Any?> = mapOf(
    "roomCode" to roomCode,
    "hostId" to hostId,
    "status" to status,
    "playerOrder" to playerOrder,
    "players" to players.mapValues { it.value.toMap() },
    "currentTurnIndex" to currentTurnIndex,
    "rollsUsed" to rollsUsed,
    "dice" to dice,
    "held" to held,
    "winnerId" to winnerId,
    "turnDeadline" to turnDeadline,
    "openingRolls" to openingRolls,
    "openingRollTied" to openingRollTied,
    "cardCount" to cardCount
)

private fun Player.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "joinedAt" to joinedAt,
    "scores" to scores,
    "yahtzeeBonusCount" to yahtzeeBonusCount,
    // Was missing, which silently dropped the HOST's dice colour: joining writes the Player
    // object directly and so carried it, but room creation goes through this map.
    "diceColor" to diceColor
)

private fun DataSnapshot.toGameState(): GameState? {
    if (!exists()) return null
    val roomCode = child("roomCode").getValue(String::class.java) ?: return null
    val hostId = child("hostId").getValue(String::class.java) ?: ""
    val status = child("status").getValue(String::class.java) ?: GameState.STATUS_LOBBY
    val playerOrder = child("playerOrder").children.mapNotNull { it.getValue(String::class.java) }
    val players = child("players").children.mapNotNull { playerSnap ->
        val id = playerSnap.child("id").getValue(String::class.java) ?: return@mapNotNull null
        val name = playerSnap.child("name").getValue(String::class.java) ?: ""
        val joinedAt = playerSnap.child("joinedAt").getValue(Long::class.java) ?: 0L
        val scores = playerSnap.child("scores").children.mapNotNull scoreLoop@{ scoreSnap ->
            val key = scoreSnap.key ?: return@scoreLoop null
            val value = scoreSnap.getValue(Int::class.java) ?: return@scoreLoop null
            key to value
        }.toMap()
        val bonus = playerSnap.child("yahtzeeBonusCount").getValue(Int::class.java) ?: 0
        val diceColor = playerSnap.child("diceColor").getValue(Int::class.java) ?: 0
        id to Player(id, name, joinedAt, scores, bonus, diceColor)
    }.toMap()
    val currentTurnIndex = child("currentTurnIndex").getValue(Int::class.java) ?: 0
    val rollsUsed = child("rollsUsed").getValue(Int::class.java) ?: 0
    val dice = child("dice").children.mapNotNull { it.getValue(Int::class.java) }
        .ifEmpty { listOf(1, 1, 1, 1, 1) }
    val held = child("held").children.mapNotNull { it.getValue(Boolean::class.java) }
        .ifEmpty { List(5) { false } }
    val winnerId = child("winnerId").getValue(String::class.java) ?: ""
    val turnDeadline = child("turnDeadline").getValue(Long::class.java) ?: 0L
    val openingRolls = child("openingRolls").children.mapNotNull { rollSnap ->
        val key = rollSnap.key ?: return@mapNotNull null
        val value = rollSnap.getValue(Int::class.java) ?: return@mapNotNull null
        key to value
    }.toMap()
    val openingRollTied = child("openingRollTied").children.mapNotNull { it.getValue(String::class.java) }
    val cardCount = child("cardCount").getValue(Int::class.java) ?: 1

    return GameState(
        roomCode, hostId, status, playerOrder, players,
        currentTurnIndex, rollsUsed, dice, held, winnerId, turnDeadline,
        openingRolls, openingRollTied, cardCount
    )
}
