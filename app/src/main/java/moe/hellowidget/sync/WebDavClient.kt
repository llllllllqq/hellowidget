package moe.hellowidget.sync

/**
 * 同步失败的可分类原因。界面按它给出本地化文案，测试按它断言。
 * 分类刻意做得细：用户看到「凭据错误」和「服务器不支持写入」需要完全不同的动作。
 *
 * v7.5 起不再有「前置条件失败」与「文件过大」两种 —— 上传是强制覆盖、也不下载云端内容。
 */
enum class WebDavError {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    PARENT_NOT_FOUND,
    NOT_SUPPORTED,
    LOCKED,
    INSUFFICIENT_STORAGE,
    SERVER_ERROR,
    REDIRECT,
    NETWORK,
    TIMEOUT,
    TLS_UNTRUSTED,
    TLS,
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
 * v7.5 起只保留「单向上传」需要的两个方法：写文件（强制覆盖）与建目录。
 * 读取（HEAD/PROPFIND/GET）、复制与删除都不再需要 —— 同步不读云端状态，
 * 因此客户端里也没有任何条件请求。
 *
 * 所有方法都是**阻塞**的（内部是 Socket IO），调用方负责放到 Dispatchers.IO 上；
 * 接口化是为了让 SyncManager 的测试可以注入假实现。
 */
interface WebDavClient {

    /**
     * 把正文写到该地址，**强制覆盖**云端已有内容。
     *
     * 不带任何 `If-Match` / `If-None-Match` / `If-Unmodified-Since` 条件：
     * 本应用的产品语义就是「本地是唯一真相，上传即覆盖」。
     */
    fun put(url: String, body: ByteArray)

    /** 创建集合（目录）；已存在视为成功 */
    fun mkcol(url: String)

    fun close()
}
