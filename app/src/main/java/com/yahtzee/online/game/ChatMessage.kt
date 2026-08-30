package com.yahtzee.online.game

/** One line said in a room. */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val at: Long
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
