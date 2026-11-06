package app.filenfiles.core

import java.util.Calendar
import java.util.TimeZone

/** What the camera backup uploads and where it puts it (pure, so it is unit-tested). */
object BackupRules {
    private val PHOTOS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "raw", "arw", "cr2", "nef")
    private val VIDEOS = setOf("mp4", "mov", "3gp", "mkv", "webm", "m4v", "avi")

    /** Skips hidden files, Android's in-progress (".pending-…") and trashed (".trashed-…") media and non-media. */
    fun isBackupCandidate(name: String, includeVideos: Boolean): Boolean {
        if (name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in PHOTOS || (includeVideos && ext in VIDEOS)
    }

    /** Folder name for a file taken at [millis], e.g. "2026-11" (device time zone unless [zone] is given). */
    fun monthFolder(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** Identity of one file's content as seen on the phone; a changed size or timestamp counts as a new file. */
    fun key(path: String, size: Long, modified: Long) = "$path|$size|$modified"

    /** A file still being written by the camera should wait a moment. */
    fun isSettled(modified: Long, now: Long, minAgeMs: Long = 30_000) = now - modified >= minAgeMs
}
