package com.helloworld

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.helloworld.MainActivity.Companion.KEY_SAVED_TEXT
import com.helloworld.MainActivity.Companion.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * 用户内容的持久化存储（DataStore，官方原子写入）。
 *
 * - 原子性：DataStore 内部采用「写临时文件 + fsync + 原子重命名」，任意时刻崩溃
 *   磁盘上都是完整的旧文件或完整的新文件，绝无写一半的状态。
 * - 持久性：updateData 挂起直到数据真正落盘（含 fsync）完成后才返回；
 *   失败抛出异常，绝不静默丢失。
 * - 完整性：文件格式为 [UTF-8 内容][4 字节 CRC32]，读取时校验，
 *   发现损坏会保留现场文件并自动重建，保证应用始终可用。
 * - 性能：仅当调用 write() 时才写盘，无自动保存、无轮询、无后台任务。
 */
object ContentStore {

    private const val FILE_NAME = "user_content.dat"
    private const val TAG = "ContentStore"

    /**
     * 保存专用作用域（独立于 Activity 生命周期）。
     * 用于退出/切后台等场景：即使 Activity 紧接着被销毁，写盘也一定执行完成。
     */
    val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var store: DataStore<String>

    /** 在 [HelloWidgetApp.onCreate] 中调用一次（全局唯一实例，避免同文件多实例竞争） */
    fun init(context: Context) {
        if (::store.isInitialized) return
        val appContext = context.applicationContext
        store = DataStoreFactory.create(
            produceFile = { File(appContext.filesDir, FILE_NAME) },
            serializer = ContentSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { exception ->
                // CRC 校验失败：先保留损坏文件现场，再以空内容重建
                backupCorruptFile(appContext)
                Log.e(TAG, "内容文件损坏，已重建并保留现场：${exception.message}")
                ContentSerializer.defaultValue
            },
            migrations = listOf(PrefsContentMigration(appContext)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    /** 读取当前内容（挂起；任何异常回退为空串） */
    suspend fun read(): String = try {
        store.data.first()
    } catch (e: Exception) {
        Log.e(TAG, "读取失败，回退为空内容", e)
        ContentSerializer.defaultValue
    }

    /**
     * 原子写入：updateData 挂起直到内容真正写盘（fsync + 原子重命名）成功后返回。
     * @return true = 已持久化；false = 写入失败（旧内容保持不变）
     */
    suspend fun write(text: String): Boolean = try {
        store.updateData { text }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "写入失败（旧内容未受影响）", e)
        false
    }

    private fun backupCorruptFile(context: Context) {
        try {
            val src = File(context.filesDir, FILE_NAME)
            if (src.exists()) {
                val dest = File(context.filesDir, "corrupt_${System.currentTimeMillis()}.dat")
                src.copyTo(dest, overwrite = true)
                Log.w(TAG, "损坏文件已备份为 ${dest.name}（可用于人工恢复）")
            }
        } catch (e: IOException) {
            Log.w(TAG, "损坏文件备份失败", e)
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
        val payload = bytes.copyOfRange(0, bytes.size - Int.SIZE_BYTES)
        val storedCrc = bytes.readBigEndianInt(bytes.size - Int.SIZE_BYTES)
        val actualCrc = crc32(payload)
        if (storedCrc != actualCrc) {
            throw CorruptionException("CRC32 校验失败：期望 $storedCrc，实际 $actualCrc")
        }
        return payload.toString(Charsets.UTF_8)
    }

    override suspend fun writeTo(value: String, output: OutputStream) {
        val payload = value.toByteArray(Charsets.UTF_8)
        output.write(payload)
        output.write(intToBigEndianBytes(crc32(payload)))
    }

    private fun crc32(bytes: ByteArray): Int =
        CRC32().apply { update(bytes) }.value.toInt()

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
