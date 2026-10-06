# HelloWidget

一个轻量 Android 应用：**文本编辑器 + 桌面小组件**。在应用里输入文本，退出后内容以**可上下滚动的小部件**形式展示在桌面，支持自定义外观，且**零后台进程、数据永不损坏**。

当前版本：**v8.0.0**（versionCode 28，compileSdk 36 / minSdk 24 / targetSdk 36）

## 功能特性

| 特性 | 说明 |
|---|---|
| 📝 文本编辑 | 全屏多行编辑器，内容仅在退出 / 返回 / 切后台时保存（不做编辑自动保存） |
| 🧭 顶部导航栏 | 编辑页顶部一条应用栏，4 个常驻入口一眼可见：**外观设置 / WebDAV 同步 / 立即上传 / 撤回**（原先占屏幕底部的两个按钮已上移删除）。导航栏高度固定 56dp，上方那条系统栏（状态栏 / 刘海 / 挖孔）由**独立占位条**承担高度 —— 系统栏再高也不会挤压标题和按钮（v7.7.1 修复） |
| ↩️ 无限次撤回 | 顶部「撤回」按钮逐步退回每一次编辑，**不设次数上限**，直到精确变回刚打开应用时的内容；旋转 / 深色模式重建后撤回历史依然可用；撤回后光标自动落在改动处 |
| ⬆️ 立即上传 | 顶部「立即上传」按钮：先把编辑器当前内容落盘，再立刻上传到云端，**不受 1 分钟节流限制**；未启用/未配置同步时会明确提示去设置，而不是点了没反应。**上次成功上传后本地又有改动时，图标右上角亮一个橙色圆点**（小米橙，运行时叠加，不换图标）—— 打开应用时只做这个检测，**不会因此自动上传**（v7.8） |
| ☁️ WebDAV 同步 | 把最新内容**纯单向上传**到你自己的 WebDAV（坚果云 / Nextcloud / 群晖 NAS）：只依赖本机记录判断内容有没有变，变了就写一个**新文件** `note<unix 时间戳>.txt`，旧文件永不触碰 —— 云端自动保留每一次上传的历史，也就不需要读取、比对或清理远端。同步期间通知栏有进度条（**每次上传都保证可见**，最短 1.5 秒），上传成功还会弹一个 Toast；结束立即停止，不留常驻后台 |
| ⌨️ 进入即输入 | 打开应用后光标自动落在**第一行行首**，并**自动弹出输入法**，省掉一次点击（读盘完成后触发，不会先弹键盘后填内容） |
| 🌙 自适应深色模式 | 编辑页随系统深色模式自动切换黑白，切换时自动保存当前内容；小组件颜色不受影响 |
| 🪟 可滚动小组件 | ListView 集合式小组件，桌面即可上下滑动阅读全部内容（所有 Android 版本支持） |
| 🎨 外观自定义 | 设置页可调字体大小(10–34sp)、字体颜色、背景颜色、背景透明度；**字体大小同时决定编辑器的文字大小**（改一处两处同步）；并可分别自定义**浅色模式 / 深色模式编辑器**的背景色与文字色；实时预览即时生效，支持自定义 RGB 取色 |
| 🔒 原子写入 | 内容存储采用 **Jetpack DataStore** 官方原子写入（临时文件 + fsync + 原子重命名），任意时刻崩溃都不会产生"写一半"的损坏文件 |
| ✅ CRC32 校验 | 文件格式 `[UTF-8 内容][4字节 CRC32]`，读取时校验；发现损坏自动保留现场文件并重建 |
| 🪫 零后台占用 | 无自动保存、无轮询、无常驻服务。小组件数据服务为绑定式，仅桌面渲染时才临时启动；自动上传**只跟在「保存内容」之后**（退出 / 返回 / 切后台 / 旋转 / 深色模式等所有保存路径），两次自动上传至少间隔 1 分钟，结束即停前台服务；打开应用只做一次本地检测（读哈希，不联网）并据此点亮橙点（v7.8）。v7.9 起另有**一条**系统级兜底：保存成功后若上传没能完成，会给系统排一个（固定 id、只会有一个）**一次性持久化重试任务**，系统在**有网络**时拉起进程补传，成功即撤销 —— 不轮询、不常驻、失败按指数退避，且**最多自动重试 5 次**（用完就停，不长期驻留） |
| 🔇 静默保存 | 保存成功不弹任何提示（v7.7 起删除全部「已保存」toast）；只有写盘失败才提示「保存失败」，旧内容不受影响 |
| 🔐 隐私优先 | `allowBackup=false` + `dataExtractionRules`：用户文本**既不参与云备份，也不参与设备间迁移** |
| 📏 长度上限 | 内容上限 100,000 字符，避免超大文本导致内存溢出；超出时给出可见提示 |
| ♿ 无障碍 | 色板具备可访问名称、选中状态与 48dp 触控目标；滑杆带 `labelFor` 关联标签；顶部导航栏的图标按钮均带中文可访问名称 |
| 🌐 中英双语 | 默认中文，`values-en` 提供英文 |

