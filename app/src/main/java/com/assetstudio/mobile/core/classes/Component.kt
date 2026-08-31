package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Component.cs、Behaviour.cs、Renderer.cs(StaticBatchInfo)
 */

import com.assetstudio.mobile.core.ObjectReader

/** 组件基类，对应 C# AssetStudio.Component */
abstract class Component(reader: ObjectReader) : EditorExtension(reader) {
    val m_GameObject: PPtr = PPtr(reader)
}

/** 行为基类，对应 C# AssetStudio.Behaviour（m_Enabled 为 byte） */
abstract class Behaviour(reader: ObjectReader) : Component(reader) {
    val m_Enabled: Int = reader.readUInt8()

    init {
        reader.alignStream()
    }
}

/** 静态合批信息，对应 C# AssetStudio.StaticBatchInfo */
class StaticBatchInfo(reader: ObjectReader) {
    val firstSubMesh: Int = reader.readUInt16()
    val subMeshCount: Int = reader.readUInt16()
}
