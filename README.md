# HelloWidget

一个轻量 Android 应用：**文本编辑器 + 桌面小组件**。在应用里输入文本，退出后内容以**可上下滚动的小部件**形式展示在桌面，支持自定义外观，且**零后台进程、数据永不损坏**。

当前版本：**v7.6**（versionCode 21，minSdk 21 / targetSdk 35）

## 功能特性

| 特性 | 说明 |
|---|---|
| 📝 文本编辑 | 全屏多行编辑器，内容仅在退出 / 返回 / 切后台时保存（不做编辑自动保存） |
| ☁️ WebDAV 同步 | 把最新内容**纯单向上传**到你自己的 WebDAV（坚果云 / Nextcloud / 群晖 NAS）：只依赖本机记录判断内容有没有变，变了就写一个**新文件** `note<unix 时间戳>.txt`，旧文件永不触碰 —— 云端自动保留每一次上传的历史，也就不需要读取、比对或清理远端。同步期间通知栏有进度条（**每次上传都保证可见**，最短 1.5 秒），上传成功还会弹一个 Toast；结束立即停止，不留常驻后台 |
| ⌨️ 进入即输入 | 打开应用后光标自动落在**第一行行首**，并**自动弹出输入法**，省掉一次点击（读盘完成后触发，不会先弹键盘后填内容） |
| 🌙 自适应深色模式 | 编辑页随系统深色模式自动切换黑白，切换时自动保存当前内容；小组件颜色不受影响 |
| 🪟 可滚动小组件 | ListView 集合式小组件，桌面即可上下滑动阅读全部内容（所有 Android 版本支持） |
| 🎨 外观自定义 | 设置页可调小组件字体大小(10–34sp)、字体颜色、背景颜色、背景透明度；并可分别自定义**浅色模式 / 深色模式编辑器**的背景色与文字色；实时预览即时生效，支持自定义 RGB 取色 |
| 🔒 原子写入 | 内容存储采用 **Jetpack DataStore** 官方原子写入（临时文件 + fsync + 原子重命名），任意时刻崩溃都不会产生"写一半"的损坏文件 |
| ✅ CRC32 校验 | 文件格式 `[UTF-8 内容][4字节 CRC32]`，读取时校验；发现损坏自动保留现场文件并重建 |
| 🪫 零后台占用 | 无自动保存、无轮询、无常驻服务。小组件数据服务为绑定式，仅桌面渲染时才临时启动；同步只在「关闭编辑器 / 打开应用 / 手动点击」时进行，且自动同步两次至少间隔 30 分钟，结束即停前台服务、不留任何后台任务 |
| 🔔 保存确认 | 真正写盘成功后才提示「已保存 ✓」；失败提示「保存失败」，旧内容不受影响 |
| 🔐 隐私优先 | `allowBackup=false` + `dataExtractionRules`：用户文本**既不参与云备份，也不参与设备间迁移** |
| 📏 长度上限 | 内容上限 100,000 字符，避免超大文本导致内存溢出；超出时给出可见提示 |
| ♿ 无障碍 | 色板具备可访问名称、选中状态与 48dp 触控目标；滑杆带 `labelFor` 关联标签 |
| 🌐 中英双语 | 默认中文，`values-en` 提供英文 |

## 版本历史

