package com.yahtzee.online.net

import android.util.Log
import com.google.firebase.auth.FirebaseAuth

/**
 * Gets the app an anonymous Firebase session, so the database can stop accepting writes from
 * anyone at all.
 *
 * Until now every rule in the database read `".write": true`. That is not a subtle hole: with the
 * project id — which ships inside the APK and cannot be hidden — one `curl` could rewrite the
 * leaderboard, empty a game in progress, or delete the lot, without ever running the app. Signing
 * in anonymously gives every install a real auth token, which lets the rules demand
 * `auth != null` and turns that one-line attack back into "reverse-engineer the app first".
 *
 * ## Anonymous, and why the profile id is still not the uid
 *
 * The obvious next step — key everything by `auth.uid` and have the rules enforce
 * `auth.uid === $profileId` — is deliberately not taken. A uid is minted by Firebase per install
 * and cannot be moved, and this app's recovery code exists precisely so a player can carry their
 * identity to a new phone. Binding rows to the uid would mean a restored profile arrives at its
 * own leaderboard row and its own seat in a game and is refused. Recovery is a feature players
 * use; per-row write protection guards against a fellow player who has decompiled the APK. The
 * former is worth more here, so identity stays the local profile id and auth is the gate on the
 * door rather than a lock on every drawer.
 *
 * ## Timing
 *
 * A session is written to disk on first sign-in and restored by [FirebaseAuth] on every launch
 * after, so [start] is a no-op almost always. Only the very first launch on a device actually
 * makes a network call, and the splash screen holds for two seconds — far longer than that call
 * takes — before anything can be written. [awaitReady] exists for the paths that do not pass
 * through the splash, such as the background turn check.
 */
object FirebaseSignIn {

    private const val TAG = "FirebaseSignIn"

    /** How long a caller will wait for a first-ever sign-in before giving up and trying anyway. */
    private const val WAIT_TIMEOUT_MS = 8_000L

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** Attempts before giving up until the next launch. */
    private const val MAX_ATTEMPTS = 5

    /** First retry delay; doubles each time. */
    private const val RETRY_BASE_MS = 400L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** True once this process has a session. Reads straight off the SDK, which caches to disk. */
    val isReady: Boolean get() = auth.currentUser != null

    private var attempting = false

    /**
     * Kicks off anonymous sign-in if there is not already a session. Safe to call repeatedly and
     * from anywhere.
     *
     * Retries, because the one attempt this made originally was fired from `Application.onCreate`
     * — the very first thing that happens in the process, while the app is still starting and the
     * network stack may not be ready to carry a request yet. It failed there every single cold
     * start on a device with a perfectly good connection, and since the SDK never retries a failed
     * `signInAnonymously` by itself, that left the whole process with no session at all. Backing
     * off and asking again costs nothing when the first attempt works, which is the usual case.
     */
    fun start() {
        if (isReady || attempting) return
        attempting = true
        attempt(1)
    }

    private fun attempt(number: Int) {
        auth.signInAnonymously()
            .addOnSuccessListener {
                attempting = false
                Log.i(TAG, "Signed in anonymously on attempt $number")
            }
            .addOnFailureListener { error ->
                if (number < MAX_ATTEMPTS) {
                    val delay = RETRY_BASE_MS shl (number - 1)
                    Log.w(TAG, "Sign-in attempt $number failed, retrying in ${delay}ms: ${error.message}")
                    handler.postDelayed({ attempt(number + 1) }, delay)
                    return@addOnFailureListener
                }
                attempting = false
                // Worth shouting about: with the tightened rules, no session means no database.
                // If it has failed this many times it is not a startup race — the likeliest
                // remaining cause is Anonymous sign-in being switched off for the project, which
                // is a console setting and not something the app can fix.
                // Not fatal, and not final: every screen that comes to the front tries again, so a
                // phone that was asleep or out of signal at launch repairs itself on being picked
                // up. What this line means is that if the database starts refusing this device,
                // here is the reason.
                Log.e(TAG, "Anonymous sign-in failed after $number attempts; writes will be refused", error)
            }
    }

    /**
     * Runs [block] once there is a session — immediately if there already is one.
     *
     * [block] is run even if sign-in never succeeds, after [WAIT_TIMEOUT_MS]. Refusing to run it
     * would turn a Firebase outage into an app that silently does nothing at all; letting the
     * write go and be rejected at least fails somewhere the existing error paths can see it.
     */
    fun awaitReady(block: () -> Unit) {
        if (isReady) {
            block()
            return
        }

        var done = false
        val listener = object : FirebaseAuth.AuthStateListener {
            override fun onAuthStateChanged(firebaseAuth: FirebaseAuth) {
                if (firebaseAuth.currentUser == null || done) return
                done = true
                firebaseAuth.removeAuthStateListener(this)
                block()
            }
        }
        auth.addAuthStateListener(listener)
        start()

        handler.postDelayed({
            if (done) return@postDelayed
            done = true
            auth.removeAuthStateListener(listener)
            Log.w(TAG, "Proceeding without a session after ${WAIT_TIMEOUT_MS}ms")
            block()
        }, WAIT_TIMEOUT_MS)
    }
}
