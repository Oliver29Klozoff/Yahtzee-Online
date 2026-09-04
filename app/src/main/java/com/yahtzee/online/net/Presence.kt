package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.game.PlayerProfile

/**
 * Who is actually here.
 *
 * The question a group of three or four asks constantly and the app could not answer: is anybody
 * about? Without it the only way to find out is to send an invite and wait, which is a poor
 * substitute for a dot next to somebody's name.
 *
 * This is the one thing a realtime database does better than anything else you would reach for.
 * `onDisconnect` is registered with the server *before* it is needed, so the going-offline write
 * happens even if the phone is switched off, loses signal or the process is killed — none of which
 * a client can report on its own, and all of which are how people actually stop playing.
 *
 * Foreground only, deliberately. Presence here means "looking at the app and able to take a turn",
 * not "has it installed and the phone is on" — the second is true almost always and worth nothing.
 */
object Presence {

    private val db = FirebaseDatabase.getInstance()

    private fun ref(profileId: String) = db.getReference("presence").child(profileId)

    /**
     * Screens currently in front of somebody.
     *
     * Counted rather than set, because moving between screens stops one activity after starting
     * the next: a plain flag would blink offline every time somebody opened the scorecard.
     */
    private var visibleScreens = 0

    private var connectionListener: ValueEventListener? = null

    fun enter(context: android.content.Context) {
        visibleScreens++
        if (visibleScreens > 1) return

        val id = PlayerProfile.getId(context)
        if (id.isEmpty()) return
        val name = PlayerProfile.getName(context)

        // Rewritten every time the connection comes back, not once at startup. A disconnect
        // discards the onDisconnect registration along with the connection, so a phone that
        // reconnects after a dead spot would otherwise be online for ever once it finally quit.
        val connected = db.getReference(".info/connected")
        connectionListener?.let { connected.removeEventListener(it) }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) != true) return
                ref(id).onDisconnect().setValue(
                    mapOf("name" to name, "online" to false, "at" to ServerValue.TIMESTAMP)
                )
                ref(id).setValue(
                    mapOf("name" to name, "online" to true, "at" to ServerValue.TIMESTAMP)
                )
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        connectionListener = listener
        connected.addValueEventListener(listener)
    }

    fun leave(context: android.content.Context) {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
        if (visibleScreens > 0) return

        val id = PlayerProfile.getId(context)
        if (id.isEmpty()) return
        connectionListener?.let { db.getReference(".info/connected").removeEventListener(it) }
        connectionListener = null
        ref(id).setValue(
            mapOf(
                "name" to PlayerProfile.getName(context),
                "online" to false,
                "at" to ServerValue.TIMESTAMP
            )
        )
    }

    /** Everybody currently in the app, by profile id. */
    fun watch(onOnline: (Set<String>) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onOnline(
                    snapshot.children
                        .filter { it.child("online").getValue(Boolean::class.java) == true }
                        .mapNotNull { it.key }
                        .toSet()
                )
            }

            override fun onCancelled(error: DatabaseError) = onOnline(emptySet())
        }
        db.getReference("presence").addValueEventListener(listener)
        return listener
    }

    fun stopWatching(listener: ValueEventListener) {
        db.getReference("presence").removeEventListener(listener)
    }
}
