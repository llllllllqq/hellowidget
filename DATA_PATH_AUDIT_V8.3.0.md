# HelloWidget 数据通路审计报告（保存 / 写入 / 读取）

- 审计对象：`main` @ `2e74fa4`（v8.3.0 / versionCode 34）
- 日期：2026-10-18
- 性质：**纯分析，未修改任何代码，未提交**（本文档是新增的未跟踪文件，推 `main` 会触发发版流水线，故刻意不提交）
- 方法：逐行读数据通路源码 → 核对 `datastore-core-1.2.1` 的实际字节码（不是看注释）→ 查官方文档确认平台语义 → 对照现有测试看每条结论有没有被覆盖

结论先行：**存储内核（原子写 + CRC + 单实例）是可靠的，真正的问题都集中在"错误处理边界"上** —— 读取失败被当成"空内容"、损坏恢复对用户完全静默、退出时写盘失败仍然关窗。其中前两条会**静默**丢掉用户唯一的那份文本，是本报告里最需要处理的问题。

---

## 1. 数据通路全景

### 1.1 只有一条内容通路

| 操作 | 入口 | 实现 |
|---|---|---|
| 写 | `MainActivity.saveContent()` → `ContentStore.saveScope.launch` → `ContentStore.write()` | `MainActivity.kt:627-680`（调用方）、`ContentStore.kt:82-90` |
| 读（编辑页） | `MainActivity.onCreate` 的协程 | `MainActivity.kt:160-165` |
| 读（小组件） | `TextWidgetService.reload()` | `TextWidgetService.kt:53-57` |
| 读（同步） | `SyncManager.contentReader` 默认实现 | `SyncManager.kt:100`、`359-389` |
| 初始化 | `HelloWidgetApp.onCreate` 调一次 `ContentStore.init` | `HelloWidgetApp.kt:13` |

全文只有 `ContentStore` 一处 `updateData`、三处 `read()`（编辑页 / 小组件 / 同步），**没有任何旁路写入**。这是很好的性质：审计面积小。

```text
[编辑页 EditText]  ← 唯一权威副本在内存；键入/删除只改内存，不落盘（无自动保存）
        │
        │ 生命周期事件：失焦 / Home / onStop / 返回键 / 深色模式
        ▼
saveContent()  主线程取 text.toString()  ──launch──▶  saveScope(Dispatchers.IO)
                                                              │
                                                              ▼
ContentStore.write() → DataStore.updateData → ContentSerializer
                                                              │ 写 .tmp → FileDescriptor.sync() → 原子重命名
                                                              ▼
                        filesDir/user_content.dat = [UTF-8 正文][4 字节大端 CRC32]
                                                              │
              ┌───────────────────────────────┬───────────────┴───────────────┐
              ▼                               ▼                               ▼
   编辑页启动读盘                   小组件 reload()                     同步 uploadOnce()
   setText + 解禁编辑器             runBlocking 读盘 → RemoteViews      读盘 → SHA-256 → 按需 PUT 新文件
```

### 1.2 生命周期 → 存 / 读 / 传 对照表

| 事件 | 写盘 | 触发上传 | 读盘 |
|---|---|---|---|
| 键入 / 删除 | ❌ 不做 | ❌ | ❌ |
| 窗口失焦（下拉通知栏 / 多任务 / 切应用） | ✅ 静默 | ✅ | ❌ |
| Home（`onUserLeaveHint`） | ✅ 静默 | ✅ | ❌ |
| 切后台 / 最近任务滑掉 / 跳设置页（`onStop`） | ✅ | ✅ | ❌ |
| 返回键 | ✅ **等写完才 `finish()`** | ✅ | ❌ |
| 旋转 | ✅（`onStop` 静默） | ✅ | ✅ 新 Activity 读盘（文本另由实例状态恢复） |
| 深色模式切换 | ✅ 先保存，**保存完成后才 `recreate()`** | ✅ | ✅ `recreate()` 后读盘 |
| 打开应用 | ❌ | ❌（「只读盘」语义） | ✅ |
| 桌面小组件渲染 | ❌ | ❌ | ✅ |
| 后台进程被杀 | 已在 `onStop` 完成 | — | 下次启动读盘 |
| **崩溃 / OOM / 断电（前台编辑中）** | **❌ 本次会话全丢** | ❌ | — |
| **返回键时写盘失败** | ❌（Toast 后仍 `finish`） | ❌ | — |
| 文件损坏 | 清空后重建 | 可能上传空内容 | 得空 |
| 读盘异常 | 下次保存写入空内容 | 可能上传空内容 | 得空 |
| 系统备份恢复 | — | — | 恢复后首次启动读盘 |

