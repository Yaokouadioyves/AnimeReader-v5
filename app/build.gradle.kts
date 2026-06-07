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

android {
    namespace = "com.animereader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.animereader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "5.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.translate)
}
