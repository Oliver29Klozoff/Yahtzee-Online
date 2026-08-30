package com.yahtzee.online.ui.game

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import com.yahtzee.online.R
import kotlin.random.Random

/**
 * Emoji thrown up over the table, several at once.
 *
 * A single popup slot could only ever show the last thing anybody sent: tap four times and three
 * of them were silently thrown away, which made the row feel unresponsive exactly when someone was
 * being most enthusiastic with it. Every tap now puts its own emoji on screen, and they coexist —
 * a flurry from two people reads as a flurry rather than as one emoji flickering.
 *
 * Each is its own short-lived view. They are cheap, they remove themselves when their animation
 * ends, and the layer is capped so a stuck finger cannot fill the screen.
 */
object EmojiBurst {

    /** Beyond this the oldest are retired early; a screen of emoji is a screen of nothing. */
    private const val MAX_ON_SCREEN = 18

    private const val RISE_MILLIS = 2200L
    private const val POP_MILLIS = 380L

    /** Emoji size against the name caption under it. */
    private const val EMOJI_SCALE = 3.4f

    /**
     * Puts one emoji on the layer and sets it going.
     *
     * [name] is captioned underneath because in a four-player room the interesting part of a
     * reaction is often who sent it. Left blank it is simply omitted.
     */
    fun spawn(layer: FrameLayout, emoji: String, name: String) {
        if (layer.width == 0 || layer.height == 0) {
            // Not laid out yet — try again once it is, rather than spawning into nothing.
            layer.post { if (layer.width > 0) spawn(layer, emoji, name) }
            return
        }

        while (layer.childCount >= MAX_ON_SCREEN) {
            layer.getChildAt(0)?.let { oldest ->
                oldest.animate().cancel()
                layer.removeView(oldest)
            } ?: break
        }

        val context = layer.context
        val density = context.resources.displayMetrics.density
        val view = AppCompatTextView(context).apply {
            text = label(emoji, name)
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(context.getColor(R.color.text_dark))
            setShadowLayer(8f, 0f, 2f, android.graphics.Color.BLACK)
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layer.addView(view, params)

        // Placed once measured, since where it can go depends on how wide it turned out.
        view.post {
            if (view.parent == null) return@post
            val margin = 12 * density
            val maxX = (layer.width - view.width - margin).coerceAtLeast(margin)
            view.x = Random.nextDouble(margin.toDouble(), maxX.toDouble().coerceAtLeast(margin + 1.0)).toFloat()
            // Starts low over the table and climbs, so the eye follows it upward and away rather
            // than having it appear already in the middle of everything.
            view.y = layer.height * 0.62f
            animate(view, layer, density)
        }
    }

    private fun animate(view: View, layer: FrameLayout, density: Float) {
        val rise = layer.height * Random.nextDouble(0.30, 0.46).toFloat()
        val sway = (Random.nextFloat() - 0.5f) * 90f * density / 3f
        val spin = (Random.nextFloat() - 0.5f) * 26f

        view.alpha = 0f
        view.scaleX = 0.3f
        view.scaleY = 0.3f

        val pop = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofKeyframe(
                View.SCALE_X,
                Keyframe.ofFloat(0f, 0.3f),
                Keyframe.ofFloat(0.5f, 1.18f),
                Keyframe.ofFloat(1f, 1f)
            ),
            PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y,
                Keyframe.ofFloat(0f, 0.3f),
                Keyframe.ofFloat(0.5f, 1.05f),
                Keyframe.ofFloat(1f, 1f)
            ),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
        ).apply {
            duration = POP_MILLIS
            interpolator = DecelerateInterpolator(1.5f)
        }

        // Every one takes a slightly different path and turn, so a flurry looks like a handful of
        // separate things rather than one thing drawn several times.
        val climb = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -rise),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, sway),
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, spin)
        ).apply {
            duration = RISE_MILLIS
            interpolator = LinearInterpolator()
        }

        // Fades over the back half of the climb rather than vanishing at the end of it.
        val fade = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
            startDelay = RISE_MILLIS / 2
            duration = RISE_MILLIS / 2
            interpolator = DecelerateInterpolator()
        }

        val set = AnimatorSet()
        set.play(pop).with(climb)
        set.play(fade).with(climb)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                layer.removeView(view)
            }
        })
        set.start()
    }

    private fun label(emoji: String, name: String): CharSequence {
        if (name.isEmpty()) {
            return SpannableString(emoji).apply {
                setSpan(RelativeSizeSpan(EMOJI_SCALE), 0, emoji.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val text = "$emoji\n$name"
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(EMOJI_SCALE), 0, emoji.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
