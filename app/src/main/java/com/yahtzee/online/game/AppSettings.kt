package com.yahtzee.online.game

import android.content.Context

/**
 * Player preferences that are not about the dice themselves.
 *
 * All local: these change how the game behaves or looks on this device only. The one setting
 * that cannot work this way is the turn length, which every player in a room has to agree on —
 * that travels in the room state instead, chosen by the host.
 */
object AppSettings {

    private const val PREFS = "app_settings"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_CONFIRM_SCORING = "confirm_scoring"
    private const val KEY_TABLE_COLOR = "table_color"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_MOTION = "dice_motion"
    private const val KEY_BOT_SKILL = "bot_skill"

    /** Table felt colours. Black is the original look and stays the default. */
    val TABLE_COLORS: List<Pair<String, Int>> = listOf(
        "Black" to 0xFF000000.toInt(),
        "Felt green" to 0xFF12351F.toInt(),
        "Casino red" to 0xFF3A0F14.toInt(),
        "Navy" to 0xFF0B1A33.toInt(),
        "Walnut" to 0xFF2A1810.toInt(),
        "Slate" to 0xFF15181C.toInt()
    )

    fun keepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
    }

    /**
     * Requires a second tap to commit a score. Off by default, since it adds a tap to every
     * turn — but a mis-tap is unrecoverable and can decide a game, so it is worth offering.
     */
    fun confirmScoring(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_SCORING, false)

    fun setConfirmScoring(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_SCORING, on).apply()
    }

    fun tableColor(context: Context): Int =
        prefs(context).getInt(KEY_TABLE_COLOR, TABLE_COLORS.first().second)

    fun setTableColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_TABLE_COLOR, color).apply()
    }

    fun soundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, on).apply()
    }

    fun hapticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, on).apply()
    }

    /** How much of the dice animation to play. */
    enum class DiceMotion(val label: String, val durationScale: Float) {
        FULL("Full", 1f),
        QUICK("Quick", 0.55f),
        OFF("Off", 0f)
    }

    fun diceMotion(context: Context): DiceMotion {
        val name = prefs(context).getString(KEY_MOTION, DiceMotion.FULL.name)
        return runCatching { DiceMotion.valueOf(name!!) }.getOrDefault(DiceMotion.FULL)
    }

    fun setDiceMotion(context: Context, motion: DiceMotion) {
        prefs(context).edit().putString(KEY_MOTION, motion.name).apply()
    }

    /**
     * How well the bots play. This is the one setting here that changes the game rather than
     * its presentation, but it is still local: bots only exist in solo games.
     */
    /**
     * Bot difficulty. Expert searches rather than following rules -- see [com.yahtzee.online.bot.ExpertStrategy] --
     * and is the default, since the levels below it exist to be easier than a good opponent
     * rather than to stand in for one.
     */
    enum class BotSkill(val label: String) { EASY("Easy"), NORMAL("Normal"), HARD("Hard"), EXPERT("Expert") }

    fun botSkill(context: Context): BotSkill {
        val name = prefs(context).getString(KEY_BOT_SKILL, BotSkill.EXPERT.name)
        return runCatching { BotSkill.valueOf(name!!) }.getOrDefault(BotSkill.EXPERT)
    }

    fun setBotSkill(context: Context, skill: BotSkill) {
        prefs(context).edit().putString(KEY_BOT_SKILL, skill.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
