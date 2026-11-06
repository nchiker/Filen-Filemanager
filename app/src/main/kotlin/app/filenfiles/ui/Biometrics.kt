package app.filenfiles.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint/face, with the screen lock (PIN, pattern, password) as the fallback. */
private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

fun canAuthenticate(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

/** Shows the system prompt; [onResult] gets true on success and false on cancel/failure. */
fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null, onResult: (Boolean) -> Unit) {
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH)
        .apply { subtitle?.let { setSubtitle(it) } }.build()
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
    }).authenticate(info)
}
