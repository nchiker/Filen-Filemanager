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
    private var leftAt = 0L

    val isLocked: Boolean get() = prefs.appLock && locked

    fun onStop() { leftAt = SystemClock.elapsedRealtime() }
    fun onStart() {
        if (prefs.appLock && !locked && SystemClock.elapsedRealtime() - leftAt >= prefs.lockTimeoutMs) locked = true
    }
    fun unlock() { locked = false }
}
