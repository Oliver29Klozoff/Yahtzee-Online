package com.yahtzee.online.ui.game

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.Chat
import com.yahtzee.online.game.ChatMessage
import com.yahtzee.online.ui.ColorContrast

/**
 * Talking to the other people in the room.
 *
 * A sheet rather than a panel on the game screen, which has no room to spare: the scorecard is
 * already the element every other thing on that screen squeezes, and a permanent chat pane would
 * be one more. Opened when you want it, out of the way when you do not.
 *
 * Reactions were built first and deliberately were not this — a fixed set of taps needs no
 * keyboard and cannot say anything worth reporting. Chat can, and in a room anyone with a
 * five-character code can walk into. Worth knowing about; it is a friends-and-family game and the
 * request was explicit, so it is here, but that is the trade being made.
 */
class ChatSheet(private val activity: Activity) {

    private var dialog: BottomSheetDialog? = null
    private var listView: LinearLayout? = null
    private var emptyView: TextView? = null
    private var scroll: ScrollView? = null

    /** The newest message this sheet has drawn, so an update only redraws when there is news. */
    private var lastDrawnAt = Long.MIN_VALUE
    private var lastDrawnCount = -1

    /** Kept so a redraw can rewire the long-press without the caller passing it again. */
    private var onDelete: ((ChatMessage) -> Unit)? = null

    fun show(
        messages: List<ChatMessage>,
        localPlayerId: String,
        onSend: (String) -> Unit,
        onDelete: (ChatMessage) -> Unit
    ) {
        this.onDelete = onDelete
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(R.layout.dialog_chat)

        val input = sheet.findViewById<EditText>(R.id.chatInput)
        val send = sheet.findViewById<Button>(R.id.chatSend)
        listView = sheet.findViewById(R.id.chatList)
        emptyView = sheet.findViewById(R.id.chatEmpty)
        scroll = sheet.findViewById(R.id.chatScroll)

        // Coloured here rather than left to the layout.
        //
        // A bottom sheet is not styled by the activity's theme, and the accent walk that repaints
        // tagged views never reaches inside a dialog — so the send button took the sheet theme's
        // own default text colour, which is the same blue the button is tinted with. It rendered
        // as a blank blue rectangle: the label was there, correct, and completely invisible.
        val accent = AccentColor.resolve(activity)
        send?.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        send?.setTextColor(ColorContrast.textOn(accent))

        val submit = {
            val text = input?.text?.toString().orEmpty()
            if (Chat.clean(text) != null) {
                onSend(text)
                input?.setText("")
            }
        }
        send?.setOnClickListener { submit() }
        // The keyboard's own send key, because reaching for a button after typing is a step
        // nobody takes twice.
        input?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }

        sheet.setOnDismissListener {
            dialog = null
            listView = null
            emptyView = null
            scroll = null
        }

        dialog = sheet
        lastDrawnAt = Long.MIN_VALUE
        render(messages, localPlayerId)
        sheet.show()
    }

    val isShowing: Boolean get() = dialog?.isShowing == true

    /**
     * Redraws the history if anything has arrived since last time.
     *
     * Guarded on the newest timestamp because the room updates on every roll and every held die,
     * and rebuilding the list on each of those would fight whatever the reader is doing — losing
     * their scroll position several times a turn.
     */
    fun update(messages: List<ChatMessage>, localPlayerId: String) {
        if (!isShowing) return
        val newest = messages.maxOfOrNull { it.at } ?: Long.MIN_VALUE
        // The count matters as much as the timestamp. Deleting anything other than the most
        // recent message leaves the newest exactly where it was, so a guard on the timestamp
        // alone would decide there was nothing to redraw and leave the deleted line on screen.
        if (newest == lastDrawnAt && messages.size == lastDrawnCount) return
        render(messages, localPlayerId)
    }

    private fun confirmDelete(message: ChatMessage) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_delete_title)
            .setMessage(activity.getString(R.string.chat_delete_message, message.text))
            .setPositiveButton(R.string.delete) { _, _ -> onDelete?.invoke(message) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun render(messages: List<ChatMessage>, localPlayerId: String) {
        val list = listView ?: return
        lastDrawnAt = messages.maxOfOrNull { it.at } ?: Long.MIN_VALUE
        lastDrawnCount = messages.size

        emptyView?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()

        val density = activity.resources.displayMetrics.density
        val accent = AccentColor.resolve(activity)

        messages.forEach { message ->
            val mine = message.senderId == localPlayerId
            val block = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (mine) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
            }

            block.addView(
                AppCompatTextView(activity).apply {
                    text = message.senderName
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    // Your own name in the accent, so a glance down the column tells you who is
                    // who without reading a single name.
                    setTextColor(if (mine) accent else activity.getColor(R.color.text_muted))
                }
            )
            block.addView(
                AppCompatTextView(activity).apply {
                    text = message.text
                    textSize = 15f
                    setTextColor(activity.getColor(R.color.text_dark))
                    gravity = if (mine) Gravity.END else Gravity.START
                }
            )

            // Hold your own message to take it back. Only your own: deleting somebody else's
            // words is a different thing entirely, and not one a dice game needs.
            if (mine) {
                block.isLongClickable = true
                block.setOnLongClickListener {
                    confirmDelete(message)
                    true
                }
            }
            list.addView(block)
        }

        // Land on the newest, which is the one anybody opening this wants to see.
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
