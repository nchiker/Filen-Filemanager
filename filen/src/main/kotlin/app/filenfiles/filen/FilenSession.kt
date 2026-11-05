package app.filenfiles.filen

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.filen.net.FilenTls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import uniffi.filen_sdk_rs.JsClient
import uniffi.filen_sdk_rs.JsClientConfig
import uniffi.filen_sdk_rs.LoginParams
import uniffi.filen_sdk_rs.LogLevel
import uniffi.filen_sdk_rs.StringifiedClient
import uniffi.filen_sdk_rs.UnauthJsClient

/** Owns the authenticated Filen client and its (encrypted-at-rest) persisted session. */
class FilenSession(private val context: Context) {
    sealed interface State {
        data object Unknown : State
        data object LoggedOut : State
        data class LoggedIn(val email: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state

    @Volatile private var _client: JsClient? = null
    val client: JsClient get() = _client ?: error("Not signed in to Filen")
    val isSignedIn: Boolean get() = _client != null

    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "filen_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun unauth(): UnauthJsClient {
        FilenTls.init(context)
        return UnauthJsClient.fromConfig(JsClientConfig(null, null, null, null, LogLevel.WARN, null))
    }

    /** Restore a previous session from storage. No network call is made. */
    fun restore() {
        val raw = prefs.getString("session", null)
        if (raw == null) { _state.value = State.LoggedOut; return }
        try {
            val s = decode(raw)
            _client = unauth().fromStringified(s)
            _state.value = State.LoggedIn(s.email)
        } catch (e: Exception) {
            prefs.edit().remove("session").apply()
            _state.value = State.LoggedOut
        }
    }

    suspend fun login(email: String, password: String, twoFactor: String?) {
        val c = unauth().login(LoginParams(email.trim(), password, twoFactor?.takeIf { it.isNotBlank() }))
        val s = c.toStringified()
        prefs.edit().putString("session", encode(s)).apply()
        _client = c
        _state.value = State.LoggedIn(s.email)
    }

    fun logout() {
        prefs.edit().remove("session").apply()
        _client = null
        _state.value = State.LoggedOut
    }

    private fun encode(s: StringifiedClient) = JSONObject()
        .put("email", s.email).put("userId", s.userId.toString()).put("rootUuid", s.rootUuid)
        .put("authInfo", s.authInfo).put("privateKey", s.privateKey).put("apiKey", s.apiKey)
        .put("authVersion", s.authVersion.toInt())
        .toString()

    private fun decode(raw: String): StringifiedClient {
        val j = JSONObject(raw)
        return StringifiedClient(
            email = j.getString("email"), userId = j.getString("userId").toULong(),
            rootUuid = j.getString("rootUuid"), authInfo = j.getString("authInfo"),
            privateKey = j.getString("privateKey"), apiKey = j.getString("apiKey"),
            authVersion = j.getInt("authVersion").toUByte(),
        )
    }
}
