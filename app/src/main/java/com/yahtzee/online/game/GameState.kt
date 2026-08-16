package com.yahtzee.online.game

data class Player(
    val id: String = "",
    val name: String = "",
    val joinedAt: Long = 0L,
    val scores: Map<String, Int> = emptyMap(),
    val yahtzeeBonusCount: Int = 0
)

data class GameState(
    val roomCode: String = "",
    val hostId: String = "",
    val status: String = "LOBBY",
    val playerOrder: List<String> = emptyList(),
    val players: Map<String, Player> = emptyMap(),
    val currentTurnIndex: Int = 0,
    val rollsUsed: Int = 0,
    val dice: List<Int> = listOf(1, 1, 1, 1, 1),
    val held: List<Boolean> = listOf(false, false, false, false, false),
    val winnerId: String = ""
) {
    companion object {
        const val STATUS_LOBBY = "LOBBY"
        const val STATUS_PLAYING = "PLAYING"
        const val STATUS_FINISHED = "FINISHED"
    }

    val currentPlayerId: String?
        get() = playerOrder.getOrNull(currentTurnIndex)

    fun isMyTurn(playerId: String): Boolean = currentPlayerId == playerId

    fun isGameOver(): Boolean =
        players.isNotEmpty() && players.values.all { it.scores.size == Category.values().size }
}
