# HelloWidget

一个轻量 Android 应用：**文本编辑器 + 桌面小组件**。在应用里输入文本，退出后内容以**可上下滚动的小部件**形式展示在桌面，支持自定义外观，且**零后台进程、数据永不损坏**。

当前版本：**v7.1**（versionCode 16，minSdk 21 / targetSdk 35）

## 功能特性

| 特性 | 说明 |
|---|---|
| 📝 文本编辑 | 全屏多行编辑器，内容仅在退出 / 返回 / 切后台时保存（不做编辑自动保存） |
| ⌨️ 进入即输入 | 打开应用后光标自动落在**第一行行首**，并**自动弹出输入法**，省掉一次点击（读盘完成后触发，不会先弹键盘后填内容） |
| 🌙 自适应深色模式 | 编辑页随系统深色模式自动切换黑白，切换时自动保存当前内容；小组件颜色不受影响 |
| 🪟 可滚动小组件 | ListView 集合式小组件，桌面即可上下滑动阅读全部内容（所有 Android 版本支持） |
| 🎨 外观自定义 | 设置页可调小组件字体大小(10–34sp)、字体颜色、背景颜色、背景透明度；并可分别自定义**浅色模式 / 深色模式编辑器**的背景色与文字色；实时预览即时生效，支持自定义 RGB 取色 |
| 🔒 原子写入 | 内容存储采用 **Jetpack DataStore** 官方原子写入（临时文件 + fsync + 原子重命名），任意时刻崩溃都不会产生"写一半"的损坏文件 |
| ✅ CRC32 校验 | 文件格式 `[UTF-8 内容][4字节 CRC32]`，读取时校验；发现损坏自动保留现场文件并重建 |
| 🪫 零后台占用 | 无自动保存、无轮询、无常驻服务。小组件数据服务为绑定式，仅桌面渲染时才临时启动；保存完成即结束，CPU 自动释放 |
| 🔔 保存确认 | 真正写盘成功后才提示「已保存 ✓」；失败提示「保存失败」，旧内容不受影响 |
| 🔐 隐私优先 | `allowBackup=false` + `dataExtractionRules`：用户文本**既不参与云备份，也不参与设备间迁移** |
| 📏 长度上限 | 内容上限 100,000 字符，避免超大文本导致内存溢出；超出时给出可见提示 |
| ♿ 无障碍 | 色板具备可访问名称、选中状态与 48dp 触控目标；滑杆带 `labelFor` 关联标签 |
| 🌐 中英双语 | 默认中文，`values-en` 提供英文 |

## 版本历史

- **v7.1** 进入应用即输入：读盘完成后自动聚焦编辑器、光标落在第一行行首，并主动弹出输入法；边到边下自行消费输入法 insets（键盘不再遮挡底部按钮）；CI 新增**模拟器仪器化测试**，发版前在真实 Android 运行环境验证输入法确实弹出
- **v7.0** 投产 QA 修复：保存门禁缺陷（加载未完成时的输入丢失）、隐私备份、内存上限、无障碍、边到边适配、图标与国际化；工具链升级至 AGP 8.6.1 / Gradle 8.7 / Kotlin 2.0.21 / targetSdk 35；CI 重建为「质量门禁 + 构建 + 自动发版」
- **v6.0** 新增自适应系统深色模式（编辑页随系统自动切换黑白，切换时自动保存）；设置页新增浅色/深色模式编辑器颜色自定义；小组件颜色不受影响
- **v5.8** 修复短内容误滚动半行（内边距移入列表项）；新增可调防误触余量设置（默认 4dp）
- **v5.7** 小部件整段单条渲染：行高正常、长内容滚动、短内容整块可点击
- **v5.6** 修复 MIUI 加载（回归 ListView）；空白区按尺寸自动补齐可点击；失焦即保存并弹 Toast
- **v5.5** 小部件整块可点击（Android 12+ ScrollView 方案）；多任务键保存 Toast 不再丢失
- **v5.4** 修复 MIUI 小部件无法加载（移除 ListView 级点击）
- **v5.3** 包名改为 `moe.hellowidget`；小组件空白区域点击可打开应用；Home/多任务键保存 Toast 不再被后台抑制
- **v5.2** Home/多任务键/切应用也弹保存 Toast；新增 Material「widgets」开源图标；项目改名 hellowidget
- **v5.1** 修复退出时保存 Toast 不显示的问题（返回键改为「先写盘确认 → Toast → 再退出」）
- **v5.0** DataStore 原子写入 + CRC32 校验，仅退出/返回/切后台时保存，旧数据自动迁移
- **v4.0** 新增小组件外观设置页（字体大小/颜色/背景色/透明度）
- **v3.0** 小组件改为列表式可上下滚动
- **v2.0** Java → Kotlin 迁移，ViewBinding 现代化改造
- **v1.0** 文本编辑器 + 桌面小组件

