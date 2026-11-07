# AGENTS.md — 在本仓库工作的规则

本文件写给**在本仓库里工作的编码 agent**。它记录的是项目维护者（用户）与本项目长期协作中形成的硬性规则、流程与踩坑经验。

优先级：**用户当前的明确指令 > 本文件 > README.md**。本文件与 README 冲突时以本文件为准（典型例子：README 有「本地构建」章节，那是写给人类开发者的，**不构成对 agent 的本地构建许可**）。

规则若已过时（例如用户解除了某条限制），**先改本文件，再改行为**；不要一边违反一边在本文件之外解释。

---

## 1. 项目速览

| 项 | 值 |
|---|---|
| 应用 | `moe.hellowidget`：文本编辑器 + 桌面小组件；可选 WebDAV **单向上传**（不下载、不比对、无冲突） |
| 工程 | 单模块 `:app`，Kotlin + View 体系（无 Compose） |
| 工具链 | AGP **9.4.1**（内置 Kotlin / KGP 2.2.10）、Gradle **9.6.0**（wrapper 固定）、CI 用 **JDK 21**、字节码目标 **17** |
| SDK | compileSdk **36** / targetSdk **36** / minSdk **24** |
| 网络 | 运行时唯一网络库 **OkHttp 5.4.0**，全部在 `app/src/main/java/moe/hellowidget/sync/` |
| 存储 | 正文：DataStore（`filesDir/datastore`）+ CRC32；配置：`hello_prefs`（SharedPreferences） |
| 版本号 | **唯一来源** `gradle.properties` 的 `hellowidget.versionName` / `hellowidget.versionCode` |
| 发版 | push `main` → GitHub Actions 自动构建 + 发布 GitHub Release（tag 由 versionName 派生） |
| 许可证 | **MIT**。设计上参考过 GPL-3.0 项目（Fossify Notes），但**不得复制其代码/资源/文案** |
| 唯一测试者 | 用户本人。CI 能覆盖的在 CI 验，覆盖不了的要明确写「需真机验收」 |

---

## 2. 铁律（违反即返工）

1. **不在本地编译、跑测试、开模拟器**（用户明确要求：能上云的编译调试一律走 GitHub Actions）。
   允许的本地操作只有：读代码、`git`、`grep`/`git grep`、`android docs`、以及**只读**的产物检查（`aapt2 dump`、`apksigner verify`、`unzip -l`、`gh run view` 等）。
   **不要**执行 `./gradlew ...`、`sdkmanager`、`adb`、emulator（除非用户当轮明确要求）。
2. **Android 平台问题只用 `android docs` 查**（见 §3），不要凭记忆回答 API 行为，也不要拿网页搜索结果替代官方文档。
3. **先有证据再加代码**：每个改动必须能追溯到「用户的明确要求 / CI 抓到的真实失败 / 审计报告里的事实」。**不做预防性加固、不假设性加分支**。
4. **只做被要求的事**：不顺手加功能、不顺手重构无关代码、不为「更规范」改别人没让改的东西。本仓库历史上专门有过一版（v8.3.0）是**删掉自己乱加的功能**。
5. **版本号只改 `gradle.properties` 两行**；tag / Release 标题 / APK 元数据全部由它派生，禁止手工建 tag 或手改 Release。
6. **不新增权限、不新增依赖**。确有必要时：先说明证据与代价并征得用户同意；新增依赖前必须核对 AAR 元数据的 `minCompileSdk`（本项目卡在 36，见 §10）。
7. **CI 是唯一事实来源**：没跑通对应 CI job 之前，不得声称「已修复 / 已验证」。
8. **不提交**：`.qa-tmp/`、`local.properties`、`*.keystore`、构建产物、任何 secret。不把 secrets echo/打印进日志。
9. **双语文案必须同步**：`app/src/main/res/values/strings.xml` 与 `values-en/strings.xml` 条目一一对应（当前 120/120，改动后自行对齐）。
10. **不要删除/改写 `CHANGELOG.md` 的历史条目**（尤其是「边界」与「刻意没做」段落，它们是后人的证据）。
11. **禁止把工具缓存 / 中间产物放 `/tmp`**。本环境的 `bash` **每条命令相互独立，`/tmp` 不保留**（实测：上一条命令写进 `/tmp` 的文件，下一条命令就找不到了）。而 `android docs` 首次会下载约 **290MB**（CLI 运行时 bundle + 知识库），缓存若落在 `/tmp` 就等于**每次调用都重下一遍，纯烧流量**。
    需要可写 HOME 的工具，**只对该条命令**临时指定 HOME，缓存落在工作区的 gitignored 目录 `.android-user/`：
    ```bash
    HOME=$PWD/.android-user android docs search "app widget collections"
    ```
    **不要 `export HOME`** —— 那会把后续所有命令的 HOME 一起改掉（`gh` 认证、git 全局配置、Gradle 缓存都会跟着漂）。其它工具的缓存同理放工作区（如 `gh` 用 `XDG_CACHE_HOME=$PWD/.qa-tmp/ghcache`），下载的 APK / 日志 / 中间文件放 `.qa-tmp/`，**一律不要用 `/tmp`**。

