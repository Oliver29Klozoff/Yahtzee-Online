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

        handler.postDelayed({
            // First launch goes to the name page; afterwards the saved name is used and the
            // start screen opens directly.
            val next = if (PlayerProfile.hasName(this)) {
                MainActivity::class.java
            } else {
                NameActivity::class.java
            }
            startActivity(Intent(this, next))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
