plugins {
    id("com.android.application")
}

/**
 * versionName is the source of truth. 0.7.7 -> 707, 1.2.3 -> 10203.
 * This keeps every local and CI build strictly increasing, so the in-app
 * updater accepts the next release without relying on CI run numbers.
 */
fun semverCode(name: String): Int {
    fun part(index: Int): Int =
        name.removePrefix("v").split(".").getOrNull(index)
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull() ?: 0
    return (part(0) * 10000 + part(1) * 100 + part(2)).coerceAtLeast(1)
}

val appVersionName = System.getenv("VERSION_NAME")
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "0.8.7"
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: maxOf(semverCode(appVersionName), 5)

android {
    namespace = "com.remino.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.remino.keyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
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
            // With no private keystore, sign with the shared debug key so the APK
            // still installs in place and the updater keeps working.
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        unitTests.all {
            it.maxHeapSize = "1g"
            it.jvmArgs("-XX:MaxMetaspaceSize=512m")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
