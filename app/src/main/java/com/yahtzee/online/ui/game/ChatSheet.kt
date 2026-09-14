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

    private var replyBar: View? = null
    private var replyLabel: TextView? = null

    /**
     * The message being answered, or null.
     *
     * Held on the sheet rather than passed around because it outlives a redraw: the room updates
     * several times a turn, and a reply half typed must not lose what it was aimed at.
     */
    private var replyingTo: ChatMessage? = null

    /** The history as last drawn, so a quoted line can be looked up by id. */
    private var known: List<ChatMessage> = emptyList()

    fun show(
        messages: List<ChatMessage>,
        localPlayerId: String,
        onSend: (String, String) -> Unit,
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

        replyBar = sheet.findViewById(R.id.chatReplyBar)
        replyLabel = sheet.findViewById(R.id.chatReplyLabel)
        sheet.findViewById<Button>(R.id.chatReplyCancel)?.setOnClickListener { stopReplying() }

        val submit = {
            val text = input?.text?.toString().orEmpty()
            if (Chat.clean(text) != null) {
                onSend(text, replyingTo?.id.orEmpty())
                input?.setText("")
                // The reply is spent once sent; the next message is its own unless aimed again.
                stopReplying()
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
            replyBar = null
            replyLabel = null
            replyingTo = null
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

    /**
     * What holding a message offers.
     *
     * A menu rather than a straight action, because there are now two things a long press could
     * mean and only one of them is undoable. Replying is offered on every message; taking one
     * back only on your own.
     */
    private fun showActions(message: ChatMessage, mine: Boolean) {
        val actions = buildList {
            add(activity.getString(R.string.chat_reply) to { startReplying(message) })
            if (mine) add(activity.getString(R.string.chat_delete) to { confirmDelete(message) })
        }

        // One option is not a choice worth showing a menu for.
        if (actions.size == 1) {
            actions.first().second()
            return
        }

        AlertDialog.Builder(activity)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    private fun startReplying(message: ChatMessage) {
        replyingTo = message
        replyBar?.visibility = View.VISIBLE
        replyLabel?.text = activity.getString(
            R.string.chat_replying_to,
            message.senderName,
            message.text
        )
    }

    private fun stopReplying() {
        replyingTo = null
        replyBar?.visibility = View.GONE
    }

    /**
     * The line quoted above a reply, or null if this message answers nothing.
     *
     * A reply can outlive what it answered — the history is pruned, and a message can be taken
     * back — so a missing original is said plainly rather than left as a reply to nothing, which
     * reads as the quote having failed to load.
     */
    private fun quotedFor(message: ChatMessage): String? {
        if (message.replyTo.isEmpty()) return null
        val original = known.firstOrNull { it.id == message.replyTo }
            ?: return activity.getString(R.string.chat_reply_gone)
        return activity.getString(
            R.string.chat_replying_to,
            original.senderName,
            original.text
        )
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
        known = messages

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
            // What this answers, above what it says, in the order the two are read.
            quotedFor(message)?.let { quoted ->
                block.addView(
                    AppCompatTextView(activity).apply {
                        text = quoted
                        textSize = 12f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(activity.getColor(R.color.text_muted))
                        gravity = if (mine) Gravity.END else Gravity.START
                    }
                )
            }

            block.addView(
                AppCompatTextView(activity).apply {
                    text = message.text
                    textSize = 15f
                    setTextColor(activity.getColor(R.color.text_dark))
                    gravity = if (mine) Gravity.END else Gravity.START
                }
            )

            // Hold any message to answer it; your own also offers taking it back. Deleting
            // somebody else's words stays off the menu — a different thing entirely, and not one
            // a dice game needs.
            block.isLongClickable = true
            block.setOnLongClickListener {
                showActions(message, mine)
                true
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
