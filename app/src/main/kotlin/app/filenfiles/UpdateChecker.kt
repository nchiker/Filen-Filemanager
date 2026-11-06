package app.filenfiles

import app.filenfiles.core.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Looks up the newest GitHub release. One anonymous request, no identifiers; the app never installs anything itself. */
object UpdateChecker {
    const val REPO = "nchiker/Filen-Filemanager"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class Release(val version: String, val pageUrl: String, val apkUrl: String?)

    /** Newest published release, or null if it can't be determined (offline, rate-limited, no release yet). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val assets = j.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                }
                Release(j.getString("tag_name"), j.optString("html_url", RELEASES_PAGE), apk)
            } finally { c.disconnect() }
        }.getOrNull()
    }

    fun isNewer(release: Release, installed: String) = Versions.isNewer(release.version, installed)
}
