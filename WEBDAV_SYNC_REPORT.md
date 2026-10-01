# HelloWidget v7.2 —— WebDAV 同步：设计、取舍与验证报告

> ⚠️ **本文描述的部分行为已被 v7.5 取代**：v7.5 起同步改为**纯单向上传**——
> 不再读取/比对云端状态，不再有冲突检测与冲突副本，也不再使用临时文件 + `MOVE`。
> 现行为见 [V7.5_ONE_WAY_UPLOAD_REPORT.md](V7.5_ONE_WAY_UPLOAD_REPORT.md)；
> 本文保留为当时的设计与验证记录。


> 本文记录 v7.2「WebDAV 同步」这一新功能的设计依据、产品决定、安全取舍，以及**每一条结论由什么证据支撑**。
> 写作风格与 [V7.1_RELEASE_REPORT.md](V7.1_RELEASE_REPORT.md) / [QA_FIX_REPORT.md](QA_FIX_REPORT.md) 保持一致：只写有证据的结论。

- 版本：`versionName=7.2` / `versionCode=17`（`gradle.properties` 单一来源）
- 兼容性：minSdk 21 / targetSdk 35 / compileSdk 35（未变）
- 依赖：**未新增任何第三方依赖**（同步客户端为零依赖自写实现）

---

## 1. 需求与产品决定

原始需求：**「添加 webdav 同步最新的内容」**。

需求本身有多个会显著改变实现的分叉点，因此先与用户逐条确认，结论如下（实现严格按此执行）：

| 分叉点 | 用户决定 | 对实现的影响 |
|---|---|---|
| 同步方向 | **只上传**（本地 → 云端）；自动路径永不下载 | 决策表以「本地哈希 + 云端 ETag」为基准，云端变了**不自动下载**；下载只出现在用户明确选择的「用云端覆盖本地」里 |
| 冲突处理 | **弹窗询问** | 检测到冲突既不覆盖云端也不覆盖本地，置「待处理冲突」并弹出对话框 + 通知栏两个动作按钮 |
| 冲突选项 | 提供「用云端覆盖本地」 | 该路径需先把本地版本作为冲突副本留在云端（否则本地版本会被销毁） |
| 触发时机 | **关闭编辑器后**跑一次；**打开应用时**检查上次是否成功，未成功则补一次 | 三条触发路径 + 手动按钮，全部汇入 `SyncLauncher` |
| 通知 | 同步期间通知栏留进度条，结束即停 | 前台服务（`dataSync`）+ 进度通知，`stopSelf` 后不留任何后台任务 |
| 节流 | **严格 30 分钟**（所有自动触发） | 被节流时不发任何请求；未上传的改动保留到下次允许的时机或「立即同步」 |
| 传输安全 | 允许明文 http + 支持自签名证书 | `cleartextTrafficPermitted="true"` + 界面风险提示 + TOFU 指纹固定（**不是** trust-all） |

### 1.1 「只上传」下冲突意味着什么

既然云端永远不会自动覆盖本地，冲突就只剩一种成因：**云端被本机之外的写入改过**（电脑网页端、其它设备、脚本）。此时若照常上传，就会**静默销毁**那次外部修改。因此判定基准是：

- `localHash` = 当前本地内容的 SHA-256（内容寻址，不受时钟漂移影响）
- `lastUploadedHash` = 上一次**成功上传**的内容哈希（`null` = 本机从未上传过）
- 云端是否被改过 = 比对上次观察到的 `ETag`（首选）→ `Last-Modified` → `Content-Length`

汇总成决策表（`sync/SyncEngine.kt`，纯函数，可 JVM 单测）：

| 云端 | lastUploadedHash | 云端被改 | 本地被改 | 动作 |
|---|---|---|---|---|
| 不存在 | 任意 | – | – | `PUT` 创建（`If-None-Match: *` 语义） |
| 存在 | `null`（首次） | – | – | 先 `GET` 比对：相同 → 确认一致；不同 → **冲突** |
| 存在 | 有 | 否 | 否 | 无事可做 |
| 存在 | 有 | 否 | 是 | 上传 |
| 存在 | 有 | 是 | 否 | **冲突**（云端被外部修改） |
| 存在 | 有 | 是 | 是 | **冲突**（双方都改） |

存在「待处理冲突」时，**所有自动触发一律跳过**（`SkipReason.PENDING_CONFLICT`）——否则下一次「关闭编辑器」就会把冲突里那份外部修改直接覆盖掉。