- **v7.6** 每次上传写成**新文件** `note<unix 时间戳>.txt`（前缀与扩展名取自设置页的「文件名」字段，如 `我的笔记.md` → `我的笔记1759312800.md`）：云端因此自动保留全部历史，**旧文件永不被覆盖**；文件名取 `max(现在, 上次时间戳+1)`，同一秒内连续上传也不会撞名，设备时钟被回拨时仍单调递增。依旧只上传到指定目录，**不列出、不读取、不清理远端**（每次上传仍是 1 个请求，目录不存在时 2 个）；设置页显示写入规则与「云端自动保留历史」说明（见 [V7.6_HISTORY_UPLOAD_REPORT.md](V7.6_HISTORY_UPLOAD_REPORT.md)）
- **v7.5** WebDAV 同步改为**纯单向上传**：删掉全部云端比对与冲突处理（不再 HEAD/PROPFIND/GET、不比对 ETag/修改时间/大小、不再有冲突弹窗与冲突副本），只靠本机「上次成功上传的内容哈希」判断有没有改动，变了就直接 `PUT` 强制覆盖云端（必要时先 `MKCOL` 建目录），本机没变则一个请求都不发。同时修复「上传了却没有通知栏进度」：上传常常几百毫秒就结束、通知刚发出去就被收掉，现在保证每次上传的进度通知至少可见 1.5 秒；前台服务无法启动时的进程内兜底路径也会发通知（旧实现那条路径完全没有反馈）；**上传成功新增 Toast「已上传到云端」**，即使通知一闪而过也能知道结果（见 [V7.5_ONE_WAY_UPLOAD_REPORT.md](V7.5_ONE_WAY_UPLOAD_REPORT.md)）
- **v7.4** 修复坚果云 WebDAV 同步反复出现 **409** 的问题：坚果云有三处非标准行为 —— ①目标已存在时 `MOVE` 一律回 409（RFC 要求 `Overwrite:T` 成功）②对已存在的目录 `MKCOL` 回 409（RFC 要求 405）③读不存在的路径回 409 + `AncestorsNotFound`（RFC 要求 404）。现在分别降级为「带前置条件的直接 PUT」、「视为目录已存在（仅 `AncestorsNotFound` 才报上级目录不存在）」与「视为文件不存在」；错误详情还会带出服务器给的异常名便于定位。CI 增加第二台按坚果云脾气回 409 的桩服务器与 4 个真机级回归用例（见 [V7.4_NUTSTORE_409_REPORT.md](V7.4_NUTSTORE_409_REPORT.md)）
- **v7.3** Android 15 边到边适配补齐：同步设置页此前只消费系统栏 insets、不处理输入法，边到边后键盘会盖住表单与底部按钮 —— 现改为「外层 FrameLayout 承担 insets + `maxOf(系统栏, 输入法)`」，并给该页补上 `windowSoftInputMode="adjustResize"`；新增同步页键盘遮挡的仪器化回归用例；CI 的模拟器矩阵扩到 **API 34 + API 35**（API 35 才是系统强制边到边的那一档）（见 [V7.3_EDGE_TO_EDGE_REPORT.md](V7.3_EDGE_TO_EDGE_REPORT.md)）
- **v7.2** WebDAV 同步：把最新内容上传到自己的 WebDAV（只上传、不自动下载），云端被外部修改时弹窗询问并保留双方副本；关闭编辑器后与打开应用时自动触发，自动同步严格间隔 30 分钟；同步期间前台服务 + 通知栏进度条，结束后立即停止；明文 http 会给出风险提示，自签名证书按用户确认的指纹固定（TOFU）。**不新增任何第三方依赖**（自写零依赖 HTTP/1.1 客户端）；CI 仪器化测试改为打一个真实运行的 WebDAV 服务器，用服务器请求日志证明真的使用了 `MKCOL`/`PUT`/`MOVE`/`COPY`（见 [WEBDAV_SYNC_REPORT.md](WEBDAV_SYNC_REPORT.md)）
- **v7.1** 进入应用即输入：读盘完成后自动聚焦编辑器、光标落在第一行行首，并主动弹出输入法；边到边下自行消费输入法 insets（键盘不再遮挡底部按钮）；CI 新增**模拟器仪器化测试**，发版前在真实 Android 运行环境验证输入法确实弹出（验证证据见 [V7.1_RELEASE_REPORT.md](V7.1_RELEASE_REPORT.md)）
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
3. **仪器化测试**：在 CI 的 Android **34 / 35** 双档模拟器（KVM 硬件加速）上运行 `androidTest`。除了「自动弹出输入法」这类只能在真实 Android 运行环境观察的行为，还会在 runner 上启动仓库自带的零依赖 WebDAV 服务器（`.github/scripts/webdav_stub_server.py`，模拟器经 `10.0.2.2` 访问），端到端验证上传、冲突与节流，并把服务器请求日志打印到 Actions 日志里作为证据
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
hellowidget.versionName=7.6
hellowidget.versionCode=21
```

CI 会自动用 `versionName` 生成 tag（`v7.6`）与 Release 标题，APK 元数据也取自同一处，三者不会再漂移。

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
├── .github/workflows/build.yml    # CI：质量门禁 + 构建 + 仪器化测试 + 发布 Release
├── .github/scripts/webdav_stub_server.py  # CI 用的零依赖 WebDAV 测试服务器
├── gradlew / gradle/wrapper/      # Gradle wrapper（版本锁定 8.7）
├── app/
│   ├── build.gradle.kts           # 构建配置（compileSdk 35 / minSdk 21 / targetSdk 35）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/moe/hellowidget/
│       │   │   ├── MainActivity.kt        # 编辑器 + 保存流程 + 同步触发
│       │   │   ├── SettingsActivity.kt    # 外观设置页（小组件 + 编辑器颜色）
│       │   │   ├── SyncActivity.kt        # WebDAV 同步设置页（凭据/状态/冲突/证书指纹）
│       │   │   ├── SyncService.kt         # 同步前台服务（dataSync，含通知栏进度条）
│       │   │   ├── SyncActionReceiver.kt  # 通知栏动作（覆盖云端 / 用云端覆盖本地）
│       │   │   ├── sync/                  # 同步核心
│       │   │   │   ├── SyncEngine.kt          # 决策表 + 30 分钟闸门（纯函数，可单测）
│       │   │   │   ├── HttpWebDavClient.kt    # 零依赖 Socket/SSLSocket HTTP/1.1 + TOFU 证书固定
│       │   │   │   ├── SyncManager.kt         # 编排：触发、冲突、写回、状态流
│       │   │   │   ├── SyncSettings.kt        # 同步配置与状态存取
│       │   │   │   ├── SyncNotifier.kt        # 进度/失败/冲突通知
│       │   │   │   ├── SyncConfig.kt          # URL 与文件名校验、HTTP 日期
│       │   │   │   ├── SyncLauncher.kt        # 统一触发入口（前台服务，失败降级为进程内）
│       │   │   │   └── SyncErrorText.kt       # 错误分类 → 本地化文案
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

## WebDAV 同步使用说明

在应用底部点「☁ WebDAV 同步」，填写服务器目录地址、文件名、用户名与口令，打开开关后保存。

| 服务 | 目录地址示例 |
|---|---|
| 坚果云 | `https://dav.jianguoyun.com/dav/你的目录/`（需在「账户信息 → 安全选项」生成**应用密码**） |
| Nextcloud | `https://你的域名/remote.php/dav/files/用户名/目录/` |
| 群晖 / NAS | `http://192.168.1.5:5005/dav/目录/`（明文 http 会显示风险提示） |

