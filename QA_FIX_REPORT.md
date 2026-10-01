# HelloWidget v7.0 修复报告

配套文档：[QA_REPORT.md](QA_REPORT.md)（修复前的投产 QA 审计结论）

| 项目 | 内容 |
|---|---|
| 修复基线 | `main` @ `8c5562a`（v6.0） |
| 发布版本 | **v7.0**（versionCode 15 / versionName 7.0 / minSdk 21 / targetSdk 35） |
| Release | <https://github.com/llllllllqq/hellowidget/releases/tag/v7.0> |
| 验证方式 | **全部编译、静态分析、单元测试在 GitHub Actions 云端完成，本地零构建** |
| 验证结果 | Lint **0 Error / 6 Warning**（修复前 37 Warning）、单元测试 **20/20 通过**、签名 APK 构建并发布成功 |

---

## 一、BLOCKER

| # | 问题 | 修复 | 证据 |
|---|---|---|---|
| B1 | 发布流水线 100% 失效（`android-actions/setup-android@v3` 报 `Failed to find package 'tools'`） | 删除该 action，改用 runner 预装 SDK | 修复前 main 复现失败 [run 36800735034](https://github.com/llllllllqq/hellowidget/actions/runs/36800735034)；修复后 [run 36803546316](https://github.com/llllllllqq/hellowidget/actions/runs/36803546316) 全绿并成功发版 |
| B2 | 加载未完成时用户输入被静默丢弃 | 保存门禁改为 `loadCompleted \|\| editorTouched`；编辑器在加载完成前禁用；失败后允许重试 | `MainActivitySaveGateTest` 3 个用例在云端通过（含修复前的缺陷复现版本，见下） |

**B2 的修复前后对照**：QA 阶段先用 Robolectric 写了一个测试**复现缺陷**（断言「输入丢失」），
修复后把同一测试翻转为断言「输入必须落盘」，并补充「未输入时不得用空内容覆盖磁盘」与「长度过滤器生效」两条契约。

---

## 二、CRITICAL

| # | 问题 | 修复 |
|---|---|---|
| C1 | `targetSdk 34` 不满足 Google Play 的 API 35+ 要求 | 升级到 **compileSdk / targetSdk 35**，并同步处理 Android 15 强制边到边 |

工具链同步升级（依据 AGP 8.6 官方兼容表：最高 API 35、最低 Gradle 8.7、JDK 17）：

```
AGP        8.2.2  → 8.6.1
Gradle     8.5    → 8.7（并提交 wrapper，版本可复现）
Kotlin     1.9.22 → 2.0.21（落入同一支持窗口）
compileSdk 34     → 35
targetSdk  34     → 35
```

边到边适配：两个 Activity 调用 `enableEdgeToEdge()` 并用 `ViewCompat` 消费 system bars + displayCutout insets，
避免内容被状态栏/导航栏遮挡。

---

## 三、MAJOR

| # | 问题 | 修复 |
|---|---|---|
| M1 | 私密笔记会被上传 Google 云备份 | `allowBackup="false"` + `dataExtractionRules`（API 31+）+ `fullBackupContent`（API ≤30）；官方文档指出仅 `allowBackup=false` 在部分厂商 Android 12+ 上不阻止设备间迁移，故三者齐备 |
| M2 | 失焦保存用 `runBlocking` 阻塞主线程 | 改为应用级作用域异步写盘，主线程零阻塞 |
| M3 | 大文本无上限导致 OOM | 新增 **100,000 字符**输入上限（自定义 `InputFilter`，超限有可见提示）；序列化读取省掉一份 payload 副本 |
| M4 | 滑杆拖动每帧全量刷新控件 | 拖动时只做本地预览，**松手才落盘并刷新桌面小组件** |
| M5 | 无 Gradle wrapper | 提交 `gradlew` / `gradlew.bat` / `gradle/wrapper/*`（锁定 8.7），CI 与本地统一用 `./gradlew` |
| M6 | action 用可变 tag、`contents: write` 全局、keystore 落在工作区 | 全部 action **固定到 commit SHA**；权限默认只读，仅 release job 申请 `contents: write`；keystore 只写入 `$RUNNER_TEMP` 并 `always()` 清理 |
| M7 | fork PR 必然失败 | 签名配置改为条件启用：无 keystore/口令时产出未签名包，流程照常通过 |
| M8 | 版本元数据手工维护、两个 APK 同 versionCode | 版本号收敛到 `gradle.properties` 单一来源，CI 从中派生 tag 与标题；Release 正文改为自动生成 changelog |
| M9 | 无任何质量门禁 | Lint + 单元测试成为发版前置（`needs` 依赖），并加 `concurrency` 与 `timeout-minutes` |
| M10 | 无自动化测试 | 新增 **20 个** JVM 单元测试（17 个序列化/损坏检测 + 3 个保存语义），Robolectric 在云端跑真实 Activity 生命周期 |
| M11 | 无障碍不可用 | 色板：48dp 触控目标、`contentDescription`、`stateDescription` 表达选中；SeekBar 加 `labelFor`；取色对话框 R/G/B 滑杆加名称 |
| M12 | 硬编码字符串 + 无英文 | 硬编码字符串全部移入资源；新增 `values-en` 完整英文；长度提示改为 `plurals` |

---

## 四、MINOR / INFO

- **Jetifier**：已移除。它在加入 Robolectric 后实测直接崩（`JetifyTransform ... Unsupported class file major version 65`）。
- **R8**：release 开启 `isMinifyEnabled` + `isShrinkResources`，删除会让 R8 完全失效的 keep-all 规则。**APK 2.61MB → 861KB**，DEX 已混淆。
- **图标**：重新生成圆角方形 + 正圆两套位图（消除 `IconLauncherShape`×10、`IconDuplicates`×5），adaptive icon 增加 `<monochrome>` 层（Android 13+ 主题图标）。
- **组件描述**：`appwidget-provider` 补 `android:description`（选择器里可读）。
- **损坏备份**：`corrupt_*.dat` 只保留最近 3 份，不再无限增长并被一起备份。
- **弱项加固**：小组件 receiver 改为 `exported="false"`（与 Android Studio 官方模板一致，系统仍可投递广播）；主题色解析兼容 ColorStateList。
- **Lint 收敛 37 → 6**：剩余 6 条为 `GradleDependency`×5 与 `OldTargetApi`×1，理由见下节。

---

## 五、有意未修改的项（附证据，非遗漏）

| 项 | 决定与依据 |
|---|---|
| `appcompat` 停在 1.7.0 | 1.8.0 的 `appcompat-resources` 声明 `minSdk 23`，本项目 minSdk 21，CI 实测 Manifest 合并失败。升级需放弃 Android 5.0/5.1，属产品决策 |
| `core-ktx` 停在 1.13.1 | 1.19.1 的 AAR 元数据要求 `minCompileSdk=37` + AGP 9.1.0 |
| `activity-ktx` 停在 1.9.3 | 1.13.0 的 AAR 元数据要求 `minCompileSdk=36` + AGP 8.9.1 |
| `datastore-core` / `lifecycle-runtime-ktx` 暂不升级 | 元数据虽兼容，但数据完整性与生命周期是核心路径，本次不做无收益的版本跳跃 |
| `OldTargetApi`（提示可到 36） | KB 中 Play 要求为 API 35+；升 36 需再次升级 AGP 并处理 Android 16 行为变更，留作后续 |
| 真机/模拟器 E2E | 本次未做。功能级验证依赖云端 JVM 测试；小部件在真实桌面的表现（尤其 `exported="false"` 与边到边）**建议装一次 v7.0 目视确认** |

---

## 六、发版流程（下次只需改两行）

```properties
# gradle.properties
hellowidget.versionName=7.1
hellowidget.versionCode=16
```

提交并推送 `main` → CI 自动跑「Lint + 单测 → 构建 → 发版」，
tag（`v7.1`）、Release 标题、APK 元数据全部由这一处派生，不会再漂移。

推送 `qa/**` 分支只跑质量门禁与构建，不发版，适合预验证。

---

## 七、本次云端验证记录

| 阶段 | 运行 | 结果 |
|---|---|---|
| 修复前复现 BLOCKER | [36800735034](https://github.com/llllllllqq/hellowidget/actions/runs/36800735034) | ✗ 失败（复现根因） |
| 工具链升级 | [36802328960](https://github.com/llllllllqq/hellowidget/actions/runs/36802328960) | ✓ 通过 |
| 功能/隐私/无障碍修复 | [36802698657](https://github.com/llllllllqq/hellowidget/actions/runs/36802698657) | ✓ 通过（20 测试） |
| wrapper + CI 重建 | [36803241593](https://github.com/llllllllqq/hellowidget/actions/runs/36803241593) | ✓ 通过 |
| **main 发版** | [36803546316](https://github.com/llllllllqq/hellowidget/actions/runs/36803546316) | ✓ **Release v7.0 已发布** |
