plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


android {
    namespace = "dev.deepagent.mobile"
    compileSdk = 35


    defaultConfig {
        applicationId = "dev.deepagent.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
