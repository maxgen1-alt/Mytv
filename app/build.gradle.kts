import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Trakt credentials (for the Continue Watching row) come from local.properties
// or environment variables so they never get committed.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

android {
    namespace = "com.mytv.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mytv.launcher"
        minSdk = 28
        targetSdk = 35
        // Every CI run gets a higher number, which is what the auto-updater compares.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"

        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${secret("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${secret("TRAKT_CLIENT_SECRET")}\"")
        // Free key from themoviedb.org, for the Top 10 above the apps row.
        buildConfigField("String", "TMDB_API_KEY", "\"${secret("TMDB_API_KEY")}\"")
        // Free key from omdbapi.com, for IMDb ratings (falls back to TMDB's score without it).
        buildConfigField("String", "OMDB_API_KEY", "\"${secret("OMDB_API_KEY")}\"")
        // Your own Supabase project, to keep ratings in sync between TVs (optional).
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${secret("SUPABASE_KEY")}\"")
        buildConfigField("String", "SYNC_HOUSEHOLD", "\"${secret("SYNC_HOUSEHOLD")}\"")
        // Where the auto-updater looks for new releases.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "maxgen1-alt/Mytv"
        buildConfigField("String", "UPDATE_REPO", "\"$repo\"")
    }

    // One fixed key for every build: Android only accepts an update signed
    // with the same key as the installed app. CI writes it from the
    // SIGNING_KEYSTORE_BASE64 secret; without it we fall back to the debug key.
    val keystore = rootProject.file("signing/mytv.keystore")
    signingConfigs {
        create("mytv") {
            storeFile = keystore
            storePassword = "mytvlauncher"
            keyAlias = "mytv"
            keyPassword = "mytvlauncher"
        }
    }

    buildTypes {
        release {
            // R8 optimizes Compose a lot (smaller, faster code); our code uses no reflection.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystore.exists()) "mytv" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// java.time dates and Kotlin lists in our models are never mutated: tell Compose
// they're stable, so rows skip redrawing when their data hasn't changed.
composeCompiler {
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose_stability.conf")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Installs the baseline profiles Compose ships with, so the TV pre-compiles the
    // hot UI code instead of interpreting it: much smoother scrolling and startup.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is stubbed in local unit tests; use the real one.
    testImplementation("org.json:json:20240303")
}
