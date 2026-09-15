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

    /**
     * The codes rooms are given when nobody picks one.
     *
     * Words rather than characters, because a code is read out across a table far more often than
     * it is typed, and five random letters are read out one at a time — which is where "was that
     * an I or a one" comes from. A word is said once and heard once.
     *
     * That also disposes of the confusable pairs rather than dodging them. The old generator left
     * out I, O, 0 and 1 because they are misheard against each other; nothing here contains a
     * digit at all, so there is nothing for a letter to be mistaken for.
     *
     * Eight characters is the ceiling, so these are short by necessity — which suits them, since
     * the joke has to survive being shouted across a room. Most are about what the dice just did
     * to somebody. Kept wholesome: it is a family game, and a room code is the one string in the
     * app that gets read aloud to whoever is in earshot.
     */
    val NAMES: List<String> = listOf(
        // What the dice did.
        "BOXCARS", "SNAKEYES", "HOTDICE", "COLDDICE", "LUCKYCUP", "RATTLER", "TUMBLER",
        "CLATTER", "SHAKEIT", "BLOWONIT", "DICEY", "WOBBLY", "ROLLERS", "SIXPACK",
        // What it did to the scorecard.
        "ALLSIXES", "ALLONES", "TRIPLES", "CHANCEIT", "ZEROHERO", "SCRATCH", "GOOSEEGG",
        // FULLBOAT rather than a chopped FULLHOUSE: it is what the hand is actually called, and
        // it fits.
        "BIGYAHTZ", "NOYAHTZ", "FULLBOAT", "STRAIGHT", "REROLL",
        // What people say when it goes wrong.
        "NOTAGAIN", "SOCLOSE", "ROBBED", "YIKES", "OOPS", "WELP", "RIGGED", "UNFAIR",
        "WHYME", "SIGH", "GROAN", "FACEPALM", "TILTED", "SALTY", "JINXED", "CURSED",
        // And when it goes right.
        "BLESSED", "LUCKBOX", "HIGHROLL", "SWEEPER", "CRUSHED", "STOMPED", "EASYWIN",
        "TOOEASY", "TOPDOG", "BIGSHOT", "HOTSHOT", "SHOWOFF", "SMUG", "GLOATER", "CLUTCH",
        // Table talk.
        "REMATCH", "ONEMORE", "LASTONE", "BEDTIME", "SNACKS", "CRUMBS", "ELBOWS", "KITCHEN",
        // Nonsense, for the ones that are funny on their own.
        "GOOSE", "MOOSE", "WALRUS", "PENGUIN", "BADGER", "WOMBAT", "FERRET", "NOODLE",
        "PICKLE", "WAFFLE", "BISCUIT", "CRUMPET", "MUFFIN", "PANCAKE", "GRAVY", "BEANS",
        "TOAST", "CHEESE", "PEANUT", "POTATO", "TURNIP", "PARSNIP", "GHERKIN", "SPROUT",
        "CUSTARD", "TRIFLE", "SCONE", "BRISKET", "PRETZEL", "NACHOS", "QUICHE", "RHUBARB"
    )

    private val ALLOWED = Regex("^[A-Z0-9]+$")

    /** How a typed code is read: case and stray spaces are not part of what somebody meant. */
    fun normalise(raw: String): String =
        raw.filterNot { it.isWhitespace() }.uppercase()

    fun isValid(code: String): Boolean =
        code.length in MIN_LENGTH..MAX_LENGTH && ALLOWED.matches(code)

    /**
     * A fresh code: one of [NAMES].
     *
     * A name can of course collide with a room already using it, which five random characters
     * essentially never did. That is handled where it should be — the repositories check whether
     * a code is free and draw again if it is not — and with a pool this size against the handful
     * of rooms alive at once, drawing again is rare and drawing five times running is not a thing
     * that happens.
     */
    fun random(random: Random = Random.Default): String = NAMES[random.nextInt(NAMES.size)]
}
