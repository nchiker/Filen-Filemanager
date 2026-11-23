package app.filenfiles.transfer

import org.json.JSONObject
import java.io.File

/**
 * `filen-pending:...` document ids other apps were handed by createDocument -> the real `filen:<uuid>` id once the upload
 * landed. Kept on disk (survives process death) and capped, oldest dropped first. Without it an app that saves a file and
 * later reopens its URI would be told the file does not exist.
 */
class DocIdMap(private val file: File, private val cap: Int = 500) {
    private val map = LinkedHashMap<String, String>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            o.keys().forEach { k -> map[k] = o.getString(k) }
        }
    }

    @Synchronized fun get(pendingId: String): String? = map[pendingId]

    @Synchronized fun put(pendingId: String, realId: String) {
        map.remove(pendingId); map[pendingId] = realId
        while (map.size > cap) map.remove(map.keys.first())
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject(map as Map<*, *>).toString())
            tmp.renameTo(file)
        }
    }
}