## 云编译（GitHub Actions）

推送 `main` 分支或手动触发工作流，自动完成：

1. **质量门禁**：Android Lint + JVM 单元测试（Robolectric），任一失败即中止
2. 编译 Debug + Release（Release 使用 secrets 中的 keystore 签名）
3. **仪器化测试**：在 CI 的 Android 34 模拟器（KVM 硬件加速）上运行 `androidTest`，验证「自动弹出输入法」这类只能在真实 Android 运行环境观察的行为
4. 上传构建产物（Actions 页面 Artifacts）
5. **自动发布 GitHub Release**（仅 `main` 分支，且前三步全部通过），附签名 APK，可直接下载：

```
https://github.com/llllllllqq/hellowidget/releases/latest
```

### 触发方式

- **自动**：推送 `main` 分支（会发版）；推送 `qa/**` 分支（只跑质量门禁与构建，不发版）
- **手动**：仓库 Actions 页 → **Build & Release** → **Run workflow**

### 所需 Secrets

Release 签名使用以下仓库 Secrets（已配置）。**没有 secrets 时（例如 fork 的 PR）流程不会失败**，只是产出未签名的 release 包：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 签名 keystore 文件的 Base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

### 发新版流程

只改 `gradle.properties` 两行，提交并推送 `main`：

```properties
hellowidget.versionName=7.1
hellowidget.versionCode=16
```

CI 会自动用 `versionName` 生成 tag（`v7.1`）与 Release 标题，APK 元数据也取自同一处，三者不会再漂移。

## 本地构建

```bash
# 需要 Android SDK（把 sdk.dir 写入 local.properties，该文件已被 gitignore）
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK

# 签名 Release 需要以下环境变量；不设置时产出未签名包（不会构建失败）
export HELLOWIDGET_KEYSTORE=/path/to/hello-release.keystore
export KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...

# 质量门禁（与 CI 完全一致）
./gradlew :app:lintDebug :app:testDebugUnitTest

# 仪器化测试（需要已连接的设备或模拟器；CI 上由 GitHub Actions 自动跑）
./gradlew :app:connectedDebugAndroidTest
```

Gradle 版本由仓库内的 wrapper 固定（`gradle/wrapper/gradle-wrapper.properties` → 8.7），无需本机安装 Gradle。

## 项目结构

```
hellowidget/
├── .github/workflows/build.yml    # CI：质量门禁 + 构建 + 发布 Release
├── gradlew / gradle/wrapper/      # Gradle wrapper（版本锁定 8.7）
├── app/
│   ├── build.gradle.kts           # 构建配置（compileSdk 35 / minSdk 21 / targetSdk 35）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/moe/hellowidget/
│       │   │   ├── MainActivity.kt        # 编辑器 + 保存流程
│       │   │   ├── SettingsActivity.kt    # 外观设置页（小组件 + 编辑器颜色）
│       │   │   ├── EditorSettings.kt      # 编辑器颜色存取
│       │   │   ├── HelloWidgetApp.kt      # Application：初始化 DataStore
│       │   │   ├── ContentStore.kt        # DataStore 原子写入 + CRC32 + 旧数据迁移
│       │   │   ├── TextWidgetProvider.kt  # 小组件 Provider
│       │   │   ├── TextWidgetService.kt   # 绑定式数据服务
│       │   │   └── WidgetSettings.kt      # 小组件外观设置存取
│       │   └── res/                       # 布局、字符串（中/英）、主题、图标
│       ├── test/                          # JVM 单元测试（Robolectric，云端运行）
│       └── androidTest/                   # 仪器化测试（CI 模拟器上运行）
├── build.gradle.kts               # 根构建配置（AGP 8.6.1, Kotlin 2.0.21）
├── gradle.properties              # 全局配置 + 版本号唯一来源
└── settings.gradle.kts
```

## 数据存储说明

- **用户内容**：`filesDir/user_content.dat`（DataStore 格式：`[UTF-8 内容][CRC32]`）
- **外观设置**：SharedPreferences `hello_prefs`（字体大小/颜色/背景等，非关键数据）
- **损坏恢复**：CRC 校验失败时保留现场文件 `corrupt_<时间戳>.dat`（**只保留最近 3 份**）并重建，应用始终可用
- **隐私**：`allowBackup="false"` 且 `dataExtractionRules` 排除全部域 → 内容不会上传 Google 云备份，也不会随设备迁移

## 开源许可证

本项目基于 **MIT License** 开源，任何人可自由使用、修改、分发（含商用），仅需保留版权声明与许可证文本，详见 [LICENSE](LICENSE)。

第三方组件声明（含应用图标出处）见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
