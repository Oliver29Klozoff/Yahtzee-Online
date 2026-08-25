package com.yahtzee.online

import android.app.Application
import com.yahtzee.online.net.FirebaseSignIn

/**
 * Exists for one reason: to get anonymous sign-in started before anything touches the database.
 *
 * Doing it here rather than in the splash screen covers the entry points that never show a
 * screen — the periodic turn check runs in this same process with no activity in sight, and it
 * writes.
 */
class YahtzeeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseSignIn.start()
    }
}
