# HelloWidget 投产 QA 报告

| 项目 | 内容 |
|---|---|
| 被测版本 | `main` @ `8c5562a`（v6.0, versionCode 14, versionName 6.0） |
| 被测范围 | 全部 Kotlin 源码（8 个文件 / 1139 行）、全部资源、Gradle 配置、CI/CD 发布链 |
| 测试方法 | **全部编译、静态分析、单元测试均在 GitHub Actions 云端完成，本地未执行任何构建** |
| QA 分支 | `qa/production-audit`（`main` 未被改动，仍为 `8c5562a`） |
| 最终全绿运行 | [QA Gate #36801460236](https://github.com/llllllllqq/hellowidget/actions/runs/36801460236) |
| 结论 | **不具备投产条件**：发布流水线当前完全失效（BLOCKER），另有 2 项 CRITICAL、12 项 MAJOR |

---

## 一、结论摘要

好消息：核心数据层（DataStore 原子写 + CRC32 校验）设计经源码级复核**是正确的**，无密钥泄漏、无多余权限、
minSdk 21 兼容性无问题、Lint **0 Error**。18 个新增单元测试全部通过。

坏消息：**项目的发布通道现在 100% 跑不通**（这是本轮实测复现的最重问题），
并且存在一个**会静默丢弃用户输入**的保存逻辑缺陷（已在云端用测试实证），
以及一系列隐私、无障碍、供应链、工程化缺口。

| 严重度 | 数量 | 说明 |
|---|---|---|
| BLOCKER | 2 | 发布流水线失效；保存门禁致输入丢失 |
| CRITICAL | 1 | Google Play 上架 API 等级不达标 |
| MAJOR | 12 | 隐私备份、主线程阻塞、OOM、无障碍、供应链、无质量门禁等 |
| MINOR | 10 | 构建卫生、图标、国际化、版本元数据等 |
| INFO | 4 | 低影响项，建议知悉 |

---

## 二、BLOCKER

### B1. 发布流水线当前 100% 失效 —— 项目已无法构建/发布任何 APK

- **位置**：`.github/workflows/build.yml:28`（`android-actions/setup-android@v3`）
- **根因日志**：
  ```
  Warning: Failed to find package 'tools'
  Error: The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1
  ```
- **实测复现**：在 `main` 上手动触发既有工作流 → [Build Android APK #36800735034](https://github.com/llllllllqq/hellowidget/actions/runs/36800735034) 在 `Setup Android SDK` 步骤失败（28 秒）。
  同批次三个 job 全部倒在同一行：[QA Gate #36800531533](https://github.com/llllllllqq/hellowidget/actions/runs/36800531533)。
- **影响**：README 描述的「推送 main 自动出签名 APK + GitHub Release」通道完全不可用；
  最后一次成功发布停留在 2026-08-01（`v6.0.25`）。任何代码改动现在都无法交付到用户手里。
- **已修复并验证**：QA 分支改用 runner 预装 SDK（`ANDROID_HOME=/usr/local/lib/android/sdk`），
  [运行 #36800797588](https://github.com/llllllllqq/hellowidget/actions/runs/36800797588) 起三个 job 全部通过，
  并实测产出 `app-debug.apk`（3.32 MB）与**已签名** `app-release.apk`（2.61 MB）。
- **待办**：把同一修复应用到 `build.yml`（本次为保持 `main` 只读，只改了 `qa.yml`）。

### B2. 保存门禁缺陷：加载未完成时的用户输入被静默丢弃

- **位置**：`app/src/main/java/moe/hellowidget/MainActivity.kt:122-123`（`shouldSaveOnLeave`）、
  `:150-155`（`onStop`）、`:83-94`（返回键）
- **机制**：三条保存路径全部以 `loadCompleted` 为门禁，而它只在异步读盘返回后才置 true（`:62-71`）。
  代码注释（`:24`、`:147-148`）断言「`loadCompleted==false` 时磁盘上已是完整内容」——
  该断言在用户已经输入后不再成立：磁盘是**旧**内容，编辑器是**新**内容。
  正确门禁应为 `if (editorTouched || loadCompleted)`。
- **云端实证**：新增 `MainActivitySaveGateTest`（Robolectric，CI 上运行，6.59s 通过），
  在同一个测试方法内做 A/B 对照：
  - 阶段 A（`loadCompleted=false`）按 Home / 切后台 → 断言磁盘仍是旧内容 → **输入确实丢失**
  - 阶段 B（仅把门禁置 true）同样操作 → 断言新内容已写盘 → **证明差异只来自这道门禁**
- **暴露窗口**：与内容文件大小成正比（`readFrom` 要整读 + CRC32）。小笔记为数十毫秒，
  大内容可达数百毫秒至秒级；`recreate()`（旋转 / 深色模式切换）会重新打开该窗口。
- **影响**：对一款「定位就是别丢文字」的笔记应用，属于静默、不可恢复的数据丢失，且用户毫无提示。
- **回归保护**：该测试已入库，修复后应把断言方向翻转为「新输入必须被写盘」。

---

## 三、CRITICAL

### C1. Google Play 上架 API 等级不达标（仅影响上架，不影响 GitHub 分发）

- **位置**：`app/build.gradle.kts:13`（`targetSdk = 34`）
- **官方依据**（`android docs` 检索 Android Knowledge Base）：
  > Starting August 31 2025: New apps and app updates must target Android 15 (**API level 35**) or higher to be submitted to Google Play.
  （来源：<https://developer.android.com/google/play/requirements/target-sdk>）
- **影响**：`targetSdk 34 < 35`，新应用与更新**无法提交到 Google Play**；已有应用对新设备用户不可见。
- **说明**：本项目当前通过 GitHub Release 侧载分发，该渠道不受影响。
  若未来要走 Play，需升到 35（按年度递增规律，现时可能已要求 36），须同步处理行为变更。

---

## 四、MAJOR

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| M1 | **私密笔记会被上传到 Google 云备份**：`allowBackup="true"` 且无 `dataExtractionRules`/`fullBackupContent` | `AndroidManifest.xml:6`；内容在 `ContentStore.kt:53`、`MainActivity.kt:249,253` | Auto Backup 默认包含 `filesDir` 与 SharedPreferences；这是本应用**唯一的离机数据通路**，且 README 未披露、用户无法关闭 |
| M2 | **主线程 `runBlocking` 写盘 + 全量控件刷新**：失焦/Home/多任务键时同步阻塞 UI 线程完成 读+CRC+序列化+写+fsync，并做 `getAppWidgetIds`/`updateAppWidget`/`notify` 全套 binder IPC | `MainActivity.kt:129-139`（`:132`），由 `:102-109`、`:115-120` 触发 | 小文本约 10–30ms；1MB 量级掉帧；10MB 量级可达数百毫秒–秒级，慢设备上具备 ANR 能力 |
| M3 | **大文本无上限 → OOM 崩溃**：`readFrom` 同时持有 bytes + payload 副本 + String（约 3 份）；`read()/write()` 只 catch `Exception`，`OutOfMemoryError` 会逃逸并杀进程 | `ContentStore.kt:112-125`、`:67-88`；`TextWidgetService.kt:54` | 低堆设备（32–64MB）粘贴大文本后应用直接不可用；DataStore 官方也声明仅适合小数据 |
| M4 | **设置页滑杆「控件更新风暴」**：`onProgressChanged` 每帧调用 `persist()` → 全量 SharedPreferences 写入 + 全量控件更新，且每次通知都让宿主回调 `onDataSetChanged` → 重新整读并 CRC 全量内容 | `SettingsActivity.kt:89-102`（`:101`）、`:110-120`、`:146-156`；`TextWidgetProvider.kt:40-71`；`TextWidgetService.kt:49-57` | 拖动任一滑杆时桌面卡顿、binder 抖动、耗电；无节流、未判断 `fromUser` |
| M5 | **无 Gradle wrapper**：无 `gradlew`、无 `gradle/wrapper/`，构建版本只存在于 CI 配置里 | 仓库根目录；`README.md:67-68` 却教人跑 `gradle assembleDebug` | 贡献者 `./gradlew` 直接失败；本地与 CI 工具链可能静默分叉 |
| M6 | **供应链风险**：6 个 action 全部用可变大版本 tag（`@v4`/`@v3`/`@v2`），且 `permissions: contents: write` 是 workflow 级、被所有第三方 action 继承；keystore 解码后留在工作区、无清理步骤 | `build.yml:19,22,28,31,56,62,70`；`:10-11`；`:43` | 任一 action 被重打 tag 即可以写权限运行，且磁盘上同时存在签名密钥（已确认未泄漏进 artifact） |
| M7 | **fork / Dependabot PR 必然失败**：`pull_request` 触发但 secrets 不可用，`base64 -d` 产出 0 字节 keystore，而 release 签名配置是无条件的 | `build.yml:6-7,39-43`；`app/build.gradle.kts:22-29,38` | 无法接受外部贡献；无「仅验证」的 PR 构建路径 |
| M8 | **版本元数据不自动同步**：`versionCode` 恒为 14、`versionName` 恒为 "6.0"，而 tag 用 `github.run_number`；**v6.0.24 与 v6.0.25 发布了两个内容不同但 versionCode 相同的 APK** | `app/build.gradle.kts:14-15`；`build.yml:72-73` | 破坏升级语义（`install -r`、Play 会拒绝重复 versionCode）；release 说明与真实改动脱节 |
| M9 | **无任何质量门禁**：无 lint、无单元测试、无依赖漏洞扫描、无 `--stacktrace`、无 `concurrency`、无 `timeout-minutes` | `build.yml` 全文 | 历史上 4/25 次运行失败，全部是编译/资源错误直接推上 main；公开仓库每次 push 都自动发版，却没有一道自动化关卡 |
| M10 | **无自动化测试** | 原本无 `src/test`、无 `src/androidTest` | 见第九节：本轮已补 18 个用例 |
| M11 | **无障碍不可用**：色板是裸 `View`，无 `contentDescription`、无 role、无 `stateDescription`；触控目标仅 36dp（低于 48dp 建议）；选中状态仅靠粉色边框（颜色单独承载信息）；SeekBar 无 `labelFor`、无可访问名 | `SettingsActivity.kt:260-296`；`activity_settings.xml:86/97,165/176,198/209` | TalkBack 用户面对约 70 个无法区分的可点节点，完全无法选色或判断当前选中项 |
| M12 | **国际化缺口**：硬编码用户可见字符串（`widget_preview.xml:13` 中文预览文案、`SettingsActivity.kt:284` 的「＋」、`:321` 的 R/G/B、`:109/:113/:127/:131` 的拼接文本）；只有中文 `values/`，无 `values-en` | 见左 | 英文设备显示全中文 UI（含小部件选择器预览）；非 Play 政策阻塞，但阻碍任何国际化发布 |

---

## 五、MINOR / INFO

**MINOR**

1. **`android.enableJetifier=true` 已实测破坏构建**（`gradle.properties:7`）：加入 Robolectric 后，
   Jetifier 改写传递依赖 `org.bouncycastle:bcprov-jdk18on:1.78.1` 时报
   `JetifyTransform ... Unsupported class file major version 65`，
   导致 `generateDebugLintReportModel` 与 `compileDebugUnitTestKotlin` 双双失败（[#36801134784](https://github.com/llllllllqq/hellowidget/actions/runs/36801134784)）。本项目全 AndroidX、无遗留依赖，该开关纯属多余且有害 —— **已在本分支移除并验证通过**。
2. **release 未启用压缩/混淆**：`isMinifyEnabled=false`、`isShrinkResources` 未设，
   `proguard-rules.pro:6` 的 keep-all 规则形同虚设（APK 偏大、符号与行号完整保留）。无敏感信息可泄漏，属成本问题。
3. **release tag / 标题 / 正文硬编码在 workflow 内**：`build.yml:72-73`，历史上连续 8 次提交手工改写 tag 前缀与文案。
4. **启动图标质量**：Lint 报 10×`IconLauncherShape`（图标填满方形区域 / round 图标非圆形）、
   5×`IconDuplicates`（`ic_launcher.png` 与 `ic_launcher_round.png` 内容完全相同）、2×`MonochromeLauncherIcon`（缺 Android 13+ 主题图标层）。
5. **小部件预览缺失/不可读**：`widget_info.xml:6` 只有 API 31+ 的 `previewLayout`，无 `previewImage` → API 21–30 选择器只显示应用图标；
   `widget_preview.xml` 白字透明底在浅色卡片上对比度约 1:1。
6. **损坏备份无限增长**：`ContentStore.kt:94` 每次 CRC 失败都写一份 `corrupt_<ts>.dat`，从不清理，且会被云备份一起上传。
7. **保存失败不重试**：`MainActivity.kt:130` 在写入结果未知前就置 `savedOnLeave=true`，
   失败后同一轮 `onStop` 被跳过，Toast 写着「请重试」却没有任何重试机制。
8. **返回键在慢写盘时像失灵**：`:85-86` 一旦 `exitingByBack=true` 后续返回全被吞掉，而 `finish()` 要等异步写盘完成。
9. **工具链不匹配**：Kotlin 1.9.22 不在 JetBrains 对 Gradle 8.5 的支持矩阵内（KGP 1.9.20–1.9.25 → Gradle 6.8.3–8.1.1）；实测 21/25 次运行通过，属「能跑但无保障」。
10. **构建卫生**：每个 action 都刷 Node.js 20 弃用警告；`local.properties` 步骤冗余；两次独立 gradle 调用；无 `timeout-minutes`。

**INFO**

11. `TextWidgetProvider` 的 receiver 声明 `exported="true"`（`AndroidManifest.xml:33`）：任意应用可伪造 `APPWIDGET_UPDATE` 广播。
    实测影响低（不返回用户内容，平台只接受本包拥有的 widget id），可收紧为 `exported="false"` 或加权限。
12. `SettingsActivity.themeAttrColor()`（`:402-406`）以 `value.data` 取主题颜色，AppCompat 的 `textColorPrimary` 是 ColorStateList，个别主题下可能取不到有效 ARGB —— 需真机确认。
13. 0 字节内容文件走 `isEmpty` 直接返回空串（`ContentStore.kt:113-114`），绕过 CRC 校验与损坏备份，属静默降级。
14. `ContentStore.store` 未初始化时不崩溃而是静默降级（`read()`→`""`、`write()`→`false`），当前路径不可达，但属于「静默失败」陷阱。

---

## 六、已排除项（经复核确认**不是**缺陷，避免误报）

- **`Int.SIZE_BYTES` 在 minSdk 21 安全**：它解析到 Kotlin stdlib 的 `Int.Companion.SIZE_BYTES`（编译期常量，折叠为字面量 4），
  并非 Java 的 `Integer.BYTES`（API 24+），不会 `NoSuchFieldError`。
- **异步加载不会覆盖用户输入**：`editorTouched` 在任何文本变化时置位（含框架恢复实例状态时触发的 watcher），
  且 `loadCompleted = true` 与 `setText` 之间无挂起点。加载侧门禁是正确的 —— 缺陷在保存侧。
- **损坏文件备份顺序正确**：查阅 `androidx.datastore:datastore-core:1.1.1` 源码，
  `readDataOrHandleCorruption` 先调用 corruptionHandler、之后才写入替换数据，因此 `backupCorruptFile` 保留的是真实现场。
- **CRC 文件格式自洽且有历史兼容**：`[UTF-8 内容][4 字节大端 CRC32]`，自 `5080c7d` 引入以来格式未变，仅做过包名重命名。
- **不会创建重复 DataStore 实例**：`ContentStore.kt:50` 的 `isInitialized` 守卫 + 单一 Application 调用点。
- **`PendingIntent` flag 正确**：`FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`，且对 API 23 以下正确降级（否则会崩）。
- **无 `INTERNET` 权限、无其它权限**；存储在 `filesDir` + `MODE_PRIVATE`，非 world-readable；日志不含用户内容。
- **签名 keystore 未被提交**：`*.keystore` 已被 `.gitignore` 覆盖，`git ls-files` 与全部历史均无该文件。
- **release APK 命名与签名正确**：实测产出 `app-release.apk` 2.61 MB，含 v1 签名，非 `-unsigned`。
- **Gradle 缓存有效**：`setup-gradle@v4` 已恢复/保存 `~/.gradle`，无需额外配置。
- **minSdk 21 与依赖兼容**：三个 androidx 依赖 AAR manifest 的最低 SDK 分别为 14 / 19 / 19，无合并冲突。
- **API 31 专属 XML 属性在低版本安全**：`previewLayout`/`targetCellWidth`/`targetCellHeight` 被旧平台解析器直接忽略，不崩溃。
- **`RemoteViewsService.onCreate` 中的同步读盘符合官方设计**：官方文档明确允许在 `onDataSetChanged()` 同步做重活，
  且 `onCreate` 需超过 20 秒才 ANR —— 不是缺陷（仅在超大内容时值得优化）。
- **启动图标资源覆盖完整**：adaptive icon + mdpi…xxxhdpi 位图齐备，API 21–25 不会 `NotFoundException`。

---

## 七、本轮云端测试资产（`qa/production-audit` 分支）

| 资产 | 内容 |
|---|---|
| `.github/workflows/qa.yml` | 3 个独立 job：**Android Lint** / **JVM 单元测试** / **Debug+Release 构建**；失败也归档报告；带 `concurrency` 取消旧运行；不依赖失效的第三方 action |
| `ContentSerializerTest.kt` | 17 个用例：文件格式契约（大端 CRC32）、无损往返（ASCII/中文/emoji/多行/NUL/空/1MiB）、7 类字节级损坏检测（翻转 payload、翻转 CRC、截断、追加、乱序、长度不足、空流） |
| `MainActivitySaveGateTest.kt` | 1 个 Robolectric 用例：A/B 对照确定性复现 B2 缺陷 |
| 构建配置 | 移除 `enableJetifier`；新增 `junit`、`robolectric 4.13`、`testOptions.isIncludeAndroidResources` |

**最终结果**（[#36801460236](https://github.com/llllllllqq/hellowidget/actions/runs/36801460236)，全绿）：

```
Android Lint     BUILD SUCCESSFUL in 2m 4s      0 Error / 37 Warning
Unit Tests       18 tests, 0 failures, 0 errors （ContentSerializerTest 17 + MainActivitySaveGateTest 1）
Build APKs       app-debug.apk 3,323,379 B / app-release.apk 2,612,134 B（已签名）
工具链           Temurin JDK 17.0.20.1 + Gradle 8.5 + AGP 8.2.2 + compileSdk 34
```

**Lint 37 条告警分布**：`IconLauncherShape`×10、`SetTextI18n`×6、`IconDuplicates`×5、`HardcodedText`×4、
`UnusedAttribute`×3、`GradleDependency`×3、`MonochromeLauncherIcon`×2、`Overdraw`×1、`OldTargetApi`×1、
`ObsoleteLintCustomCheck`×1、`Autofill`×1。完整 file:line 明细见附件 `qa-lint-reports` artifact。

---

## 八、建议处置顺序

**P0（立即，否则无法交付）**
1. 把 `build.yml:28` 的失效 action 换成 runner 预装 SDK（已在本分支验证，可直接摘取 `qa.yml` 的写法）。
2. 修 B2 保存门禁：把三条路径的门禁改为 `editorTouched || loadCompleted`，返回键在 `!loadCompleted` 时也应保存而非直接 `finish()`；
   翻转 `MainActivitySaveGateTest` 的断言方向作为回归保护。

**P1（投产前必须）**
3. 关闭或收窄备份：`allowBackup="false"`，或提供 `dataExtractionRules` 只备份必要项（含隐私披露）。
4. 保存路径去阻塞：改成 `ContentStore.saveScope` 异步写 + Toast 回调（返回键也走同一路径），消除主线程 `runBlocking`。
5. 增加输入长度上限（例如 64KB–256KB）与超限提示，堵住 OOM 路径。
6. 设置页滑杆节流（仅 `fromUser` + `onStopTrackingTouch` 时刷新控件），或延迟合并刷新。
7. 提交 Gradle wrapper（8.5）并在 CI 改用 `./gradlew`。
8. 收敛供应链：action 固定到 commit SHA、`permissions` 按 job 最小化、keystore 写到 `$RUNNER_TEMP` 并 `if: always()` 清理。
9. 把 `qa.yml` 的质量门禁合并进主流水线，并让 `build.yml` 对 fork PR 优雅降级（无 secrets 时只出未签名包）。

**P2（质量提升）**
10. 无障碍整改：色板加 `contentDescription` + `stateDescription`、触控目标提到 48dp、SeekBar 加 `labelFor`。
11. 修复 Lint 告警（图标 17 条、硬编码字符串、拼接文本）。
12. 版本元数据单一来源（tag 由 `versionName` 派生、`versionCode` 由 `run_number` 注入）。
13. 若计划上架 Play：升 targetSdk 至 35/36、开启 R8 压缩与资源收缩、补隐私政策与 AAB 配置。

---

## 九、说明与局限

- 本报告**未在真机/模拟器上运行**应用（用户要求编译与调试走云端；功能级 E2E 需要云端模拟器或真机）。
  M2/M3/M4 的性能与 ANR 量级为基于代码路径与官方文档的推断，已标注置信度。
- 所有「已排除」结论均经过独立复核（含官方文档检索、依赖源码查阅、`api-versions.xml` 比对），
  目的是避免把「看起来可疑」误报为缺陷。
- 报告生成时的 `main` 分支未被修改，全部改动位于 `qa/production-audit` 分支，可随时废弃或转为 PR。
