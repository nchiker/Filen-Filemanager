package io.filen.net

import android.content.Context

/**
 * The Filen Rust SDK checks TLS certificates through Android's platform trust manager and fails
 * every handshake closed until the app hands it the JVM. The native symbol is exported by the SDK as
 * `Java_io_filen_net_FilenTls_nativeInit`, so this class name/package is fixed by the library.
 */
object FilenTls {
    @Volatile private var ready = false

    @JvmStatic private external fun nativeInit(context: Context): Boolean

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        System.loadLibrary("filen_sdk_rs")
        ready = nativeInit(context.applicationContext)
        check(ready) { "Filen TLS verifier could not be initialised" }
    }
}
