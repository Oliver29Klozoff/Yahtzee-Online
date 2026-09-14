package com.yahtzee.online.game

/**
 * Somebody being asked to get on with their turn.
 *
 * A game played a turn at a time depends entirely on people remembering to take theirs, and
 * "your turn" notifications only fire when the background check happens to run. This is the
 * direct approach: whoever is waiting says so.
 */
data class Nudge(
    val byName: String,
    val toPlayerId: String,
    val at: Long
)

/** One line said in a room. */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val at: Long,
    /**
     * The message this one answers, or empty.
     *
     * Just the id. The quoted line is looked up in the history rather than copied in beside it,
     * so an edited or withdrawn message does not leave a stale copy of itself quoted underneath
     * somebody else's reply. The cost is that a reply outliving what it answered has nothing to
     * show, which the sheet says plainly instead of pretending.
     */
    val replyTo: String = ""
)

object Chat {

    /**
     * Longest line accepted. Enough for a sentence, short enough that nobody writes an essay into
     * a game room, and short enough that the whole history stays small.
     */
    const val MAX_LENGTH = 140

    /**
     * How many lines a room keeps.
     *
     * The room carries its chat, and the room is read whole on every update — every roll, every
     * held die, every score. An unbounded history would mean a game two hundred messages in
     * re-downloading all two hundred on each of those, so the oldest are dropped once the list
     * passes this.
     */
    const val MAX_MESSAGES = 60

    /**
     * Trims and sanity-checks a line, or returns null if there is nothing worth sending.
     *
     * Newlines are folded to spaces rather than rejected: a message pasted from elsewhere should
     * still go, and a multi-line message in a single-line row renders as a mess.
     */
    fun clean(raw: String): String? {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        return collapsed.take(MAX_LENGTH)
    }
}
