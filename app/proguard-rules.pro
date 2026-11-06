# --- Filen SDK bridge -------------------------------------------------------------------------------------------
# UniFFI's generated Kotlin talks to the Rust library through JNA, which finds Structure fields, callback
# interfaces and library methods by reflection / symbol name. Renaming or stripping any of it breaks the bridge
# at runtime (and only in release builds), so the whole binding package is kept as is.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# JNI: the SDK exports Java_io_filen_net_FilenTls_nativeInit, so this exact class and method name must survive.
-keep class io.filen.net.FilenTls { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep readable stack traces (mapping.txt is published with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink (behind EncryptedSharedPreferences): annotation-only classes that aren't on the Android classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# Tink's protobuf-lite messages are reached by reflection (field names); keep them.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }

# WorkManager (camera backup) builds its Room database and its workers by reflection at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
