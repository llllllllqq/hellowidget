plugins {
    // AGP 9 起**内置 Kotlin 支持**，不再需要（也不允许）应用 org.jetbrains.kotlin.android：
    // 该插件与 AGP 9 的新 DSL 不兼容，官方迁移指南要求移除。
    // 内置 Kotlin 自带的 KGP 版本由 AGP 决定（AGP 9.4.1 → KGP 2.2.10），
    // jvmTarget 默认等于 compileOptions.targetCompatibility（本项目 = 17），因此也不再写 kotlinOptions。
    id("com.android.application")
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
    // compileSdk 36（Android 16）：AGP 9.4 支持的最高 API 是 37，但 Play 只要求 targetSdk 36，
    // 而 AndroidX 里 activity-ktx 1.13 / core-ktx 1.18 的 AAR 元数据要求 minCompileSdk=36。
    // 再往上（core-ktx 1.19）会把要求推到 minCompileSdk=37，属另一条路线，暂不采用。
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.hellowidget"
        // minSdk 21 → 24（Android 7.0）：放弃 Android 5.0/5.1/6.0。
        // 换来：appcompat 1.8.0（默认 minSdk 已提到 23）、Robolectric 4.16+（已移除 SDK 21/22 支持）、
        // 原生 multidex，以及 API 24 起可用的 JobScheduler.getPendingJob。
        minSdk = 24
        // targetSdk 36：Google Play 自 2026-08-31 起要求新包与更新必须 target API 36+，
        // 35 已不合规；同时 Android 16 的行为变更（预测性返回默认开启等）随之下发。
        targetSdk = 36
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

    testOptions {
        // android.jar 方法在 JVM 单测中返回默认值而不是抛 "not mocked"
        unitTests.isReturnDefaultValues = true
        // Robolectric 需要真实资源（布局/主题）
        unitTests.isIncludeAndroidResources = true
        // JDK 17 起 java.base 的内部包默认不再对外开放。JSSE 的服务端握手（TlsPinningTest
        // 里的 SSLServerSocket 桩）需要反射 java.net.InetAddress 的私有字段，
        // 不加这几行会直接抛 InaccessibleObjectException：
        //   Unable to make java.net.InetAddress$InetAddressHolder ... accessible:
        //   module java.base does not "opens java.net" to unnamed module
        //
        // 这里用的是 Robolectric 官方 getting-started 的整套 --add-opens（我们原先只有前 4 条）：
        // 升到 Robolectric 4.17 且 CI 用 JDK 21 后，缺项更容易在运行时炸出来。
        unitTests.all { test ->
            test.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }
}

dependencies {
    // minSdk 24 之后 appcompat 1.8.0 才可用：1.8.0 把默认 minSdk 从 21 提到了 23
    // （旧版因此被钉在 1.7.0 —— 1.8.0 的 appcompat-resources 声明 minSdk 23，minSdk 21 下清单合并失败）。
    //
    // 1.7.0 → 1.8.0 有两条变更直接落在本项目「绕 AppCompat 内部行为」的两处实现上，升级后要重点回归：
    //   1) "Fix Toolbar height calculation to include title/subtitle vertical margins"
    //      → 我们顶部导航栏是被 v7.7.1 事故修成「固定 56dp + status_bar_spacer 占位条」的，
    //        正是为了绕开 Toolbar.onLayout 空间不足时把标题贴底裁成一条缝的行为。
    //        已有两层断言看着它：JVM（灌 53dp inset 断言内容区完整）+ 真机（把导航栏画进 Bitmap 数白色像素）。
    //   2) "Dispatch configuration changes in AppCompatActivity to the view tree"
    //      → 本项目的深色模式走的是官方「Activity 自己处理 uiMode」路线
    //        （Manifest configChanges="uiMode" + MainActivity.onConfigurationChanged 手动保存后 recreate()），
    //        这次变更改的正是这条路径。
    implementation("androidx.appcompat:appcompat:1.8.0")
    // DataStore 官方原子写入（自定义 Serializer + CRC32）
    implementation("androidx.datastore:datastore-core:1.2.1")
    // lifecycleScope（生命周期感知的协程作用域）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // enableEdgeToEdge + WindowInsetsCompat（targetSdk 35 起系统强制边到边，36 起连 opt-out 都被禁用）
    implementation("androidx.activity:activity-ktx:1.13.0")
    // ViewCompat.setStateDescription / updatePadding 等 View 扩展
    // 注意：core-ktx 1.19.x 的 AAR 元数据是 minCompileSdk=37 + minAgp 9.1.0，
    // 而本项目的 compileSdk 是 36（Play 只要求 targetSdk 36），因此停在 1.18.0（要求 minCompileSdk=36）。
    implementation("androidx.core:core-ktx:1.18.0")
    // 每个请求阶段都要有超时且可取消：手写 Socket 的写超时缺失会让半开连接卡住整次同步。
    // OkHttp 5.x 的 class 元数据是 mv=[2,1,0]，要求 Kotlin ≥ 2.1 —— v7.9 当时编译用的是
    // Kotlin 2.0.21（会报 "Module was compiled with an incompatible version of Kotlin"），
    // 所以只能停在 4.12.0。本版起改用 AGP 9 的内置 Kotlin（KGP 2.2.10），门槛满足，升到 5.x；
    // 用到的东西都在（对着 okhttp-android-5.4.0 的 classes.jar 逐个 javap 核过）：
    // callTimeout / connectTimeout / readTimeout / writeTimeout / followRedirects /
    // followSslRedirects / protocols / retryOnConnectionFailure / sslSocketFactory / hostnameVerifier、
    // Response.peekBody(long)（!）、Credentials.basic(u, p, Charset)、Request.Builder.method(name, body)。
    //
    // 为什么是 5.4.0 而不是 5.5.0（**这是 docs 不会告诉你、只有读 AAR 元数据才知道的硬约束**）：
    // Android 侧实际解析到的是 okhttp-android 这个变体，它的 aar-metadata 里写着
    //   okhttp-android:5.4.0 → minCompileSdk=36   ✔ 与本项目 compileSdk 36 相符
    //   okhttp-android:5.5.0 → minCompileSdk=37   ✘ 会直接让 Gradle 报
    //                                              "dependency requires ... compile against version 37"
    // 5.0.0~5.3.0 的 minCompileSdk=1（无约束），5.4.0 是「compileSdk 36 下最新的 5.x」。
    // 想用 5.5.0 就先把 compileSdk 抬到 37（那时 core-ktx 也能一起升到 1.19.x）。
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // 投产 QA：JVM 单元测试
    testImplementation("junit:junit:4.13.2")
    // 投产 QA：Robolectric —— 在云端 JVM 上跑真实 Activity 生命周期，无需模拟器。
    // 4.17 支持到 SDK 37；4.16 起已移除 SDK 21/22 支持，与本版 minSdk 24 方向一致。
    testImplementation("org.robolectric:robolectric:4.17")

    // v7.1：仪器化测试（CI 里的 Android 模拟器上运行）。
    // 只影响 androidTest 变体：不进入发布包，也不改变应用的 minSdk。
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
