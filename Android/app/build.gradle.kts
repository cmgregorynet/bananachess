plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from Gradle properties or the environment, never from the repo. Without
// all four the release build is produced unsigned.
val releaseSigning =
    listOf("BANANA_KEYSTORE", "BANANA_KEYSTORE_PASSWORD", "BANANA_KEY_ALIAS", "BANANA_KEY_PASSWORD")
        .associateWith { name -> providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull }
        .takeIf { values -> values.values.all { !it.isNullOrBlank() } }
        ?.mapValues { (_, value) -> checkNotNull(value) }

android {
    namespace = "com.charlesbarton.bananachess"
    compileSdk {
        version =
            release(libs.versions.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
            }
    }
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.charlesbarton.bananachess"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // The same as iOS: marketing version 0.1, build 1.
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("BANANA_KEYSTORE"))
                storePassword = releaseSigning.getValue("BANANA_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("BANANA_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("BANANA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Activities launched by Compose UI tests need this manifest entry in the debug build.
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
