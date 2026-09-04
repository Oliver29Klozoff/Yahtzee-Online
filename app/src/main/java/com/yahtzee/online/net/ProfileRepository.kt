package com.yahtzee.online.net

import com.google.firebase.database.FirebaseDatabase
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.ProfileSync

/**
 * Keeps a player's own history with their identity rather than with their phone.
 *
 * Stored under the profile id, which is exactly what a recovery code carries, so restoring a code
 * on a new phone can fetch everything that used to be left behind.
 *
 * Deliberately last-writer-wins, with one guard. Two phones on the same identity is a thing the
 * app already warns about and cannot really support; the guard is not for that, it is for the
 * ordinary case of a phone that has been offline. A pull that would replace more games with fewer
 * is refused, because the only way that happens is a stale snapshot landing on a phone that has
 * been played on since.
 */
class ProfileRepository(private val context: android.content.Context) {

    private val db = FirebaseDatabase.getInstance()

    private fun ref(profileId: String) = db.getReference("profiles").child(profileId)

    /** Files this device's history against its identity. Safe to call as often as is convenient. */
    fun push(profileId: String = PlayerProfile.getId(context)) {
        if (profileId.isEmpty()) return
        ref(profileId).setValue(
            mapOf(
                "updatedAt" to System.currentTimeMillis(),
                "data" to ProfileSync.snapshot(context)
            )
        )
    }

    /**
     * Fetches the stored history and writes it over this device's, unless doing so would lose
     * games. [onDone] reports whether anything was actually applied.
     */
    fun pull(profileId: String = PlayerProfile.getId(context), onDone: (Boolean) -> Unit) {
        if (profileId.isEmpty()) {
            onDone(false)
            return
        }
        ref(profileId).child("data").get()
            .addOnSuccessListener { snapshot ->
                val stored = snapshot.children.mapNotNull { entry ->
                    val key = entry.key ?: return@mapNotNull null
                    val value = entry.getValue(String::class.java) ?: return@mapNotNull null
                    key to value
                }.toMap()

                if (stored.isEmpty()) {
                    onDone(false)
                    return@addOnSuccessListener
                }
                if (ProfileSync.gamesIn(stored) < ProfileSync.gamesIn(ProfileSync.snapshot(context))) {
                    // What is here is further along than what was stored. Keep it, and put it back
                    // so the next phone gets the better of the two.
                    push(profileId)
                    onDone(false)
                    return@addOnSuccessListener
                }
                ProfileSync.apply(context, stored)
                onDone(true)
            }
            .addOnFailureListener { onDone(false) }
    }
}
