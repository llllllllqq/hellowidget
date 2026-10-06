plugins {
    // AGP 9.4.1（2026-09 稳定版）：官方兼容表要求 Gradle ≥ 9.6.0、JDK 17、最高 API 37。
    // AGP 9 起**内置 Kotlin 支持**（运行时自带 KGP 2.2.10），因此这里不再声明
    // org.jetbrains.kotlin.android —— 它与 AGP 9 的新 DSL 不兼容，官方迁移指南明确要求移除。
    id("com.android.application") version "9.4.1" apply false
}
