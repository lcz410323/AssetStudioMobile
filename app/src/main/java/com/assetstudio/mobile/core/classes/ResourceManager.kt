package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/ResourceManager.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class ResourceManager(reader: ObjectReader) : UnityObject(reader) {
    val m_Container: Array<Pair<String, PPtr>>

    init {
        val m_ContainerSize = reader.readArrayCount()
        m_Container = Array(m_ContainerSize) { Pair(reader.readAlignedString(), PPtr(reader)) }
    }
}
