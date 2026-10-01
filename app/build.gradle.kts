plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 版本号单一来源 ----------
// 唯一来源是根目录 gradle.properties 的 hellowidget.versionName / hellowidget.versionCode。
// APK 元数据、Release tag、Release 说明全部由它派生，避免三者互相漂移。
val appVersionName: String = (findProperty("hellowidget.versionName") as String?)
    ?: error("gradle.properties 缺少 hellowidget.versionName")
val appVersionCode: Int = (findProperty("hellowidget.versionCode") as String?)?.toIntOrNull()
    ?: error("gradle.properties 缺少（或非法）hellowidget.versionCode")

android {
    namespace = "moe.hellowidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.hellowidget"
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // 仪器化测试运行器（v7.1 起在 CI 模拟器上验证「输入法真的弹出来了」这类只能观测真机的行为）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // keystore 路径优先取环境变量（CI 把它放在 $RUNNER_TEMP，不落在工作区里）
            val envKeystore = System.getenv("HELLOWIDGET_KEYSTORE")
            val ksFile = if (!envKeystore.isNullOrEmpty()) {
                file(envKeystore)
            } else {
                rootProject.file("hello-release.keystore")
            }
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
    // 注意：appcompat 1.8.0 的 appcompat-resources 要求 minSdk 23，本项目 minSdk 21，
    // 因此锁定在 1.7.0（最后一个支持 minSdk 21 的版本）。
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

    // v7.1：仪器化测试（CI 里的 Android 模拟器上运行）。
    // 只影响 androidTest 变体：不进入发布包，也不改变应用的 minSdk。
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
