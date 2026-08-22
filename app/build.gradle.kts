import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing. The upload key is supplied either via a local, untracked
// `keystore.properties` (developer machines — see keystore.properties.example)
// or via environment variables (CI). Neither the keystore nor its passwords are
// ever committed. When no upload key is configured we fall back to the debug
// key so local and CI APK builds still produce an installable artifact — but a
// Play-Store-bound AAB MUST be built with the real upload key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("storeFile", "KEYSTORE_FILE")
val hasReleaseSigning: Boolean = releaseStoreFile != null

// Single source of truth for the app version. `VERSION_NAME` lives in
// gradle.properties and is bumped automatically by semantic-release on each
// release. versionCode is derived from the semver so it always increases.
val appVersionName: String = (project.findProperty("VERSION_NAME") as String?) ?: "0.0.0"
// Bands are 1000-wide (minor, patch each < 1000) so a long-lived minor cycle
// can run to patch 999, and minor to 999, without ever colliding with or
// regressing below the next component — Play rejects a non-increasing
// versionCode. 1.10.0 -> 1_010_000; previous 1.9.0 -> 1_009_000 (both larger
// than the old major*10000+minor*100+patch scheme, so the sequence keeps rising).
val appVersionCode: Int = appVersionName.substringBefore("-").split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "ovh.battistella.ondes"
    compileSdk = 36

    defaultConfig {
        applicationId = "ovh.battistella.ondes"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the dedicated upload key when configured (keystore.properties
            // or CI env vars); otherwise fall back to the debug key so local
            // and CI APK builds still produce an installable artifact.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Material 3 Expressive APIs (motion scheme, wavy progress, loading
        // indicators) are still annotated experimental in material3 1.4.x.
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
    }
    androidResources {
        // Generate a locales_config.xml from the values-* folders so the new
        // languages appear in Android 13+'s per-app language picker. The default
        // (unqualified) resources are English — declared in res/resources.properties.
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // The exported Room schemas are put on the unit-test classpath (AGP does
        // not merge src/test/assets) so MigrationTest can build a database at an
        // older version and run the real migration against it. Instrumented tests
        // read the same files from assets, where MigrationTestHelper expects them.
        getByName("test").resources.srcDir(layout.projectDirectory.dir("schemas"))
        getByName("androidTest").assets.srcDir(layout.projectDirectory.dir("schemas"))
    }

    testOptions {
        unitTests {
            // Robolectric + Compose UI tests need the merged Android resources and
            // manifest available to the JVM (host) test runtime.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Export a JSON schema per database version into app/schemas. Checked in, they
// are the reference MigrationTest validates each migration against (issue P1-10).
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
