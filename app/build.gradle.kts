plugins {
    id("com.android.application") version "8.6.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

android {
    namespace = "com.davidsm.milkfilter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.davidsm.milkfilter"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // Release signing reads from ~/.gradle/gradle.properties (machine-local, never committed) so
    // the same key is reused across every release build on this machine. Falls back to unsigned
    // if those properties aren't set (e.g. a fresh checkout), rather than failing the build.
    val releaseStoreFile = project.findProperty("MILKFILTER_RELEASE_STORE_FILE") as String?
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = project.findProperty("MILKFILTER_RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("MILKFILTER_RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("MILKFILTER_RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
