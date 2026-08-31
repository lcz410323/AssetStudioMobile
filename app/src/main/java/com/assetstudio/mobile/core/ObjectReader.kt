package com.assetstudio.mobile.core

import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.io.EndianType
import com.assetstudio.mobile.core.io.BuildTarget
import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.core.io.BuildType
import com.assetstudio.mobile.core.serialized.ObjectInfo
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.core.serialized.SerializedFileFormatVersion
import com.assetstudio.mobile.core.serialized.SerializedType

/**
 * 对象读取器：基于整个序列化文件的字节数组，定位到单个对象的起始位置进行读取。
 * 与 AssetStudio 的 ObjectReader 对应。
 */
class ObjectReader(
    buffer: ByteArray,
    val assetsFile: SerializedFile,
    objectInfo: ObjectInfo
) : EndianBinaryReader(buffer, assetsFile.readerEndian, objectInfo.byteStart.toInt()) {

    val m_PathID: Long = objectInfo.m_PathID
    val byteStart: Long = objectInfo.byteStart
    val byteSize: Long = objectInfo.byteSize
    val type: ClassIDType = ClassIDType.fromValue(objectInfo.classID)
    val serializedType: SerializedType? = objectInfo.serializedType
    val platform: BuildTarget = assetsFile.m_TargetPlatform
    val m_Version: SerializedFileFormatVersion = assetsFile.header.m_Version

    val version: IntArray get() = assetsFile.version
    val buildType: BuildType? get() = assetsFile.buildType

    /** 当前对象所在的 SerializedFile 字节 */
    val fileBuffer: ByteArray = buffer

    fun reset() {
        position = byteStart.toInt()
    }
}
