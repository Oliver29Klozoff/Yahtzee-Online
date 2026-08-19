package com.yahtzee.online.ui

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yahtzee.online.R
import com.yahtzee.online.update.UpdateChecker

class SettingsActivity : AppCompatActivity() {

    private lateinit var updateChecker: UpdateChecker

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
    }
}
