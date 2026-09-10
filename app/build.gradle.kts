plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Apply the Firebase plugin only when the (non-committed) google-services.json
// is present in the working tree — CI injects it from a repo secret
// (GOOGLE_SERVICES_JSON, base64) so FCM builds work without committing keys.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "cz.digitalnivedomi.diarium"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.digitalnivedomi.diarium"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0.0"

        // Supabase project — the native app talks to PostgREST/Edge Functions
        // directly with the user's JWT. The anon key is public by design.
        buildConfigField("String", "SUPABASE_URL", "\"https://vmqbslghzgfotwhzgawa.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZtcWJzbGdoemdmb3R3aHpnYXdhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEyNjg1ODQsImV4cCI6MjA5Njg0NDU4NH0.t6MyER-5umBKDaHYFHmzfWXBM6VjT9mSsRFlpQJ3gGk\"")
        // OAuth deep link: diarium://auth-callback#access_token=...
        buildConfigField("String", "AUTH_SCHEME", "\"diarium\"")
        buildConfigField("String", "AUTH_HOST", "\"auth-callback\"")
        // Supabase project ref — used for the sb-<ref>-auth-token storage key.
        buildConfigField("String", "SUPABASE_REF", "\"vmqbslghzgfotwhzgawa\"")
        // Deep link the app itself handles for "open check-in" (notification taps).
        buildConfigField("String", "DEEP_LINK_CHECKIN", "\"diarium://open/checkin\"")
        // Legacy web endpoints, still called by the recycled background workers
        // (usage-stats sync and push-token registration). Both are removed in M6,
        // when those workers talk to Supabase directly.
        buildConfigField("String", "DIARIUM_URL", "\"https://diarium-two.vercel.app\"")
        buildConfigField("String", "SAVE_ENTRY_URL", "\"https://diarium-two.vercel.app/api/save-entry\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
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
    testOptions {
        // Robolectric needs real resources to inflate the theme in unit tests.
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
        }
    }
    lint {
        // Fail the build on real errors, but don't block on style nags.
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Compose (BOM keeps every artifact on one version)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Networking (REST) + JSON
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Photos
    implementation(libs.coil.compose)

    // Firebase Cloud Messaging (recycled from the wrapper)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Local unit tests (JVM). Robolectric lets us render Compose off-device.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test)
    testImplementation(libs.androidx.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
}
