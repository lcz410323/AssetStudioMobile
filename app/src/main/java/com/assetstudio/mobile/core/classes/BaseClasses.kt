package com.assetstudio.mobile.core.classes

import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.io.BuildTarget
import com.assetstudio.mobile.core.io.BuildType
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.core.serialized.SerializedType
import com.assetstudio.mobile.core.serialized.TypeTree
import com.assetstudio.mobile.core.serialized.TypeTreeHelper

/**
 * 所有 Unity 序列化对象的基类，对应 AssetStudio 的 Object。
 */
open class UnityObject(val reader: ObjectReader) {
    val assetsFile: SerializedFile = reader.assetsFile
    val m_PathID: Long = reader.m_PathID
    val version: IntArray = reader.version
    val buildType: BuildType? = reader.buildType
    val platform: BuildTarget = reader.platform
    val type: ClassIDType = reader.type
    val serializedType: SerializedType? = reader.serializedType
    val byteSize: Long = reader.byteSize

    /** 展示名称（默认子类会覆盖/填充） */
    open val displayName: String get() = m_Name ?: type.name

    var m_Name: String? = null

    init {
        reader.reset()
        if (platform == BuildTarget.NoTarget) {
            val m_ObjectHideFlags = reader.readUInt32()
        }
    }

    /** 通过 TypeTree 转储对象内容为文本 */
    fun dump(): String? {
        serializedType?.m_Type?.let { return dump(it) }
        return null
    }

    fun dump(m_Type: TypeTree): String? {
        return try {
            TypeTreeHelper.readTypeString(m_Type, reader)
        } catch (e: Exception) {
            null
        }
    }

    /** 通过 TypeTree 读取为键值结构 */
    fun toType(): Any? {
        serializedType?.m_Type?.let { return toType(it) }
        return null
    }

    fun toType(m_Type: TypeTree): Any? {
        return try {
            TypeTreeHelper.readType(m_Type, reader)
        } catch (e: Exception) {
            null
        }
    }

    fun getRawData(): ByteArray {
        reader.reset()
        return reader.readBytes(byteSize.toInt())
    }
}

/** Editor 扩展基类 */
abstract class EditorExtension(reader: ObjectReader) : UnityObject(reader) {
    init {
        if (platform == BuildTarget.NoTarget) {
            val m_PrefabParentObject = PPtr(reader)
            val m_PrefabInternal = PPtr(reader) // PPtr<Prefab>
        }
    }
}

/** 带名称的对象基类 */
abstract class NamedObject(reader: ObjectReader) : EditorExtension(reader) {
    val mName: String = reader.readAlignedString()

    override val displayName: String get() = mName
}
