import java.util.Properties

plugins {
    id("com.android.application")
}

// Framework Views only, like Plume. The only libraries are for playback: Media3 (ExoPlayer) and an ffmpeg audio decoder.
android {
    namespace = "dev.alder"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.albyjnr.cas"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "1.16.0"
        // TVs are ARM; the x86 builds of ffmpeg's native code would only add size.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. Without that file
    // (a fresh clone) release builds fall back to the debug key, so anyone can still build and sideload.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    lint {
        // Media3's "unstable API" opt-in warnings and gesture-back advice don't apply to a remote-driven TV app on a
        // pinned Media3 release; the rest of lint stays meaningful and fails the build on errors.
        disable += listOf("UnsafeOptInUsageError", "GestureBackNavigation", "SetTextI18n", "ObsoleteSdkInt")
        abortOnError = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // -PdebugKey: an optimised build that installs over a debug one on a test TV (same key, data kept).
            signingConfig = signingConfigs.findByName("release")?.takeUnless { project.hasProperty("debugKey") }
                ?: signingConfigs.getByName("debug")
        }
    }
}

// CHANGELOG.md goes into the app as an asset, so "What's new" shows the notes for the version installed, offline.
abstract class ChangelogAsset : DefaultTask() {
    @get:InputFile abstract val changelog: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun copy() {
        changelog.get().asFile.copyTo(outputDir.get().file("CHANGELOG.md").asFile, overwrite = true)
    }
}

val changelogAsset = tasks.register<ChangelogAsset>("changelogAsset") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAsset::outputDir) }
}

// Media3 stays on the version Jellyfin's ffmpeg decoder is built against: its native glue targets one exact release.
val media3 = "1.9.0"

dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3") // live and sports addons stream HLS
    implementation("androidx.media3:media3-ui:$media3")
    // Software audio for what TVs can't decode themselves (DTS, TrueHD), so those files still have sound.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // the real org.json for tests of Meta parsing (the SDK stub throws)
}