### 1.3 代码里明确的 8 条不变量（本报告逐条核对过）

1. 本地只有一份权威内容；云端只写不读（每次新文件，不删不改历史）。
2. **先写盘、后触发上传**；上传读的是磁盘，不是编辑器内存。
3. 打开应用只读盘：不算哈希、不检测、不联网。
4. 要不要真的发请求只由**内容哈希**决定。
5. 同一时刻最多一次 PUT（`Mutex` + 合并）。
6. 保存不阻塞主线程（`saveScope` 独立于 Activity 生命周期）。
7. `DataStore` 全局唯一实例（`ContentStore.init` 只调一次）。
8. 读盘完成前编辑器 `disabled`，防止"用空编辑器覆盖旧内容"。

这 8 条在实现上**都成立**。下面列的是它们之外、以及"成立但边界不稳"的部分。

---

## 2. 存储内核：这些设计是对的（先确认，避免误伤）

| 设计 | 证据 | 结论 |
|---|---|---|
| 原子写：写临时文件 → fsync → 原子重命名 | 字节码核对：`FileWriteScope$writeData$2` 里先 `FileOutputStream` 写 `.tmp`，再 `FileDescriptor.sync()`，然后 `FileStorageConnection` 用 `.tmp` + `FileMoves_androidKt.atomicMoveTo`（API 26+ 走 `Files.move(ATOMIC_MOVE)`，更低版本 `File.renameTo`） | 注释里"写临时文件 + fsync + 原子重命名"是**事实**，不是抄来的说法 |
| 写串行、读不被写阻塞、读只返回已落盘数据 | 官方文档《DataStore》原文：*Reads return only the data that has been persisted to disk / Read-after-write consistency / Writes are serialized / Reads are never blocked by writes* | 应用依赖的语义都是官方承诺的 |
| 文件格式 CRC32 校验 | `ContentSerializer.readFrom`（`ContentStore.kt:132-147`）：长度 >= 4、大端 CRC、不一致抛 `CorruptionException`；`ContentSerializerTest` 覆盖位翻转/截断/追加/长度不足 | 随机损坏一定能被发现 |
| 损坏时保留现场 | `ContentStore.kt:57-62` + `92-104`：先把 `user_content.dat` 复制成 `corrupt_<ts>.dat`，再让 DataStore 用空值重建；最多留 3 份 | 现场确实留了（但用户够不到，见 P0-2） |
| 写入失败不静默 | `ContentStore.write` 返回 `false`，`MainActivity` 弹「保存失败」 | 正常路径是对的（返回键路径除外，见 P0-3） |
| 编辑器加载前禁用 | `MainActivity.kt:142` + `:162-165`；`MainActivitySaveGateTest.leavingBeforeLoad_doesNotWipeStoredContent` 固化 | "加载未完成就离开"不会清空磁盘 |
| 上传判据 = 内容哈希 | `SyncManager.kt:359-389`；内容没变时一个请求都不发 | 防重复上传的机制真实存在 |
| 单飞 + 合并 | `SyncManager.performSync`（`:179-214`）：`Mutex` + `requestSeq/claimedSeq` | 绝不并发 PUT |
| 小组件在 binder 线程同步读盘 | `RemoteViewsFactory.onDataSetChanged` 官方允许："You can perform processing-intensive operations synchronously within the `onDataSetChanged()` callback." | `runBlocking` 在这里**不是**缺陷（不要按"主线程阻塞"去改） |

---

## 3. 缺陷清单

严重度按"丢内容的可能性 × 用户能否察觉"排序。**P0 = 会静默或不可逆地丢用户唯一副本**。

