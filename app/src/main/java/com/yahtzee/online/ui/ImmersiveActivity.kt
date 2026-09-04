package com.yahtzee.online.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.net.FirebaseSignIn
import com.yahtzee.online.net.Presence

/**
 * Base for every screen in the app: hides the system status bar (where notification icons/
 * heads-up banners live) and nav bar while the app is in the foreground, re-applying it if the
 * system ever reveals them again (e.g. after a user edge-swipe, or returning from another app).
 * Notifications themselves still arrive normally in the background — this only keeps them out
 * of view while this activity is on screen, not real Do-Not-Disturb suppression.
 */
abstract class ImmersiveActivity : AppCompatActivity() {

    private companion object {
        /**
         * The full inset is far more than this screen needs. Nothing sits directly under the
         * camera — the title is hard left and the icons hard right, while a punch-hole is
         * centred — so what is actually wanted is clearance from the top edge, not room for the
         * whole cutout. Capping keeps that from eating a chunk of every screen.
         */
        const val MAX_TOP_PADDING_DP = 22f

        /** So a phone reporting no inset at all still does not start flush against the glass. */
        const val MIN_TOP_PADDING_DP = 10f
    }

    /** The accent this screen was actually built with, to notice later that it has changed. */
    private var builtWithAccent: Int? = null

    /**
     * Applies the chosen accent before anything is inflated. It has to happen here rather than in
     * each screen: a theme set after the content view is built reaches nothing already drawn, and
     * every screen in the app passes through this class on its way up.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        val accent = AccentColor.getColor(this)
        builtWithAccent = accent
        setTheme(AccentColor.themeFor(accent))
        super.onCreate(savedInstanceState)
    }

    /**
     * Whether this screen keeps clear of the camera. True everywhere except the splash, whose
     * whole point is artwork running edge to edge — padding it would leave a black band across
     * the top and shift the image off centre.
     */
    protected open val padsForDisplayCutout: Boolean get() = true

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
        applyTopInset()
        // The theme carried the nearest preset; this puts the exact colour on everything that
        // asked for the accent.
        AccentColor.retint(
            findViewById(android.R.id.content),
            AccentColor.themeColorOf(this),
            AccentColor.getColor(this)
        )
    }

    /**
     * Holds the content below the camera.
     *
     * Hiding the system bars puts the top of the layout at the very top of the glass, which on a
     * phone with a punch-hole or a notch means the title and the icons beside it sit level with
     * the camera. The inset is read rather than guessed at, because the height differs by device
     * and a fixed value would be wrong on most of them.
     *
     * The bars' *live* inset is no use here — they are hidden, so it reads zero. What is wanted
     * is the room they would occupy, which is also a reasonable margin on a phone with no cutout
     * at all; the cutout itself reports its inset whether the bars are showing or not, and on the
     * phones where it is taller than the status bar it is the one that wins.
     */
    private fun applyTopInset() {
        if (!padsForDisplayCutout) return
        val content = findViewById<View>(android.R.id.content) ?: return
        val density = resources.displayMetrics.density
        val floor = (MIN_TOP_PADDING_DP * density).toInt()
        val ceiling = (MAX_TOP_PADDING_DP * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            view.updatePadding(top = maxOf(bars, cutout).coerceIn(floor, ceiling))
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    /**
     * Presence is published from the base class so it covers every screen without any of them
     * having to remember. Counted across activities, so moving from the menu into a game — which
     * starts the next screen before stopping this one — does not blink offline and back.
     */
    override fun onStart() {
        super.onStart()
        Presence.enter(this)
    }

    override fun onStop() {
        super.onStop()
        Presence.leave(this)
    }

    override fun onResume() {
        super.onResume()

        // Another go at signing in, on every screen the player lands on.
        //
        // The attempt made at process start can legitimately fail: launch the app while the phone
        // is dozing and it has no working DNS, so the sign-in fails and — since nothing else ever
        // asked again — the process would spend the rest of its life with no session and a
        // database that refuses it. Retrying whenever a screen comes to the front costs nothing
        // (it returns immediately once there is a session) and means the app repairs itself the
        // moment somebody is actually looking at it.
        FirebaseSignIn.start()

        // Screens behind the one that changed the accent are already built, and coming back to
        // them only resumes them — nothing re-inflates, so they keep wearing the old colour until
        // the app is killed. Rebuilding here is what makes the change reach the whole app rather
        // than only the screen it was made on. This settles after one pass: the new instance
        // records the accent it was built with, so it matches and comes straight through.
        if (builtWithAccent != AccentColor.getColor(this)) {
            recreate()
            return
        }
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
