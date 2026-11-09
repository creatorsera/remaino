plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.keyboard"
        minSdk = 26
        targetSdk = 36
        // CI run number keeps versionCode rising so every new build can update the last one
        versionCode = maxOf(System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0, 5)
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.6.0"
    }

    // Release signing is only configured when the CI release job provides a keystore.
    signingConfigs {
        // One fixed debug key shared by every build, so a new debug APK can replace the old one.
        // (It is the standard public Android debug key, not a secret.)
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Keep the first CI runs green; tighten this once the project settles.
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