### P0-1 读盘失败被当成"空内容"（fail-open），下一次保存会把空内容写回磁盘

**现象**：`ContentStore.read()` 的契约是"任何异常回退为空串"：

```kotlin
// ContentStore.kt:68-76
suspend fun read(): String = try {
    store.data.first()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(TAG, "读取失败，回退为空内容", e)
    ContentSerializer.defaultValue   // ""
}
```

调用方无法区分三种状态：**读到空便签** / **文件不存在** / **读失败**。编辑页拿到 `""` 后照常继续：

```kotlin
// MainActivity.kt:160-165
val savedText = withContext(Dispatchers.IO) { ContentStore.read() }
loadCompleted = true            // 不管读没读到，都算"加载完成"
if (!editorTouched) {
    binding.editor.setText(savedText)   // 于是用户看到的是"我的便签是空的"
}
```

**触发条件**：`store.data.first()` 抛出任何非 `CorruptionException` 的异常。真实可能的有：文件被外部删除/替换后首次读取（`FileNotFoundException`）、存储介质 IO 错误、DataStore 内部状态异常。`CorruptionException` 被 corruption handler 接住，但 **`IOException` 不会**。

**后果链**（这是最严重的地方：全程没有任何提示）：
1. 用户打开应用 → 便签显示为空（用户会以为"内容没了吗？"，但很多用户会直接开始重新输入或退出）；
2. 用户退出/切后台 → `shouldPersist()` 为 `true`（`loadCompleted` 已置位，`MainActivity.kt:306`）→ 写入 `""`；
3. 写盘**成功**，因此**没有**「保存失败」提示；磁盘上唯一那份内容被 4 字节的"空便签"覆盖；
4. 小组件刷新成空（`TextWidgetService.kt:55`）；
5. 同步按内容哈希判定"变了"→ 上传一个空文件（见 P1-3）。

**为什么现有测试没拦住**：`ContentStore.read()` 没有可注入的失败缝（对比 `SyncManager.contentReader` 是可注入的），测试只能覆盖"正常读到空/正常读到内容"；`MainActivitySaveGateTest` 覆盖的是"加载尚未完成"，不是"加载失败"。

**缓解方向**（不实施，仅建议）：`read()` 返回 `Ok(text)/Empty/Failed` 三态；`Failed` 时**禁止**走保存路径（或保留上一份已知良好内容），并在界面上明确告知；把 `loadCompleted` 拆成 `loadOk/loadFailed`。原则是：**在成功读到磁盘内容之前，永远不要用内存里的内容覆盖磁盘。**

---

### P0-2 文件损坏后的"重建"对用户完全静默，且现场文件没有任何恢复入口

**现象**：CRC 不匹配 → `ReplaceFileCorruptionHandler` 把现场复制成 `corrupt_<ts>.dat` 后返回空值，DataStore 随即将 `user_content.dat` 重写成空。界面上：**没有任何提示**，只有一行 `Log.e`（`ContentStore.kt:59-61`）。

**触发条件**：CRC 不匹配（介质位翻转、外部工具截断、系统备份恢复出一份不完整文件等）。这次"原子写"本身不会产生半个文件，但原子写保护不了外部原因。

**后果**：
- 用户打开应用只看到空便签，且**没有任何"内容损坏"的痕迹**（连一句"上次内容读取失败"都没有）；
- 现场文件在应用私有目录里，普通用户**没有任何入口**能拿到它（没有 UI、没有导出、走 adb 也需要设备可调试）；所以"可用于人工恢复"目前只对开发者成立；
- 如果损坏发生在**小组件进程**的首次读取（用户没打开应用，只是桌面刷新），同样静默清空；
- `corrupt_*.dat` 会被 `path="."` 的备份规则一起带进云备份（最多 3 份旧文本），这既是隐私面也是"备份里躺着一份应用永远不会读的文件"。

**为什么现有测试没拦住**：`ContentSerializerTest` 只验证"坏字节会抛 `CorruptionException`"；**corruption handler 这一层（备份文件是否生成、是否 prune、用户可见结果）没有任何测试**。

