package com.yahtzee.online.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.update.UpdateChecker

class SettingsActivity : ImmersiveActivity() {

    private lateinit var updateChecker: UpdateChecker
    private lateinit var dicePreview: Dice3DView
    private var selectedColor: Int = DicePreferences.PALETTE.first().second
    private var darkPips: Boolean = true

    /** Guards the sliders while they are being set from a preset, so they do not feed back. */
    private var syncingSliders = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
        darkPips = DicePreferences.useDarkPips(this)

        dicePreview = findViewById(R.id.dicePreview)
        dicePreview.setDiceColor(selectedColor)
        dicePreview.setDarkPips(darkPips)

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
        renderSwatches()
        renderTableColors()
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
        val button = findViewById<Button>(R.id.pipStyleButton)
        fun refresh() {
            button.text = getString(if (darkPips) R.string.pips_dark else R.string.pips_light)
        }
        refresh()
        button.setOnClickListener {
            darkPips = !darkPips
            DicePreferences.setDarkPips(this, darkPips)
            dicePreview.setDarkPips(darkPips)
            dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
            refresh()
        }
    }

    private fun applyColor(color: Int, reroll: Boolean) {
        selectedColor = color
        DicePreferences.setColor(this, color)
        dicePreview.setDiceColor(color)
        if (reroll) dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
        renderSwatches()
    }

    /** Simple on/off button, since a Switch would need a Material theme this app does not use. */
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
