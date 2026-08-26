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

    /**
     * Seats the solver with the score it made of this duel's dice.
     *
     * Written by whichever device asked for it, because the answer does not depend on the device:
     * the tape comes from the duel code and the strategy is deterministic, so every phone that
     * runs it arrives at the same number. That also means it cannot be argued with — a player who
     * suspects the expert of an easy ride can recompute it and get the identical result.
     *
     * Asking a second time is refused by the same write-once rule that stops a player replaying
     * their own round — the seat already holds a score, and the rules will not overwrite one. That
     * is the desired outcome rather than a limitation, since a re-run on the same tape would
     * produce the identical number anyway; the button is hidden once the seat is taken so it never
     * comes up.
     */
    fun addExpert(code: String, expertName: String, score: Int) {
        duelRef(code).child("players").child(Duel.EXPERT_ID).setValue(
            mapOf(
                "name" to expertName,
                "joinedAt" to System.currentTimeMillis(),
                "score" to score,
                "finishedAt" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Opens a fresh duel carrying the same people across.
     *
     * The names are seated up front so the new duel reads as a rematch rather than as an empty
     * room — you can see who you are waiting on before anybody has played. They still have to be
     * sent the link: nothing here can reach into someone else's phone and put a duel on their
     * start screen, so the share sheet follows immediately.
     *
     * The solver is deliberately not carried over. Its score belongs to the old tape, and copying
     * it forward would post a number for dice it never saw.
     */
    fun createRematch(previous: DuelState, hostName: String, onResult: (String) -> Unit) {
        val code = Duel.generateCode()
        val now = System.currentTimeMillis()

        val seats = previous.players
            .filterNot { Duel.isExpert(it.id) }
            .associate { player ->
                val name = if (player.id == localPlayerId) hostName else player.name
                player.id to mapOf("name" to name, "joinedAt" to now)
            }
            .toMutableMap()
        seats[localPlayerId] = mapOf("name" to hostName, "joinedAt" to now)

        duelRef(code).setValue(
            mapOf(
                "createdAt" to now,
                "createdBy" to localPlayerId,
                "hostName" to hostName,
                "players" to seats
            )
        ).addOnSuccessListener {
            Duel.remember(context, code)
            onResult(code)
        }
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
