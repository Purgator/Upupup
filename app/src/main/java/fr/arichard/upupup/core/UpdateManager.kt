package fr.arichard.upupup.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.app.NotificationCompat
import fr.arichard.upupup.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater backed by GitHub Releases.
 *
 * Once a day (throttled, triggered on app open) it fetches the latest-release metadata;
 * when a newer version exists it downloads the APK in the background (unmetered networks
 * only) and posts a notification whose tap opens the system installer. Android verifies
 * the APK is signed with the same key before installing, so a tampered file can never
 * replace the app.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val API_URL = "https://api.github.com/repos/Purgator/Upupup/releases/latest"
    private const val ASSET_NAME = "Upupup.apk"
    private const val MAX_APK_BYTES = 50L * 1024 * 1024
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2

    enum class Status { UP_TO_DATE, UPDATE_READY, UPDATE_DEFERRED, ERROR }
    data class Result(val status: Status, val version: String? = null, val detail: String? = null)

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * Best-effort background check, called on app open. Hardened so a pending update is
     * never lost:
     *
     * - An already-downloaded update is re-surfaced on every call (local, no network), so
     *   a missed/swiped notification always comes back next time the app is opened.
     * - The GitHub fetch is throttled to once per 24h, EXCEPT while an update is "deferred"
     *   (found but not yet downloaded because we were on mobile data): then any call with
     *   an unmetered network downloads it right away, instead of waiting out the day.
     *
     * Call from a background thread.
     */
    fun maybeDailyCheck(context: Context) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        if (!prefs.autoUpdate) return

        // Re-surface an update that was already downloaded but not yet installed.
        pendingDownloadedVersion(appContext)?.let { notifyUpdateReady(appContext, it) }

        val due = System.currentTimeMillis() - prefs.lastUpdateCheck >= CHECK_INTERVAL_MS
        if (!due && !prefs.updateDeferred) return

        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm != null && !cm.isActiveNetworkMetered
        val result = check(appContext, allowDownload = unmetered)
        // Deferred (found but couldn't download on metered data) is the only state that must
        // stay retryable, so leave the flag set and let the next unmetered check retry it.
        prefs.updateDeferred = result.status == Status.UPDATE_DEFERRED
        prefs.lastUpdateCheck = System.currentTimeMillis()
        if (result.status == Status.UPDATE_READY) {
            notifyUpdateReady(appContext, result.version!!)
        }
    }

    /** Highest already-downloaded update newer than the installed app, or null. Local only. */
    private fun pendingDownloadedVersion(context: Context): String? =
        File(context.cacheDir, "updates").listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.name.endsWith(".apk") }
            ?.map { it.name.removePrefix("Upupup-v").removeSuffix(".apk") }
            ?.filter { isNewer(it, currentVersion(context)) }
            ?.maxWithOrNull(::compareVersions)

    /**
     * Checks GitHub for a newer release and, if [allowDownload], fetches its APK
     * into the cache. Does not touch the throttle timestamp — the caller owns that.
     * Call from a background thread.
     */
    @Synchronized
    fun check(
        context: Context,
        allowDownload: Boolean,
        onDownloading: (() -> Unit)? = null,
    ): Result {
        val appContext = context.applicationContext
        return try {
            val json = JSONObject(httpGet(API_URL))

            val remote = json.getString("tag_name").removePrefix("v").trim()
            val current = currentVersion(appContext)
            cleanupOldApks(appContext, current)
            if (!isNewer(remote, current)) return Result(Status.UP_TO_DATE, current)

            var assetUrl: String? = null
            var assetSize = -1L
            val assets = json.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                if (asset.optString("name") == ASSET_NAME) {
                    assetUrl = asset.optString("browser_download_url")
                    assetSize = asset.optLong("size", -1)
                    break
                }
            }
            if (assetUrl.isNullOrEmpty() || !assetUrl.startsWith("https://")) {
                return Result(Status.ERROR, remote, "release has no $ASSET_NAME asset")
            }
            if (assetSize !in 1..MAX_APK_BYTES) {
                return Result(Status.ERROR, remote, "unexpected asset size $assetSize")
            }

            val apk = apkFile(appContext, remote)
            if (apk.isFile && apk.length() == assetSize) return Result(Status.UPDATE_READY, remote)
            if (!allowDownload) return Result(Status.UPDATE_DEFERRED, remote)

            onDownloading?.invoke()
            download(assetUrl, apk, assetSize)
            Log.i(TAG, "Downloaded update $remote (${apk.length()} bytes)")
            Result(Status.UPDATE_READY, remote)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            Result(Status.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Starts installing the downloaded update APK for [version]. */
    fun install(context: Context, version: String) {
        ApkInstaller.install(context, apkFile(context, version))
    }

    private fun notifyUpdateReady(context: Context, version: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val installIntent = Intent(context, ApkInstaller.Receiver::class.java)
            .setAction(ApkInstaller.ACTION_START_INSTALL)
            .putExtra(ApkInstaller.EXTRA_VERSION, version)
        // FLAG_UPDATE_CURRENT: the version travels as an extra, and cached PendingIntents
        // keep their old extras unless explicitly updated.
        val pending = PendingIntent.getBroadcast(
            context, 3, installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(context.getString(R.string.update_ready_title, version))
                .setContentText(context.getString(R.string.update_ready_text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
    }

    /** True when [remote] is a strictly newer dotted version than [local]. */
    internal fun isNewer(remote: String, local: String): Boolean = compareVersions(remote, local) > 0

    /** Numeric dotted-version comparison: 1.10 > 1.9. Non-numeric parts count as 0. */
    internal fun compareVersions(a: String, b: String): Int {
        // First digit run only: "7-rc1" is 7, not the digit-concatenation 71.
        fun segment(s: String) =
            s.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        val x = a.split('.').map(::segment)
        val y = b.split('.').map(::segment)
        for (i in 0 until maxOf(x.size, y.size)) {
            val cmp = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun apkFile(context: Context, version: String): File =
        File(context.cacheDir, "updates/Upupup-v$version.apk").apply { parentFile?.mkdirs() }

    /** Removes cached update APKs that are no longer newer than the installed app. */
    private fun cleanupOldApks(context: Context, current: String) {
        File(context.cacheDir, "updates").listFiles()?.forEach { file ->
            // check() is synchronized and downloads only after this cleanup, so any
            // .tmp lying around is a dead leftover from a killed download.
            if (file.name.endsWith(".tmp")) {
                file.delete()
                return@forEach
            }
            val version = file.name.removePrefix("Upupup-v").removeSuffix(".apk")
            if (!isNewer(version, current)) file.delete()
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Upupup-updater")
            if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, expectedSize: Long) {
        val tmp = File(target.path + ".tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Upupup-updater")
                if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            if (tmp.length() != expectedSize) {
                throw Exception("size mismatch: got ${tmp.length()}, expected $expectedSize")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw Exception("could not move downloaded file")
        } finally {
            tmp.delete()
        }
    }
}
