package com.assetstudio.mobile.core.crypto

/*
 * 加密/加壳 AssetBundle 自动探测与解密。
 *
 * 移动端游戏 bundle 常见"加密"形式（AB 管理器等同类工具普遍支持）：
 * 1. 头部垃圾字节：文件开头附加自定义头（签名/索引），"UnityFS" 魔数不在偏移 0
 * 2. 单字节 XOR：整个文件（或仅头部区域）与固定 key 异或，魔数不可见
 * 3. 已知密钥 AES：头部或整文件 AES 加密，密钥硬编码在游戏 so 中（需用户手动提供）
 *
 * 本模块自动处理 1/2（无感知），并为 3 提供手动解密原语。
 * 自动处理绝不影响标准文件：魔数位于偏移 0 的正常 bundle 直接原样通过。
 */

/** Unity 容器签名（C# AssetStudio CheckFileType 认可的完整集合） */
private val UNITY_SIGNATURES = arrayOf(
    "UnityFS", "UnityWeb", "UnityRaw", "UnityArchive", "UnityWebData1.0"
)

/** 魔数搜索窗口：自定义头一般不超过 1MB */
private const val MAGIC_SCAN_LIMIT = 1 shl 20

/** 自动解包结果 */
class UnwrapResult(
    /** 首选解包数据 */
    val data: ByteArray,
    /** 备选候选（首选解析失败时依次重试，例如"仅头部 XOR"的不同边界） */
    val fallbacks: List<ByteArray> = emptyList()
)

object EncryptedBundleDecoder {

    /**
     * 自动解包入口：检测头部垃圾字节与单字节 XOR 加密。
     * @return null 表示文件是标准结构（魔数在偏移 0），无需处理；
     *         否则返回解包结果（含备选候选）
     */
    fun autoUnwrap(data: ByteArray): UnwrapResult? {
        if (data.size < 32) return null
        // 标准文件：魔数就在偏移 0
        if (startsWithSignature(data, 0) != null) return null

        // ① 单字节 XOR 加密探测（优先：加密文件魔数被破坏但结构完整）
        detectXorKey(data)?.let { key ->
            val full = xorBytes(data, key, data.size)
            if (verifyCandidate(full, 0)) {
                // 候选边界：全文件 / 仅头部若干字节（游戏可能只加密头部）
                val fallbacks = listOf(0x1000, 0x400, 0x100, 0x40)
                    .filter { it < data.size }
                    .map { xorBytes(data, key, it) }
                return UnwrapResult(full, fallbacks)
            }
        }

        // ② 头部垃圾字节：窗口内搜索 Unity 签名（要求签名后紧跟合法版本号）
        val limit = minOf(data.size - 8, MAGIC_SCAN_LIMIT)
        var i = 1
        while (i < limit) {
            val sig = findSignatureAt(data, i)
            if (sig != null && verifyCandidate(data, i)) {
                // 剥离前置垃圾字节
                return UnwrapResult(data.copyOfRange(i, data.size))
            }
            i++
        }
        return null
    }

    /** 文件偏移 0 处的签名（null = 非标准 Unity 容器头） */
    fun startsWithSignature(data: ByteArray, offset: Int): String? {
        for (sig in UNITY_SIGNATURES) {
            val b = sig.toByteArray(Charsets.US_ASCII)
            if (offset + b.size + 1 <= data.size && data[offset + b.size] == 0.toByte()) {
                var ok = true
                for (i in b.indices) if (data[offset + i] != b[i]) { ok = false; break }
                if (ok) return sig
            }
        }
        return null
    }

    /**
     * 校验候选偏移处的 UnityFS/UnityWeb 头部合法性：
     * version 为小整数（3~9），防止把 bundle 内部恰好出现的字符串误认成文件头。
     */
    private fun verifyCandidate(data: ByteArray, offset: Int): Boolean {
        val sig = findSignatureAt(data, offset) ?: return false
        val after = offset + sig.length + 1
        if (after + 4 > data.size) return false
        val version = ((data[after].toInt() and 0xFF) shl 24) or
            ((data[after + 1].toInt() and 0xFF) shl 16) or
            ((data[after + 2].toInt() and 0xFF) shl 8) or
            (data[after + 3].toInt() and 0xFF)
        return version in 3..9
    }

