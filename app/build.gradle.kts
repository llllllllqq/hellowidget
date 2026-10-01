plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 版本号单一来源 ----------
// CI 通过 -PversionCode / -PversionName 注入（见 .github/workflows/release.yml），
// 本地与 IDE 直接使用下面的默认值。tag、APK 元数据、Release 说明全部由它派生。
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 15
val appVersionName: String = (project.findProperty("versionName") as String?) ?: "7.0"

android {
    namespace = "moe.hellowidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.hellowidget"
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        viewBinding = true
    }

    /**
     * Release 签名：**仅**在 keystore 与三个口令都齐备时才启用。
     * 这样 fork PR / 普通贡献者在没有 secrets 的环境下仍然能构建出未签名的 release 包，
     * 而不是像以前那样必然失败。
     */
    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("hello-release.keystore")
            val storePwd = System.getenv("KEYSTORE_PASSWORD")
            val aliasName = System.getenv("KEY_ALIAS")
            val keyPwd = System.getenv("KEY_PASSWORD")
            if (ksFile.exists() && !storePwd.isNullOrEmpty() &&
                !aliasName.isNullOrEmpty() && !keyPwd.isNullOrEmpty()
            ) {
                storeFile = ksFile
                storePassword = storePwd
                keyAlias = aliasName
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩/混淆 + 资源收缩（清单组件由 AGP 自动 keep，见 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只有真正配置好的签名才应用；否则产出未签名包（app-release-unsigned.apk）
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // android.jar 方法在 JVM 单测中返回默认值而不是抛 "not mocked"
        unitTests.isReturnDefaultValues = true
        // Robolectric 需要真实资源（布局/主题）
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // DataStore 官方原子写入（自定义 Serializer + CRC32）
    implementation("androidx.datastore:datastore-core:1.1.1")
    // lifecycleScope（生命周期感知的协程作用域）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // enableEdgeToEdge + WindowInsetsCompat（targetSdk 35 起系统强制边到边）
    implementation("androidx.activity:activity-ktx:1.9.3")
    // ViewCompat.setStateDescription / updatePadding 等 View 扩展
    implementation("androidx.core:core-ktx:1.13.1")

    // 投产 QA：JVM 单元测试
    testImplementation("junit:junit:4.13.2")
    // 投产 QA：Robolectric —— 在云端 JVM 上跑真实 Activity 生命周期，无需模拟器
    testImplementation("org.robolectric:robolectric:4.13")
}
