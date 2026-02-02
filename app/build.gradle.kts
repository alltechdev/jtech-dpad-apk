plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.android.cts.jtech"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.android.cts.jtech"
        minSdk = 14
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("jtechforums")
            storePassword = "jtechforums"
            keyAlias = "jtechforums"
            keyPassword = "jtechforums"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.multidex:multidex:2.0.1")
}
