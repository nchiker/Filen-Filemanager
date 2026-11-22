package app.filenfiles.filen

import app.filenfiles.core.RemoteFile
import uniffi.filen_sdk_rs.AnyDirWithContext
import uniffi.filen_sdk_rs.AnyFile
import uniffi.filen_sdk_rs.AnyNormalDir
import uniffi.filen_sdk_rs.FileMeta
import uniffi.filen_sdk_rs.JsFileDownloadCallback
import uniffi.filen_sdk_rs.ManagedFuture
import uniffi.filen_types.ParentUuid
import java.io.File as JFile
import uniffi.filen_sdk_rs.File as FFile

/** [PinRemote] on the Filen SDK (personal drive). */
class FilenPinRemote(private val session: FilenSession) : PinRemote {
    private val client get() = session.client

    private fun keyOf(f: FFile) = f.stableUuid ?: f.uuid

    private fun remote(f: FFile): RemoteFile {
        val m = (f.meta as? FileMeta.Decoded)?.v1
        val name = m?.name ?: "file-${f.uuid}"
        return RemoteFile(keyOf(f), f.uuid, name, f.size.toLong(), m?.modified ?: f.timestamp, m?.mime?.takeIf { it.isNotBlank() })
    }

    override suspend fun file(key: String): RemoteLookup? {
        val f = client.getFileByStableUuidOptional(key) ?: client.getFileOptional(key) ?: return null
        return RemoteLookup(remote(f), f.parent is ParentUuid.Trash)
    }

    override suspend fun folder(uuid: String): List<RemoteFile>? {
        val dir = client.getDirOptional(uuid)
        if (dir == null || dir.parent is ParentUuid.Trash) return null
        return client.listDirRecursive(AnyDirWithContext.Normal(AnyNormalDir.Dir(dir)), null).files
            .filter { it.parent !is ParentUuid.Trash }.map(::remote)
    }

    override suspend fun download(file: RemoteFile, dest: JFile) {
        val f = client.getFileOptional(file.version) ?: error("File is no longer available")
        retryNetwork { _ ->
            dest.delete() // the SDK restarts from byte 0
            val cb = object : JsFileDownloadCallback { override fun onUpdate(downloadedBytes: ULong) {} }
            client.downloadFileToPath(AnyFile.File(f), dest.absolutePath, cb, ManagedFuture(null, null))
        }
    }
}
