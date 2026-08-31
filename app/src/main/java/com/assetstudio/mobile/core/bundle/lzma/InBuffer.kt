package com.assetstudio.mobile.core.bundle.lzma

/**
 * 来自 7zip/Common/InBuffer.cs。
 * C# 版包装 Stream 并做分块缓冲；内存模型下直接包装输入 ByteArray，
 * 保留 Init/ReleaseStream/ReadByte/GetProcessedSize 接口语义。
 * 与 C# 一致：流耗尽后 ReadByte 返回 0xFF。
 */
class InBuffer(bufferSize: Int = 1 shl 16) {

    @Suppress("UNUSED_PARAMETER")
    private val bufferSize = bufferSize // 内存模型下无分块需求，保留构造参数以对应 C# 签名

    private var data: ByteArray = ByteArray(0)
    private var pos: Int = 0
    private var limit: Int = 0
    private var streamWasExhausted: Boolean = false

    /** 对应 C# Init(Stream)；此处绑定整个输入数组及起始偏移 */
    fun init(data: ByteArray, offset: Int = 0) {
        this.data = data
        this.pos = offset
        this.limit = data.size
        this.streamWasExhausted = false
    }

    /** 对应 C# ReleaseStream()：解除引用 */
    fun releaseStream() {
        data = ByteArray(0)
        pos = 0
        limit = 0
    }

    /** 对应 C# ReadBlock()：内存模型下数据已全部就绪 */
    fun readBlock(): Boolean = !streamWasExhausted

    /** 读取一个字节（0..255），越界返回 0xFF（与 C# 耗尽行为一致） */
    fun readByte(): Int {
        if (pos >= limit) {
            streamWasExhausted = true
            return 0xFF
        }
        return data[pos++].toInt() and 0xFF
    }

    fun getProcessedSize(): Long = pos.toLong()
}
