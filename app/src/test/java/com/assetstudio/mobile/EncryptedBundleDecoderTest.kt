package com.assetstudio.mobile

import com.assetstudio.mobile.core.crypto.EncryptedBundleDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 加密 bundle 自动探测/解密测试：
 * 1. 标准文件不受影响
 * 2. 头部垃圾字节剥离
 * 3. 全文件单字节 XOR
 * 4. 仅头部 XOR（备选候选）
 * 5. 手动解密（XOR 循环密钥 / AES-CBC 往返）
 */

/** 构造最小合法 UnityFS 头（signature + \0 + version=6 + 剩余填充） */
private fun fakeUnityFs(size: Int): ByteArray {
    val out = ByteArray(size)
    val sig = "UnityFS".toByteArray(Charsets.US_ASCII)
    System.arraycopy(sig, 0, out, 0, sig.size)
    out[sig.size] = 0 // NUL 终止
    // version = 6 (uint32 BE)
    out[sig.size + 1] = 0; out[sig.size + 2] = 0; out[sig.size + 3] = 0; out[sig.size + 4] = 6
    // 其余填充可辨识数据
    for (i in (sig.size + 5) until size) out[i] = (i % 251).toByte()
    return out
}

class EncryptedBundleDecoderTest {

    @Test
    fun `标准文件不触发解包`() {
        val data = fakeUnityFs(4096)
        assertNull(EncryptedBundleDecoder.autoUnwrap(data))
    }

    @Test
    fun `头部垃圾字节被剥离`() {
        val bundle = fakeUnityFs(4096)
        val junk = ByteArray(137) { (it * 7 + 3).toByte() }
        val wrapped = junk + bundle
        val result = EncryptedBundleDecoder.autoUnwrap(wrapped)
        assertNotNull(result)
        assertContentEquals(bundle, result.data)
    }

    @Test
    fun `全文件 XOR 自动解密`() {
        val bundle = fakeUnityFs(4096)
        val key = 0x5A
        val encrypted = bundle.map { (it.toInt() xor key).toByte() }.toByteArray()
        val result = EncryptedBundleDecoder.autoUnwrap(encrypted)
        assertNotNull(result)
        assertContentEquals(bundle, result.data)
        assertEquals(key, EncryptedBundleDecoder.detectXorKey(encrypted))
        // 生成了备选候选（仅头部 XOR 边界）
        assertTrue(result.fallbacks.isNotEmpty())
    }

    @Test
    fun `仅头部 XOR 由备选候选覆盖`() {
        val bundle = fakeUnityFs(8192)
        val key = 0x37
        val encrypted = bundle.copyOf()
        for (i in 0 until 0x1000) encrypted[i] = (encrypted[i].toInt() xor key).toByte()
        // 仅头部 XOR：全文件解密（首选）不等于原文件，备选 0x1000 边界才正确
        val result = EncryptedBundleDecoder.autoUnwrap(encrypted)
        assertNotNull(result)
        val full = result.data
        // 首选（全文件 XOR）尾部数据被破坏
        assertTrue(!full.contentEquals(bundle))
        // 备选中的 0x1000 边界版本完整还原
        val match = result.fallbacks.firstOrNull { it.contentEquals(bundle) }
        assertNotNull(match, "备选候选应包含仅头部 0x1000 XOR 的正确解密结果")
    }

    @Test
    fun `随机数据不误判`() {
        val random = ByteArray(8192) { (it * 31 + 17).toByte() }
        assertNull(EncryptedBundleDecoder.autoUnwrap(random))
        assertNull(EncryptedBundleDecoder.detectXorKey(random))
    }

    @Test
    fun `hex 编解码往返`() {
        val bytes = byteArrayOf(0x0d, 0x0a.toByte(), 0x1b, 0x2c, 0x00, 0x7f.toByte())
        val hex = EncryptedBundleDecoder.bytesToHex(bytes)
        assertEquals("0d0a1b2c007f", hex)
        assertContentEquals(bytes, EncryptedBundleDecoder.hexToBytes(hex))
        // 空格/冒号容忍
        assertContentEquals(bytes, EncryptedBundleDecoder.hexToBytes("0d 0a:1b 2c 00 7f"))
    }

    @Test
    fun `手动 XOR 循环密钥解密`() {
        val plain = fakeUnityFs(2048)
        val key = byteArrayOf(0x11, 0x22, 0x33)
        val encrypted = plain.mapIndexed { i, b -> (b.toInt() xor key[i % 3].toInt()).toByte() }.toByteArray()
        val decrypted = EncryptedBundleDecoder.manualDecrypt(
            encrypted, key, EncryptedBundleDecoder.DecryptMode.XOR
        )
        assertContentEquals(plain, decrypted)
    }

    @Test
    fun `AES CBC 手动解密往返`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it + 1).toByte() }
        // 明文长度须为 16 倍数（PKCS5 填充由 Cipher 处理）
        val plain = ByteArray(160) { (it % 97).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        val encrypted = cipher.doFinal(plain)
        val decrypted = EncryptedBundleDecoder.manualDecrypt(
            encrypted, key, EncryptedBundleDecoder.DecryptMode.AES_CBC, iv
        )
        assertContentEquals(plain, decrypted)
    }

    @Test
    fun `前缀偏移保留不加密头部`() {
        val prefix = "FAKEHDR!".toByteArray()
        val body = fakeUnityFs(1024)
        val key = 0x66
        val encryptedBody = body.map { (it.toInt() xor key).toByte() }.toByteArray()
        val file = prefix + encryptedBody
        val decrypted = EncryptedBundleDecoder.manualDecrypt(
            file,
            byteArrayOf(key.toByte()),
            EncryptedBundleDecoder.DecryptMode.XOR,
            skipPrefix = prefix.size
        )
        assertContentEquals(prefix + body, decrypted)
    }
}