## 版本历史

- **v8.0.0** 工具链与依赖整体升级（功能不变；唯一的用户可见变化是**不再支持 Android 5.0/5.1/6.0**）。①**minSdk 21 → 24**：这是 appcompat 1.8.0（默认 minSdk 已提到 23）与 Robolectric 4.16+（已移除 SDK 21/22 模拟）的前提，同时白拿原生 multidex 与 API 24 起的 `JobScheduler.getPendingJob`，三处按版本号分叉的死分支随之删除。②**targetSdk 35 → 36、compileSdk 35 → 36**：Google Play 自 **2026-08-31** 起要求新包与更新必须 target API 36+，35 已不合规。③**AGP 8.6.1 → 9.4.1、Gradle 8.7 → 9.6.0**，并改用 **AGP 内置 Kotlin**（随 AGP 提供 KGP 2.2.10）——`org.jetbrains.kotlin.android` 与 AGP 9 的新 DSL 不兼容，官方迁移指南要求移除；`kotlinOptions{}` 随之下线（`jvmTarget` 默认等于 `compileOptions.targetCompatibility`，仍是 17）。④依赖整体升级：**appcompat 1.8.0 / core-ktx 1.18.0 / activity-ktx 1.13.0 / lifecycle-runtime-ktx 2.11.0 / datastore-core 1.2.1 / OkHttp 5.4.0 / Robolectric 4.17**（`androidx.test` 与 JUnit 已是最新，未动）。两个**只有读产物才知道**的硬约束决定了这条线的上限：`androidx.core:core:1.19.x` 的 AAR 元数据要求 `minCompileSdk=37`，`okhttp-android:5.5.0` 同样要求 37（5.4.0 才是 36），所以停在 compileSdk 36 + core-ktx 1.18.0 + OkHttp 5.4.0；升级前先按 Gradle module metadata 把整棵依赖图（45 个模块）的 aar-metadata 核了一遍，**0 个与 compileSdk 36 / minSdk 24 冲突**，并对 `okhttp-android-5.4.0` 的 classes.jar 逐个 `javap` 确认要用的 API 与 Kotlin 元数据（`mv=[2,1,0]`）都在。⑤CI：三个 job 统一 **JDK 21**（Robolectric 4.16+ 在 SDK 36 上跑测试强制要求），Gradle action 升到 v6.4.0，模拟器矩阵由 **API 34/35 改为 API 35/36**（targetSdk 36 的行为变更——预测性返回默认开启、边到边 opt-out 被彻底禁用等——只有 API 36 真机能验；原先 34 那档关心的「未强制边到边 + 显式 `enableEdgeToEdge()`」路径由 Robolectric 的 `@Config(sdk = [34])` 继续覆盖），并新增 **release 包冒烟**：AGP 9 起资源收缩完全并入 R8（`optimizedResourceShrinking`）、keep 规则语义收紧（`strictFullModeForKeepRules`），而仪器化测试跑的是 debug 包 —— 现在会把真正要发布的 release 包装进模拟器启动一次，确认进程存活且无 `FATAL EXCEPTION`。⑥升级过程中被真机 CI 抓到的两件事（静态分析都看不见）：**API 36 系统镜像自带硬件键盘**，而 workflows 里的 `enable-hw-keyboard: false` 在 emulator-runner 里其实是**空操作**（它只在为 true 时才写 `hw.keyboard=yes`），于是软键盘根本不会被画出来、`ime()` inset 恒为 0 —— 同轮 35/36 对照取证：API 35 的 `pIme=294`、API 36 的 `pIme=0`（窗口高度都没变），修复是脚本里显式 `settings put secure show_ime_with_hard_keyboard 1`；以及 **appcompat 1.8.0 有两条变更恰好落在我们「绕 AppCompat 内部行为」的两处实现上**（Toolbar 高度计算 → v7.7.1 那次的固定 56dp 顶栏；配置变更分发到 view tree → `configChanges="uiMode"` 的深色模式手动重建），两条既有回归用例正盯着它们。⑦顺手清掉两处历史遗留：`themes.xml` 里从 Material 模板抄来、在 `Theme.AppCompat.*` 中并不存在的属性残留（aapt 会静默忽略）与那次手改留下的错误缩进；以及 Manifest 上 minSdk 21 时代的 `tools:ignore="UnusedAttribute"` 兼容标注。⑧minSdk 24 还让 AGP **默认关闭 v1（JAR）签名**（v2/v3 已足够覆盖 API 24+），于是 CI 里两处「包有没有签名」的判定（release job 与新增的 release 冒烟）必须从 `META-INF/CERT` 换成 `apksigner verify` —— 实测 release 包里确实只有 v2/v3 签名块，旧判定会把已签名的包判成未签名，直接卡死发版。
- **v7.9.0** 修「保存了却没上传、只能清后台」这个报障。①**网络层换成 OkHttp 4.12.0**（单例原型客户端 + `connectTimeout 15s` / `readTimeout 30s` / `writeTimeout 30s` / **`callTimeout 60s`**，`close()` 真正 cancel 在途请求）：旧的手写 Socket 客户端**没有写超时**、`close()` 也是空实现，一条半开连接（对端不再 ACK / 网络切换 / NAT 映射失效）就能让阻塞的 `write()` 卡住，而全局互斥锁被它占着 → 之后每一次保存、甚至「立即上传」都只是静默排队，进程被系统冻结时更会表现为「永久坏掉」；`callTimeout` 官方语义是覆盖 DNS 解析 → 建连 → 写正文 → 服务端处理 → 读响应**全流程**。**为什么不是 OkHttp 5.x**：5.x 的 class 元数据版本是 `mv=[2,1,0]`，需要 Kotlin ≥ 2.1 才能消费，而本项目编译用的是 Kotlin 2.0.21（会直接报 "Module was compiled with an incompatible version of Kotlin"）；4.12.0 的 `mv=[1,8,0]` 与 Kotlin 2.0.x 兼容，且本次要用的能力（`callTimeout`、`Call.cancel`、`sslSocketFactory(factory, tm)`、`Credentials.basic(u, p, UTF_8)`）它全都有 —— 升 Kotlin 与升 OkHttp 5 留作单独的后续改动，不混进这次修 bug 的版本。②**自愈通道**：写盘成功后立刻给系统排一个 `JobScheduler` **一次性持久化任务**（`NETWORK_TYPE_ANY` + 指数退避 + `setPersisted`，id 固定只会有一个），上传成功即撤销；只对**看起来是暂时性**的失败（网络 / 超时 / IO / 5xx / 目录缺失 / 锁定 / 配额）重试，凭据错误、证书不受信这类需要人介入的失败不自动重试（避免重试风暴）；自动重试**最多 5 次**（指数退避，60 秒起步），用完就停 —— 这样既不做「后台长期驻留」（系统里只留这一个一次性任务，成功即撤销，没有任何周期任务、常驻服务或线程），又能覆盖「网络抖一下 / 服务器短暂不可用」这类瞬时故障；预算用完时同步设置页显示「已用完 N 次自动重试」，用户点「立即同步」或再次保存即可重新排队。为此**新增一个 normal 级权限 `ACCESS_NETWORK_STATE`**：`JobSchedulerService.enforceValidJobRequest` 对「带连通性约束的任务」强制要求调用方持有它，少了它 `schedule()` 直接抛 `SecurityException`、整条自愈通道静默失效（这正是 CI 真机日志抓到的：`ACCESS_NETWORK_STATE required for jobs with a connectivity constraint`）；该权限安装即授予、无运行时弹窗、不涉及隐私，代价是「一个网络权限都不要」的说法到此为止 —— 换来的是「保存了却没传上去」有一条系统级兜底。③**失败不再静默**：进锁前后、服务启动 / 结束（含耗时）、同步结果都打日志；同步设置页新增「上次尝试」与「自动重试：已排队 / 无」两行，复发时用户截一张图就够。④协程被取消也留下明确终态（旧实现会让状态行永远停在「正在同步…」），客户端构造异常同样被接住。⑤`startForeground()` 失败改为降级为进程内同步（旧实现是未捕获异常 → 直接崩溃），并实现 Android 15 要求的 `Service.onTimeout()`。⑥JVM 用例 117 → 148 条（任务排入 / 只留一个 / 撤销 / 退避与最短延迟 / 临时性失败判定 / 成功后结束 / 取消后终态 / 重试预算上限 / 保存路径确实排了任务 / Manifest 权限与 `BIND_JOB_SERVICE` / OkHttp 的整次调用超时与 `close()` 取消），真机用例新增 1 条（失败后系统必须收下重试任务、网络恢复后补传成功并撤销），CI 还会导出同步相关 logcat。**不变量全部保持**：无条件 PUT、不跟随跳转、无任何 `If-*`、TOFU 指纹只增不减、绝不并发 PUT、1 分钟闸门与橙点语义不变
- **v7.8.0** 上传时机收敛为「**保存即上传**」+ 打开应用**只看不传**：①删除「打开应用（含旋转 / 深色模式重建 / 进程恢复）自动补一次上传」这条路径（`SyncTrigger.APP_OPEN` 与 `SyncEngine.shouldSyncOnOpen` 一并删除），打开应用改为**纯检测** —— `SyncManager.hasPendingUpload()` 用「当前内容哈希 vs 上次成功上传的哈希」判断有没有改动，零网络、零落盘、不写 `lastAttemptAt`；②检测结果表现为顶部「立即上传」按钮图标右上角的**橙色圆点**（`#FF6900` 小米橙，紫底上最醒目；用 `LayerDrawable` 在运行时叠加，**不替换原图标、不新增图标资源**），成功上传后自动熄灭；③**任何保存都触发自动上传**（返回键 / 失焦 / 切后台 / 旋转 / 跳设置页 / 深色模式切换，原来这后三种是静默保存不上传），密集保存由 1 分钟闸门合并；④删除 `syncAfterLeave()`（没有内容需要保存就不该有上传）；⑤上传失败改为弹 **Toast** 报错（不再留失败通知，原因仍记在同步状态行）；⑥新增 8 条 JVM 用例（检测语义、橙点开关、空内容新用户不亮、保存即上传）与 3 条真机用例（打开应用不碰服务器、橙点真的被画成橙色像素、无改动时零橙色像素）
- **v7.7.2** 自动同步的节流间隔从 **30 分钟缩短为 1 分钟**（`SyncEngine.MIN_SYNC_INTERVAL_MS = 60 * 1000L`）：关闭编辑器 / 打开应用触发的自动同步，现在只要距上次尝试满 1 分钟就会执行，不再一次失败就干等半小时；「立即上传 / 立即同步」的 MANUAL 通道依旧完全不受节流限制，其余行为未改。单测新增一条钉住该值的用例（`SyncEngineTest.throttleInterval_isOneMinute`）
- **v7.7.1** 🔥 修复 v7.7.0 的严重可用性问题：**顶部导航栏在系统栏较高的手机上被挤成一条缝，标题和 4 个按钮全都看不见**。原因是 v7.7.0 把顶部系统栏 inset 当成了导航栏自己的 `paddingTop`，而导航栏高度是固定的 `?attr/actionBarSize`（56dp）—— 内容可用高度变成「56dp − 系统栏高度」，在用户手机上（`systemBars ∪ displayCutout` 的 top ≈ 53dp）只剩约 3dp，AppCompat 的 `Toolbar.onLayout` 在空间不足时把标题**贴底**放置，于是标题被裁成底部一条约 7px 的缝、4 个按钮完全不可见。现在系统栏那条高度由**独立占位条**（`status_bar_spacer`）承担、导航栏高度写死为 `@dimen/top_bar_height`（56dp），「内容区 == 56dp」与 insets 彻底无关；同时把两条**新回归用例**补上（JVM 灌入 53dp 的真实 inset 断言内容区完整；真机 E2E 断言标题/按钮尺寸并把导航栏画进 Bitmap 数白色像素，证明它们真的被画出来），并删除旧用例里那条把成因写成预期的断言（`paddingTop >= 状态栏高度`）（见 [V7.7.1_TOP_BAR_SQUEEZE_FIX.md](V7.7.1_TOP_BAR_SQUEEZE_FIX.md)）
- **v7.7.0** 编辑页改为**顶部导航栏**：「外观设置」「WebDAV 同步」从屏幕底部上移，并新增「**立即上传**」（先落盘再以 MANUAL 触发，不受 30 分钟节流）与「**撤回**」（逐步退回每一次编辑，不设次数上限，直到精确回到刚打开时的内容；撤回历史放在 ViewModel 里，旋转 / 深色模式重建后依然可用）；设置里的「字体大小」现在**同时决定编辑器文字大小**；删除全部「已保存」toast（编辑器 2 处 + WebDAV 设置页 1 处，写盘失败仍会提示）（见 [V7.7_TOP_BAR_UNDO_REPORT.md](V7.7_TOP_BAR_UNDO_REPORT.md)）
- **v7.6.1** 上传文件名的时间戳从 unix **秒**改为**毫秒**（`note1790856196197.txt`）：同一秒内密集上传（自动 + 手动 + 多设备）不再依赖「+1 秒」兜底也能各写一个文件，文件名排序精度更高。功能改动只有一行
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
hellowidget.versionName=7.9.0
hellowidget.versionCode=27
```

CI 会自动用 `versionName` 生成 tag（`v7.9.0`）与 Release 标题，APK 元数据也取自同一处，三者不会再漂移。

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

Gradle 版本由仓库内的 wrapper 固定（`gradle/wrapper/gradle-wrapper.properties` → 9.6.0），无需本机安装 Gradle。

## 项目结构

```
hellowidget/
├── .github/workflows/build.yml    # CI：质量门禁 + 构建 + 仪器化测试 + 发布 Release
├── .github/scripts/webdav_stub_server.py  # CI 用的零依赖 WebDAV 测试服务器
├── .github/scripts/release_smoke.sh       # CI 用的 release 包冒烟（装进模拟器启动一次）
├── gradlew / gradle/wrapper/      # Gradle wrapper（版本锁定 9.6.0）
├── app/
│   ├── build.gradle.kts           # 构建配置（compileSdk 36 / minSdk 24 / targetSdk 36）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/moe/hellowidget/
│       │   │   ├── MainActivity.kt        # 编辑器 + 顶部导航栏（外观/WebDAV/立即上传/撤回）+ 保存流程
│       │   │   ├── UndoHistory.kt         # 无限次撤回：记录每一步文本变更并反向套用
│       │   │   ├── UndoHistoryViewModel.kt# 让撤回历史跨配置变更（旋转/深色模式重建）存活
│       │   │   ├── SettingsActivity.kt    # 外观设置页（小组件 + 编辑器颜色）
│       │   │   ├── SyncActivity.kt        # WebDAV 同步设置页（凭据/状态/证书指纹）
│       │   │   ├── SyncService.kt         # 同步前台服务（dataSync，含通知栏进度条）
│       │   │   ├── SyncRetryJobService.kt # 系统级兜底重试任务（JobScheduler 拉起，不显示通知）
│       │   │   ├── sync/                  # 同步核心
│       │   │   │   ├── SyncEngine.kt          # 决策表 + 1 分钟闸门（纯函数，可单测）
│       │   │   │   ├── OkHttpWebDavClient.kt  # OkHttp 5.4：全流程超时 + 可取消 + TOFU 证书固定
│       │   │   │   ├── TlsPinning.kt          # 证书指纹校验（TOFU，只增不减）
│       │   │   │   ├── SyncManager.kt         # 编排：触发、上传、状态流；hasPendingUpload() 纯检测（橙点）
│       │   │   │   ├── SyncSettings.kt        # 同步配置与状态存取
│       │   │   │   ├── SyncNotifier.kt        # 进度通知与上传成功/失败 Toast
│       │   │   │   ├── SyncConfig.kt          # URL 与文件名（前缀+时间戳+扩展名）校验
│       │   │   │   ├── SyncLauncher.kt        # 统一触发入口（前台服务，失败降级为进程内）
│       │   │   │   ├── SyncRetry.kt           # 失败后的系统级一次性重试任务（排入/撤销/该不该重试）
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
├── build.gradle.kts               # 根构建配置（AGP 9.4.1 + AGP 内置 Kotlin）
├── gradle.properties              # 全局配置 + 版本号唯一来源
└── settings.gradle.kts
```

## WebDAV 同步使用说明

在应用顶部导航栏点「WebDAV 同步」（云朵图标），填写服务器目录地址、文件名、用户名与口令，打开开关后保存。日常上传不必进这一页：编辑页顶部的「立即上传」按钮随时可用。

| 服务 | 目录地址示例 |
|---|---|
| 坚果云 | `https://dav.jianguoyun.com/dav/你的目录/`（需在「账户信息 → 安全选项」生成**应用密码**） |
| Nextcloud | `https://你的域名/remote.php/dav/files/用户名/目录/` |
| 群晖 / NAS | `http://192.168.1.5:5005/dav/目录/`（明文 http 会显示风险提示） |

