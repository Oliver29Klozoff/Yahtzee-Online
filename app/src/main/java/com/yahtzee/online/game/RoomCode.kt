package com.yahtzee.online.game

import kotlin.random.Random

/**
 * What a room code may be.
 *
 * Codes are read aloud across a table and typed in by somebody who has not seen them written
 * down, which is what shapes all of this. A generated code avoids the characters that get
 * misheard or mistyped — no I against 1, no O against 0 — because nobody chose it and nobody can
 * be expected to remember which of the two it was.
 *
 * A code somebody picks for themselves is held to a looser standard on purpose. They typed it,
 * they are the one saying it out loud, and refusing COOL for containing an O would be pedantry
 * about a problem they do not have.
 */
object RoomCode {

    /**
     * Eight is not a style choice. Tournament matches record the room they are played in, and the
     * database rules cap that field at eight characters — a longer code would write a room that
     * no bracket could ever point at.
     */
    const val MAX_LENGTH = 8

    /** Short enough to be worth typing, long enough not to be stumbled into by a stranger. */
    const val MIN_LENGTH = 3

    /** The generated alphabet: no I, O, 0 or 1, which are the ones that get read back wrong. */
    private const val GENERATED = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val ALLOWED = Regex("^[A-Z0-9]+$")

    /** How a typed code is read: case and stray spaces are not part of what somebody meant. */
    fun normalise(raw: String): String =
        raw.filterNot { it.isWhitespace() }.uppercase()

    fun isValid(code: String): Boolean =
        code.length in MIN_LENGTH..MAX_LENGTH && ALLOWED.matches(code)

    /** A fresh code, five characters as they have always been. */
    fun random(random: Random = Random.Default): String =
        (1..GENERATED_LENGTH).map { GENERATED[random.nextInt(GENERATED.length)] }.joinToString("")

    private const val GENERATED_LENGTH = 5
}
