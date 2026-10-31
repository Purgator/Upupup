package fr.arichard.upupup.core

import android.content.Context
import fr.arichard.upupup.R

/** Bundled, localized release notes and the versions still awaiting the user's attention. */
object ReleaseNotes {

    /** Last version released before in-app release notes were introduced. */
    internal const val BASELINE_VERSION = "1.12"

    // Add each release here, newest first, and keep its EN/FR resource arrays in sync.
    private val entries = listOf(
        "1.14" to R.array.release_notes_1_14,
        "1.13" to R.array.release_notes_1_13,
    )

    internal val versions: List<String>
        get() = entries.map { it.first }

    /** Selects all known releases newer than [lastSeen] and no newer than [current]. */
    internal fun versionsToShow(
        available: List<String>,
        lastSeen: String?,
        current: String,
        freshInstall: Boolean,
    ): List<String> {
        if (lastSeen == null && freshInstall) return emptyList()
        val from = lastSeen ?: BASELINE_VERSION
        if (!UpdateManager.isNewer(current, from)) return emptyList()

        return available
            .filter { UpdateManager.isNewer(it, from) && !UpdateManager.isNewer(it, current) }
            .distinct()
            .sortedWith { left, right -> UpdateManager.compareVersions(right, left) }
    }

    /** Returns localized notes for all unseen versions, or none when the setting is disabled. */
    fun pending(context: Context): List<Pair<String, List<String>>> {
        val prefs = Prefs(context)
        if (!prefs.showReleaseNotes) return emptyList()

        val current = UpdateManager.currentVersion(context)
        val freshInstall = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        } catch (e: Exception) {
            false
        }
        val pendingVersions = versionsToShow(versions, prefs.lastSeenVersion, current, freshInstall)
        return pendingVersions.mapNotNull { version ->
            entries.firstOrNull { it.first == version }?.let { (_, resourceId) ->
                version to context.resources.getStringArray(resourceId).toList()
            }
        }
    }

    /** Records the installed version after its notes have been dismissed or skipped. */
    fun markSeen(context: Context) {
        Prefs(context).lastSeenVersion = UpdateManager.currentVersion(context)
    }
}