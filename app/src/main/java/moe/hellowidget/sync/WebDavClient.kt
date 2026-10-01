package moe.hellowidget.sync

/** 云端文件的元数据（stat / PUT 的结果） */
data class RemoteFile(val etag: String?, val lastModifiedMs: Long, val size: Long) {
    fun toState(): RemoteState = RemoteState(etag, lastModifiedMs, size)
}

/**
 * 同步失败的可分类原因。界面按它给出本地化文案，测试按它断言。
 * 分类刻意做得细：用户看到「凭据错误」和「服务器不支持原子写入」需要完全不同的动作。
 */
enum class WebDavError {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    PARENT_NOT_FOUND,
    NOT_SUPPORTED,
    PRECONDITION_FAILED,
    LOCKED,
    INSUFFICIENT_STORAGE,
    SERVER_ERROR,
    REDIRECT,
    NETWORK,
    TIMEOUT,
    TLS_UNTRUSTED,
    TLS,
    TOO_LARGE,
    BAD_RESPONSE,
    IO
}

class WebDavException(
    val error: WebDavError,
    val httpStatus: Int = 0,
    val detail: String = "",
    cause: Throwable? = null
) : Exception(detail.ifEmpty { error.name }, cause)

/**
 * WebDAV 客户端抽象。
 *
 * 所有方法都是**阻塞**的（内部是 Socket IO），调用方负责放到 Dispatchers.IO 上；
 * 接口化是为了让 SyncManager 的测试可以注入假实现。
 */
interface WebDavClient {

    /** 取远端元数据；文件不存在返回 null。抛 [WebDavException] 表示其他失败。 */
    fun stat(url: String): RemoteFile?

    /** 读取正文（上限 [maxBytes]，超限抛 TOO_LARGE）；文件不存在返回 null */
    fun get(url: String, maxBytes: Int): ByteArray?

    /**
     * 原子上传：先 PUT 到 `<url>.uploading` 再 MOVE 覆盖正式文件。
     * 目的与本地 DataStore 的「写临时文件 + 原子重命名」完全一致 ——
     * 连接中断时正式文件要么是旧的完整内容，要么是新的完整内容，绝不被截断。
     *
     * @param ifNoneMatchStar true = 只在目标不存在时创建（并发创建会得到 412）
     * @param ifMatch ETag 前置条件（创建场景传 null）
     * @param ifUnmodifiedSinceMs 无 ETag 时的退路：目标修改时间不得晚于该时刻
     */
    fun putAtomic(
        url: String,
        body: ByteArray,
        ifNoneMatchStar: Boolean,
        ifMatch: String?,
        ifUnmodifiedSinceMs: Long?
    ): RemoteFile?

    /** 服务端复制（不传输正文），用于生成冲突副本 */
    fun copy(sourceUrl: String, destUrl: String, overwrite: Boolean)

    /** 创建集合（目录）；已存在视为成功 */
    fun mkcol(url: String)

    /** 删除；不存在视为成功 */
    fun delete(url: String)

    fun close()
}
