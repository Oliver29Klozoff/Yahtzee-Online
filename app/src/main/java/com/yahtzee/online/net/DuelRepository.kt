package com.yahtzee.online.net

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.game.Duel
import com.yahtzee.online.game.DuelPlayer
import com.yahtzee.online.game.DuelState
import com.yahtzee.online.game.PlayerProfile

/**
 * The duel's shared state: who is in it, and what they scored.
 *
 * Deliberately tiny. A duel does not need a synchronised game — everyone plays the same fixed
 * tape on their own phone, at their own pace, offline if they like. The only thing that has to
 * travel is the final number, so that is the only thing here.
 */
class DuelRepository(private val context: Context) {

    private val root = FirebaseDatabase.getInstance().getReference("duels")

    val localPlayerId: String = PlayerProfile.getId(context)

    private fun duelRef(code: String) = root.child(code)

    /**
     * Opens a duel with the creator already seated.
     *
     * Seating the creator immediately rather than waiting for them to play is what makes the
     * invite make sense: whoever opens the link sees who challenged them, by name, before
     * deciding to play.
     */
    fun createDuel(hostName: String, onResult: (String) -> Unit) {
        val code = Duel.generateCode()
        val now = System.currentTimeMillis()
        duelRef(code).setValue(
            mapOf(
                "createdAt" to now,
                "createdBy" to localPlayerId,
                "hostName" to hostName,
                "players" to mapOf(
                    localPlayerId to mapOf("name" to hostName, "joinedAt" to now)
                )
            )
        ).addOnSuccessListener {
            Duel.remember(context, code)
            onResult(code)
        }
    }

    /**
     * Takes a seat, if the duel exists.
     *
     * A seat is only written when there is not already one: rejoining must not overwrite a score
     * that has been posted, which would let a player wipe their own result and play again.
     */
    fun joinDuel(code: String, playerName: String, onResult: (Boolean) -> Unit) {
        val ref = duelRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onResult(false)
                return@addOnSuccessListener
            }
            val seat = ref.child("players").child(localPlayerId)
            if (snapshot.child("players").child(localPlayerId).exists()) {
                seat.child("name").setValue(playerName)
            } else {
                seat.setValue(
                    mapOf("name" to playerName, "joinedAt" to System.currentTimeMillis())
                )
            }
            Duel.remember(context, code)
            onResult(true)
        }.addOnFailureListener { onResult(false) }
    }

    /**
     * Posts this device's score.
     *
     * The rules refuse a second write to `score`, so a duel cannot be replayed for a better
     * number even by an app built to try. The local mark is the fast path for the UI; the rule is
     * the one that actually holds.
     */
    fun submitScore(code: String, name: String, score: Int) {
        Duel.markPlayed(context, code)
        duelRef(code).child("players").child(localPlayerId).updateChildren(
            mapOf(
                "name" to name,
                "score" to score,
                "finishedAt" to System.currentTimeMillis()
            )
        )
    }

    fun listen(code: String, onUpdate: (DuelState?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onUpdate(snapshot.toDuelState(code))
            override fun onCancelled(error: DatabaseError) = onUpdate(null)
        }
        duelRef(code).addValueEventListener(listener)
        return listener
    }

    fun stopListening(code: String, listener: ValueEventListener) {
        duelRef(code).removeEventListener(listener)
    }

    fun readOnce(code: String, onResult: (DuelState?) -> Unit) {
        duelRef(code).get()
            .addOnSuccessListener { onResult(it.toDuelState(code)) }
            .addOnFailureListener { onResult(null) }
    }

    fun deleteDuel(code: String) {
        duelRef(code).removeValue()
    }
}

private fun DataSnapshot.toDuelState(code: String): DuelState? {
    if (!exists()) return null
    val players = child("players").children.mapNotNull { seat ->
        val id = seat.key ?: return@mapNotNull null
        DuelPlayer(
            id = id,
            name = seat.child("name").getValue(String::class.java) ?: "",
            // Absent means "still playing", which is a different thing from a score of zero —
            // and a zero is a real, postable result, so the two must not collapse together.
            score = seat.child("score").getValue(Long::class.java)?.toInt(),
            finishedAt = seat.child("finishedAt").getValue(Long::class.java) ?: 0L
        )
    }
    return DuelState(
        code = code,
        createdBy = child("createdBy").getValue(String::class.java).orEmpty(),
        players = players
    )
}
