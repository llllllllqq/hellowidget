# HelloWidget

一个轻量 Android 应用：**文本编辑器 + 桌面小组件**。在应用里输入任意长文本，退出后内容以**可上下滚动的小部件**形式展示在桌面，支持自定义外观，且**零后台进程、数据永不损坏**。

## 功能特性

| 特性 | 说明 |
|---|---|
| 📝 文本编辑 | 全屏多行编辑器，内容仅在退出 / 返回 / 切后台时保存（不做编辑自动保存） |
| 🪟 可滚动小组件 | ListView 集合式小组件，桌面即可上下滑动阅读全部内容（所有 Android 版本支持） |
| 🎨 外观自定义 | 设置页可调字体大小(10–34sp)、字体颜色、背景颜色、背景透明度，实时预览即时生效，支持自定义 RGB 取色 |
| 🔒 原子写入 | 内容存储采用 **Jetpack DataStore** 官方原子写入（临时文件 + fsync + 原子重命名），任意时刻崩溃都不会产生"写一半"的损坏文件 |
| ✅ CRC32 校验 | 文件格式 `[UTF-8 内容][4字节 CRC32]`，读取时校验；发现损坏自动保留现场文件并重建 |
| 🪫 零后台占用 | 无自动保存、无轮询、无常驻服务。小组件数据服务为绑定式，仅桌面渲染时才临时启动；保存完成即结束，CPU 自动释放 |
| 🔔 保存确认 | 真正写盘成功后才提示「已保存 ✓」；失败提示「保存失败」，旧内容不受影响 |

## 版本历史

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

1. 编译 Debug + Release（Release 使用仓库内 keystore 签名）
2. 上传构建产物（Actions 页面 Artifacts）
3. **自动发布 GitHub Release**，附签名 APK，可直接下载：

```
https://github.com/llllllllqq/hellowidget/releases/latest
```

### 触发方式

- **自动**：推送 `main` 分支
- **手动**：仓库 Actions 页 → **Build Android APK** → **Run workflow**

### 所需 Secrets

Release 签名使用以下仓库 Secrets（已配置）：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 签名 keystore 文件的 Base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

## 本地构建

```bash
# 需要 Android SDK（本地 sdk.dir 写入 local.properties）
gradle assembleDebug     # Debug APK
gradle assembleRelease   # 签名 Release APK（需上述 4 个环境变量）
```

## 项目结构

```
hellowidget/
├── .github/workflows/build.yml   # GitHub Actions 云编译 + 发布 Release
├── app/
│   ├── build.gradle.kts           # 构建配置（compileSdk 34, minSdk 21）
│   └── src/main/
│       ├── AndroidManifest.xml    # 清单（Application/Activity/小组件 Receiver/Service）
│       ├── java/moe/hellowidget/
│       │   ├── MainActivity.kt    # 编辑器 + 保存流程（onStop 保存，返回键先存后退）
│       │   ├── SettingsActivity.kt# 小组件外观设置页（含 RGB 取色）
│       │   ├── HelloWidgetApp.kt  # Application：初始化全局 DataStore
│       │   ├── ContentStore.kt    # DataStore 原子写入 + CRC32 序列化 + 旧数据迁移
│       │   ├── TextWidgetProvider.kt  # 小组件 Provider（绑定数据源/背景色/点击）
│       │   ├── TextWidgetService.kt   # 绑定式数据服务（按行渲染文本）
│       │   └── WidgetSettings.kt  # 外观设置存取
│       └── res/                   # 布局、字符串、主题、widget_info
├── build.gradle.kts               # 根构建配置（AGP 8.2.2, Kotlin 1.9.22）
├── settings.gradle.kts
├── gradle.properties
└── hello-release.keystore         # 签名密钥库
```

## 数据存储说明

- **用户内容**：`filesDir/user_content.dat`（DataStore 格式：`[UTF-8 内容][CRC32]`）
- **外观设置**：SharedPreferences `hello_prefs`（字体大小/颜色/背景等，非关键数据）
- **损坏恢复**：CRC 校验失败时自动保留现场文件 `corrupt_<时间戳>.dat` 并重建，应用始终可用