---

## 2. 架构与数据流

```
MainActivity（保存成功 / 打开应用）
SyncActivity（立即同步 / 冲突弹窗）
通知动作（SyncActionReceiver）
        │
        ▼
SyncLauncher ──► SyncService（前台服务 dataSync + 通知栏进度条）
                   │  SyncManager.performSync(trigger, resolution)
                   │    ├─ 闸门：启用？配置完整？待处理冲突？30 分钟节流？
                   │    ├─ ContentStore.read() → SHA-256
                   │    ├─ HttpWebDavClient.stat()  → SyncEngine.decide()
                   │    ├─ 上传（原子上传）/ 冲突（通知 + 弹窗）/ 无事可做
                   │    └─ 记录基线（哈希 + ETag/时间/大小）
                   ▼
              DataStore(user_content.dat) ──► TextWidgetProvider（桌面小组件刷新）
```

- 触发点全部在**用户可见的转换处**（返回键退出时仍在可见状态、失焦、打开应用、手动按钮、点击通知动作），因此不违反 Android 12+ 对后台启动前台服务的限制。
- 万一前台服务仍被系统拒绝（`ForegroundServiceStartNotAllowedException` 等），`SyncLauncher` 捕获后**降级为进程内协程同步**：功能不丢，只是没有通知栏进度。
- 同步结束即 `stopForeground(REMOVE)` + `stopSelf()`；应用其余部分保持「无轮询、无常驻、无周期任务」的原有设计。

---

## 3. 关键实现细节与理由

### 3.1 为什么不用 `HttpURLConnection`（本报告最重要的一条技术取舍）

计划阶段原本打算用 `HttpURLConnection`。落地前核对后发现一个**不可接受的硬约束**：

> `HttpURLConnection.setRequestMethod()` 在 JDK 实现里维护着方法**白名单**（GET / POST / HEAD / OPTIONS / PUT / DELETE / TRACE），其余方法抛 `ProtocolException`；Android 官方文档对该方法的说明沿用的是同一份白名单。

而 WebDAV 恰恰依赖白名单之外的动词：`PROPFIND` / `MKCOL` / `MOVE` / `COPY`。把功能压在「某个 Android 版本恰好没做这个校验」上，是把正确性交给运气。

因此改为**零依赖自写 HTTP/1.1**（`sync/HttpWebDavClient.kt`，基于 `Socket` / `SSLSocket`）。附带两个收益：

1. **同一份代码在 JVM 单测与模拟器上走完全相同的路径** —— 单测里用本机 `ServerSocket` 桩服务器，可以断言**真实发出的请求行与请求头**（而不是只断言自己的分支逻辑）；
2. 可以强制 `Connection: close` 并**绝不自动跟随跳转** —— 后者是 Basic 凭据泄漏的经典途径（302 到第三方域名会把 `Authorization` 一起送出去）。

代价是每个请求新建一条 TCP/TLS 连接（不复用连接池）。一次同步只有 3~8 个请求，换来的是没有连接状态、没有保活线程。

### 3.2 原子上传

与本地 DataStore 的「写临时文件 + 原子重命名」完全同构：

```
PUT  <file>.uploading        无条件写临时文件（固定名字，残留会被下次覆盖，不会堆积）
HEAD <file>                  覆盖前二次确认云端仍是决策时那个版本
MOVE <file>.uploading → <file>   Overwrite: T（覆盖）/ F（创建）
```

`MOVE` 的前置条件按 RFC 4918 校验的是**请求 URI（即临时文件）**，无法表达「目标必须没变」，所以第 2 步的二次 `stat` 才是真正的守护：它把竞态窗口从「整个上传时长」压缩到毫秒级。服务器不支持 `MOVE`（405/501）时降级为直接 `PUT`：放弃原子性，但保留前置条件语义（此时 `If-Match` / `If-Unmodified-Since` 才有意义）。

**已知的诚实边界**：二次确认与 `MOVE` 之间仍有极窄窗口，且部分服务器不校验 `MOVE` 的前置条件。这一点无法在客户端彻底消除，已在代码注释与本报告中如实标注。

### 3.3 冲突解决：任选一边都不丢数据

| 选择 | 步骤 |
|---|---|
| 保留本地 | ① 云端当前内容 → `COPY` 成 `note.conflict-<时间戳>.txt`（不支持 `COPY` 时 `GET` + `PUT`，1 MiB 上限）② 本地覆盖主文件 |
| 用云端覆盖本地 | ① 本地内容 → 上传成冲突副本 ② `GET` 云端内容 → 写回 `ContentStore` + 刷新小组件 ③ 记录「内容已被替换」时间戳，编辑页回到前台时自动重新载入 |

