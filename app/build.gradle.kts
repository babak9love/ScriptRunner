plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scriptrunner.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scriptrunner.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the auto-generated debug key so the APK is installable directly.
            // For Play Store / real distribution, replace with your own keystore.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = false
        viewBinding = false
    }

    // Strip build metadata that only Google Play uses
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/com/android/build/gradle/app-metadata.properties",
                "/META-INF/{AL2.0,LGPL2.1}",
                "/kotlin/**",
                "/DebugProbesKt.bin",
                "/kotlin-tooling-metadata.json"
            )
        }
    }
}

// No androidx / material / constraintlayout / webkit: the app only uses
// framework classes (Activity, WebView, Notification), so nothing is needed here.
dependencies {
}