---

## 3. 用 `android docs` 查 Android 文档（用户指定方式）

```bash
# 1) 搜索：返回标题 + kb:// URL + 摘要
android docs search "app widget collection RemoteViews"

# 2) 取全文
android docs fetch kb://android/develop/ui/views/appwidgets/collections

# 3) 环境自检（SDK 路径、版本）
android info
```

**环境注意（本工作区实测）：**

- `android` 在 PATH（`/home/pi/.local/bin/android`）；`android info` 报 `sdk: /home/pi/Android/Sdk`。
- 默认 HOME（`/home/pi`）不可写，直接跑会报 `Failed to open ".../.android/cli/bundles/lock": Read-only file system`。
- **正确写法：给这一条命令临时指定可写 HOME**，缓存落在工作区的 `.android-user/`（已 gitignore）：
  ```bash
  HOME=$PWD/.android-user android docs search "app widget collections"
  ```
  HOME 前缀只作用于这一条命令；**不要 `export HOME`**（会改掉后续所有命令的 HOME：`gh` 认证、git 全局配置、Gradle 缓存都会跟着漂）。
- 首次调用会下载约 **290MB**（CLI 运行时 bundle + Knowledge Base）。本环境 `/tmp` 每条命令独立、不保留，**缓存必须落在工作区**（`.android-user/`、`.qa-tmp/`），否则每次调用都会重下、纯烧流量。
- 若仍看到 `Failed to update Knowledge Base zip: /home/pi/.android/... Read-only file system` 的告警（CLI 的 Java 侧还在按默认 user.home 找缓存），把两个变量都指过去即可（等价、无告警）：
  ```bash
  HOME=$PWD/.android-user ANDROID_USER_HOME=$PWD/.android-user/.android android docs search "..."
  ```
- 工作区若已有 `.qa-tmp/adocs` 包装脚本（gitignored），作用等价，直接 `bash .qa-tmp/adocs docs search "..."`。
- 若运行环境提供 skill 机制，先加载 `android-cli` skill；否则直接用上面的命令。

**什么时候必须查：** API 行为/弃用/版本门控、Manifest 属性语义、权限、JobScheduler / 前台服务 / insets / 小组件 / DataStore 等平台机制、官方推荐做法。

**怎么用结论：** 把依据写进代码注释（附 `kb://` 路径或官方文章标题），让下一个人能复核。**不要**只写「官方文档说」却不给定位。

**什么时候不用查：** 纯 Kotlin 语法、项目内部业务逻辑、已有代码的既有决策（先读该文件 KDoc，它通常已经记录了原因与证据）。

**其他 `android` 子命令**（`layout`/`screen`/`run`/`install`/`emulator`/`analyze`/`preview`）：默认不用。`analyze` 属于只读产物检查，可用于看 APK/AAR 信息；需要真机交互的命令只在用户当轮明确要求时使用。

---

## 4. CI 工作流

### 触发方式（`.github/workflows/build.yml`）

