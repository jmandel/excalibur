plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.exe.kindleconverter"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.exe.kindleconverter"
        minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-fexceptions") } }
        vectorDrawables { useSupportLibrary = true }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // Release signing comes from a keystore provided via env (a GitHub Actions secret,
    // decoded to a file). When it's absent (local dev / PRs), release falls back to the
    // debug signing config so the build still produces an installable APK.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Compress native libs in the APK (libwasmtime.so is ~20MB, stored uncompressed
        // by default). They're extracted at install, so this shrinks the download by
        // ~13MB with no runtime cost.
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material3:material3")
    // material-icons-core (the small common set) comes transitively via material3; we
    // deliberately do NOT pull material-icons-extended (thousands of icons, ~tens of MB
    // of dex unshrunk in a debug build). The few icons not in core are defined locally.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.zxing:core:3.5.3") // QR generation (R8 strips the unused decoder)

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