行为约定：

- **纯单向上传，且每次都是新文件**：本机内容一变，下一次同步就往目录里写一个 `note<unix 毫秒时间戳>.txt`；旧文件**永远不会被覆盖或删除**，云端自动保留全部历史。本机没变则**一个请求都不发**（省流量、省服务器配额）
- **不读云端**：客户端接口里根本没有读取方法 —— 不列目录、不下载、不比对，也不清理旧数据；没有冲突与冲突副本。历史文件多起来不需要本应用操心
- **文件名**：`<前缀><unix 毫秒时间戳><扩展名>`，前缀与扩展名来自「文件名」字段（默认 `note.txt` → `note1790856196197.txt`）；同一毫秒内的第二次上传会 +1 毫秒避免撞名
- **目标目录不存在时会自动创建**（服务器回 409/404 时补一次 `MKCOL` 再重试上传）
- **上传时机（v7.8）**：只有「保存内容」才触发自动上传（退出 / 返回、切后台，以及旋转、切换深色模式等触发的自动保存），两次自动上传之间至少间隔 1 分钟（密集保存由闸门合并）；**打开应用不再上传**，只检测「上次成功上传后本地有没有新改动」，有则在顶部「立即上传」按钮上亮一个橙点。编辑页顶部的「立即上传」与同步设置页的「立即同步」都不受限制
- **通知与结果提示**：Android 13+ 需要通知权限才能看到进度条；拒绝权限时同步照常工作。每次**确实发生上传**时，进度通知至少显示 1.5 秒；上传成功弹 Toast「已上传到云端」，**上传失败也弹 Toast**（v7.8 起失败不再往通知栏留东西，完整原因仍记在同步设置页的「同步状态」里）
- **上传失败会自动补传（v7.9）**：写盘成功后立刻给系统排一个一次性持久化任务（`JobScheduler`，需要网络、按指数退避、进程被杀或重启后依然在），上传成功即撤销；因此「保存了却没上传」最多只是一次延迟，不再需要清后台。同步设置页会显示「自动重试：已排队 / 无」。只有看起来是暂时性的失败才自动重试（网络 / 超时 / IO / 5xx / 目录缺失 / 锁定 / 配额），凭据错误、证书不受信这类需要你先处理的问题不会反复重试
- **网络请求全程有超时（v7.9）**：单次同步的上限是 60 秒（覆盖 DNS → 建连 → 写正文 → 读响应），任何一次卡住都不会让后续同步被无限期挡住 —— 旧版没有写超时，一条半开连接就能造成「只能清后台」
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