两条路径都保证**另一方的版本以副本形式保留在服务器上**；同一秒内的第二次冲突用 `-2`、`-3` 后缀去重，绝不覆盖上一份副本。

### 3.4 安全取舍

| 项 | 决定 | 理由 |
|---|---|---|
| 明文 `http://` | 允许（`network_security_config.xml` 的 `base-config cleartextTrafficPermitted="true"`），界面常驻红色风险提示 | 官方文档把「opt in to cleartext」列为应尽量避免，但自建 NAS 的 WebDAV 绝大多数只有 `http://192.168.x.x`，不允许则功能等于不可用；风险由用户在界面上明确可见 |
| 跳转 | **不跟随**，直接报错并提示填写最终地址 | 防止 Basic 凭据被 302 带到第三方域名 |
| 用户安装的 CA | 显式信任（`<certificates src="user"/>`） | API 24+ 默认不再信任用户证书库；加回后用户可把自建 CA 装进系统证书库 |
| 自签名证书 | **TOFU 指纹固定**：握手失败时显示叶子证书公钥的 SHA-256 指纹，用户确认后才信任；此后只接受该指纹，指纹变化再次要求确认 | 既支持自签名 NAS，又不是 `trust-all`；不使用 `setDefaultSSLSocketFactory`，不影响全局 |
| 口令存储 | 应用私有 SharedPreferences（已排除云备份与换机迁移），界面建议使用**应用专用密码** | 在未 root 设备上其他应用读不到；真正的威胁模型（拿到已解锁设备的人）下，本机密钥同样在设备内，边际收益很小。此取舍已写进代码注释与 README |
| 日志 | 任何日志与错误信息都经过口令脱敏 | 防止把凭据写进 logcat |
| 远端体积 | 读取上限 1 MiB（与编辑器 100,000 字符上限配套） | 防止误把超大文件读进内存导致 OOM |

---

## 4. 与既有保证的兼容性

- **同步默认关闭**（`sync_enabled` 默认 `false`）：未启用时 `SyncLauncher` 直接返回，不启动任何服务；v7.1 的「进入即输入 / 光标在行首 / 退出即保存」行为与全部既有测试保持不变。
- **编辑中绝不覆盖编辑器**：自动路径只上传，永远不会在用户打字时替换编辑器内容；唯一会写回本地的路径是用户在冲突弹窗里明确选择「用云端覆盖本地」，并且会在编辑页 `onResume` 时重新载入 + 提示。
- **上传的是「已落盘的内容」**：只有 `ContentStore.write()` 成功返回后才触发上传，不会把磁盘上的旧内容推到云端，也不会把内存里的半成品推上去。
- 底部按钮由 1 个变为 2 个（`⚙ 外观设置` / `☁ WebDAV 同步`），v7.1 的「按钮不被键盘遮挡」断言按 id 判定，仍然成立（已由模拟器测试复验）。

---

## 5. 验证

### 5.1 分层证据

| 断言 | 证据 | 环境 |
|---|---|---|
| 决策表 6 行、云端变化三级降级、首次同步先比对、30 分钟边界、手动绕过、冲突命名、SHA-256 已知向量 | `SyncEngineTest`（纯 JVM） | CI `quality` |
| 地址归一化（补 `/`、剥离 URL 里的用户名口令、拒绝无 scheme/ftp/query）、文件名安全（拒绝 `/` `..` 等）、百分号编码、HTTP 日期解析 | `SyncConfigTest`（纯 JVM） | CI `quality` |
| 真实请求行/请求头：`HEAD` 元数据、`PROPFIND` 回退、`MKCOL` 语义、`PUT` 正文与 `Content-Type`、`If-Match`/`If-Unmodified-Since` 二次确认、**302 不跟随（凭据不外泄）**、原子上传 `PUT .uploading` + `MOVE`、`MOVE` 不支持时降级、错误分类、1 MiB 上限、分块/EOF 正文 | `HttpWebDavClientTest`（Robolectric + 本机 `ServerSocket` 桩，断言桩收到的原始请求） | CI `quality` |
| 同步状态持久化、失败不清哈希基线、冲突挂起、触发入口、**未启用同步时不启动任何服务** | `SyncSettingsTest`（Robolectric） | CI `quality` |
| **真实 Android 网络栈**下的完整流程、服务器端字节一致、冲突不覆盖、两种解法都留副本、节流不发请求、前台服务 + 通知渠道 + 结束后服务停止 | `SyncE2eInstrumentedTest`（API 34 模拟器 × runner 上真实运行的 Python WebDAV 服务器） | CI `instrumented` |
| 「真的用了 WebDAV 动词」 | 模拟器测试读取**服务器端请求日志**（`HEAD/PROPFIND`、`MKCOL`、`PUT *.uploading`、`MOVE`、`COPY`），日志同时打印在 Actions 里 | CI `instrumented` |
| 自签名证书 → 报 `TLS_UNTRUSTED` 并回传指纹；确认指纹后放行；指纹不对仍拒绝 | `TlsPinningTest`（测试专用自签名证书 + 真实 `SSLServerSocket` 握手，见 `app/src/test/resources/tls/`） | CI `quality` |

