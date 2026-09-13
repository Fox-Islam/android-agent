plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.foxislam.androidagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foxislam.androidagent"
        // takeScreenshot() is API 30
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "1.1.0"
    }

    signingConfigs {
        // Sideload-only: an accessibility-driven automation app is not Play-distributable,
        // so there is no upload key to protect. CI supplies the keystore from repo secrets;
        // locally it falls back to a git-ignored sideload.jks, and a clone with neither just
        // produces an unsigned release. To make your own:
        //   keytool -genkeypair -keystore sideload.jks -alias agent -keyalg RSA \
        //     -keysize 2048 -validity 10000 -storepass sideload -keypass sideload
        val keystore = System.getenv("SIGNING_KEYSTORE_PATH")
            ?.takeIf { it.isNotBlank() }
            ?.let(::file)
            ?: rootProject.file("sideload.jks")

        if (keystore.exists()) {
            create("sideload") {
                storeFile = keystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "sideload"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "agent"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "sideload"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.findByName("sideload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        // The diagnostics report names the build it came from, which is the first thing
        // anyone reading a bug report needs
        buildConfig = true
    }

    testOptions {
        // The pure decision logic - hooks, risk, compaction - is worth testing on the JVM,
        // and all of it logs. Without this every one of those tests dies inside android.util.Log
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // lintVital runs on every assembleRelease and is the single slowest task in the
        // build. This is a sideload app with no Play submission to gate, so run lint when
        // you want it - `./gradlew :app:lint` - instead of on every release build
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

// Expanding the baseline profiles that AndroidX ships costs ~30s of every release build,
// to shave a little off cold start. Not a trade worth making for a sideloaded tool; the
// property that used to control this was removed in AGP 8, so disable the tasks directly
tasks.matching { it.name.contains("ArtProfile") }.configureEach { enabled = false }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.luaj)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(kotlin("test"))
}