**缓解方向**：损坏时给出用户可见的告知（一句话 + 可复制的诊断），并至少提供一个"恢复上一次可用内容"的入口；`pruneCorruptBackups` 的"只留 3 份"在没有恢复入口时等于把唯一可恢复副本也慢慢删掉。

---

### P0-3 返回键退出时写盘失败，仍然 `finish()`，本次编辑内容随 Activity 一起消失

**现象**：

```kotlin
// MainActivity.kt:206-213
exitingByBack = true
if (shouldPersist()) {
    saveContent(notifyFailure = true, syncTrigger = SyncTrigger.CLOSE_EDITOR,
                reason = SaveReason.BACK) { finish() }
}
```

`saveContent` 无论成功失败都会调 `onDone()`：

```kotlin
// MainActivity.kt:666-678
withContext(Dispatchers.Main) {
    if (!ok) { savedOnLeave = false }
    TextWidgetProvider.updateWidgets(appContext)
    if (notifyFailure && !ok) { Toast... }
    onDone()          // ← 失败也 finish()
}
```

**触发条件**：写盘失败（磁盘写满、IO 错误、文件系统异常）。虽然罕见，但这是**唯一一条"用户在看着屏幕、内容还在、却必然丢失"**的路径。

**为什么注释里的补救不生效**：`saveContent` 的 KDoc 说"失败时清除 `savedOnLeave`，让后续 `onStop` 有机会重试"（`MainActivity.kt:616`）。但在返回键路径上：`exitingByBack = true` 会让 `onStop` 的保存条件 `!exitingByBack`（`:368`）直接为假；而且 `onStop` 通常已经在 `onDone` 之前发生。**所以返回键路径失败 = 无重试、无补救、直接关窗。**

**为什么现有测试没拦住**：`SaveToastTest` 只覆盖"成功时不弹 Toast"；没有任何"写盘失败"的测试（也没有注入写失败的手段）。

**缓解方向**：返回键保存失败时**不要 finish**（留在编辑页，允许用户重试 / 复制内容 / 手动处理），或写一份兜底草稿文件；至少不要在同一帧里既提示失败又把界面销毁。

---

### P1-1 两条并发的保存可能乱序落盘，"旧快照"可能覆盖"新快照"

**现象**：`saveContent` 在**主线程**取快照，然后把写入丢进 `Dispatchers.IO` 的公共作用域，**没有串行化**：