| 分支 | 行为 |
|---|---|
| push `qa/**` | 只跑质量门禁 + 构建 + 仪器化 + release 冒烟，**不发版**（日常开发用这个） |
| push `main` | 上面全部 + **发布 Release**（tag = `v<versionName>`） |
| pull_request → main | 跑质量门禁（fork 无 secrets 时 release 包为未签名，流程不失败） |
| `workflow_dispatch` | 手动触发 |

Job 组成：`quality`（Lint + JVM 单元测试）→ `build`（debug/release APK）→ `instrumented`（模拟器 **API 35 与 36**，含 WebDAV 端到端）→ `release-smoke`（把要发布的 release 包装进模拟器起一次）→ `release`（仅 main）。

### 日常循环

```bash
git switch -c qa/<下一个版本号或主题>     # 例：qa/8.5.2、qa/security-audit
# ... 改代码、改测试、改文档 ...
git commit -m "v8.5.2: 一句话主题"        # 修 bug 用 fix(v8.5.2): ...，纯文档用 docs(...): ...
git push -u origin HEAD

# 观察 CI（gh 需已认证：gh auth status）
gh run list --branch qa/<...> --limit 5
gh run view <run-id> --json status,conclusion,url,jobs \
  --jq '"\(.status)/\(.conclusion) \(.url)", (.jobs[] | "\(.conclusion // .status)\t\(.name)")'
```

- 若本机 `~/.cache` 不可写导致 `gh` 报错，加 `XDG_CACHE_HOME=$PWD/.qa-tmp/ghcache`。
- 从 Actions / Release 下载的 APK、日志、artifacts 一律放 `.qa-tmp/`，**不要放 `/tmp`**（本环境 `/tmp` 每条命令独立，下一条命令就没了）。
- 工作区里可能有 `.qa-tmp/watch_quiet.sh <branch>` 之类**本地辅助脚本**（gitignored，别的环境不一定存在）；不要依赖它们，`gh` 命令才是通用做法。
- **失败时**：读 job 日志定位根因；区分「产品缺陷」与「测试写错/断言循环论证」两类（两种都发生过）；修因、重跑。**不要通过删断言、放宽 CI 或改 workflow 让红灯变绿。**

### 合并/发版

用户确认后：改版本号 + CHANGELOG + README → push `main` → 等 `quality/build/instrumented/release-smoke/release` 全绿 → 核对 Release（tag、Latest、APK）。

---

## 5. 发布清单（每次发版按顺序做完）

1. **版本号**：`gradle.properties` 的 `versionName` 升版、`versionCode` +1（用户指定时以用户为准）。
2. **CHANGELOG.md 顶部新增一条**，必须包含：**起因**（用户要求 / 真机证据 / CI 失败 / 审计发现）、**改动**、**证据**（CI run id、`tests=...`、真机截图/日志结论）、**边界**（做不到的、需用户真机验收的、刻意没做的）。格式沿用现有条目的 ①②③ 编号与加粗结论。
3. **README.md**：顶部「当前版本」、版本历史首条、受影响的特性行、以及任何已过时的说明。
4. **同步周边文档/注释**：相关 KDoc、XML 顶部注释（如 `widget_info.xml` 的尺寸契约）、`THIRD_PARTY_NOTICES.md`（若涉及新资源）。
5. **push `main`**，确认 Release `vX.Y.Z` 为 Latest；把 APK 下载到 `.qa-tmp/`（**不要 `/tmp`**）做只读校验：
   ```bash
   aapt2 dump badging app-release.apk | head          # versionCode/versionName/targetSdk
   aapt2 dump xmltree app-release.apk --file AndroidManifest.xml | grep -E "exported|permission|allowBackup"
   apksigner verify --print-certs app-release.apk      # 签名 SHA-256 必须与上一版一致
   unzip -l app-release.apk | head                     # 确认没有多出意外文件/原生库
   ```
6. **汇报**：本版改了什么、没做什么、边界是什么、需要用户在真机上验收哪几步。

---

## 6. 测试规则

