plugins { id("com.android.library") }
android {
    namespace = "app.filenfiles.saf"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { api(project(":core")) }
