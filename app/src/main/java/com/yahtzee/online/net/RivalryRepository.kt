package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.RivalryResult

/** A head-to-head record as both players see it. */
data class SharedRecord(val wins: Int = 0, val losses: Int = 0, val draws: Int = 0) {
    val played: Int get() = wins + losses + draws
}

/**
 * The record between two people, kept where both of them can see it.
 *
 * It was kept on each phone separately, which meant two tallies of the same games computed from
 * two sets of local data. They agree right up until one of them misses a game — somebody
 * reinstalled, somebody was offline when it finished — and then they disagree for ever with
 * nothing able to reconcile them. A record that settles arguments has to be one record.
 *
 * ## Why the outcomes are stored rather than the counts
 *
 * The obvious shape is a pair of counters, and it is wrong: both players report the same finished
 * game, so a counter would be incremented twice, and guarding against that needs some notion of
 * who reports and what happens when that one never does.
 *
 * Storing the outcome *per game* removes the problem instead of managing it. Both players write
 * the same value to the same key, so the second write says exactly what the first said, and the
 * totals are counted on the way out. A duplicate is not merely tolerated, it is invisible; a
 * report that never arrives is filled in by the other player.
 */
class RivalryRepository(private val context: android.content.Context) {

    private val db = FirebaseDatabase.getInstance()

    /** What a drawn game is stored as, since a draw has no winner to name. */
    private val drawn = "draw"

    companion object {
        /**
         * The key for a pair, from both ids sorted.
         *
         * Sorted so the two players derive the same key without agreeing on who is "first" —
         * whoever writes, and from whichever phone, they land on the same record. Get this wrong
         * and nothing breaks visibly: each player simply writes to their own private key and the
         * feature quietly becomes the per-device tally it was meant to replace.
         */
        fun pairId(a: String, b: String): String = listOf(a, b).sorted().joinToString("_")
    }

    private fun ref(opponentId: String) =
        db.getReference("rivalries")
            .child(pairId(PlayerProfile.getId(context), opponentId))
            .child("games")

    /** Files one finished game. Safe to call from both phones, and twice from either. */
    fun report(roomCode: String, opponentId: String, result: RivalryResult) {
        if (roomCode.isEmpty() || opponentId.isEmpty()) return
        val me = PlayerProfile.getId(context)
        if (me.isEmpty() || me == opponentId) return

        val winner = when (result) {
            RivalryResult.WIN -> me
            RivalryResult.LOSS -> opponentId
            RivalryResult.DRAW -> drawn
        }
        ref(opponentId).child(roomCode).setValue(winner)
    }

    /** The record against one person, counted from the games both of them have filed. */
    fun load(opponentId: String, onResult: (SharedRecord) -> Unit) {
        if (opponentId.isEmpty()) {
            onResult(SharedRecord())
            return
        }
        val me = PlayerProfile.getId(context)
        ref(opponentId).get()
            .addOnSuccessListener { onResult(count(it, me)) }
            .addOnFailureListener { onResult(SharedRecord()) }
    }

    private fun count(snapshot: DataSnapshot, me: String): SharedRecord {
        var wins = 0
        var losses = 0
        var draws = 0
        snapshot.children.forEach { game ->
            when (game.getValue(String::class.java)) {
                null -> Unit
                drawn -> draws++
                me -> wins++
                else -> losses++
            }
        }
        return SharedRecord(wins, losses, draws)
    }
}