- **修 bug 必须带回归用例**：先有能复现的断言（在 CI 上看到红），再修到绿；只补「跟着实现走」的断言不算。
- **断言行为，不锁实现**；特别警惕**循环论证**：不要用被测函数自己的返回值（或同一份 mock 状态）去证明同一件事。
- **Robolectric 限制**（已踩过）：
  - 无法用 `AppWidgetHost`/`allocateAppWidgetId`（需要系统签名权限）；
  - 未 attach 到窗口的 View，`performClick` 会投递到永不执行的 RunQueue —— 断言点击前要把 View attach 到真实 Activity 并 idle 主 Looper；
  - 默认 `LooperMode.PAUSED`，注意 `shadowOf(getMainLooper()).idle()`。
- **仪器化测试只在 CI 模拟器上跑**（API 35/36），不要本地 `connectedDebugAndroidTest`。
- 测试里**禁止真实联网**：端到端用 `.github/scripts/webdav_stub_server.py`（模拟器经 `10.0.2.2` 访问）。
- 时间/时区相关断言固定 `Asia/Shanghai`，避免 CI 机器时区导致漂移。
- 用例数量变化写进 CHANGELOG；数字以 CI 日志里的 `tests=... skipped=0 failures=0 errors=0` 为准，不要凭本地估算。

---

## 7. 代码与文案约定

- Kotlin，4 空格缩进；可见性最小化（`private` / `internal`）。
- **安全关键逻辑单独成文件 + 完整 KDoc**（例：`sync/TlsPinning.kt`）。
- **注释用中文，写「为什么 / 证据 / 边界」**，而不是复述代码在做什么；历史踩坑注释不要删。
- 所有用户可见文案进 `strings.xml` 双语；中文 Kotlin 字符串里用「」而不是直引号（aapt2 对撇号敏感）；KDoc 里避免出现 `*/*`（会破坏块注释）。
- 不新增全局可变单例（`ContentStore` / `SyncManager` 是既有例外）。
- 不引入 WebView / JS 桥；新增组件默认 `exported="false"`，需要跨进程暴露的必须配签名级权限；`PendingIntent` 一律 `FLAG_IMMUTABLE`。
- 改动前先读目标文件的 KDoc —— 本仓库大量设计决策（含「为什么不这样做」）都写在那里。

---

## 8. 安全与网络不变量（不得回退）

这些是历次审计与真机取证定下来的语义，改动任何一条都需要用户明确同意，并在 CHANGELOG 里写清代价：

- **无条件 `PUT`**：不带任何 `If-Match` / `If-None-Match` / `If-*-Since`；每次上传写**新文件**（历史天然保留）。
- **绝不跟随跳转**（`followRedirects=false`）：防止 Basic 凭据被 302 带去第三方域名。
- **TOFU 证书固定「只增不减」**：系统信任锚永远先校验，用户确认的指纹只是**额外**放行；不得改成「信任所有证书」。
- **绝不并发 `PUT`**（单飞 + 合并）；**要不要发请求只由内容哈希决定**；**只有失败才有重试间隔**。
- **保存即尝试上传；打开应用只读盘**（不检测、不联网）。
- **口令不得出现在日志 / Toast / 状态区 / 通知**（错误详情必须先 `sanitize`）。
- **明文 http 的风险提示必须保留**（其为自建 NAS 刻意支持）；「信任用户 CA」也是刻意配置 —— 不要以「更安全」为由收紧到让用户连不上自己的服务器。
- **备份规则就是「到底备份了什么」的可审计清单**（`backup_rules.xml` / `data_extraction_rules.xml`），改它必须同步 UI 说明、README、CHANGELOG。
- 依赖升级/安全评估：涉及网络层时，用公开漏洞库（OSV API、Snyk、GitHub Advisory）核对**精确版本**，把查询结论写进报告；不要凭「版本号看起来旧」下判断。

---

## 9. 许可与外部参考

- 本项目 **MIT**（`LICENSE`）。**可以**阅读 GPL 项目的公开源码做设计对比；**不可以**复制其代码、资源、文案、布局。实现依据以 Android 官方文档为准，并在注释里标注来源。
- 新增第三方资源必须更新 `THIRD_PARTY_NOTICES.md`。

---

## 10. 常见坑（都踩过）

