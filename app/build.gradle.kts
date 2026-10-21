import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read optional online-provider API keys from local.properties (never committed).
// Leave them blank to run the app with offline-only features.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String): String = (localProps.getProperty(name) ?: "")

// CI build number → version (each build installs over the previous one), and the fixed release
// key from the CI secrets: every APK must carry the SAME signature or Android refuses to update.
val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.animereader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.animereader"
        minSdk = 26
        targetSdk = 34
        versionCode = ciBuild ?: 1
        versionName = "5.${ciBuild ?: 0}"

        // Phones only: the x86 / x86_64 / 32-bit copies of the ML Kit native libraries (emulators,
        // old devices) were ~85 MB of the APK.
        ndk { abiFilters += "arm64-v8a" }

        // Online providers read these at runtime (stubs today). Supply in local.properties:
        //   OPENAI_API_KEY=sk-...
        //   DEEPL_API_KEY=...
        //   GOOGLE_API_KEY=...
        //   ELEVENLABS_API_KEY=...
        buildConfigField("String", "OPENAI_API_KEY", "\"${key("OPENAI_API_KEY")}\"")
        buildConfigField("String", "DEEPL_API_KEY", "\"${key("DEEPL_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_API_KEY", "\"${key("GOOGLE_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${key("ELEVENLABS_API_KEY")}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) create("fixed") {
            storeFile = releaseKeystore
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No key (local build, PR from a fork): fall back to the debug key so it still installs.
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    // The Phonon model (assets/phonon, ~75 MB, fetched by CI) is copied out once at first use:
    // stored uncompressed it copies quickly and doesn't slow the build down.
    androidResources {
        noCompress += listOf("gguf", "safetensors")
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    implementation(libs.okhttp)
    // Calls libptts_ffi.so (offline Phonon voice, built by CI into src/main/jniLibs).
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar one is a stub that throws).
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
