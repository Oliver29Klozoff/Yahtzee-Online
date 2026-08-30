package com.yahtzee.online.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.yahtzee.online.R
import com.yahtzee.online.audio.SoundEngine
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.ActiveGamesStore
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.ProfileRecovery
import com.yahtzee.online.game.TableLogoStore
import com.yahtzee.online.update.UpdateChecker

class SettingsActivity : ImmersiveActivity() {

    private lateinit var updateChecker: UpdateChecker
    private lateinit var dicePreview: Dice3DView
    private val sound by lazy { SoundEngine(this) }
    private var selectedColor: Int = DicePreferences.PALETTE.first().second
    private var pipStyle: DicePreferences.PipStyle = DicePreferences.PipStyle.AUTO

    /** Guards the sliders while they are being set from a preset, so they do not feed back. */
    private var syncingSliders = false

    /** Same guard for the accent sliders, which are set from a swatch as well as dragged. */
    private var syncingAccent = false

    /** What the screen is currently painted in, so a live retint knows what to replace. */
    private var shownAccent = 0

    /** The preset theme in force, to notice when a drag has moved far enough to change it. */
    private var appliedTheme = 0

    private companion object {
        /** Below this an accent used as text on a black page stops being readable. */
        const val MIN_ACCENT_BRIGHTNESS = 35

        /** A die can be black; its slider cannot be, or the control vanishes into the page. */
        const val MIN_SLIDER_BRIGHTNESS = 0.5f
    }

    /** Set once the logo control is built, so the picker result can refresh it. */
    private var refreshTableLogo: (() -> Unit)? = null

