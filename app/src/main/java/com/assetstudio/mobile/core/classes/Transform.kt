package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Transform.cs、RectTransform.cs
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Quaternion
import com.assetstudio.mobile.core.math.Vector3

open class Transform(reader: ObjectReader) : Component(reader) {
    val m_LocalRotation: Quaternion = reader.readQuaternion()
    val m_LocalPosition: Vector3 = reader.readVector3()
    val m_LocalScale: Vector3 = reader.readVector3()
    val m_Children: Array<PPtr>
    val m_Father: PPtr

    init {
        val m_ChildrenCount = reader.readArrayCount()
        m_Children = Array(m_ChildrenCount) { PPtr(reader) }
        m_Father = PPtr(reader)
    }
}

class RectTransform(reader: ObjectReader) : Transform(reader)
