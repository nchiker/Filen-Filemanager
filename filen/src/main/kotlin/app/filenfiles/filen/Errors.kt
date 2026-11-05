package app.filenfiles.filen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import uniffi.filen_sdk_rs.ErrorKind
import uniffi.filen_sdk_rs.FilenSdkException
import java.io.IOException

/** What a failure means for the user, independent of which layer produced it. */
enum class FailureKind { AUTH, NETWORK, DISK_FULL, QUOTA, OTHER }

fun Throwable.failureKind(): FailureKind {
    if (this is CancellationException) return FailureKind.OTHER
    if (this is FilenSdkException) {
        val inner = runCatching { innerMessage() }.getOrNull().orEmpty() + " " + runCatching { message() }.getOrNull().orEmpty()
        return when {
            runCatching { kind() }.getOrNull() == ErrorKind.UNAUTHENTICATED -> FailureKind.AUTH
            runCatching { serverCode() }.getOrNull() == "max_storage_reached" -> FailureKind.QUOTA
            inner.contains("No space left", ignoreCase = true) -> FailureKind.DISK_FULL
            runCatching { kind() }.getOrNull().let { it == ErrorKind.REQWEST || it == ErrorKind.RETRY_FAILED } -> FailureKind.NETWORK
            else -> FailureKind.OTHER
        }
    }
    return when {
        message?.contains("No space left", ignoreCase = true) == true || message?.contains("ENOSPC") == true -> FailureKind.DISK_FULL
        this is java.net.UnknownHostException || this is java.net.SocketException || this is java.net.SocketTimeoutException ||
            this is javax.net.ssl.SSLException -> FailureKind.NETWORK
        this is IOException && cause?.failureKind() == FailureKind.NETWORK -> FailureKind.NETWORK
        else -> FailureKind.OTHER
    }
}

/** User-facing sentence for failures with a known cause, or null to fall back to the raw message. */
fun Throwable.friendlyOrNull(): String? = when (failureKind()) {
    FailureKind.AUTH -> "Your Filen session has expired. Sign in again."
    FailureKind.NETWORK -> "Can't reach Filen. Check your connection and try again."
    FailureKind.DISK_FULL -> "Your phone is out of storage space."
    FailureKind.QUOTA -> "Your Filen storage is full."
    FailureKind.OTHER -> null
}

/**
 * Runs [block], retrying transient network failures with a short backoff. Only for idempotent work
 * (listings, downloads, thumbnails) — uploads are retried by the transfer manager, which can check for duplicates.
 */
suspend fun <T> retryNetwork(attempts: Int = 3, block: suspend (attempt: Int) -> T): T {
    var last: Throwable? = null
    for (i in 0 until attempts) {
        try { return block(i) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) {
            if (e.failureKind() != FailureKind.NETWORK) throw e
            last = e
            if (i < attempts - 1) delay(1_000L shl i) // 1 s, 2 s
        }
    }
    throw last!!
}
