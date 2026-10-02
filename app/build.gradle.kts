plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// `-Pvidgod.emulator` builds a single APK that also runs on x86/x86_64 emulators (used by the
// emulator test workflow). Normal builds ship phone ABIs only, split per ABI.
val emulatorBuild = providers.gradleProperty("vidgod.emulator").isPresent

android {
    namespace = "com.vidgod.editor"
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.vidgod.editor"
        minSdk = 26
        targetSdk = 36
        // Minutes since 2026-01-01: every newer build (local or CI) can update an older install.
        val buildMinute = (System.currentTimeMillis() / 60_000L - 29_453_760L).toInt()
        versionCode = buildMinute
        versionName = "1.0." + (System.getenv("VIDGOD_VERSION_CODE") ?: buildMinute.toString())
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // A fixed key so that every build (local or CI) can update the previous install.
            storeFile = rootProject.file("keystore/vidgod-release.jks")
            storePassword = "vidgod2026"
            keyAlias = "vidgod"
            keyPassword = "vidgod2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("release")
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += listOf("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Compressed native libraries keep the download small (ML Kit + Vosk are large).
            useLegacyPackaging = true
            // Phones only: drop emulator (x86) libraries.
            if (!emulatorBuild) excludes += listOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    splits {
        abi {
            isEnable = !emulatorBuild
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    implementation(libs.media3.common)
    implementation(libs.media3.common.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.effect.lottie)
    implementation(libs.media3.muxer)
    implementation(libs.media3.inspector)
    implementation(libs.media3.inspector.frame)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.lottie)

    implementation(libs.vosk.android)
    implementation(libs.mlkit.segmentation.selfie)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
