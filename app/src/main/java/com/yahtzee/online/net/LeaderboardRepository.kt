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
    private val boardsRef = database.getReference("boards")

    companion object {
        /**
         * Every format gets its own board.
         *
         * The old board kept one best score per player whatever the format, so a six-card total
         * sat above every one-card total no matter how well either was played — it partly ranked
         * people by how many cards they happened to choose. Splitting them keeps every number on
         * a board comparable to every other on it, without throwing away the games people spent
         * an evening on.
         */
        fun allTimeBoardId(cardCount: Int): String = "c$cardCount-all"

        /**
         * The board for one format in one month, as `cN-YYYY-MM`.
         *
         * A season exists because a best-ever board freezes: once somebody posts a big number,
         * everyone else is playing for second place for good. A month is short enough that a
         * good run wins it and long enough that one lucky game does not.
         */
        fun monthlyBoardId(cardCount: Int, at: java.util.Date = java.util.Date()): String =
            "c$cardCount-" + java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(at)
    }

    /** Files a finished game on both its format's month board and its all-time one. */
    fun submitRankedScore(cardCount: Int, playerId: String, name: String, score: Int) {
        val cards = cardCount.coerceAtLeast(1)
        submitBest(boardsRef.child(allTimeBoardId(cards)).child(playerId), name, score)
        submitBest(boardsRef.child(monthlyBoardId(cards)).child(playerId), name, score)
    }

    /** Streams one board, highest first. */
    fun observeBoard(
        boardId: String,
        limit: Int = 10,
        onChange: (List<LeaderboardEntry>) -> Unit
    ): Pair<com.google.firebase.database.Query, ValueEventListener> {
        val query = boardsRef.child(boardId).orderByChild("bestScore").limitToLast(limit)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onChange(snapshot.toEntries())
            override fun onCancelled(error: DatabaseError) = onChange(emptyList())
        }
        query.addValueEventListener(listener)
        return query to listener
    }

    /**
     * Keeps whichever score is higher, in a transaction.
     *
     * The same player may finish games on more than one device, and a plain read/compare/write
     * could lose the better result to a concurrent update.
     */
    /**
     * Takes this player off every board they appear on.
     *
     * The boards are enumerated rather than guessed at. They are keyed by format and by month —
     * `c6-all`, `c1-2026-09` and so on — so a list built here from what the app happens to know
     * would quietly leave a player on a board from a month nobody thought about, which is the one
     * outcome this must not have. "Remove my score" has to mean all of them.
     *
     * A removal is a write of null, and Firebase skips validation on those, so nothing in the
     * rules stands in the way of a player taking their own entry down.
     */
    fun removeFromBoards(playerId: String, onDone: (Int) -> Unit = {}) {
        if (playerId.isEmpty()) {
            onDone(0)
            return
        }

        // All three places a score is published, not just the current one.
        //
        // `boards` is where ranked games go now, but `leaderboard` still holds entries from
        // before the split by format — nothing writes to it any more, and it is still read — and
        // `dailyBoard` carries a row per day the daily challenge was played. A removal that took
        // somebody off only the newest of the three would leave their name on two lists and
        // report success, which is worse than not offering it.
        var pending = 3
        var removed = 0
        var failed = false

        fun finish(count: Int) {
            if (count < 0) failed = true else removed += count
            if (--pending == 0) onDone(if (failed) -1 else removed)
        }

        removeUnder(boardsRef, playerId) { finish(it) }
        removeUnder(dailyRef, playerId) { finish(it) }

        // The one flat node: the player is a direct child rather than a child of each board.
        boardRef.child(playerId).removeValue()
            .addOnSuccessListener { finish(1) }
            .addOnFailureListener { finish(-1) }
    }

    /**
     * Removes [playerId] from every child board under [parent].
     *
     * The boards are enumerated rather than guessed at. They are keyed by format and by month or
     * by day — `c6-all`, `c1-2026-09`, a date per daily challenge — so a list built in code would
     * quietly leave somebody standing on a board nobody thought to name.
     *
     * One write for the lot, so a player is never half-removed if the connection goes mid-way.
     */
    private fun removeUnder(
        parent: com.google.firebase.database.DatabaseReference,
        playerId: String,
        onDone: (Int) -> Unit
    ) {
        parent.get()
            .addOnSuccessListener { snapshot ->
                val boards = snapshot.children.mapNotNull { it.key }
                if (boards.isEmpty()) {
                    onDone(0)
                    return@addOnSuccessListener
                }
                parent.updateChildren(boards.associate { "$it/$playerId" to null })
                    .addOnSuccessListener { onDone(boards.size) }
                    .addOnFailureListener { onDone(-1) }
            }
            .addOnFailureListener { onDone(-1) }
    }

    private fun submitBest(
        ref: com.google.firebase.database.DatabaseReference,
        name: String,
        score: Int
    ) {
        if (name.isEmpty() || score <= 0) return
        ref.runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(currentData: com.google.firebase.database.MutableData):
                com.google.firebase.database.Transaction.Result {
                val existing = currentData.child("bestScore").getValue(Int::class.java) ?: 0
                if (score > existing) {
                    currentData.child("bestScore").value = score
                    currentData.child("updatedAt").value = System.currentTimeMillis()
                }
                // A rename takes effect either way, so a board never shows a name nobody uses.
                currentData.child("name").value = name
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
