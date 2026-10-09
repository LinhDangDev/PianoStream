// app module build file (Phase 1a frozen path; edits require a plan amendment).
// F1/F2: updater authenticity anchors + registry host allow-list are baked into
// BuildConfig here so no later phase ever edits Gradle (D8). The release
// keystore (release.jks) and the minisign update key pair were generated during
// Phase-1a scaffold; keystore.properties is git-ignored.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.pianostream"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pianostream"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // F2/U3 — community player-config registry host allow-list
        // (raw.githubusercontent.com: SimpMusic maxrave-dev/simpmusic-files mirror;
        //  cdn.jsdelivr.net + github.com/objects.githubusercontent.com:
        //  Faraday MetrolistGroup/faraday releases).
        buildConfigField("String", "REGISTRY_HOSTS", "\"raw.githubusercontent.com,cdn.jsdelivr.net,github.com,objects.githubusercontent.com\"")
        // F1 — updater authenticity anchors: signed-APK asset name on GitHub
        // Releases, release signing-cert SHA-256 fingerprint, minisign public key.
        buildConfigField("String", "UPDATE_REPO", "\"LinhDangDev/PianoStream\"")
        buildConfigField("String", "UPDATE_ASSET", "\"PianoStream-release.apk\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"e61c728a93f6e6f6cf8adb2143dd88c2c4e133a1abd9d572549cb22a47adafa3\"")
        buildConfigField("String", "UPDATE_PUBKEY", "\"RWSRAFOWN9GQ1KhzkU28NWyralAntsty9EoG80//t81GXrgozB8hRDvt\"")
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    flavorDimensions += listOf("dist", "source")
    productFlavors {
        // dist: github = full updater surface (Phase 5b); foss = no updater at all,
        // not even manifest entries (D9).
        create("github") { dimension = "dist" }
        create("foss") { dimension = "dist" }
        // source: stub = Phase-4 fakes seam; real = compile gate from day 1 (D3).
        create("stub") { dimension = "source" }
        create("real") { dimension = "source" }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:local"))
    implementation(project(":core:youtube"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.session)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.junit.ext)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