### 5.2 本轮实测结果

**qa 分支预验证**：[Build & Release #36835813525](https://github.com/llllllllqq/hellowidget/actions/runs/36835813525)（head `c281b9a`）

| 作业 | 结果 |
|---|---|
| Lint & Unit Tests | ✅ Lint **0 error / 6 warning**（5×GradleDependency + 1×OldTargetApi，与 v7.1 基线完全一致）；JVM 单测 **87 个全通过**（v7.1 为 24 个） |
| Build APKs | ✅ Debug + Release 均构建成功 |
| Instrumented Tests (emulator) | ✅ **7 个用例全通过**（1 个 v7.1 输入法用例 + 6 个 WebDAV 端到端用例），跑在 API 34 模拟器上 |
| Publish GitHub Release | ⏭️ 仅 `main` 分支才发布（`qa/**` 故意跳过） |

**main 分支发布验证**：[Build & Release #36836264457](https://github.com/llllllllqq/hellowidget/actions/runs/36836264457)（head `ba8a509`）

| 项 | 实测值 |
|---|---|
| 四个作业 | Lint & Unit Tests ✅ / Build APKs ✅ / Instrumented Tests (emulator) ✅ / Publish GitHub Release ✅ |
| Release | [`v7.2`](https://github.com/llllllllqq/hellowidget/releases/tag/v7.2)，非草稿、非预发行，`publishedAt=2026-10-01T08:29:51Z` |
| 产物 | `app-release.apk` 970,534 B（含 `META-INF/CERT.RSA` + `CERT.SF`，已签名）、`app-debug.apk` 3,619,685 B |
| APK 元数据（aapt2 badging） | `versionCode='17' versionName='7.2' minSdkVersion:'21' targetSdkVersion:'35' compileSdkVersion='35'` |
| 权限 | `INTERNET` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`（无多余权限） |
| 发布包内容 | `resources.arsc` 含 `network_security_config` 与新增字符串资源 → v7.2 的功能确实进了发布包；1 个 dex、无未混淆的 `moe/hellowidget` 条目（R8 压缩/混淆仍然有效） |
| v7.1 回归 | 「进入即输入（光标行首 + 自动弹输入法）」仪器化用例与全部既有单测仍然通过 |

服务器端请求日志（CI 原样打印，每个用例用独立文件名，因此一次运行里所有场景都在证据里）：

```
HEAD /dav/note-...txt            -> 404 missing        ← 先取云端元数据
MKCOL /dav                       -> 201 created         ← 自动创建目标目录
PUT  /dav/note-...txt.uploading  -> 201 len=51 created  ← 先写临时文件（不碰正式文件）
MOVE /dav/note-...txt.uploading  -> 201 Dest=/dav/note-...txt Overwrite=F   ← 原子换名＝创建
HEAD /dav/note-...txt            -> 200 size=51         ← 记录新基线

HEAD /dav/note-...txt            -> 200 size=21         ← 云端被外部改过（ETag/大小已变）
COPY /dav/note-...txt            -> 201 Dest=...conflict-20261001-082433.txt ← 冲突：先留副本
MKCOL /dav                       -> 405 exists
PUT  /dav/note-...txt.uploading  -> 201 len=15 created
HEAD /dav/note-...txt            -> 200 size=21         ← 覆盖前二次确认云端仍是决策时版本
MOVE /dav/note-...txt.uploading  -> 204 Dest=...txt Overwrite=T  ← 确认后才覆盖

GET  /dav/note-...txt            -> 200 size=12         ← 「用云端覆盖本地」：先取云端内容
PUT  /dav/note-...useRemote...conflict-20261001-082443.txt.uploading -> 201 len=12 ← 本地版本先留副本
MOVE ...conflict-20261001-082443.txt.uploading -> 201 Overwrite=F                  ← 副本落盘，官网端可查
HEAD /dav/note-...txt            -> 200 size=12         ← 云端主文件始终未被改动
```

这份日志回答了两个「光看代码说不清」的问题：**Android 网络栈确实接受 `MKCOL`/`MOVE`/`COPY`**（§3.1 的取舍成立），以及**冲突与二次确认真的在设备上按设计执行**（不是只在单测的假桩里成立）。

在 CI 上跑的 WebDAV 服务器是仓库自带的零依赖实现 `.github/scripts/webdav_stub_server.py`（仅标准库，绑定 `127.0.0.1`，模拟器经 `10.0.2.2` 访问），支持 `OPTIONS/HEAD/GET/PUT/DELETE/PROPFIND/MKCOL/MOVE/COPY`、Basic 鉴权、`If-Match`/`If-None-Match`/`If-Unmodified-Since` 求值，并提供只读控制面（`reset`/`log`/`count`/`file`/`etag`/`exists`）供测试断言服务器侧状态。已用 28 项本地脚本复查其与客户端的交互序列（含 `PUT temp → HEAD → MOVE`、`Overwrite=F` 并发创建返回 412 等）。

### 5.3 尚未被自动化覆盖的部分（如实说明）

| 缺口 | 原因 | 建议的验证方式 |
|---|---|---|
| 真实 WebDAV 服务（坚果云 / Nextcloud / 群晖） | CI 无法访问用户的自建服务，也不应把用户凭据放进 CI | 发布后由用户按 README「WebDAV 同步使用说明」做一次冒烟（填地址 → 立即同步 → 网页端看文件 → 网页端改文件 → 回来处理冲突） |
| **Android 上**的自签名 TLS 握手 | 模拟器端到端用例走明文 http（TLS 策略已由 JVM 层真实握手测试覆盖，但 Conscrypt 与 OpenJDK 的异常包装细节可能不同） | 自签名场景由用户在同步页按提示确认指纹即可 |
| OEM 定制 ROM 的前台服务/通知差异 | 只跑了 AOSP API 34 | 装机后目视确认通知栏进度条与冲突通知 |
| `MOVE` 与二次确认之间的极窄竞态窗口 | 协议本身无法表达「目标必须没变」 | 已用「先 stat 再 MOVE」把它压到毫秒级；如需更强保证只能改用直接 `PUT` + `If-Match`（放弃原子上传） |

---

## 6. CI 编排上的两个坑（记录备查）

1. **`ReactiveCircus/android-emulator-runner` 会把 `script:` 的每一行交给独立的 `/usr/bin/sh` 执行**（Actions 日志里是每行一条 `[command]/usr/bin/sh -c <行>`）。因此变量赋值、`for` 循环、反斜杠续行都无法跨行生效 —— 第一版把编排直接写在 `script:` 里，结果是 `--port ""`（`PORT=8080` 随它那一个 shell 一起消失了）。现在编排收在 `.github/scripts/run_webdav_e2e.sh`，workflow 只留一行 `bash .github/scripts/run_webdav_e2e.sh`。
2. 该 `sh` 是 **dash**：`set -o pipefail` 之类的 bash 特性会直接报 `Illegal option`。脚本里用 `bash` 明确指定解释器。

这两点带来的额外好处：端到端流程本地也能一条命令复现。

## 7. 复现方式

```bash
# 质量门禁 + 构建 + 仪器化测试（云端，不需要本地 Android 工具链）
git push origin qa/7.2-webdav      # 触发 quality / build / instrumented

# 本地只想跑逻辑层（JVM）
./gradlew :app:lintDebug :app:testDebugUnitTest

# 本地跑端到端（需要模拟器 + 本机 WebDAV 桩）
python3 .github/scripts/webdav_stub_server.py --port 8080 --user test --password test
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.webdavUrl=http://10.0.2.2:8080/dav/ \
  -Pandroid.testInstrumentationRunnerArguments.webdavControlUrl=http://10.0.2.2:8080/__control__/
```

发版：`hellowidget.versionName` / `hellowidget.versionCode` 两行 → 推 `main` → CI 自动发 Release（`release` job 依赖 `quality` + `build` + `instrumented`，仪器化测试不过就不会发版）。
