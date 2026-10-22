import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key. Every Release must carry the same signature, or `adb install -r` refuses to
// update in place and the user loses the app's settings and grants. CI hands the project's key over
// in CANVASOS_* environment variables; a maintainer building locally keeps the same four values in
// keystore.properties at the repo root (gitignored; tools/make-keystore.sh writes both). With
// neither, release builds are signed with the debug key so forks and pull requests still build,
// but that APK cannot update an install made from a Release.

// Each of the four values has one name per source: the same word in keystore.properties, an
// environment variable in CI.
class SigningSource(val label: String, names: Map<String, String>, read: (String) -> String?) {
    val values = names.mapValues { read(it.value).orEmpty() }
    val missing = names.filterKeys { values.getValue(it).isEmpty() }.values
    val complete get() = missing.isEmpty()
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val keystoreProperties = Properties().also { props ->
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { props.load(it) }
}
val signingSources = listOf(
    SigningSource(
        "the environment variables",
        mapOf(
            "storeFile" to "CANVASOS_KEYSTORE_FILE",
            "storePassword" to "CANVASOS_KEYSTORE_PASSWORD",
            "keyAlias" to "CANVASOS_KEY_ALIAS",
            "keyPassword" to "CANVASOS_KEY_PASSWORD",
        ),
    ) { System.getenv(it) },
    SigningSource("keystore.properties", signingKeys.associateWith { it }) { keystoreProperties.getProperty(it) },
)
// The first source that has all four values wins. One that has only some of them is almost
// certainly a mistake, so say which are missing rather than silently moving on.
val releaseKey = signingSources.firstOrNull { it.complete }?.values
if (releaseKey == null) {
    signingSources.filter { it.missing.size in 1 until signingKeys.size }.forEach {
        logger.warn("canvasOS: incomplete signing values in ${it.label} (missing ${it.missing.joinToString()}), ignored.")
    }
    logger.warn(
        "canvasOS: no release signing key (set the CANVASOS_KEYSTORE_FILE, CANVASOS_KEYSTORE_PASSWORD, " +
            "CANVASOS_KEY_ALIAS and CANVASOS_KEY_PASSWORD environment variables, or create keystore.properties; " +
            "see tools/make-keystore.sh). Release builds fall back to the debug key and cannot update " +
            "an install made from a Release."
    )
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

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                // rootProject.file() keeps absolute paths and resolves relative ones from the repo root.
                storeFile = rootProject.file(releaseKey.getValue("storeFile"))
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
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
