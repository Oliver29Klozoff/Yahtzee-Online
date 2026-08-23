package com.yahtzee.online.game

import android.content.Context
import java.util.UUID

/**
 * Moves a player's identity to another device.
 *
 * The profile id is the key to everything that is theirs and not on this phone: their leaderboard
 * row, their daily results, their seat in every unfinished online game, and the address invites
 * are sent to. It is generated on first launch and never leaves the device, so a lost or wiped
 * phone silently orphans all of it — the games carry on waiting for a player who no longer
 * exists anywhere.
 *
 * A code is offered rather than an account because the app has no accounts and does not want
 * any: nothing here needs a password, an email address, or a server that knows who anyone is.
 */
object ProfileRecovery {

    /**
     * Crockford's Base32 alphabet: no I, L, O or U, so the characters that get misread as one
     * another — or spell something unfortunate — are simply absent.
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val GROUP_SIZE = 4

    /** This device's code, in dash-separated groups. */
    fun codeFor(context: Context): String = format(encode(PlayerProfile.getId(context)))

    /**
     * Adopts the identity in [code], replacing this device's own.
     *
     * Returns false for anything that is not a well-formed code — the checksum means a typo is
     * rejected outright rather than quietly adopting an identity nobody owns, which would strand
     * the player somewhere no game or leaderboard row could reach.
     */
    fun restore(context: Context, code: String): Boolean {
        val id = decode(code) ?: return false
        if (id == PlayerProfile.getId(context)) return true
        PlayerProfile.setId(context, id)
        return true
    }

    /** True if [code] parses, without applying it — used to validate as it is typed. */
    fun isValid(code: String): Boolean = decode(code) != null

    internal fun encode(uuid: String): String {
        val parsed = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return ""
        val bytes = ByteArray(17)
        var value = parsed.mostSignificantBits
        for (i in 0..7) bytes[i] = (value ushr (56 - i * 8)).toByte()
        value = parsed.leastSignificantBits
        for (i in 0..7) bytes[8 + i] = (value ushr (56 - i * 8)).toByte()
        bytes[16] = checksum(bytes, 16)

        // Straight base-32 over the whole 136 bits, most significant first.
        val builder = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                builder.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) builder.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return builder.toString()
    }

    internal fun decode(code: String): String? {
        // Dashes and spaces are presentation; O/I/L are accepted as the digits they are mistaken
        // for, so a code read off a screen and typed by hand still lands.
        val cleaned = code.uppercase()
            .replace("-", "")
            .replace(" ", "")
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
        if (cleaned.length != 28) return null

        val bytes = ByteArray(17)
        var buffer = 0
        var bits = 0
        var index = 0
        for (character in cleaned) {
            val position = ALPHABET.indexOf(character)
            if (position < 0) return null
            buffer = (buffer shl 5) or position
            bits += 5
            if (bits >= 8) {
                if (index >= 17) return null
                bytes[index++] = (buffer ushr (bits - 8)).toByte()
                bits -= 8
            }
        }
        if (index != 17) return null
        if (bytes[16] != checksum(bytes, 16)) return null

        var most = 0L
        for (i in 0..7) most = (most shl 8) or (bytes[i].toLong() and 0xFF)
        var least = 0L
        for (i in 0..7) least = (least shl 8) or (bytes[8 + i].toLong() and 0xFF)
        return UUID(most, least).toString()
    }

    /** Catches transposed and mistyped characters, which a plain encoding would accept silently. */
    private fun checksum(bytes: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length) {
            sum = (sum * 31 + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    private fun format(raw: String): String =
        raw.chunked(GROUP_SIZE).joinToString("-")
}