    /** 在指定偏移寻找 Unity 签名（带 NUL 终止校验） */
    private fun findSignatureAt(data: ByteArray, offset: Int): String? {
        for (sig in UNITY_SIGNATURES) {
            val b = sig.toByteArray(Charsets.US_ASCII)
            if (offset + b.size + 1 <= data.size && data[offset + b.size] == 0.toByte()) {
                var ok = true
                for (i in b.indices) if (data[offset + i] != b[i]) { ok = false; break }
                if (ok) return sig
            }
        }
        return null
    }

    /**
     * 单字节 XOR key 探测：以 "UnityFS" 等魔数首字符反推 key 并验证整个签名。
     * @return XOR key（非 0），找不到返回 null
     */
    fun detectXorKey(data: ByteArray): Int? {
        if (data.size < 16) return null
        for (sig in UNITY_SIGNATURES) {
            val b = sig.toByteArray(Charsets.US_ASCII)
            if (data.size < b.size + 1) continue
            val key = (data[0].toInt() xor b[0].toInt()) and 0xFF
            if (key == 0) continue
            var ok = true
            for (i in 1 until b.size) {
                if (((data[i].toInt() xor key) and 0xFF) != (b[i].toInt() and 0xFF)) {
                    ok = false; break
                }
            }
            // 签名后的 NUL 终止符同样被 XOR
            if (ok && ((data[b.size].toInt() xor key) and 0xFF) == 0) return key
        }
        return null
    }

    /** 前 length 字节与 key 异或（返回新数组，其余部分原样拷贝） */
    fun xorBytes(data: ByteArray, key: Int, length: Int): ByteArray {
        val out = data.copyOf()
        val n = minOf(length, out.size)
        for (i in 0 until n) out[i] = (out[i].toInt() xor key).toByte()
        return out
    }

    // ============================ 手动解密（已知密钥） ============================

    /** 手动解密模式 */
    enum class DecryptMode { XOR, AES_ECB, AES_CBC }

    /**
     * 手动解密：按用户提供的密钥处理数据。
     * @param keyBytes 密钥字节（XOR 为循环 key；AES 要求 16/24/32 字节）
     * @param ivBytes CBC 模式的 IV（16 字节，可空则取数据前 16 字节）
     * @param skipPrefix 先跳过前若干字节（部分游戏仅加密头部之后的数据）
     */
    fun manualDecrypt(
        data: ByteArray,
        keyBytes: ByteArray,
        mode: DecryptMode,
        ivBytes: ByteArray? = null,
        skipPrefix: Int = 0
    ): ByteArray {
        require(skipPrefix in 0..data.size) { "前缀偏移越界" }
        val prefix = data.copyOfRange(0, skipPrefix)
        val body = data.copyOfRange(skipPrefix, data.size)
        val decrypted = when (mode) {
            DecryptMode.XOR -> xorWithKey(body, keyBytes)
            DecryptMode.AES_ECB -> aesDecrypt(body, keyBytes, null)
            DecryptMode.AES_CBC -> aesDecrypt(body, keyBytes, ivBytes ?: body.copyOf(16))
        }
        return prefix + decrypted
    }

    /** 循环多字节 key XOR */
    private fun xorWithKey(data: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return data.copyOf()
        val out = data.copyOf()
        for (i in out.indices) out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    /** AES 解密（自动识别 128/192/256），去除 PKCS5 填充失败的尾部容忍处理 */
    private fun aesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES 密钥长度须为 16/24/32 字节，当前 ${key.size}"
        }
        require(data.size >= key.size && data.size % 16 == 0) {
            "密文长度须为 16 的倍数"
        }
        val cipher = javax.crypto.Cipher.getInstance(
            if (iv == null) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding"
        )
        val secret = javax.crypto.spec.SecretKeySpec(key, "AES")
        if (iv == null) {
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret)
        } else {
            require(iv.size == 16) { "IV 长度须为 16 字节" }
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE, secret,
                javax.crypto.spec.IvParameterSpec(iv)
            )
        }
        return cipher.doFinal(data)
    }

    /** hex 字符串转字节（忽略空格/冒号分隔，长度须为偶数） */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "").replace("\n", "")
        require(clean.length % 2 == 0) { "hex 长度须为偶数" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "包含非 hex 字符" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 字节转 hex（小写） */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
            sb.append("0123456789abcdef"[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
