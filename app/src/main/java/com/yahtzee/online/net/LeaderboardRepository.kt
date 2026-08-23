package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** One row on the global leaderboard: a player's best game to date. */
data class LeaderboardEntry(
    val playerId: String = "",
    val name: String = "",
    val bestScore: Int = 0,
    val updatedAt: Long = 0L
)

/**
 * The global leaderboard, shared by everyone who plays.
 *
 * Keyed by [PlayerProfile] id with one row per player holding their personal best, rather than
 * a row per game. A game-per-row log would grow without bound and let one strong player fill
 * every visible place, which reads as a high-score log rather than a ranking of people.
 */
class LeaderboardRepository {

    private val database = FirebaseDatabase.getInstance()
    private val boardRef = database.getReference("leaderboard")
    private val dailyRef = database.getReference("dailyBoard")

    /**
     * Records a finished game, keeping whichever score is higher. Read-then-write is done inside
     * a transaction because the same player may finish games on more than one device, and a
     * plain read/compare/write could lose the better result to a concurrent update.
     */
    fun submitScore(playerId: String, name: String, score: Int) {
        if (playerId.isEmpty() || name.isEmpty() || score <= 0) return
        val ref = boardRef.child(playerId)
        ref.runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(currentData: com.google.firebase.database.MutableData):
                com.google.firebase.database.Transaction.Result {
                val existing = currentData.child("bestScore").getValue(Int::class.java) ?: 0
                if (score > existing) {
                    currentData.child("name").value = name
                    currentData.child("bestScore").value = score
                    currentData.child("updatedAt").value = System.currentTimeMillis()
                } else {
                    // Keep the better score, but let a rename still take effect.
                    currentData.child("name").value = name
                }
                return com.google.firebase.database.Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) = Unit
        })
    }

    /**
     * Streams the top [limit] players, highest first. Firebase orders ascending and can only
     * cap from one end, so this takes the last N by score and reverses them.
     */
    fun observeTop(limit: Int = 10, onChange: (List<LeaderboardEntry>) -> Unit): ValueEventListener {
        val query = boardRef.orderByChild("bestScore").limitToLast(limit)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onChange(snapshot.toEntries())
            override fun onCancelled(error: DatabaseError) = onChange(emptyList())
        }
        query.addValueEventListener(listener)
        return listener
    }

    /**
     * Posts a daily-challenge result under its day.
     *
     * Written once and not improved on, unlike the all-time board: the whole point of the day's
     * puzzle is one attempt at a hand everyone else is playing too, so a second submission for a
     * day already on the board is refused rather than allowed to overwrite. The device also
     * blocks a replay locally — this is the half that survives a reinstall.
     */
    fun submitDailyScore(dayId: String, playerId: String, name: String, score: Int) {
        if (dayId.isEmpty() || playerId.isEmpty() || name.isEmpty() || score <= 0) return
        val ref = dailyRef.child(dayId).child(playerId)
        ref.runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(currentData: com.google.firebase.database.MutableData):
                com.google.firebase.database.Transaction.Result {
                if (currentData.child("bestScore").getValue(Int::class.java) != null) {
                    return com.google.firebase.database.Transaction.abort()
                }
                currentData.child("name").value = name
                currentData.child("bestScore").value = score
                currentData.child("updatedAt").value = System.currentTimeMillis()
                return com.google.firebase.database.Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) = Unit
        })
    }

    /** Streams one day's board, highest first. */
    fun observeDailyTop(
        dayId: String,
        limit: Int = 10,
        onChange: (List<LeaderboardEntry>) -> Unit
    ): Pair<com.google.firebase.database.Query, ValueEventListener> {
        val query = dailyRef.child(dayId).orderByChild("bestScore").limitToLast(limit)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onChange(snapshot.toEntries())
            override fun onCancelled(error: DatabaseError) = onChange(emptyList())
        }
        query.addValueEventListener(listener)
        return query to listener
    }

    fun removeListener(listener: ValueEventListener) {
        boardRef.removeEventListener(listener)
    }

    private fun DataSnapshot.toEntries(): List<LeaderboardEntry> =
        children.mapNotNull { child ->
            val name = child.child("name").getValue(String::class.java) ?: return@mapNotNull null
            val score = child.child("bestScore").getValue(Int::class.java) ?: return@mapNotNull null
            LeaderboardEntry(
                playerId = child.key.orEmpty(),
                name = name,
                bestScore = score,
                updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: 0L
            )
        }.sortedByDescending { it.bestScore }
}
