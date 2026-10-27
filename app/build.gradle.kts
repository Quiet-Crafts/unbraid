import java.security.MessageDigest

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signing = Properties().apply {
    rootProject.file(".secrets/signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    // Android classes in JVM unit tests (Log, for one) do nothing instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "app.unbraid"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.unbraid"
        minSdk = 33
        targetSdk = 37
        versionCode = 47
        versionName = "2.6"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("release") {
            if (signing.isNotEmpty()) {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // play = Google Play, with an in-app tip jar through Play Billing (a proprietary library F-Droid won't ship);
    // foss = GitHub and F-Droid, no Google Play Billing (no tip jar). Everything else is the same app.
    flavorDimensions += "store"
    productFlavors {
        create("foss") { dimension = "store" }
        create("play") { dimension = "store" }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        // Build metadata nothing reads at runtime.
        resources.excludes += setOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/*.version")
    }
    // The UI is English only: drop library translations nobody sees.
    androidResources { localeFilters += "en" }
    // Play gets the dependency list in the bundle; the APK (GitHub/F-Droid) stays free of the signing-block extra.
    dependenciesInfo { includeInApk = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// The engine is built locally (tools/build-libbox.sh), so the build accepts only the libbox.aar whose SHA-256 is
// committed beside it: a swapped or stale AAR fails here instead of shipping.
val verifyLibbox by tasks.registering {
    val aar = layout.projectDirectory.file("libs/libbox.aar").asFile // plain files: the configuration cache keeps them
    val sum = layout.projectDirectory.file("libs/libbox.aar.sha256").asFile
    inputs.files(aar, sum)
    doLast {
        val want = sum.readText().trim().substringBefore(' ')
        val got = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        check(got == want) { "app/libs/libbox.aar doesn't match libbox.aar.sha256: rebuild it with tools/build-libbox.sh (which writes the checksum)" }
    }
}
tasks.named("preBuild") { dependsOn(verifyLibbox) }

dependencies {
    implementation(files("libs/libbox.aar"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Reads QR codes from a photo or a picture (an IVPN account ID); no camera permission, no Play services.
    implementation("com.google.zxing:core:3.5.3")
    "playImplementation"("com.android.billingclient:billing-ktx:9.1.0")
    // Billing pulls Fragment 1.1.0, whose activity-result handling predates the launchers Unbraid uses.
    "playImplementation"("androidx.fragment:fragment:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
