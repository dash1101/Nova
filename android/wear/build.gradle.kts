import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Same signing key as the phone app (a Wear app and its phone app share the package name and key).
val signingProps = Properties().apply {
    val f = rootProject.file("signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.novalabs.nova.wear"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "app.novalabs.nova"
        minSdk = 30                       // Wear OS 3+
        targetSdk = 36
        versionCode = 32
        versionName = "0.5.14-alpha"
    }

    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
}