行为约定：

- **纯单向上传，且每次都是新文件**：本机内容一变，下一次同步就往目录里写一个 `note<unix 时间戳>.txt`；旧文件**永远不会被覆盖或删除**，云端自动保留全部历史。本机没变则**一个请求都不发**（省流量、省服务器配额）
- **不读云端**：客户端接口里根本没有读取方法 —— 不列目录、不下载、不比对，也不清理旧数据；没有冲突与冲突副本。历史文件多起来不需要本应用操心
- **文件名**：`<前缀><unix 秒时间戳><扩展名>`，前缀与扩展名来自「文件名」字段（默认 `note.txt` → `note1735689600.txt`）；同一秒内的第二次上传会 +1 秒避免撞名
- **目标目录不存在时会自动创建**（服务器回 409/404 时补一次 `MKCOL` 再重试上传）
- **节流**：自动同步（关闭编辑器后 / 打开应用时）之间至少间隔 30 分钟；「立即同步」不受限制
- **通知**：Android 13+ 需要通知权限才能看到进度条；拒绝权限时同步照常工作。每次**确实发生上传**时，进度通知至少显示 1.5 秒；上传成功还会弹 Toast「已上传到云端」
- **坚果云**：坚果云对「父目录不存在」「目录已存在」「读不存在的文件」都返回非标准的 **409**，客户端已逐条兼容并降级处理（见 [V7.5_ONE_WAY_UPLOAD_REPORT.md](V7.5_ONE_WAY_UPLOAD_REPORT.md)、[V7.4_NUTSTORE_409_REPORT.md](V7.4_NUTSTORE_409_REPORT.md)）
- **自签名证书**：https 握手失败时同步页会显示证书指纹，点击「信任并记住」后按该指纹固定校验；指纹变化会再次要求确认（不会无条件信任所有证书）

## 数据存储说明

- **用户内容**：`filesDir/user_content.dat`（DataStore 格式：`[UTF-8 内容][CRC32]`）
- **外观设置**：SharedPreferences `hello_prefs`（字体大小/颜色/背景等，非关键数据）
- **损坏恢复**：CRC 校验失败时保留现场文件 `corrupt_<时间戳>.dat`（**只保留最近 3 份**）并重建，应用始终可用
- **隐私**：`allowBackup="false"` 且 `dataExtractionRules` 排除全部域 → 内容不会上传 Google 云备份，也不会随设备迁移
- **同步凭据**：WebDAV 地址/用户名/口令同样存在 `hello_prefs`（因此也在备份排除范围内）；建议使用服务端的「应用专用密码」。开启同步后，**笔记内容会按你的设置上传到你自己的 WebDAV 服务器**（服务端保存的是明文文本）

## 开源许可证

本项目基于 **MIT License** 开源，任何人可自由使用、修改、分发（含商用），仅需保留版权声明与许可证文本，详见 [LICENSE](LICENSE)。

第三方组件声明（含应用图标出处）见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