- **本环境的 `/tmp` 是「每条命令独立」的**：上一条命令写进 `/tmp` 的文件，下一条命令就没了（实测确认）。**任何缓存、中间产物、下载物都不要放 `/tmp`**，放工作区的 gitignored 目录（`.android-user/`、`.qa-tmp/`）。
- **`~/.cache` / `~/.android` 是只读的**（沙箱环境）：`gh` 用 `XDG_CACHE_HOME=$PWD/.qa-tmp/ghcache`；`android` 用 `HOME=$PWD/.android-user`（单条命令前缀，别 `export`；必要时再补 `ANDROID_USER_HOME=$PWD/.android-user/.android`）。
- **不要相信 `META-INF/CERT*` 判断 APK 是否已签名**：minSdk 24 起 AGP 默认关闭 v1 签名，必须用 `apksigner verify`。
- **AGP 9 内置 Kotlin**：不要再加 `org.jetbrains.kotlin.android` 插件或 `kotlinOptions{}`。
- **`minCompileSdk` 门槛**：`core-ktx 1.19.x` 与 `okhttp-android 5.5.x` 都要求 compileSdk 37，本项目停在 36；升级依赖前先读 AAR 的 `aar-metadata.properties`。
- 不要开启 `android.enableJetifier`（会改写第三方字节码，遇 Java 21 class 直接崩）。
- aapt2 对 XML 里未转义的撇号报错；改完 `strings.xml` 靠 CI 的 quality job（`lintDebug` 会做资源编译）发现，**不要为此在本地跑构建**。
- API 36 模拟器自带硬键盘，软键盘默认不显示（影响 IME inset 用例）；CI 里用 `settings put secure show_ime_with_hard_keyboard 1` 修正。
- 仪器化测试跑的是 **debug** 包，而发布的是 **release** 包（R8 + 资源收缩）——所以有单独的 `release-smoke` job，别把「debug 绿了」当成可以发版。

---

## 11. 汇报格式（用户偏好）

- 中文；**先结论，后证据**。
- 给可复核的证据：CI run id、失败 job/用例名、`tests=...` 数字、日志关键行、APK 哈希/签名。
- **失败了就如实说**（哪一轮、哪个用例、为什么），不要隐瞒或粉饰；本仓库的 CHANGELOG 里记录过多起「CI 抓到了我的错误」的案例。
- 明确区分：**已验证**（CI 跑过）/ **无法验证**（需用户真机，例如 launcher 拖拽、像素级效果）。
- 不夸大：没做的事说没做（「刻意没做」是 CHANGELOG 的固定段落）。

---

## 12. 关键文件索引

| 路径 | 作用 |
|---|---|
| `gradle.properties` | 版本号唯一来源 |
| `.github/workflows/build.yml` | 唯一 CI 流水线（质量门禁/构建/仪器化/冒烟/发布） |
| `CHANGELOG.md` | 每次发版的起因、证据、边界（写代码前先读顶部几条） |
| `README.md` | 面向用户的功能说明与版本历史 |
| `app/build.gradle.kts` | SDK 版本、依赖及每个版本选择的原因注释 |
| `app/src/main/AndroidManifest.xml` | 权限、组件导出、备份策略（注释里有每条决策的代价） |
| `app/src/main/res/xml/network_security_config.xml` | 明文 http / 用户 CA 的刻意取舍 |
| `app/src/main/java/moe/hellowidget/sync/OkHttpWebDavClient.kt` | 网络客户端（超时、跳转、凭据） |
| `app/src/main/java/moe/hellowidget/sync/TlsPinning.kt` | TOFU 证书固定（安全关键） |
| `app/src/main/java/moe/hellowidget/ContentStore.kt` | 正文存储与损坏恢复 |
| `app/src/main/java/moe/hellowidget/TextWidgetProvider.kt` / `TextWidgetService.kt` | 小组件 |
| `DATA_PATH_AUDIT_V8.3.0.md`、`*_REPORT.md` | 历史审计与专项报告（结论与边界） |
| `app/src/test/` / `app/src/androidTest/` | JVM（Robolectric）/ 仪器化测试 |
| `.android-user/`、`.qa-tmp/` | 工具缓存与中间产物（**gitignored，不要提交**；也**不要**改用 `/tmp`） |

---

*本文件随项目规则演进而更新。修改前先确认用户意图；改完把「为什么改」写进提交说明。*
