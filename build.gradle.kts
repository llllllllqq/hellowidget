plugins {
    // AGP 8.6.x 是支持 compileSdk/targetSdk 35 的最低主线（官方：最高 API 35，最低 Gradle 8.7，JDK 17）
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.0.21 的 Gradle 支持范围包含 8.7，与上面的 AGP 组合落在同一支持窗口内
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