    /**
     * The system photo picker, which needs no storage permission and shows only what the player
     * chooses to hand over — the app never gets access to the rest of their gallery.
     */
    private val pickLogo = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (TableLogoStore.saveCustom(this, uri)) {
            refreshTableLogo?.invoke()
            dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
        } else {
            Toast.makeText(this, R.string.table_logo_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // The base class has already painted the stored accent on, so that is what is on screen.
        shownAccent = AccentColor.getColor(this)
        appliedTheme = AccentColor.themeFor(shownAccent)

        updateChecker = UpdateChecker(this)

        val versionText = findViewById<TextView>(R.id.versionText)
        val checkButton = findViewById<Button>(R.id.checkForUpdatesButton)
        val checkProgress = findViewById<ProgressBar>(R.id.checkForUpdatesProgress)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        versionText.text = getString(R.string.version_label, versionName ?: "—")

        checkButton.setOnClickListener {
            checkButton.isEnabled = false
            checkProgress.visibility = ProgressBar.VISIBLE
            updateChecker.checkManually {
                checkButton.isEnabled = true
                checkProgress.visibility = ProgressBar.GONE
            }
        }

        selectedColor = DicePreferences.getColor(this)
        pipStyle = DicePreferences.pipStyle(this)

        dicePreview = findViewById(R.id.dicePreview)
        dicePreview.setDiceColor(selectedColor)
        dicePreview.setPipStyle(pipStyle)

        dicePreview.setTableColor(AppSettings.tableColor(this))

        setUpSliders()
        setUpPipToggle()
        setUpToggle(
            R.id.keepScreenOnButton,
            AppSettings.keepScreenOn(this)
        ) { AppSettings.setKeepScreenOn(this, it) }
        setUpToggle(
            R.id.confirmScoringButton,
            AppSettings.confirmScoring(this)
        ) { AppSettings.setConfirmScoring(this, it) }
        setUpToggle(R.id.soundButton, AppSettings.soundEnabled(this)) { on ->
            AppSettings.setSoundEnabled(this, on)
            // Play something when switching on, so the setting demonstrates itself.
            if (on) sound.play(SoundEngine.Sound.SCORE)
        }
        setUpToggle(R.id.hapticsButton, AppSettings.hapticsEnabled(this)) { on ->
            AppSettings.setHapticsEnabled(this, on)
            if (on) sound.play(SoundEngine.Sound.LAND)
        }
        setUpCycler(
            R.id.diceMotionButton,
            AppSettings.DiceMotion.values().toList(),
            AppSettings.diceMotion(this),
            { it.label }
        ) { motion ->
            AppSettings.setDiceMotion(this, motion)
            dicePreview.setMotionScale(motion.durationScale)
            dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
        }
        setUpCycler(
            R.id.botSkillButton,
            AppSettings.BotSkill.values().toList(),
            AppSettings.botSkill(this),
            { it.label }
        ) { AppSettings.setBotSkill(this, it) }

        findViewById<Button>(R.id.saveDiceButton).setOnClickListener { promptSaveDice() }
        setUpTableLogo()
        setUpAccentSliders()

        setUpProfileRecovery()
        renderSwatches()
        renderTableColors()
        renderAccentColors()
        renderSavedDice()
    }

    /**
     * Saves the current colour and pip style under a name the player chooses, so a design they
     * built with the sliders survives making the next one.
     */
    private fun promptSaveDice() {
        val input = EditText(this).apply {
            hint = getString(R.string.name_your_dice)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
            setTextColor(resources.getColor(R.color.text_dark, theme))
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val frame = LinearLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.name_your_dice)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                DicePreferences.saveDie(this, name, selectedColor, pipStyle)
                Toast.makeText(this, getString(R.string.dice_saved, name), Toast.LENGTH_SHORT).show()
                renderSavedDice()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderSavedDice() {
        val row = findViewById<LinearLayout>(R.id.savedDiceRow)
        val empty = findViewById<TextView>(R.id.savedDiceEmpty)
        row.removeAllViews()

        val saved = DicePreferences.savedDice(this)
        empty.visibility = if (saved.isEmpty()) TextView.VISIBLE else TextView.GONE

        val density = resources.displayMetrics.density
        val dieSize = (56 * density).toInt()

        saved.forEach { die ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, (14 * density).toInt(), 0)
            }
            // Shown as an actual die face rather than a colour dot: pip style is half of what
            // was saved, and a swatch cannot show it.
            cell.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dieSize, dieSize)
                setImageBitmap(DieTextureAtlas.face(die.color, 5, die.pipStyle.darkFor(die.color)))
            })
            cell.addView(TextView(this).apply {
                text = die.name
                textSize = 12f
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
                setTextColor(resources.getColor(R.color.text_muted, theme))
            })

            cell.setOnClickListener {
                pipStyle = die.pipStyle
                DicePreferences.setPipStyle(this, die.pipStyle)
                dicePreview.setPipStyle(die.pipStyle)
                applyColor(die.color, reroll = true)
                syncSlidersTo(die.color)
                setUpPipToggle()
            }
            cell.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_dice, die.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        DicePreferences.deleteSavedDie(this, die.name)
                        renderSavedDice()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            row.addView(cell)
        }
    }

    override fun onResume() {
        super.onResume()
        dicePreview.onResume()
        // Tumble the preview dice so the chosen colour is seen moving under the light, which is
        // where the glass material actually shows itself.
        dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
    }

    override fun onPause() {
        super.onPause()
        // GLSurfaceView needs its EGL context torn down with the activity; without this the
        // preview keeps a render thread alive after leaving Settings.
        dicePreview.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }

    /**
     * Hue, saturation and brightness sliders, so a player is not limited to the presets. Edits
     * apply live to the preview; the dice are only re-thrown on release, since regenerating the
     * texture on every pixel of drag would be wasteful and the tumbling would never settle.
     */
    private fun setUpSliders() {
        val hue = findViewById<SeekBar>(R.id.hueSlider)
        val saturation = findViewById<SeekBar>(R.id.saturationSlider)
        val brightness = findViewById<SeekBar>(R.id.brightnessSlider)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncingSliders) return
                val color = Color.HSVToColor(
                    floatArrayOf(
                        hue.progress.toFloat(),
                        saturation.progress / 100f,
                        brightness.progress / 100f
                    )
                )
                applyColor(color, reroll = false)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
            }
        }

        hue.setOnSeekBarChangeListener(listener)
        saturation.setOnSeekBarChangeListener(listener)
        brightness.setOnSeekBarChangeListener(listener)
        syncSlidersTo(selectedColor)
        tintDiceSliders(selectedColor)
    }

    private fun syncSlidersTo(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        syncingSliders = true
        findViewById<SeekBar>(R.id.hueSlider).progress = hsv[0].toInt()
        findViewById<SeekBar>(R.id.saturationSlider).progress = (hsv[1] * 100).toInt()
        findViewById<SeekBar>(R.id.brightnessSlider).progress = (hsv[2] * 100).toInt()
        syncingSliders = false
    }

    private fun setUpPipToggle() {
        setUpCycler(
            R.id.pipStyleButton,
            DicePreferences.PipStyle.values().toList(),
            pipStyle,
            { it.label }
        ) { style ->
            pipStyle = style
            DicePreferences.setPipStyle(this, style)
            dicePreview.setPipStyle(style)
            dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
        }
    }

    private fun applyColor(color: Int, reroll: Boolean) {
        selectedColor = color
        DicePreferences.setColor(this, color)
        dicePreview.setDiceColor(color)
        tintDiceSliders(color)
        if (reroll) dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
        renderSwatches()
    }

    /**
     * Paints the dice sliders in the dice colour, so the control shows what it is making.
     *
     * Every slider in the app is otherwise the app accent, which is right for the accent's own
     * sliders and wrong for these: dragging a hue while the track stays a fixed blue gives
     * nothing to aim with, when the whole point is the colour being chosen.
     *
     * The track takes a lifted version rather than the colour itself. A die may legitimately be
     * black, and a slider drawn in black on a black page is a control nobody can find.
     */
    private fun tintDiceSliders(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val visible = Color.HSVToColor(
            floatArrayOf(hsv[0], hsv[1], maxOf(hsv[2], MIN_SLIDER_BRIGHTNESS))
        )
        val tint = android.content.res.ColorStateList.valueOf(visible)

        listOf(R.id.hueSlider, R.id.saturationSlider, R.id.brightnessSlider).forEach { id ->
            findViewById<SeekBar>(id).apply {
                progressTintList = tint
                thumbTintList = tint
            }
        }
    }

    /** Simple on/off button, since a Switch would need a Material theme this app does not use. */
    /**
     * What is printed on the felt. "Your picture" is only offered once one has been chosen, so
     * the cycler never lands on an option that would show blank felt.
     */
    private fun setUpTableLogo() {
        val button = findViewById<Button>(R.id.tableLogoButton)

        fun refresh() {
            button.text = TableLogoStore.mode(this).label
            dicePreview.setTableLogo(TableLogoStore.mode(this))
        }

        button.setOnClickListener {
            val options = TableLogoStore.Mode.values().filter {
                it != TableLogoStore.Mode.CUSTOM || TableLogoStore.hasCustom(this)
            }
            val next = options[(options.indexOf(TableLogoStore.mode(this)) + 1) % options.size]
            TableLogoStore.setMode(this, next)
            refresh()
        }

        findViewById<Button>(R.id.chooseLogoButton).setOnClickListener {
            pickLogo.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        refreshTableLogo = ::refresh
        refresh()
    }

    private fun setUpToggle(buttonId: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val button = findViewById<Button>(buttonId)
        var value = initial
        fun refresh() {
            button.text = getString(if (value) R.string.on else R.string.off)
        }
        refresh()
        button.setOnClickListener {
            value = !value
            onChange(value)
            refresh()
        }
    }

    /**
     * A button that steps through a fixed set of options, for settings with more than two
     * states. Cheaper on space than a spinner, and these lists are short enough that cycling
     * never feels like hunting.
     */
    private fun <T> setUpCycler(
        buttonId: Int,
        options: List<T>,
        initial: T,
        label: (T) -> String,
        onChange: (T) -> Unit
    ) {
        val button = findViewById<Button>(buttonId)
        var index = options.indexOf(initial).coerceAtLeast(0)
        fun refresh() {
            button.text = label(options[index])
        }
        refresh()
        button.setOnClickListener {
            index = (index + 1) % options.size
            onChange(options[index])
            refresh()
        }
    }

    /**
     * The app's own accent. Changing it recreates this screen, because the accent is carried by
     * the theme and a theme only takes effect as a screen is built — everything already on
     * screen would otherwise keep the old colour until it was navigated away from and back.
     */
    private fun renderAccentColors() {
        val row = findViewById<LinearLayout>(R.id.accentColorRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()
        val current = AccentColor.getColor(this)

        AccentColor.PALETTE.forEach { (name, color) ->
            val swatch = TextView(this).apply {
                contentDescription = name
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        (if (color == current) 3 * density else 1 * density).toInt(),
                        if (color == current) Color.WHITE else Color.parseColor("#39404A")
                    )
                }
                setOnClickListener {
                    applyAccent(color)
                    renderAccentColors()
                    if (AccentColor.themeFor(color) != appliedTheme) recreate()
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(size, size)
                .also { it.marginEnd = (12 * density).toInt() }
            row.addView(swatch)
        }
        syncAccentSliders(current)
    }

    /**
     * Hue, vividness and brightness for the accent, so it is not limited to the six presets.
     *
     * Brightness has a floor: an accent is drawn on a black page and used as text as often as
     * fill, so a nearly-black one would leave links and labels unreadable rather than merely
     * dark. Full black is not a choice worth offering.
     */
    private fun setUpAccentSliders() {
        val brightness = findViewById<SeekBar>(R.id.accentBrightnessSlider)
        // The floor is enforced by the control rather than silently applied to whatever it
        // returns. Clamping the value instead left the bottom of the slider doing nothing and the
        // thumb springing back to where it was, which reads as a broken control.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            brightness.min = MIN_ACCENT_BRIGHTNESS
        }

        listOf(R.id.accentHueSlider, R.id.accentSaturationSlider, R.id.accentBrightnessSlider)
            .forEach { id ->
                findViewById<SeekBar>(id).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser || syncingAccent) return
                        applyAccent(accentFromSliders())
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    /**
                     * The theme only decides what the platform draws for itself — a dialog's
                     * buttons, a text cursor — and only the nearest preset of it at that, so it
                     * is worth rebuilding for only when the drag has moved far enough to change
                     * which preset is nearest. Rebuilding on every lift would throw the page back
                     * to the top for a change nobody would see.
                     */
                    override fun onStopTrackingTouch(bar: SeekBar) {
                        if (AccentColor.themeFor(AccentColor.getColor(this@SettingsActivity)) != appliedTheme) {
                            recreate()
                        }
                    }
                })
            }
    }

    private fun accentFromSliders(): Int {
        val hue = findViewById<SeekBar>(R.id.accentHueSlider).progress.toFloat()
        val saturation = findViewById<SeekBar>(R.id.accentSaturationSlider).progress / 100f
        val brightness = findViewById<SeekBar>(R.id.accentBrightnessSlider).progress / 100f
        return Color.HSVToColor(
            floatArrayOf(hue, saturation, brightness.coerceAtLeast(MIN_ACCENT_BRIGHTNESS / 100f))
        )
    }

    private fun syncAccentSliders(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        syncingAccent = true
        findViewById<SeekBar>(R.id.accentHueSlider).progress = hsv[0].toInt()
        findViewById<SeekBar>(R.id.accentSaturationSlider).progress = (hsv[1] * 100).toInt()
        findViewById<SeekBar>(R.id.accentBrightnessSlider).progress = (hsv[2] * 100).toInt()
        syncingAccent = false
    }

    /**
     * Stores the colour and repaints this screen with it immediately.
     *
     * Repainting live rather than rebuilding is what makes the sliders usable: a colour that only
     * appears once the finger lifts gives nothing to aim with, and rebuilding on every change
     * would throw the page back to the top mid-drag. [AccentColor.retint] is told what the screen
     * is currently wearing so it can find and replace exactly that, which is why the shown colour
     * is tracked rather than re-read from preferences.
     */
    private fun applyAccent(color: Int) {
        if (color == AccentColor.getColor(this)) return
        AccentColor.setColor(this, color)
        AccentColor.retint(findViewById(android.R.id.content), shownAccent, color)
        shownAccent = color
        // That repaint reaches every slider on the page, including the dice ones, so their own
        // colour goes back on afterwards.
        tintDiceSliders(selectedColor)
    }

    /**
     * The recovery code, and the way back in from another device.
     *
     * Restoring is guarded by a confirmation naming what is given up: adopting another identity
     * abandons this device's own leaderboard row and its seat in any game it is part of, and
     * there is no code for the identity being replaced unless the player wrote it down first.
     */
    private fun setUpProfileRecovery() {
        val codeView = findViewById<TextView>(R.id.recoveryCodeText)
        val body = findViewById<LinearLayout>(R.id.recoveryBody)
        val chevron = findViewById<TextView>(R.id.recoveryChevron)
        codeView.text = ProfileRecovery.codeFor(this)

        // Kept collapsed between visits rather than remembered: this is something a player opens
        // deliberately, and a section left standing open puts the code on screen for anyone who
        // happens to be looking next time Settings is opened.
        findViewById<View>(R.id.recoveryHeader).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron.setText(if (opening) R.string.collapse_chevron else R.string.expand_chevron)
        }

        findViewById<Button>(R.id.copyRecoveryButton).setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    getString(R.string.profile_recovery),
                    codeView.text.toString()
                )
            )
            Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.restoreProfileButton).setOnClickListener { promptRestoreProfile() }
        findViewById<Button>(R.id.newIdentityButton).setOnClickListener { promptNewIdentity() }
    }

    /**
     * The way out of two devices sharing one identity — from a code entered on both, or from a
     * backup that carried the first phone's profile onto the second. Until one of them takes a
     * new id they are a single seat, and cannot be in a room against each other.
     */
    private fun promptNewIdentity() {
        AlertDialog.Builder(this)
            .setTitle(R.string.new_identity)
            .setMessage(R.string.new_identity_warning)
            .setPositiveButton(R.string.new_identity) { _, _ ->
                PlayerProfile.resetId(this)
                // Games and invites belonged to the identity being left behind, so watching for
                // turns in them would be watching someone else's games.
                ActiveGamesStore.all(this).forEach { ActiveGamesStore.untrack(this, it.roomCode) }
                findViewById<TextView>(R.id.recoveryCodeText).text = ProfileRecovery.codeFor(this)
                Toast.makeText(this, R.string.new_identity_done, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRestoreProfile() {
        val input = EditText(this).apply {
            hint = getString(R.string.paste_code)
            setSingleLine()
            setTextColor(resources.getColor(R.color.text_dark, theme))
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val frame = LinearLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.restore_profile)
            .setMessage(R.string.restore_profile_warning)
            .setView(frame)
            .setPositiveButton(R.string.restore) { _, _ ->
                val code = input.text.toString().trim()
                if (ProfileRecovery.restore(this, code)) {
                    findViewById<TextView>(R.id.recoveryCodeText).text =
                        ProfileRecovery.codeFor(this)
                    Toast.makeText(this, R.string.profile_restored, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.code_invalid, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderTableColors() {
        val row = findViewById<LinearLayout>(R.id.tableColorRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()
        val current = AppSettings.tableColor(this)

        AppSettings.TABLE_COLORS.forEach { (name, color) ->
            val swatch = TextView(this).apply {
                contentDescription = name
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    // The darkest felts are nearly invisible against the page, so every swatch
                    // carries an outline and the selected one is picked out in white.
                    setStroke(
                        (if (color == current) 3 * density else 1 * density).toInt(),
                        if (color == current) Color.WHITE else Color.parseColor("#39404A")
                    )
                }
                setOnClickListener {
                    AppSettings.setTableColor(this@SettingsActivity, color)
                    dicePreview.setTableColor(color)
                    renderTableColors()
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(size, size)
                .also { it.marginEnd = (12 * density).toInt() }
            row.addView(swatch)
        }
    }

    private fun renderSwatches() {
        val row = findViewById<LinearLayout>(R.id.diceColorRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()

        DicePreferences.PALETTE.forEach { (name, color) ->
            val swatch = TextView(this).apply {
                contentDescription = name
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selectedColor) {
                        setStroke((3 * density).toInt(), Color.WHITE)
                    }
                }
                setOnClickListener {
                    applyColor(color, reroll = true)
                    syncSlidersTo(color)
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(size, size)
                .also { it.marginEnd = (12 * density).toInt() }
            row.addView(swatch)
        }
    }
}
