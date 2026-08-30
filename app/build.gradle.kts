// Android Gradle configuration
// Build: ./gradlew assembleDebug  →  app/build/outputs/apk/debug/app-debug.apk
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Firebase Cloud Messaging (google-services.json is in app/)
    id("com.google.gms.google-services")
}

android {
    namespace = "cz.digitalnivedomi.diarium"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.digitalnivedomi.diarium"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // App runs the remote web app inside a WebView wrapper.
        buildConfigField("String", "DIARIUM_URL", "\"https://diarium-two.vercel.app\"")
        // Auth deep link
        buildConfigField("String", "AUTH_SCHEME", "\"diarium\"")
        buildConfigField("String", "AUTH_HOST", "\"auth-callback\"")
        // Supabase project ref (used for the localStorage session key name)
        buildConfigField("String", "SUPABASE_REF", "\"vmqbslghzgfotwhzgawa\"")
        buildConfigField("String", "SUPABASE_URL", "\"https://vmqbslghzgfotwhzgawa.supabase.co\"")
        buildConfigField("String", "SAVE_ENTRY_URL", "\"https://diarium-two.vercel.app/api/save-entry\"")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}