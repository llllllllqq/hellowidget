package moe.hellowidget

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import moe.hellowidget.MainActivity.Companion.KEY_SAVED_TEXT
import moe.hellowidget.MainActivity.Companion.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * v8.4.0：读盘结果。
 *
 * 之前 `read()` 只返回 `String`，把「读到空便签」「文件不存在」「读失败」三件事压成了同一个
 * `""`。调用方（编辑页）无法区分，于是读失败时会把空内容当成用户的便签显示出来，
 * 用户一退出就把磁盘上唯一那份内容覆盖掉 —— 这是审计里最严重的一条静默数据丢失路径。
 * 现在读失败是一个**显式状态**，由调用方决定"不许覆盖 / 显示错误 / 不上传"。
 */
sealed interface ContentRead {
    /** 读到了磁盘内容（空便签也是合法的 [Success]） */
    data class Success(val text: String) : ContentRead

    /** 读不出来（IO 错误、文件不可读、DataStore 内部异常…）：**不是**空便签 */
    data class Failed(val cause: Throwable) : ContentRead
}

/**
 * 用户内容的持久化存储（DataStore，官方原子写入）。
 *
 * - 原子性：DataStore 内部采用「写临时文件 + fsync + 原子重命名」，任意时刻崩溃
 *   磁盘上都是完整的旧文件或完整的新文件，绝无写一半的状态（v8.4.0 对着
 *   datastore-core 1.2.1 的字节码核过：`FileDescriptor.sync()` + 原子 move）。
 * - 持久性：updateData 挂起直到数据真正落盘（含 fsync）完成后才返回；
 *   失败抛出异常，绝不静默丢失。
 * - 完整性：文件格式为 [UTF-8 内容][4 字节 CRC32]，读取时校验，
 *   发现损坏会保留现场文件并自动重建，同时记下「下次打开应用要告知用户」的标记。
 * - 性能：仅当调用 write() 时才写盘，无自动保存、无轮询、无后台任务；
 *   内容没变时连写盘都跳过（v8.4.0）。
 */
object ContentStore {

    private const val FILE_NAME = "user_content.dat"
    private const val CORRUPT_PREFIX = "corrupt_"
    private const val MAX_CORRUPT_BACKUPS = 3
    private const val TAG = "ContentStore"

    /** 「上次检测到内容损坏」的待通知标记（取自 `hello_prefs`，见 [takeCorruptionNotice]） */
    private const val KEY_CORRUPTION_FILE = "content_corruption_file"
    private const val KEY_CORRUPTION_AT = "content_corruption_at"

    /**
     * 保存专用作用域（独立于 Activity 生命周期）。
     * 用于退出/切后台等场景：即使 Activity 紧接着被销毁，写盘也一定执行完成。
     *
     * v8.4.0：**并发保存不会乱序落盘**。第一版尝试用 `limitedParallelism(1)` 串行化，
     * 被 CI 用例当场证伪：协程在 `data.first()` / `updateData` 处挂起时会**释放**调度器的
     * 执行许可，下一趟保存因此可以插进来，续体恢复顺序由 IO 线程决定 —— 20 次并发保存的
     * 最终内容会在 v18 / v19 之间漂移。真正的顺序保证来自 [writeMutex] +
     * [nextSaveSequence] 的单调序号：序号小的快照在锁内发现"已经有更新的快照落盘"时直接跳过，
     * 因此无论线程怎么调度，磁盘上永远是**序号最大**的那份快照。
     */
    val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化「读-比较-写」临界区（`updateData` 只保证写是串行的，不保证按意图排序） */
    private val writeMutex = Mutex()

    /** 保存序号来源：在**主线程**按用户操作顺序取号，见 [nextSaveSequence] */
    private val sequenceSource = AtomicLong(0L)

    /** 已经落盘的最大序号。只在 [writeMutex] 内读写 */
    private var lastAppliedSeq = 0L

    private lateinit var store: DataStore<String>

    /** 单测注入点：模拟"读失败"（生产代码为 null） */
    @VisibleForTesting
    internal var readOverride: (suspend () -> ContentRead)? = null

    /** 单测注入点：模拟"写失败"（生产代码为 null） */
    @VisibleForTesting
    internal var writeOverride: (suspend (String) -> Boolean)? = null

    /** 真正发生磁盘写入的次数（内容没变而跳过的写不算）；供测试证明"没变就不写" */
    @VisibleForTesting
    internal var diskWriteCount: Int = 0
        private set

