package com.yahtzee.online.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.Dice3DView
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.update.UpdateChecker

class SettingsActivity : ImmersiveActivity() {

    private lateinit var updateChecker: UpdateChecker
    private lateinit var dicePreview: Dice3DView
    private var selectedColor: Int = DicePreferences.PALETTE.first().second

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
        dicePreview = findViewById(R.id.dicePreview)
        dicePreview.setDiceColor(selectedColor)
        renderSwatches()
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
                    selectedColor = color
                    DicePreferences.setColor(this@SettingsActivity, color)
                    dicePreview.setDiceColor(color)
                    dicePreview.rollTo(List(5) { (1..6).random() }, List(5) { false })
                    renderSwatches()
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(size, size)
                .also { it.marginEnd = (12 * density).toInt() }
            row.addView(swatch)
        }
    }
}
