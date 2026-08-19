package com.yahtzee.online.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer Yahtzee build and, on confirmation, downloads the APK
 * asset to the app's cache dir and hands it to the system installer. The APK is transient —
 * nothing is kept around after install, matching this app's other "no files saved to device"
 * requirement; [cleanupStaleApk] sweeps any leftover on the next launch in case the install
 * flow was abandoned before the file could be deleted.
 */
class UpdateChecker(private val context: Context) {

    private val releasesUrl =
        "https://api.github.com/repos/Oliver29Klozoff/Yahtzee-Online/releases/latest"
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val PREFS = "update_prefs"
        const val KEY_DISMISSED_TAG = "dismissed_tag"
    }

    fun cleanupStaleApk() {
        try {
            val apk = File(context.externalCacheDir ?: context.cacheDir, "Yahtzee-update.apk")
            if (apk.exists()) apk.delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Launch-time check: prompts only when an update actually exists, and stays completely
     * silent otherwise — no toast on "up to date", none on network failure. A launch check that
     * announced itself every cold boot would be noise, and offline players would be nagged with
     * errors for a check they never asked for.
     *
     * Declining an update suppresses the prompt for that version, so it appears once per
     * release rather than on every launch.
     */
    fun checkOnLaunch() {
        Thread {
            val result = fetchLatestRelease() ?: return@Thread
            if (!isNewer(result.tagName, currentVersionName())) return@Thread

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_DISMISSED_TAG, null) == result.tagName) return@Thread

            mainHandler.post {
                AlertDialog.Builder(context)
                    .setTitle("${result.name} available")
                    .setMessage(result.notes.ifBlank { "A new version is ready to install." })
                    .setPositiveButton("Update now") { _, _ -> downloadAndInstall(result.apkUrl) }
                    .setNegativeButton("Not now") { _, _ ->
                        prefs.edit().putString(KEY_DISMISSED_TAG, result.tagName).apply()
                    }
                    .show()
            }
        }.start()
    }

    /** Manual "Check for Updates" entry point — always shows a result, including "up to date". */
    fun checkManually(onResult: (() -> Unit)? = null) {
        Thread {
            val result = fetchLatestRelease()
            mainHandler.post {
                onResult?.invoke()
                if (result == null) {
                    Toast.makeText(context, "Couldn't check for updates. Try again later.", Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (isNewer(result.tagName, currentVersionName())) {
                    showUpdateDialog(result)
                } else {
                    Toast.makeText(context, "You're on the latest version.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private data class ReleaseInfo(val tagName: String, val name: String, val notes: String, val apkUrl: String)

    private fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val connection = URL(releasesUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val tagName = json.getString("tag_name")
            val name = json.optString("name", tagName)
            val notes = json.optString("body", "")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return null
            ReleaseInfo(tagName, name, notes, apkUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun currentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
    }

    /** Compares dotted version numbers (tag names like "v1.4" or "1.4" both parse fine). */
    private fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val remote = remoteTag.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val local = localVersion.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        AlertDialog.Builder(context)
            .setTitle("${release.name} available")
            .setMessage(release.notes.ifBlank { "A new version is ready to install." })
            .setPositiveButton("Update now") { _, _ -> downloadAndInstall(release.apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun canInstallUnknownSources(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (!canInstallUnknownSources()) {
            AlertDialog.Builder(context)
                .setTitle("Permission needed")
                .setMessage("Yahtzee needs permission to install updates. You'll be taken to a settings screen — turn on \"Allow from this source\", then come back and press Update again.")
                .setPositiveButton("Continue") { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val progress = ProgressDialogHolder(context)
        progress.show()

        Thread {
            try {
                val apkFile = File(context.externalCacheDir ?: context.cacheDir, "Yahtzee-update.apk")
                val connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                val totalBytes = connection.contentLengthLong

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        var lastUpdateMs = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateMs > 100) {
                                lastUpdateMs = now
                                val d = downloaded
                                mainHandler.post { progress.update(d, totalBytes) }
                            }
                        }
                        val finalDownloaded = downloaded
                        mainHandler.post { progress.update(finalDownloaded, totalBytes) }
                    }
                }
                connection.disconnect()

                mainHandler.post {
                    progress.dismiss()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progress.dismiss()
                    Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private class ProgressDialogHolder(private val context: Context) {
        private var dialog: AlertDialog? = null
        private var progressBar: ProgressBar? = null
        private var label: TextView? = null

        fun show() {
            try {
                val density = context.resources.displayMetrics.density
                val pad = (24 * density).toInt()
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad, pad, pad)
                }
                label = TextView(context).apply { text = "Downloading update…" }
                progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    max = 100
                }
                layout.addView(label)
                layout.addView(
                    progressBar,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .also { it.topMargin = (16 * density).toInt() }
                )
                dialog = AlertDialog.Builder(context)
                    .setTitle("Updating Yahtzee")
                    .setView(layout)
                    .setCancelable(false)
                    .show()
            } catch (_: Exception) {
            }
        }

        fun update(downloaded: Long, total: Long) {
            val bar = progressBar ?: return
            if (total > 0) {
                bar.isIndeterminate = false
                bar.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                val mb = { bytes: Long -> "%.1f".format(bytes / 1_000_000.0) }
                label?.text = "Downloading update… ${mb(downloaded)} / ${mb(total)} MB"
            } else {
                label?.text = "Downloading update… %.1f MB".format(downloaded / 1_000_000.0)
            }
        }

        fun dismiss() {
            try {
                dialog?.dismiss()
            } catch (_: Exception) {
            }
        }
    }
}
