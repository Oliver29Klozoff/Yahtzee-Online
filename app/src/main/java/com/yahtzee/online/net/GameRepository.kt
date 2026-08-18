package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DiceRoller
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.Scoring
import java.util.UUID
import kotlin.random.Random

class GameRepository {

    private val db = FirebaseDatabase.getInstance()
    private val roller = DiceRoller()
    val localPlayerId: String = UUID.randomUUID().toString()

    private fun roomRef(code: String) = db.getReference("games").child(code)

    fun createRoom(hostName: String, onResult: (String) -> Unit) {
        val code = generateRoomCode()
        val ref = roomRef(code)
        val host = Player(id = localPlayerId, name = hostName, joinedAt = System.currentTimeMillis())
        val state = GameState(
            roomCode = code,
            hostId = localPlayerId,
            status = GameState.STATUS_LOBBY,
            playerOrder = listOf(localPlayerId),
            players = mapOf(localPlayerId to host)
        )
        ref.setValue(state.toMap()).addOnSuccessListener { onResult(code) }
    }

    fun joinRoom(code: String, playerName: String, onResult: (Boolean) -> Unit) {
        val ref = roomRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onResult(false)
                return@addOnSuccessListener
            }
            val player = Player(id = localPlayerId, name = playerName, joinedAt = System.currentTimeMillis())
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
        ref.child("status").setValue(GameState.STATUS_PLAYING)
        ref.child("dice").setValue(List(5) { 1 })
        ref.child("held").setValue(List(5) { false })
        ref.child("rollsUsed").setValue(0)
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

    fun submitScore(code: String, state: GameState, category: Category, playerId: String) {
        val player = state.players[playerId] ?: return
        if (player.scores.containsKey(category.name)) return

        val points = Scoring.score(category, state.dice)
        val ref = roomRef(code)
        val alreadyHadYahtzee = player.scores[Category.YAHTZEE.name] == 50
        ref.child("players").child(playerId).child("scores").child(category.name).setValue(points)

        if (category != Category.YAHTZEE && state.dice.groupBy { it }.values.any { it.size == 5 } && alreadyHadYahtzee) {
            ref.child("players").child(playerId).child("yahtzeeBonusCount")
                .setValue(player.yahtzeeBonusCount + 1)
        }

        val nextIndex = (state.currentTurnIndex + 1) % state.playerOrder.size
        ref.child("currentTurnIndex").setValue(nextIndex)
        ref.child("dice").setValue(List(5) { 1 })
        ref.child("held").setValue(List(5) { false })
        ref.child("rollsUsed").setValue(0)

        val allDone = state.players.values.all {
            val scores = if (it.id == playerId) it.scores + (category.name to points) else it.scores
            scores.size == Category.values().size
        }
        if (allDone) {
            val winner = state.players.values.maxByOrNull {
                val rawScores = if (it.id == playerId) it.scores + (category.name to points) else it.scores
                val byCategory = rawScores.mapKeys { entry -> Category.valueOf(entry.key) }
                Scoring.grandTotal(byCategory, it.yahtzeeBonusCount)
            }
            ref.child("status").setValue(GameState.STATUS_FINISHED)
            ref.child("winnerId").setValue(winner?.id ?: "")
        }
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
    "winnerId" to winnerId
)

private fun Player.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "joinedAt" to joinedAt,
    "scores" to scores,
    "yahtzeeBonusCount" to yahtzeeBonusCount
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
        id to Player(id, name, joinedAt, scores, bonus)
    }.toMap()
    val currentTurnIndex = child("currentTurnIndex").getValue(Int::class.java) ?: 0
    val rollsUsed = child("rollsUsed").getValue(Int::class.java) ?: 0
    val dice = child("dice").children.mapNotNull { it.getValue(Int::class.java) }
        .ifEmpty { listOf(1, 1, 1, 1, 1) }
    val held = child("held").children.mapNotNull { it.getValue(Boolean::class.java) }
        .ifEmpty { List(5) { false } }
    val winnerId = child("winnerId").getValue(String::class.java) ?: ""

    return GameState(
        roomCode, hostId, status, playerOrder, players,
        currentTurnIndex, rollsUsed, dice, held, winnerId
    )
}
