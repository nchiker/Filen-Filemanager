plugins { id("com.android.library") }
android {
    namespace = "app.filenfiles.filen"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":core"))
    // UniFFI's generated Kotlin uses JNA to load the Rust library
    api("net.java.dev.jna:jna:5.19.1@aar")
    implementation("androidx.security:security-crypto:1.1.0")
}
