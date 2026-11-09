plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// MAJOR.MINOR: raise the minor for a new feature, the major for a big change. CI passes VERSION_NAME = X.Y.Z
// (Z counts the releases of X.Y, see .github/workflows/release.yml); local builds stay at X.Y with versionCode 1.
val baseVersionName = "1.2"
val ciVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
val ciVersionParts = ciVersionName?.let { name ->
    Regex("""(\d{1,2})\.(\d{1,3})\.(\d{1,4})""").matchEntire(name)?.groupValues?.drop(1)?.map(String::toInt)
        ?: error("VERSION_NAME must be X.Y.Z, not $name")
}

// Release signing comes from the environment (see .github/workflows/release.yml).
// Without it, release builds fall back to the debug key so local builds keep working.
val releaseKeystore = System.getenv("KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }

android {
    namespace = "com.personal.portraitguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.portraitguard"
        minSdk = 26
        targetSdk = 35
        // Same formula as AppUpdater.versionCodeOf, above the earlier CI builds (versionCode = run number)
        versionCode = ciVersionParts?.let { (major, minor, patch) -> major * 10_000_000 + minor * 10_000 + patch } ?: 1
        versionName = ciVersionName ?: baseVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
