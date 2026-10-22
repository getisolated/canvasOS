plugins {
    id("com.android.application")
}

android {
    namespace = "com.getisolated.canvasos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.getisolated.canvasos"
        minSdk = 28
        targetSdk = 31
        versionCode = 8
        versionName = "1.0.0"
    }

    // standard declares itself a home screen. guard does not: TCL's package verifier refuses any
    // APK that declares HOME unless TCL signed it, so there canvasOS is opened as an app and kept in
    // front by the home guard (Guard.kt).
    flavorDimensions += "home"
    productFlavors {
        create("standard") { dimension = "home" }
        create("guard") { dimension = "home" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // Sideloaded onto one TV, never published to Play.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Local unit tests only see android.jar's stubs, where org.json throws; this is the same API, for tests.
    testImplementation("org.json:json:20240303")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("dev.mobile:dadb:2.0.0")
    // Apple TV aerial screensaver.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    // The page a phone opens from the QR code (wallpapers, uploads).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
