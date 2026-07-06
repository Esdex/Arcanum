import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

android {
    namespace = "zip.arcanum"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "zip.arcanum"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fexceptions",
                    "-frtti",
                    "-O2",
                    "-DNDEBUG"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-29",
                    "-DCMAKE_C_FLAGS=-ffile-prefix-map=${projectDir}=.",
                    "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=${projectDir}=."
                )
            }
        }
        ndk {
            // x86_64 has pre-existing SSE2/asm build issues in the VeraCrypt native layer.
            // No production Android device uses x86_64 — modern x86 emulators run
            // arm64-v8a via translation. Add "x86_64" here if emulator builds are needed.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile     = localProps["KEYSTORE_PATH"]?.let { rootProject.file(it as String) }
            storePassword = localProps["KEYSTORE_PASSWORD"] as String?
            keyAlias      = localProps["KEY_ALIAS"] as String?
            keyPassword   = localProps["KEY_PASSWORD"] as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("Boolean", "IS_FDROID", "true")
            buildConfigField("Boolean", "IS_PLAYSTORE", "false")
            buildConfigField("Boolean", "HAS_BILLING", "false")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("Boolean", "IS_FDROID", "false")
            buildConfigField("Boolean", "IS_PLAYSTORE", "true")
            buildConfigField("Boolean", "HAS_BILLING", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Only rename F-Droid APKs — Play Store artifacts go to Google Play Console as AAB
androidComponents {
    onVariants { variant ->
        if (variant.flavorName != "fdroid") return@onVariants
        val buildType = variant.buildType ?: return@onVariants
        val versionName = android.defaultConfig.versionName ?: "1.0.0"
        val suffix = if (buildType == "release") "" else "-$buildType"
        variant.outputs.forEach { output ->
            output.outputFileName.set("Arcanum-v$versionName-fdroid$suffix.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.biometric)
    implementation(libs.bouncycastle.provider)
    implementation(libs.sqlcipher)

    // Media
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // UI libraries
    implementation(libs.haze)
    implementation(libs.lottie.compose)
    implementation(libs.aboutlibraries.compose.m3)

    // EXIF reading
    implementation(libs.metadata.extractor)

    // Palette (dominant color extraction for audio player)
    implementation(libs.androidx.palette)

    // Play Billing — playstore flavor only (F-Droid must not include this)
    "playstoreImplementation"(libs.billing.ktx)

    // Image processing (Sharpness + Denoise for photo editor)
    implementation(libs.aire)

    // Utilities
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
