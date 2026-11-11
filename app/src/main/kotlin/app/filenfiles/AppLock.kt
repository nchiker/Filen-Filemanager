package app.filenfiles

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Optional lock in front of the app. A cold start with the lock enabled starts locked; leaving the app for longer
 * than the configured time locks it again. The documents provider asks [isLocked] too, so other apps can't browse
 * Filen through the system picker while the app is locked.
 */
class AppLock(private val prefs: Prefs) {
    var locked by mutableStateOf(prefs.appLock); private set
    @Volatile private var leftAt = 0L
    @Volatile private var inBackground = false

    /** The app's own screen is locked (it re-locks in [onStart] once the lock time is up). */
    val isLocked: Boolean get() = prefs.appLock && locked

    /**
     * Whether OTHER apps (the documents provider) are kept out. While the app is in the background the lock time counts
     * here too, so they are refused once it is up, not only when the app is reopened. It is never shorter than
     * [OTHERS_GRACE_MS]: after unlocking from a picker's "unlock" button the owner must be able to go back and pick
     * a folder, even with "Immediately".
     */
    val isLockedForOthers: Boolean get() = prefs.appLock &&
        (locked || (inBackground && SystemClock.elapsedRealtime() - leftAt >= maxOf(prefs.lockTimeoutMs, OTHERS_GRACE_MS)))

    /**
     * Set by the documents provider when an app holding a persisted folder grant was refused while locked; after the
     * owner unlocks, the app asks once whether that app may read its folder while locked. Kept in memory only (never
     * taken from an Intent), so another app can't fake the question.
     */
    var askReader by mutableStateOf<String?>(null)

    fun onStop() { leftAt = SystemClock.elapsedRealtime(); inBackground = true }
    fun onStart() {
        if (prefs.appLock && !locked && inBackground && SystemClock.elapsedRealtime() - leftAt >= prefs.lockTimeoutMs) locked = true
        inBackground = false
    }
    fun unlock() { locked = false }

    companion object { const val OTHERS_GRACE_MS = 60_000L }
}
