import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key lives outside the source tree (see ~/nova-app/signing/README).
val signingProps = Properties().apply {
    val f = rootProject.file("signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.novalabs.nova"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "app.novalabs.nova"
        minSdk = 31
        targetSdk = 36
        versionCode = 28
        versionName = "0.5.10-alpha"
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
            // Phone builds: -Pabi=arm64-v8a keeps the APK small enough to share. Default: all ABIs (emulator QA).
            (project.findProperty("abi") as String?)?.let { ndk { abiFilters += it.split(",") } }
        }
    }

    packaging { jniLibs { useLegacyPackaging = true } }   // compress native libs (barcode scanner)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")          // bundled model: no Play-services download
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.github.mwiede:jsch:0.2.25")                     // SSH client for the Terminal (maintained JSch fork)
    implementation("com.google.zxing:core:3.5.3")                       // QR codes for inviting phones
    implementation("dev.chrisbanes.haze:haze:1.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")          // real org.json on the JVM (Android stubs it)                    // frosted-glass backdrop blur
}
