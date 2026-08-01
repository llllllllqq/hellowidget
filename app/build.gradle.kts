plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "moe.hellowidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "moe.hellowidget"
        minSdk = 21
        targetSdk = 34
        versionCode = 14
        versionName = "6.0"
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../hello-release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    // DataStore 官方原子写入（自定义 Serializer + CRC32）
    implementation("androidx.datastore:datastore-core:1.1.1")
    // lifecycleScope（生命周期感知的协程作用域）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
