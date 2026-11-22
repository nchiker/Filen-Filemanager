package app.filenfiles.core

/** Client-side filters applied to search results (the index has no server-side filtering). Pure, unit-tested. */
enum class TypeFilter(val label: String) {
    ANY("Any type"), FOLDERS("Folders"), IMAGES("Images"), VIDEO("Video"), AUDIO("Audio"),
    DOCUMENTS("Documents"), ARCHIVES("Archives");

    fun matches(item: FileItem): Boolean {
        if (this == ANY) return true
        if (this == FOLDERS) return item.isDirectory
        if (item.isDirectory) return false
        val mime = item.mime.orEmpty().lowercase()
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return when (this) {
            IMAGES -> mime.startsWith("image/")
            VIDEO -> mime.startsWith("video/")
            AUDIO -> mime.startsWith("audio/")
            DOCUMENTS -> mime == "application/pdf" || mime.startsWith("text/") ||
                mime.contains("wordprocessing") || mime == "application/msword" || mime == "application/vnd.ms-excel" ||
                mime.contains("spreadsheet") || mime.contains("presentation") || mime == "application/vnd.ms-powerpoint" ||
                mime.startsWith("application/vnd.oasis.opendocument") ||
                ext in DOC_EXT
            ARCHIVES -> mime in ARCHIVE_MIME || mime.endsWith("+zip") || ext in ARCHIVE_EXT
            else -> true
        }
    }

    private companion object {
        val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "md", "rtf", "csv")
        val ARCHIVE_MIME = setOf("application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
            "application/x-rar-compressed", "application/vnd.rar", "application/x-tar", "application/gzip",
            "application/x-gzip", "application/x-bzip2", "application/x-xz")
        val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
    }
}

/** Items modified within the last N days; items with unknown date (0) never match a date filter. */
enum class DateFilter(val label: String, val days: Int) {
    ANY("Any time", 0), DAY("Past 24 hours", 1), WEEK("Past week", 7), MONTH("Past month", 30), YEAR("Past year", 365);

    fun matches(item: FileItem, now: Long): Boolean =
        this == ANY || (item.modified > 0 && item.modified >= now - days * 86_400_000L)
}

/** Files only (a folder's size is not meaningful); a 0-byte file is a real size. */
enum class SizeFilter(val label: String, val min: Long, val max: Long) {
    ANY("Any size", 0, Long.MAX_VALUE),
    SMALL("Under 1 MB", 0, (1L shl 20) - 1),
    MEDIUM("1–100 MB", 1L shl 20, 100L * (1L shl 20)),
    LARGE("Over 100 MB", 100L * (1L shl 20) + 1, Long.MAX_VALUE);

    fun matches(item: FileItem): Boolean =
        this == ANY || (!item.isDirectory && item.size in min..max)
}

data class SearchFilters(
    val type: TypeFilter = TypeFilter.ANY,
    val date: DateFilter = DateFilter.ANY,
    val size: SizeFilter = SizeFilter.ANY,
) {
    val active: Boolean get() = type != TypeFilter.ANY || date != DateFilter.ANY || size != SizeFilter.ANY
    fun matches(item: FileItem, now: Long = System.currentTimeMillis()): Boolean =
        type.matches(item) && date.matches(item, now) && size.matches(item)
}
