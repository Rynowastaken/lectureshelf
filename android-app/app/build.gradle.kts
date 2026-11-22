import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ryno.classrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ryno.classrecorder"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank() && File(keystorePath).exists()) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("KEY_PASSWORD").orEmpty()
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("classrecorder") {
            dimension = "channel"
            isDefault = true
        }
        create("classrecorderTest") {
            dimension = "channel"
            applicationIdSuffix = ".uitest"
            versionNameSuffix = "-uitest"
            resValue("string", "app_name", "LectureShelf UI Test")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
    }

    lint {
        // Workaround for androidx.lifecycle 2.8.x NonNullableMutableLiveDataDetector K2 UAST binary mismatch on AGP 8.7.x
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
