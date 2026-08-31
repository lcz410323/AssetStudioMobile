package com.assetstudio.mobile.core.classes

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.serialized.FileIdentifier
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.core.serialized.SerializedFileFormatVersion

/**
 * Unity 对象引用指针，对应 AssetStudio 的 PPtr<T>。
 * Kotlin 泛型擦除下通过 tryGet<T> 使用。
 */
class PPtr(reader: ObjectReader) {
    val m_FileID: Int = reader.readInt32()
    val m_PathID: Long =
        if (reader.m_Version < SerializedFileFormatVersion.Unknown_14) reader.readInt32().toLong()
        else reader.readInt64()

    private val assetsFile: SerializedFile = reader.assetsFile

    val isNull: Boolean get() = m_PathID == 0L || m_FileID < 0

    private fun tryGetAssetsFile(): SerializedFile? {
        if (m_FileID == 0) {
            return assetsFile
        }
        if (m_FileID > 0 && m_FileID - 1 < assetsFile.m_Externals.size) {
            val assetsManager = assetsFile.assetsManager
            val external = assetsFile.m_Externals[m_FileID - 1]
            val name = external.fileName
            return assetsManager.findAssetsFile(name)
        }
        return null
    }

    /** 尝试解析引用的对象 */
    fun <T : UnityObject> tryGet(): T? {
        val sourceFile = tryGetAssetsFile() ?: return null
        val obj = sourceFile.objectsDic[m_PathID] ?: return null
        @Suppress("UNCHECKED_CAST")
        return obj as? T
    }

    fun tryGetAny(): UnityObject? {
        val sourceFile = tryGetAssetsFile() ?: return null
        return sourceFile.objectsDic[m_PathID]
    }

    fun set(mObject: UnityObject) {
        val name = mObject.assetsFile.fileName
        if (assetsFile.fileName.equals(name, ignoreCase = true)) {
            // m_FileID = 0
        } else {
            val index = assetsFile.m_Externals.indexOfFirst { it.fileName.equals(name, ignoreCase = true) }
            // 只读场景下不修改外部表
            if (index >= 0) {
                // m_FileID = index + 1
            }
        }
        // m_PathID = mObject.m_PathID
    }
}
