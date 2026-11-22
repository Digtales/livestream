plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.livecast"
    compileSdk = 34
    defaultConfig { applicationId = "com.livecast"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.5")
    implementation("androidx.core:core:1.13.1")
}
