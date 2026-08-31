package com.assetstudio.mobile.core.bundle

/*
 * 来源: AssetStudio/WebFile.cs
 *
 * UnityWeb 嵌套 web 文件（"UnityWebData1.0" 签名）解析。
 * C# 版基于 Stream + BaseStream.Position 跳转；Android 内存模型下
 * 直接持有整个文件的 ByteArray，用 EndianBinaryReader.position 跳转。
 */

import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.io.EndianType
import com.assetstudio.mobile.core.io.getFileName

class WebFile(val data: ByteArray) {

    private class WebData {
        var dataOffset: Int = 0
        var dataLength: Int = 0
        var path: String = ""
    }

    var fileList: List<StreamFile> = emptyList()
        private set

    init {
        val reader = EndianBinaryReader(data, EndianType.LittleEndian)
        val signature = reader.readStringToNull()
        val headLength = reader.readInt32()
        val dataList = ArrayList<WebData>()
        while (reader.position < headLength) {
            val webData = WebData()
            webData.dataOffset = reader.readInt32()
            webData.dataLength = reader.readInt32()
            val pathLength = reader.readInt32()
            webData.path = String(reader.readBytes(pathLength), Charsets.UTF_8)
            dataList.add(webData)
        }
        fileList = dataList.map { webData ->
            val start = webData.dataOffset
            val end = start + webData.dataLength
            // C#: reader.BaseStream.Position = data.dataOffset; ReadBytes(data.dataLength)
            val fileData = if (start in 0..data.size && end <= data.size && end > start) {
                data.copyOfRange(start, end)
            } else if (start in 0 until data.size && end > start) {
                // 短读容错：越界时拷贝到末尾（对应 C# Stream.Read 语义）
                data.copyOfRange(start, data.size)
            } else {
                ByteArray(0)
            }
            StreamFile(webData.path, getFileName(webData.path), fileData)
        }
    }
}
