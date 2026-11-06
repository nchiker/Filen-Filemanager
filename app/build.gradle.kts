import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.filenfiles"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.filenfiles"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = false } }
    // Release signing key lives OUTSIDE the repo (never commit it). Override the location with FILEN_KEYSTORE_PROPS.
    // Without it, release builds fall back to the debug key (installable for testing, not for publishing).
    val ksProps = Properties().apply {
        val f = file(System.getenv("FILEN_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/APK-Keys/filen/keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (ksProps.isNotEmpty()) create("release") {
            storeFile = file(ksProps.getProperty("storeFile")); storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias"); keyPassword = ksProps.getProperty("keyPassword")
        }
    }
    lint { checkReleaseBuilds = false } // lint's release classpath needs network access; CI/dev runs lint separately
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true      // R8 code shrinking + obfuscation; keep rules in proguard-rules.pro
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":filen"))
    implementation(project(":local"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
}
