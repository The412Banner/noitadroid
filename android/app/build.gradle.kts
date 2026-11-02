// The APK around the native game: SDL2's Java glue (from .local-deps/src/SDL2, tools/android/deps.sh) and the
// native libraries tools/android/ stages into build/android/jniLibs. FMOD's fmod.jar is the user's own download
// (.local-deps/fmod), so it is picked up when present. The launcher icon is made from the builder's copy of the game
// (tools/android/build_game.py: build/android/res); without it the app has Android's default icon.
plugins {
    id("com.android.application")
}

val repo = rootDir.parentFile
val iconRes = repo.resolve("build/android/res")

android {
    namespace = "io.github.zhouwuqi.noitadroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zhouwuqi.noitadroid"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["appIcon"] =
            if (iconRes.resolve("mipmap-anydpi-v26/ic_launcher.xml").exists()) "@mipmap/ic_launcher"
            else "@android:drawable/sym_def_app_icon"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")  // a personal sideloaded build
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir(repo.resolve(".local-deps/src/SDL2/android-project/app/src/main/java"))
            jniLibs.srcDir(repo.resolve("build/android/jniLibs"))
            res.srcDir(iconRes)
            assets.srcDir(repo.resolve("build/android/assets"))  // noitadroid.properties: the game build's facts
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true  // extracted: runtime/fmod.c opens FMOD's libraries by path
    }
}

dependencies {
    implementation(fileTree(repo.resolve(".local-deps/fmod/api/core/lib")) { include("fmod.jar") })
}
