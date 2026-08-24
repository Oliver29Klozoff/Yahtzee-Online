package com.yahtzee.online.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.yahtzee.online.R
import com.yahtzee.online.game.PlayerProfile

/**
 * Full-screen splash shown for [SPLASH_DURATION_MS] before the main menu. The platform's own
 * splash-screen API can't do this — it renders a fixed-size icon masked to a circle and
 * dismisses as soon as the first frame is ready, which flashed by too fast to see. This
 * activity holds the app artwork full-screen for a fixed beat instead.
 */
class SplashActivity : ImmersiveActivity() {

    /** Full-bleed artwork: the camera sits over it rather than the layout being held clear. */
    override val padsForDisplayCutout: Boolean get() = false

    companion object {
        private const val SPLASH_DURATION_MS = 2000L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Read from the package rather than hardcoded, so it cannot drift from the build the
        // player is actually running.
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        findViewById<TextView>(R.id.splashVersion).text =
            version?.let { getString(R.string.version_label, it) }.orEmpty()

        // yahtzee://join/ABCD — carried through to the menu, which does the joining once a name
        // is known. Read here rather than acted on here: a player following an invite on a fresh
        // install still has to be asked who they are first.
        val invitedRoom = intent?.data
            ?.takeIf { it.scheme == "yahtzee" && it.host == "join" }
            ?.lastPathSegment
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }

        handler.postDelayed({
            // First launch goes to the name page; afterwards the saved name is used and the
            // start screen opens directly.
            val next = if (PlayerProfile.hasName(this)) {
                MainActivity::class.java
            } else {
                NameActivity::class.java
            }
            startActivity(
                Intent(this, next).apply {
                    if (invitedRoom != null) putExtra(MainActivity.EXTRA_JOIN_ROOM, invitedRoom)
                }
            )
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
