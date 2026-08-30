package com.yahtzee.online.ui.game

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView

/**
 * The pop an emoji makes when it lands on screen.
 *
 * Shared by reactions and by the off-the-rip shout, which were animating themselves separately
 * with two copies of the same code that had already started to drift apart.
 *
 * It is deliberately not a single interpolator on a scale. A canned overshoot reads as a box
 * growing; what makes an emoji feel alive is squash and stretch — overshooting on one axis while
 * the other is still catching up — plus a small rotation that settles. The keyframes below do
 * that in one pass, and cost nothing because it is three properties on one view.
 *
 * Phases: a fast anticipating pop, a hold that drifts upward so it never looks frozen, and a
 * fade that shrinks slightly as it goes.
 */
object EmojiPop {

    private const val POP_MILLIS = 420L
    private const val FADE_MILLIS = 260L

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Shows [content] in [popup], holding it for [holdMillis] before it fades.
     *
     * Any pop already running on this view is cancelled rather than queued: two reactions in
     * quick succession should replace each other, not form an orderly line.
     */
    fun show(popup: TextView, content: CharSequence, holdMillis: Long) {
        cancel(popup)

        popup.text = content
        popup.visibility = View.VISIBLE
        popup.alpha = 1f
        popup.translationY = 0f

        val scaleX = PropertyValuesHolder.ofKeyframe(
            View.SCALE_X,
            Keyframe.ofFloat(0f, 0.25f),
            // Wider than tall on the way out, the way anything elastic behaves when it lands.
            Keyframe.ofFloat(0.45f, 1.22f),
            Keyframe.ofFloat(0.72f, 0.94f),
            Keyframe.ofFloat(1f, 1f)
        )
        val scaleY = PropertyValuesHolder.ofKeyframe(
            View.SCALE_Y,
            Keyframe.ofFloat(0f, 0.25f),
            // Trails the X axis, which is what reads as squash rather than as a uniform zoom.
            Keyframe.ofFloat(0.45f, 1.06f),
            Keyframe.ofFloat(0.72f, 1.08f),
            Keyframe.ofFloat(1f, 1f)
        )
        val wobble = PropertyValuesHolder.ofKeyframe(
            View.ROTATION,
            Keyframe.ofFloat(0f, -12f),
            Keyframe.ofFloat(0.45f, 7f),
            Keyframe.ofFloat(0.75f, -3f),
            Keyframe.ofFloat(1f, 0f)
        )

        val pop = ObjectAnimator.ofPropertyValuesHolder(popup, scaleX, scaleY, wobble).apply {
            duration = POP_MILLIS
            interpolator = DecelerateInterpolator(1.4f)
        }

        // Rises through the hold so it reads as floating away rather than sitting there.
        val drift = ObjectAnimator.ofFloat(
            popup,
            View.TRANSLATION_Y,
            0f,
            -popup.resources.displayMetrics.density * DRIFT_DP
        ).apply {
            duration = holdMillis
            interpolator = LinearInterpolator()
        }

        val set = AnimatorSet()
        set.play(pop).with(drift)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (popup.getTag(TAG_KEY) !== set) return
                fadeOut(popup)
            }
        })
        popup.setTag(TAG_KEY, set)
        set.start()
    }

    private fun fadeOut(popup: TextView) {
        val fade = AnimatorSet()
        fade.playTogether(
            ObjectAnimator.ofFloat(popup, View.ALPHA, 1f, 0f),
            ObjectAnimator.ofFloat(popup, View.SCALE_X, popup.scaleX, 0.8f),
            ObjectAnimator.ofFloat(popup, View.SCALE_Y, popup.scaleY, 0.8f)
        )
        fade.duration = FADE_MILLIS
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (popup.getTag(TAG_KEY) !== fade) return
                reset(popup)
            }
        })
        popup.setTag(TAG_KEY, fade)
        fade.start()
    }

    private fun cancel(popup: TextView) {
        (popup.getTag(TAG_KEY) as? AnimatorSet)?.cancel()
        (popup.getTag(TAG_KEY) as? ObjectAnimator)?.cancel()
        popup.setTag(TAG_KEY, null)
        handler.removeCallbacksAndMessages(popup)
    }

    private fun reset(popup: TextView) {
        popup.visibility = View.GONE
        popup.alpha = 1f
        popup.scaleX = 1f
        popup.scaleY = 1f
        popup.rotation = 0f
        popup.translationY = 0f
        popup.setTag(TAG_KEY, null)
    }

    /** How far it climbs while it is up, in dp. */
    private const val DRIFT_DP = 26f

    /** Somewhere to keep the running animation without a field per popup. */
    private val TAG_KEY = com.yahtzee.online.R.id.emojiPopTag
}
