package moe.hellowidget

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * 投产 QA：ContentSerializer 文件格式与完整性校验的 JVM 单元测试。
 *
 * 覆盖目标（对应 README 中「DataStore 原子写入 + CRC32 校验」「数据永不损坏」的承诺）：
 *  - 文件格式必须严格等于 [UTF-8 内容][4 字节大端 CRC32]
 *  - 正常内容（ASCII / CJK / emoji / 多行 / 超大 / 空）必须无损往返
 *  - 任何字节级篡改、截断、长度不足都必须抛出 CorruptionException（而不是静默返回错误内容）
 *  - 空文件必须安全降级为空串（首次启动场景）
 */
class ContentSerializerTest {

    private fun encode(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { ContentSerializer.writeTo(value, out) }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): String =
        runBlocking { ContentSerializer.readFrom(ByteArrayInputStream(bytes)) }

    private fun assertCorruption(bytes: ByteArray, label: String) {
        try {
            val result = decode(bytes)
            fail("$label 应当被判定为损坏，但实际返回了内容：\"$result\"")
        } catch (expected: CorruptionException) {
            assertTrue("$label 抛出了 CorruptionException", true)
        }
    }

    private fun bigEndianAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    // ---------- 1. 契约与格式 ----------

    @Test
    fun defaultValue_isEmptyString() {
        assertEquals("空默认值是「首次启动无内容」的前提", "", ContentSerializer.defaultValue)
    }

    @Test
    fun fileFormat_isPayloadFollowedByBigEndianCrc32() {
        val text = "Hello, 世界 🌍"
        val payload = text.toByteArray(Charsets.UTF_8)
        val bytes = encode(text)

        assertEquals("文件长度 = 内容长度 + 4 字节 CRC", payload.size + 4, bytes.size)
        assertArrayEquals("前 N 字节必须是原始 UTF-8 内容", payload, bytes.copyOfRange(0, payload.size))

        val expectedCrc = CRC32().apply { update(payload) }.value.toInt()
        assertEquals("末 4 字节必须是大端序 CRC32", expectedCrc, bigEndianAt(bytes, bytes.size - 4))
    }

    // ---------- 2. 无损往返 ----------

    @Test
    fun roundTrip_ascii() {
        val text = "Hello, Widget!"
        assertEquals(text, decode(encode(text)))
    }

    @Test
    fun roundTrip_cjk() {
        val text = "这是一段中文内容，用于验证 UTF-8 编码往返。"
        assertEquals(text, decode(encode(text)))
    }

    @Test
    fun roundTrip_emojiAndSurrogatePair() {
        val text = "emoji: 😀🎉👨‍👩‍👧‍👦"
        assertEquals(text, decode(encode(text)))
    }

    @Test
    fun roundTrip_preservesNewlinesAndTrailingWhitespace() {
        val text = "第一行\n第二行\n\n  行尾空格  \n"
        assertEquals("多行内容必须逐字节保留，不得 trim", text, decode(encode(text)))
    }

    @Test
    fun roundTrip_embeddedNulByte() {
        val text = "a\u0000b"
        assertEquals(text, decode(encode(text)))
    }

    @Test
    fun roundTrip_emptyString() {
        val bytes = encode("")
        assertEquals("空内容 = 0 字节 payload + 4 字节 CRC", 4, bytes.size)
        assertEquals("", decode(bytes))
    }

    @Test
    fun roundTrip_largePayload_1MiB() {
        val text = "内".repeat(350_000) // 3 字节/字 ≈ 1 MiB
        val bytes = encode(text)
        assertEquals(text.length * 3 + 4, bytes.size)
        assertEquals(text, decode(bytes))
    }

    // ---------- 3. 损坏检测（「数据永不损坏」承诺的负向验证） ----------

    @Test
    fun emptyStream_decodesToEmptyString_withoutException() {
        assertEquals("首次启动文件为空时必须安全降级", "", decode(ByteArray(0)))
    }

    @Test
    fun rejectsFilesShorterThan4Bytes() {
        for (size in 1..3) {
            assertCorruption(ByteArray(size), "长度 ${size} 字节的文件")
        }
    }

    @Test
    fun detectsFlippedPayloadBit() {
        val bytes = encode("abcdef")
        bytes[2] = (bytes[2].toInt() xor 0x01).toByte()
        assertCorruption(bytes, "内容区被翻转 1 bit")
    }

    @Test
    fun detectsFlippedCrcBit() {
        val bytes = encode("abcdef")
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertCorruption(bytes, "CRC 区被翻转 1 bit")
    }

    @Test
    fun detectsTruncatedTail() {
        val bytes = encode("abcdef")
        assertCorruption(bytes.copyOfRange(0, bytes.size - 1), "文件被截断 1 字节")
    }

    @Test
    fun detectsAppendedGarbageByte() {
        val bytes = encode("abcdef")
        assertCorruption(bytes + byteArrayOf(0x00), "文件尾部被追加 1 字节")
    }

    @Test
    fun detectsSwappedPayloadOrder() {
        val bytes = encode("abcdef")
        val payload = bytes.copyOfRange(0, bytes.size - 4)
        val reversed = payload.reversedArray()
        assertCorruption(reversed + bytes.copyOfRange(bytes.size - 4, bytes.size), "内容区字节序被打乱")
    }

    // ---------- 4. 已记录的边界行为 ----------

    /**
     * 记录一个已知的固有边界：UTF-8 编码器无法表示「孤立代理项」，
     * 会被替换为 '?'。此测试固化该行为，避免将来悄无声息地改变。
     */
    @Test
    fun unpairedSurrogate_isLossilyEncoded_documentedBehavior() {
        val text = "a\uD800b"
        val decoded = decode(encode(text))
        assertNotEquals("孤立代理项无法无损往返（已记录）", text, decoded)
        assertEquals(3, decoded.length)
    }
}
