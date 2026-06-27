plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "dev.exe.kindleconverter"; compileSdk = 35
    defaultConfig { applicationId = "dev.exe.kindleconverter"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:wasi:1.7.5")
}
