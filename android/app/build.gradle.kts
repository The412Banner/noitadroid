// The APK around the native game: SDL2's Java glue (from .local-deps/src/SDL2, tools/android/deps.sh) and the
// native libraries tools/android/ stages into build/android/jniLibs. FMOD's fmod.jar is the user's own download
// (.local-deps/fmod), so it is picked up when present. The launcher icon is made from the builder's copy of the game
// (tools/android/build_game.py: build/android/res); without it the app has Android's default icon.
//
// -Pnoitadroid.public builds the APK anyone can have (tools/android/build_app.py): from build/android-app, with the
// toolchain that builds the game on the device, and nothing from the game or FMOD. Its icon is the app's own
// (tools/android/app_icon.py). A release build is signed with the key in .local-deps/keys/release.properties when
// there is one (the published APKs' key: keep it, or the app can't be updated), else with the debug key.
import java.util.Properties

plugins {
    id("com.android.application")
}

val repo = rootDir.parentFile
val public = providers.gradleProperty("noitadroid.public").isPresent
val stage = repo.resolve(if (public) "build/android-app" else "build/android")
val iconRes = stage.resolve("res")
val releaseKey = repo.resolve(".local-deps/keys/release.properties")

android {
    namespace = "io.github.zhouwuqi.noitadroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zhouwuqi.noitadroid"
        minSdk = 30  // all-files access (MANAGE_EXTERNAL_STORAGE), where the game and saves live
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["appIcon"] =
            if (!public && iconRes.resolve("mipmap-anydpi-v26/ic_launcher.xml").exists()) "@mipmap/ic_launcher"
            else "@mipmap/ic_app"
    }

    signingConfigs {
        if (releaseKey.exists()) {
            create("release") {
                val p = Properties().apply { releaseKey.inputStream().use { load(it) } }
                storeFile = releaseKey.parentFile.resolve(p.getProperty("storeFile"))
                storePassword = p.getProperty("password")
                keyAlias = p.getProperty("alias")
                keyPassword = p.getProperty("password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // FmodImport and AppFactory reach classes by name
            signingConfig = signingConfigs.getByName(if (releaseKey.exists()) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir(repo.resolve(".local-deps/src/SDL2/android-project/app/src/main/java"))
            jniLibs.srcDir(stage.resolve("jniLibs"))
            res.srcDir(iconRes)
            assets.srcDir(stage.resolve("assets"))  // noitadroid.properties: the game build's facts
        }
    }

    packaging {
        // Extracted to the native library directory: runtime/fmod.c opens FMOD's libraries by path, and the
        // toolchain's programs (libnd_*.so) can only run from there.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    if (!public) implementation(fileTree(repo.resolve(".local-deps/fmod/api/core/lib")) { include("fmod.jar") })
}
