package com.yahtzee.online.ui.tournament

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.Match
import com.yahtzee.online.game.Tournament
import com.yahtzee.online.game.TournamentState

/**
 * The draw, drawn.
 *
 * A bracket listed round by round is readable but it is not a bracket — the whole point of the
 * shape is that you can see your own name and follow the line forward to who you would meet, and
 * a list cannot show that. So this is the real thing: a column per round, each fixture a box, and
 * a line from every pair to the seat above it that the winner takes.
 *
 * Drawn rather than laid out in views. Sixteen entrants is thirty-one boxes and thirty connectors,
 * and the interesting part is the connectors, which are not a thing a LinearLayout has any way of
 * expressing. One canvas is also one measure pass, which matters on a screen that redraws every
 * time anybody anywhere reports a result.
 *
 * Geometry is the standard bracket: the first round is evenly spaced, and every round after it
 * sits at twice the pitch, centred on the pair it comes from. That is what makes the connectors
 * straight and the whole thing legible without labels.
 */
class BracketView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Tapped a fixture that is yours and playable. */
    var onPlay: ((Match) -> Unit)? = null

    private var state: TournamentState? = null
    private var localPlayerId: String = ""

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val boxWidth = dp(150f)
    private val rowHeight = dp(26f)
    private val boxHeight = rowHeight * 2
    private val columnGap = dp(34f)

    /** Vertical pitch of the first round. Everything else is derived from it. */
    private val pitch = boxHeight + dp(20f)

    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val connector = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(0.75f) }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f) }
    private val scorePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        textAlign = Paint.Align.RIGHT
    }
    private val roundPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val playPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val box = RectF()

    /** Where each fixture ended up, so a tap can be matched back to one. */
    private val hitBoxes = mutableListOf<Pair<Match, RectF>>()

    private val headerHeight = dp(22f)

    fun setBracket(state: TournamentState, localPlayerId: String) {
        this.state = state
        this.localPlayerId = localPlayerId
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rounds = state?.rounds ?: 0
        val firstRound = state?.matchesIn(0)?.size ?: 0
        val width = if (rounds == 0) 0f else rounds * boxWidth + (rounds - 1) * columnGap
        // The PLAY label hangs below its box, so the last row needs room for it or it is cut in
        // half by the bottom of the view — which is exactly where it lands in a two-player draw.
        val height = if (firstRound == 0) 0f else headerHeight + firstRound * pitch + dp(14f)
        setMeasuredDimension(
            resolveSize(width.toInt(), widthMeasureSpec),
            resolveSize(height.toInt(), heightMeasureSpec)
        )
    }

    /** The vertical centre of a fixture: first round evenly, everything above it at twice the pitch. */
    private fun centreOf(round: Int, slot: Int): Float {
        val spread = pitch * (1 shl round)
        return headerHeight + spread * (slot + 0.5f)
    }

    private fun leftOf(round: Int): Float = round * (boxWidth + columnGap)

    override fun onDraw(canvas: Canvas) {
        val state = state ?: return
        hitBoxes.clear()

        val accent = AccentColor.resolve(context)
        val dark = resources.getColor(R.color.text_dark, context.theme)
        val muted = resources.getColor(R.color.text_muted, context.theme)
        val rowBorder = resources.getColor(R.color.row_border, context.theme)

        connector.color = rowBorder
        divider.color = rowBorder

        val rounds = state.rounds
        val mine = state.nextMatchFor(localPlayerId)

        // Connectors first, so the boxes sit on top of them and the lines appear to stop at the edge.
        for (round in 0 until rounds - 1) {
            state.matchesIn(round).forEach { match ->
                val fromX = leftOf(round) + boxWidth
                val fromY = centreOf(round, match.slot)
                val toX = leftOf(round + 1)
                val toY = centreOf(round + 1, match.slot / 2)
                val midX = (fromX + toX) / 2
                canvas.drawLine(fromX, fromY, midX, fromY, connector)
                canvas.drawLine(midX, fromY, midX, toY, connector)
                canvas.drawLine(midX, toY, toX, toY, connector)
            }
        }

        for (round in 0 until rounds) {
            roundPaint.color = muted
            val label = if (rounds - round > 3) {
                context.getString(R.string.tourney_round_n, round + 1)
            } else {
                context.getString(Tournament.roundName(round, rounds))
            }
            canvas.drawText(label, leftOf(round), headerHeight - dp(8f), roundPaint)

            state.matchesIn(round).forEach { match ->
                drawMatch(canvas, state, match, round, accent, dark, muted, mine?.id == match.id)
            }
        }
    }

    private fun drawMatch(
        canvas: Canvas,
        state: TournamentState,
        match: Match,
        round: Int,
        accent: Int,
        dark: Int,
        muted: Int,
        isMine: Boolean
    ) {
        val left = leftOf(round)
        val centre = centreOf(round, match.slot)
        box.set(left, centre - boxHeight / 2, left + boxWidth, centre + boxHeight / 2)
        hitBoxes += match to RectF(box)

        boxFill.color = resources.getColor(R.color.background, context.theme)
        canvas.drawRoundRect(box, dp(6f), dp(6f), boxFill)
        boxStroke.color = if (isMine) accent else resources.getColor(R.color.row_border, context.theme)
        canvas.drawRoundRect(box, dp(6f), dp(6f), boxStroke)
        canvas.drawLine(box.left, centre, box.right, centre, divider)

        val playable = isMine && match.ready && !match.decided
        drawSeat(canvas, state, match.aId, match.aScore, box.top, match, accent, dark, muted, playable)
        drawSeat(canvas, state, match.bId, match.bScore, centre, match, accent, dark, muted, playable)

        // The one thing to do next, on the row where the eye found your name.
        if (playable) {
            playPaint.color = accent
            canvas.drawText(
                context.getString(R.string.tourney_play),
                box.centerX(),
                box.bottom + dp(12f),
                playPaint
            )
        }
    }

    private fun drawSeat(
        canvas: Canvas,
        state: TournamentState,
        id: String,
        score: Int,
        top: Float,
        match: Match,
        accent: Int,
        dark: Int,
        muted: Int,
        playable: Boolean
    ) {
        val won = match.decided && match.winnerId == id
        // An empty seat in the first round is a bye; anywhere else it is a fixture still waiting
        // on the round below, and saying "bye" there would be a lie.
        val name = when {
            id.isNotEmpty() -> state.players[id]?.name.orEmpty()
            match.round == 0 -> context.getString(R.string.tourney_bye_seat)
            else -> context.getString(R.string.tourney_tbd)
        }

        namePaint.color = when {
            id.isEmpty() -> muted
            id == localPlayerId -> accent
            won -> dark
            match.decided -> muted
            else -> dark
        }
        namePaint.typeface = if (won) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val padding = dp(9f)
        // Only a fixture somebody actually played has a score. A bye is decided the moment the
        // draw is made, so its stored score is a zero that was never rolled — printing it reads as
        // a whitewash rather than as a free pass.
        val played = match.decided && match.aId.isNotEmpty() && match.bId.isNotEmpty()
        val scoreText = if (played && id.isNotEmpty()) score.toString() else ""
        val scoreWidth = if (scoreText.isEmpty()) 0f else scorePaint.measureText(scoreText) + dp(6f)
        val room = boxWidth - padding * 2 - scoreWidth
        val shown = TextUtils.ellipsize(name, namePaint, room, TextUtils.TruncateAt.END)

        // Centred in the seat rather than sitting on its baseline, so the two rows look even.
        val baseline = top + rowHeight / 2 - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(shown, 0, shown.length, box.left + padding, baseline, namePaint)

        if (scoreText.isNotEmpty()) {
            scorePaint.color = if (won) dark else muted
            scorePaint.typeface = if (won) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(scoreText, box.right - padding, baseline, scorePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val match = hitBoxes.firstOrNull { it.second.contains(event.x, event.y) }?.first
            ?: return true
        val mine = state?.nextMatchFor(localPlayerId)
        if (match.id == mine?.id && match.ready && !match.decided) {
            performClick()
            onPlay?.invoke(match)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