    /** 在 [HelloWidgetApp.onCreate] 中调用一次（全局唯一实例，避免同文件多实例竞争） */
    fun init(context: Context) {
        if (::store.isInitialized) return
        val appContext = context.applicationContext
        store = DataStoreFactory.create(
            produceFile = { File(appContext.filesDir, FILE_NAME) },
            serializer = ContentSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { exception ->
                // CRC 校验失败：先保留损坏文件现场，再以空内容重建。
                // v8.4.0：同时记一个持久化标记 —— 损坏可能发生在小组件/后台进程里，
                // 用户当时并不在编辑页；下次打开应用时由 MainActivity 提示一次。
                val backup = backupCorruptFile(appContext)
                recordCorruptionNotice(appContext, backup)
                Log.e(TAG, "内容文件损坏，已重建并保留现场：${exception.message}")
                ContentSerializer.defaultValue
            },
            migrations = listOf(PrefsContentMigration(appContext)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    /**
     * v8.4.0：读取当前内容，**区分"读到空内容"与"读失败"**。
     * 任何异常都变成 [ContentRead.Failed]，不再被偷偷压成空串。
     */
    suspend fun readResult(): ContentRead {
        readOverride?.let { return it.invoke() }
        return try {
            ContentRead.Success(store.data.first())
        } catch (e: CancellationException) {
            throw e // 协程取消不吞掉
        } catch (e: Exception) {
            Log.e(TAG, "读取失败（不再回退为空内容，交由调用方决定）", e)
            ContentRead.Failed(e)
        }
    }

    /**
     * 兼容旧调用点：读失败时返回空串。
     *
     * **生产代码不要用它** —— 这正是 v8.4.0 修掉的 fail-open 行为。只有测试
     * （以及"空串确实是安全降级"的场景）才应该走这里；编辑页 / 小组件 / 同步
     * 都已经改用 [readResult]。
     */
    @VisibleForTesting
    internal suspend fun read(): String = when (val result = readResult()) {
        is ContentRead.Success -> result.text
        is ContentRead.Failed -> ""
    }

    /**
     * 取一个保存序号。**调用方应在主线程按用户操作发生的顺序取号**（见
     * [moe.hellowidget.MainActivity.saveContent]），再把它传给 [write]，
     * 这样即使两次保存的协程乱序执行，磁盘上也只会保留序号最大的快照。
     */
    internal fun nextSaveSequence(): Long = sequenceSource.incrementAndGet()

    /**
     * 原子写入：updateData 挂起直到内容真正写盘（fsync + 原子重命名）成功后返回。
     *
     * v8.4.0（用户选定）：
     *  - **内容没变就不写盘**：每次失焦/切后台都会走一次保存，而真正需要落盘的只有
     *    "内容变了"的那一次；内容没变时从 DataStore 的内存缓存里取当前值比较即可。
     *    这不减少上传触发（保存路径仍然调用 [moe.hellowidget.sync.SyncLauncher]，
     *    同步层自己按内容哈希短路），因此"保存即上传"的语义不变。
     *  - **旧快照不会覆盖新快照**：见 [saveScope] 的说明。`sequence` 默认在当前线程取号，
     *    生产路径由调用方在主线程取号后传入。
     *
     * @param sequence 本次保存的快照序号（单调递增；越新越大）
     * @return true = 已持久化（或本来就已经是这份内容 / 已有更新的快照落盘）；
     *   false = 写入失败（旧内容保持不变）
     */
    suspend fun write(text: String, sequence: Long = nextSaveSequence()): Boolean {
        writeOverride?.let { return it.invoke(text) }
        return try {
            writeMutex.withLock {
                if (sequence <= lastAppliedSeq) {
                    Log.i(
                        TAG,
                        "已有更新的保存快照落盘（#$lastAppliedSeq > #$sequence），跳过这次写盘"
                    )
                    return@withLock true
                }
                if (store.data.first() == text) {
                    Log.i(TAG, "内容未变化，跳过写盘（${text.length} 字）")
                } else {
                    store.updateData { text }
                    diskWriteCount++
                }
                lastAppliedSeq = sequence
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "写入失败（旧内容未受影响）", e)
            false
        }
    }

    /**
     * 把内容文件复制成 `corrupt_<时间戳>.dat` 现场，返回现场文件名（拿不到时 null）。
     * 必须在 DataStore 用空值重建**之前**调用（corruption handler 里就是那个时机）。
     */
    private fun backupCorruptFile(context: Context): String? = try {
        val src = File(context.filesDir, FILE_NAME)
        if (!src.exists()) {
            null
        } else {
            val dest = File(context.filesDir, "$CORRUPT_PREFIX${System.currentTimeMillis()}.dat")
            src.copyTo(dest, overwrite = true)
            Log.w(TAG, "损坏文件已备份为 ${dest.name}（可用于人工恢复）")
            pruneCorruptBackups(context)
            dest.name
        }
    } catch (e: IOException) {
        Log.w(TAG, "损坏文件备份失败", e)
        null
    }

    /** 记下"下次打开应用要告诉用户内容损坏过"（取一次即清，见 [takeCorruptionNotice]） */
    @VisibleForTesting
    internal fun recordCorruptionNotice(context: Context, backupFileName: String?) {
        context.prefs.edit()
            .putString(KEY_CORRUPTION_FILE, backupFileName ?: "")
            .putLong(KEY_CORRUPTION_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 取出"上次检测到内容损坏"的现场文件名并清掉标记（返回 `""` = 有损坏但没留下现场）。
     * 只在编辑页读盘成功之后调用，因此同一个损坏事件只会提示一次。
     */
    fun takeCorruptionNotice(context: Context): String? {
        val prefs = context.prefs
        if (!prefs.contains(KEY_CORRUPTION_FILE)) return null
        val fileName = prefs.getString(KEY_CORRUPTION_FILE, "") ?: ""
        prefs.edit()
            .remove(KEY_CORRUPTION_FILE)
            .remove(KEY_CORRUPTION_AT)
            .apply()
        return fileName
    }

    /**
     * 只保留最近 [MAX_CORRUPT_BACKUPS] 份损坏备份。
     * 旧实现每次损坏都新增一份且从不清理，用户私密文本会无限累积，
     * 还会一起计入系统备份配额。
     */
    private fun pruneCorruptBackups(context: Context) {
        val backups = context.filesDir
            .listFiles { f -> f.isFile && f.name.startsWith(CORRUPT_PREFIX) && f.name.endsWith(".dat") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        backups.drop(MAX_CORRUPT_BACKUPS).forEach { stale ->
            if (stale.delete()) {
                Log.w(TAG, "已清理过期的损坏备份 ${stale.name}")
            }
        }
    }
}

/**
 * 序列化器：文件 = [UTF-8 内容字节][4 字节 CRC32，大端序]。
 * 读取时校验 CRC，不匹配即视为损坏，抛出 [CorruptionException] 交由 DataStore 恢复。
 */
object ContentSerializer : Serializer<String> {

    override val defaultValue: String = ""

    override suspend fun readFrom(input: InputStream): String {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return ""
        if (bytes.size < Int.SIZE_BYTES) {
            throw CorruptionException("内容文件损坏：长度不足（${bytes.size} 字节）")
        }
        val payloadLength = bytes.size - Int.SIZE_BYTES
        val storedCrc = bytes.readBigEndianInt(payloadLength)
        val actualCrc = crc32(bytes, payloadLength)
        if (storedCrc != actualCrc) {
            throw CorruptionException("CRC32 校验失败：期望 $storedCrc，实际 $actualCrc")
        }
        // 直接用 (数组, 偏移, 长度) 构造字符串，省掉 copyOfRange 的那一份 payload 副本，
        // 大内容下内存峰值明显下降
        return String(bytes, 0, payloadLength, Charsets.UTF_8)
    }

    override suspend fun writeTo(value: String, output: OutputStream) {
        val payload = value.toByteArray(Charsets.UTF_8)
        output.write(payload)
        output.write(intToBigEndianBytes(crc32(payload)))
    }

    private fun crc32(bytes: ByteArray, length: Int = bytes.size): Int =
        CRC32().apply { update(bytes, 0, length) }.value.toInt()

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun intToBigEndianBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}

/** 一次性迁移：把旧 SharedPreferences 里的内容搬到 DataStore（官方 DataMigration 机制） */
private class PrefsContentMigration(private val context: Context) : DataMigration<String> {

    override suspend fun shouldMigrate(currentData: String): Boolean =
        currentData.isEmpty() &&
            !(context.prefs.getString(KEY_SAVED_TEXT, "") ?: "").isEmpty()

    override suspend fun migrate(currentData: String): String =
        context.prefs.getString(KEY_SAVED_TEXT, "") ?: ""

    override suspend fun cleanUp() {
        context.prefs.edit().remove(KEY_SAVED_TEXT).apply()
    }
}
