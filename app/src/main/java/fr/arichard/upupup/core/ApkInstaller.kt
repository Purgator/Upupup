package fr.arichard.upupup.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import fr.arichard.upupup.R
import java.io.File

/**
 * Installs an APK through the modern [PackageInstaller] session API.
 *
 * For a self-update (same package, same signing key) on Android 12+, the session is
 * created with USER_ACTION_NOT_REQUIRED so the update applies with no confirmation at
 * all. When the system still requires confirmation — first install, or OEM policy — it
 * replies with STATUS_PENDING_USER_ACTION and we launch the single dialog it hands back.
 * If session creation fails outright (old/quirky devices), we fall back to the classic
 * installer intent so updating still works.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    const val ACTION_START_INSTALL = "fr.arichard.upupup.action.START_INSTALL"
    const val ACTION_INSTALL_STATUS = "fr.arichard.upupup.action.INSTALL_STATUS"
    const val EXTRA_VERSION = "version"

    fun install(context: Context, apk: File) {
        if (!apk.isFile || apk.length() <= 0) {
            Log.w(TAG, "APK missing or empty: $apk")
            return
        }
        try {
            installViaSession(context.applicationContext, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Session install failed, falling back to installer intent", e)
            fallbackInstall(context, apk)
        }
    }

    private fun installViaSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("upupup.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    session.fsync(out)
                }
            }
            val callback = Intent(ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "Committed install session $sessionId")
    }

    private fun fallbackInstall(context: Context, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Receives both the "user tapped the update notification" trigger and the
     * PackageInstaller status callbacks.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START_INSTALL -> {
                    val version = intent.getStringExtra(EXTRA_VERSION) ?: return
                    UpdateManager.install(context, version)
                }
                else -> handleStatus(context, intent)
            }
        }

        private fun handleStatus(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not launch install confirmation", e)
                    }
                }
                PackageInstaller.STATUS_SUCCESS ->
                    Log.i(TAG, "Update installed")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Install failed: $msg")
                    toast(context, context.getString(R.string.update_error, msg ?: "?"))
                }
            }
        }

        private fun toast(context: Context, message: String) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        }
    }
}
