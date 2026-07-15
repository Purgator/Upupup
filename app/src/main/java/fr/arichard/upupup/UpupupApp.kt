package fr.arichard.upupup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import fr.arichard.upupup.core.TickingNotifications

/**
 * Tracks whether any of our screens is visible: the timer/stopwatch shade notifications
 * exist only while the app is in the background — on screen, the tabs themselves show
 * the live state, so a notification would just duplicate it.
 */
class UpupupApp : Application(), Application.ActivityLifecycleCallbacks {

    private var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (visibleActivities++ == 0) {
            TickingNotifications.cancelTimer(this)
            TickingNotifications.cancelStopwatch(this)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        // A new activity's onStart always precedes the old one's onStop, so the count
        // only reaches zero when the whole app leaves the screen.
        if (--visibleActivities == 0 && !activity.isChangingConfigurations) {
            TickingNotifications.showTimer(this)
            TickingNotifications.showStopwatch(this)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
