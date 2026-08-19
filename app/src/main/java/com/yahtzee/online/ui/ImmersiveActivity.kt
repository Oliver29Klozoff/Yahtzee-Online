package com.yahtzee.online.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen in the app: hides the system status bar (where notification icons/
 * heads-up banners live) and nav bar while the app is in the foreground, re-applying it if the
 * system ever reveals them again (e.g. after a user edge-swipe, or returning from another app).
 * Notifications themselves still arrive normally in the background — this only keeps them out
 * of view while this activity is on screen, not real Do-Not-Disturb suppression.
 */
abstract class ImmersiveActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }
}
