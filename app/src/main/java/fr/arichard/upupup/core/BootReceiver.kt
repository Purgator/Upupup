package fr.arichard.upupup.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager registrations do not survive a reboot (or an app update, or a clock
 * change that could make a computed trigger wrong) — this receiver re-arms them all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> AlarmScheduler.scheduleAll(context)
        }
    }
}