```kotlin
// MainActivity.kt:633-635
val text = binding.editor.text.toString()
ContentStore.saveScope.launch { ContentStore.write(text) ... }

// ContentStore.kt:46
val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

`DataStore.updateData` 保证"写是串行的"（官方语义），但**不保证"按调用方意图排序"**：两条协程分别从线程池取出后，谁先到达 DataStore 的写队列是调度决定的，与 `launch` 的先后无关。

**触发条件**：两次保存的快照**不同**且**时间重叠**。常见守卫（`savedOnLeave`、`exitingByBack`、`isChangingConfigurations`）挡掉了大部分组合，但"立即上传"（MANUAL）不参与这些守卫：例如「点立即上传 → 打字 → 再点立即上传」「点立即上传后立刻切后台触发 `onStop` 保存」都可能让两趟写重叠。

**后果**：磁盘上最后留下的是**旧快照**；如果进程在此之前结束，最后一次编辑就丢了（本次保存各自都报告成功，因此没有任何错误提示；下一次保存才会纠正，但如果用户正好退出去了就没有下一次）。

**为什么现有测试没拦住**：没有"并发保存顺序"的测试（`MainActivitySaveGateTest` 里的都是单次保存）。

**缓解方向**：把 `saveScope` 收敛成单线程执行（`Dispatchers.IO.limitedParallelism(1)`）或在 `saveContent` 里加互斥/序号，让"后写的快照一定不旧于先写的"。

---

### P1-2 没有自动保存/草稿：崩溃、OOM、断电会丢掉**整段未保存会话**

**现象**：保存只发生在生命周期事件上（见 §1.2 表），**编辑过程中一个字节都不落盘**。这是明确的设计取舍（"不做编辑自动保存"），但它与"这份文本只有一份、且是应用的全部价值"放在一起时，风险不对称：

- 从打开应用到第一次失焦/切后台之间，**全部输入都只在内存里**；
- 前台崩溃、系统低内存杀进程、断电、用户"强行停止"，都会让这段内容永久消失（磁盘上还是上次打开时的内容）；
- 云端也就无法"实时兜底"：上传只在保存之后发生，所以云端最多落后到上一次保存。

**补充**：旋转依赖 `savedInstanceState` 携带 EditText 全文（100k 字符 ≈ 200–400KB，仍在 1MB binder 预算内，但已经很接近）；如果将来上限调大，这条路径会变成崩溃点。深色模式切换没有这个问题（先写完盘再 `recreate()`，`MainActivity.kt:574-592`）。

**为什么现有测试没拦住**：这是行为取舍，不是 bug；测试覆盖的是"保存时机"，没有覆盖"未保存会话的持久性"。

**缓解方向**：如果接受少量写放大，可加"停顿 N 秒写草稿"（与正式文件分开）或"`onPause` 时轻量落盘"；否则应在文档里明确"编辑中崩溃会丢本次会话"。

---

### P1-3 同步路径同源 fail-open：读盘失败会把"空内容"当成新版本上传

**现象**：`SyncManager.contentReader` 就是 `ContentStore.read()`（`SyncManager.kt:100`），`uploadOnce` 直接哈希并上传：

```kotlin
// SyncManager.kt:359-362
val localText = contentReader()                 // 失败时 = ""
val bytes = localText.toByteArray(Charsets.UTF_8)
val localHash = SyncEngine.sha256Hex(bytes)     // 与上次哈希不同 → 判定"有改动"
```

**后果**：云端多出一个**空文件**（文件名带新时间戳，历史不丢），并 `recordSuccess(hash(""))`；用户会看到「已上传到云端」。之后本地内容恢复时哈希不同，会再传一次 ✓，所以不是永久损坏，但"云端最新一份是空的"这件事可能误导用户（以及任何按"最新文件"读取云端的工具）。

**为什么现有测试没拦住**：同步测试全部通过 `contentReader` 注入假内容，永远不会走 `ContentStore.read()` 的失败分支。

**缓解方向**：同 P0-1 —— 让"读失败"可区分；同步层遇到读失败应当报错而不是上传空内容。

---

### P2-1 撤回历史无上限：长会话内存持续增长（并与 P1-2 联动）

`UndoHistory` 每次文本变更存一个 `Edit(start, removed, inserted)`（`UndoHistory.kt:27,53`），**从不裁剪**（"无限次撤回"是明确需求），并放在 ViewModel 里跨配置变更存活。逐键编辑下每键一个对象+两个 String：10 万次按键 ≈ 10–20MB 级别；一次"全选删除/整段粘贴"还会额外持有 10 万字符的字符串。

**后果**：低内存设备上长会话存在 OOM 风险；而 OOM 发生在前台编辑时，恰好命中 P1-2（未保存会话丢失）。这不是"撤回功能错了"，而是"无限历史"与"无自动保存"叠加后的风险组合。

### P2-2 每次保存都整份重写、都起一次前台服务（即使内容没变、一个请求都不发）

`saveContent` 无条件 `ContentStore.write`（`MainActivity.kt:636`）→ 无条件 `SyncRetry.schedule`（`:642`）→ 无条件 `SyncLauncher.request`（`:656-660`）。内容没变时同步层会靠哈希短路（一个请求都不发），但：

- 磁盘：每次失焦/切后台都整份重写（100k 字符 ≈ 300KB）；
- 通知：每次保存都会起前台服务并在通知栏闪一下进度条（"无事可做"时立即收掉）；
- 系统任务：每次都排一次 JobScheduler，同步成功后再撤销。

**后果**：功能正确，但与"零后台占用、无打扰"的自我描述有张力；下拉通知栏这类**高频失焦**会反复触发。**缓解**：保存层做"与上次写入相同的文本就跳过"，同步层在触发前先算哈希（代价是把哈希提前到主线程或让保存协程自己判断）。

### P2-3 "最多自动重试 5 次"会被每次保存重置，长期失效的条件比文档描述的弱

`SyncRetry.schedule()` 每次保存都 `setRetryAttempts(0)`（`SyncRetry.kt:139`），而预算检查在 `runOnce`（`:249-254`）。所以"最多 5 次（用完就停，不长期驻留）"实际是**每次保存一批**的上限：只要用户还在反复切前台/切后台（每次都算一次保存），预算就一直被重置，兜底任务可以长期存在。

**后果**：不违背数据安全，但与 README/CHANGELOG 里"最多自动重试 5 次"的不变量表述不一致；排障时容易被误读。

### P2-4 内容文件没有格式版本号/应用标识

`ContentSerializer` 的格式只有 `[UTF-8 正文][CRC32]`（`ContentStore.kt:128-170`），没有 magic/version 头。CRC 只回答"完整吗"，不回答"是什么版本、是不是本应用的"。

**后果**：将来要改格式（加密、压缩、分片）时没有 in-band 判别；一份来自未来版本的文件无法给出"请升级应用"这类明确拒绝。当前唯一的历史迁移是 `PrefsContentMigration`（v4→v5，一次性，`ContentStore.kt:173-185`）。

### P2-5 0 字节文件被当成"合法的空内容"（而写方永远不会产出 0 字节）

`readFrom` 对空流直接返回 `""`（`ContentStore.kt:133-134`），但 `writeTo` 写空字符串也会输出 4 字节 CRC（`:149-153`）。所以 **0 字节只可能来自外部**（截断、错误恢复、手工创建），却被当成"用户把便签清空了"，随后被正常保存覆盖。

`ContentSerializerTest.emptyStream_decodesToEmptyString_withoutException` 把这一行为**固化成了预期**——也就是说这是有意的选择；但它与"写方不变量"矛盾，属于需要在格式层做一次二选一的边界。

### P2-6 长度上限是"输入层约束"，不是"存储层约束"

`MAX_CONTENT_CHARS = 100_000` 只通过 `InputFilter` 施加（`MainActivity.kt:109-124`）；`ContentStore.write()` 接受任意长度，`read()` 也不校验。于是：

- 历史版本（v7.0 之前无上限）留下的超长内容、或外部恢复进来的超长文件，会**原样读进编辑器**；
- 之后用户再输入：`keep = MAX - 现有长度` 为负 → **一个字都打不进去**（只能删），并且提示是「已达最大长度」——用户看不出"为什么我一打开就满了"；
- `setText` 是否套用 `InputFilter` 属于框架实现细节（AOSP 各版本一致的做法是在创建 Editable 时套 filters，但建议真机确认）。**如果套用**，超长内容在加载时就会被静默截断到 10 万字符，下一次保存把截断后的版本写回 → 尾部内容丢失；**如果不套用**，就是上面"再也打不进字"的状态。两种都不该出现。

**缓解方向**：把上限提升为存储层校验（读写都检查），超长时给出明确、可操作的提示；并且给"升级前就超长"的内容一条明确的处理路径（例如只读展示 + 提示先删减）。

### P2-7 系统备份把"运行时同步状态"一起带走，且两处文档与实现相反

备份规则是 `include domain="sharedpref" path="."`（`backup_rules.xml:14-15`、`data_extraction_rules.xml:16-23`），于是 `hello_prefs` 里**所有**键都会跟着云备份/换机迁移走：配置（含明文口令，已接受）、以及运行时状态 `sync_last_uploaded_hash`、`sync_retry_attempts`、`sync_next_retry_at`、`sync_last_launch_note` 等。

- **行为面**：新设备恢复后，`retryAttempts` 可能是 5（"预算已用完"）、`lastUploadedHash` 是旧设备的哈希。前者会让自动补传在恢复后一度失效（下次保存会重置）；后者通常无害（哈希是按内容算的）。
- **文档面**：`SyncSettings` 的 KDoc 仍写着这个 prefs "已被完全排除在云备份与换机迁移之外"（`SyncSettings.kt:9-14`），**与 v8.2.0 起的规则文件完全相反**；v8.3.0 只修了 UI 文案，没改这段注释。另外 `CHANGELOG` v8.2.0 里"不备份运行时状态"说的是已被删除的文件通道，容易误读成现在也不备份。
- **隐私面**：`file` 域 `path="."` 还会带上最多 3 份 `corrupt_*.dat`（旧文本）和 DataStore 的 `.tmp`。

### P2-8 fsync 覆盖"文件数据"，不覆盖"目录项"——"返回即已真正落盘"是略强的表述

字节码显示：临时文件 `FileDescriptor.sync()` 之后做原子重命名，**没有对父目录 fsync**。因此突然断电时最坏情况是"重命名回滚"：磁盘上仍是完整的旧文件（**不会损坏**），但刚返回成功的那次保存可能看不到。这是 DataStore 的边界，不是本应用的 bug；只是 `ContentStore` KDoc 里"updateData 挂起直到数据真正落盘（含 fsync）"在断电语义上说得比实际强半档。

### P2-9 设置读写在主线程（首次加载 prefs 会阻塞主线程）

内容读盘严格在 IO 线程，但设置不是：`MainActivity.onResume → applyEditorAppearance → EditorSettings.*`、`onCreateOptionsMenu → applyTopBarColors`、`SyncLauncher.isReady → SyncSettings.*` 都在主线程读 `hello_prefs`。首次访问会同步加载 XML（毫秒级，通常可接受），但这是本应用里唯一的"主线程磁盘 IO"，与"内容绝不阻塞主线程"的自我要求不一致。

### P2-10 「立即上传」在未配置同步时不会顺便落盘

```kotlin
// MainActivity.kt:285-295
if (!SyncLauncher.isReady(this)) {
    Toast...; return          // ← 直接返回，不保存
}
saveContent(... SyncTrigger.MANUAL)
```

配置齐全时这个按钮等于"保存 + 上传"；未配置时它**只提示、不保存**。行为不一致，用户如果把它当"保存键"用，会在未配置时白点。

### P2-11 系统备份恢复与运行中的进程不协调（进程内缓存）

`DataStoreImpl` 带 `DataStoreInMemoryCache`：进程存活期间正常读路径**不回盘**。如果文件在应用运行期间被外部替换（系统恢复、其他工具），本进程察觉不到；用户此时再退出，就可能把缓存里的旧内容写回去，覆盖刚恢复的内容。正常情况下系统恢复发生在应用未运行时，概率低，但这是"外部写入者"这一类风险的边界。

---

## 4. 明确"不算缺陷"的部分（避免后续误改）

| 看起来可疑 | 实际结论 |
|---|---|
| 小组件里 `runBlocking` 读盘 | 官方明确允许在 `onDataSetChanged()` 里同步做重活；这里读的是毫秒级缓存，且发生在 binder 线程而非主线程。**不要按"主线程阻塞"去改** |
| 没有 `onPause` 保存 | 有失焦/`onStop` 两道更早/更稳的钩子；`onPause` 未必触发（分屏等），`onStop` 更晚但一定在进程冻结前 |
| CRC32 不够"安全" | 它防的是存储损坏，不是篡改；这个场景不需要密码学校验 |
| DataStore 文件放在 `filesDir` 根而不是 `files/datastore/` | 官方推荐位置只是约定；本项目用 `path="."` 备份整个 `file` 域，位置不影响备份；代价是内容文件与 `corrupt_*`、临时文件同目录（可读性差，不是 bug） |
| 上传永远是"新文件、不删旧文件" | 单向上传的产品语义，云端天然留历史；也顺带成为 P0-1/P1-3 的最后一道恢复手段（云端还能找到旧的完整版本） |
| 撤回历史跨旋转存活 | ViewModel 的正确用法 |
| `ContentStore.init` 在 `Application` 里一次 | 单进程、单实例，`DataStore` 同文件多实例会抛异常，这里避免了 |
| 没有"云→本地"读取 | 产品语义（只上传不下发），不是能力缺失 |

---

## 5. 风险矩阵与优先级（建议，不实施）

| 编号 | 问题 | 触发概率 | 后果 | 用户能否察觉 | 建议优先级 |
|---|---|---|---|---|---|
| P0-1 | 读失败 = 空内容，随后覆盖 | 低 | 静默丢全部内容 | **不能** | **最先处理** |
| P0-2 | 损坏静默重建、现场不可达 | 低 | 静默丢全部内容 | **不能**（仅 logcat） | 高 |
| P0-3 | 返回键写失败仍关窗 | 很低 | 丢本次编辑，有 Toast | 能（但无法挽回） | 高 |
| P1-3 | 读失败上传空内容 | 低（同 P0-1） | 云端最新一份为空 | 能（Toast 说上传成功会误导） | 随 P0-1 一起解决 |
| P1-1 | 并发保存乱序 | 低 | 丢最后一次编辑 | 不能 | 中 |
| P1-2 | 无自动保存 | 中（崩溃/OOM/断电） | 丢整段会话 | 能（但无法挽回） | 中（产品取舍） |
| P2-1 | 撤回历史无上限 | 中（长会话） | OOM → 触发 P1-2 | 不能 | 中低 |
| P2-2 | 每次保存重写 + 起 FGS | 高（每次失焦） | 耗电/闪通知/闪存写入 | 能 | 中低 |
| P2-3 | 重试预算被重置 | 中 | 与文档不一致 | 不能（诊断时才看） | 低 |
| P2-6 | 超长历史内容 | 很低 | 打不进字或截断 | 能（但不懂为什么） | 低 |
| P2-7/8/9/10/11 | 备份状态、fsync 语义、主线程 prefs、按钮行为、外部改文件 | 低 | 体验/文档层面 | 部分 | 低 |

---

## 6. 现有测试对这些点的覆盖

| 主题 | 已被钉住 | 没被覆盖 |
|---|---|---|
| 文件格式/完整性 | `ContentSerializerTest`（往返、CJK/emoji、NUL、1MiB、4 类损坏、空流） | corruption handler 的备份/重建/prune 行为 |
| 保存闸门 | `MainActivitySaveGateTest`（未完成加载时输入要保存；未输入不得清空） | **读失败**（无法注入）、**写失败**（含返回键路径） |
| 保存时机 | `SaveToastTest`（成功不弹 Toast）、`MainActivityTopBarTest`（保存触发上传/兜底任务） | 并发保存的顺序 |
| 撤回 | `UndoHistoryTest`（行为） | 内存上限/长会话 |
| 同步 | `SyncManager*`、`SyncRetry*`、`SyncWindowRetry*`（用注入的 reader） | 真实 `ContentStore.read()` 失败分支 |
| 小组件 | 仪器化用例覆盖像素/尺寸 | 读失败时的显示、空白内容的处理 |

一句话：**测试把"正常路径"和"已知历史事故"钉得很牢；所有缺口都恰好在"错误必须向上传播、而不是被吞掉"的地方。**

---

## 7. 附：本次审计的证据与命令

- 源码：`ContentStore.kt`（全文）、`MainActivity.kt`（生命周期/保存/过滤器）、`TextWidgetService.kt`、`TextWidgetProvider.kt`、`HelloWidgetApp.kt`、`SyncManager.kt`、`SyncEngine.kt`、`SyncSettings.kt`、`SyncRetry.kt`、`SyncLauncher.kt`、`SyncService.kt`、`SyncRetryJobService.kt`、`UndoHistory.kt`、`WidgetSettings.kt`、`AndroidManifest.xml`、`backup_rules.xml`、`data_extraction_rules.xml`
- 平台实现（不是猜的）：`datastore-core-android-1.2.1.jar` 里 `FileWriteScope$writeData$2`（`FileDescriptor.sync()`）、`FileStorageConnection`（`.tmp` + rename）、`FileMoves_androidKt.atomicMoveTo`、`DataStoreImpl.inMemoryCache`
- 官方文档：DataStore（一致性/原子性/损坏处理/备份）<https://developer.android.com/topic/libraries/architecture/datastore>；Collection widgets（`onDataSetChanged` 可同步做重活）<https://developer.android.com/develop/ui/views/appwidgets/collections>；SharedPreferences（`apply()` 异步写盘）<https://developer.android.com/training/data-storage/shared-preferences>
- 未做的事：没有连真机/模拟器实测"超长内容 reload"与"写盘失败"这两条只能靠注入/真机验证的路径；没有改任何代码。
