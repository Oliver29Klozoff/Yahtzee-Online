package com.yahtzee.online.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor

/**
 * Base for every screen in the app: hides the system status bar (where notification icons/
 * heads-up banners live) and nav bar while the app is in the foreground, re-applying it if the
 * system ever reveals them again (e.g. after a user edge-swipe, or returning from another app).
 * Notifications themselves still arrive normally in the background — this only keeps them out
 * of view while this activity is on screen, not real Do-Not-Disturb suppression.
 */
abstract class ImmersiveActivity : AppCompatActivity() {

    /**
     * Applies the chosen accent before anything is inflated. It has to happen here rather than in
     * each screen: a theme set after the content view is built reaches nothing already drawn, and
     * every screen in the app passes through this class on its way up.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AccentColor.current(this).theme)
        super.onCreate(savedInstanceState)
    }

    /**
     * Wires up the standard back arrow for any layout that includes a view with id
     * `backButton`, so screens get it just by putting the button in their XML. Done here
     * rather than per-activity because the system back affordance is hidden by immersive
     * mode, making an on-screen arrow the primary way back on every screen that has one.
     */
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        findViewById<View?>(R.id.backButton)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

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
