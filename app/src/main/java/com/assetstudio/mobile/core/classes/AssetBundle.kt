package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/AssetBundle.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class AssetInfo(reader: ObjectReader) {
    val preloadIndex: Int = reader.readInt32()
    val preloadSize: Int = reader.readInt32()
    val asset: PPtr = PPtr(reader)
}

class AssetBundle(reader: ObjectReader) : NamedObject(reader) {
    val m_PreloadTable: Array<PPtr>
    val m_Container: Array<Pair<String, AssetInfo>>

    init {
        val m_PreloadTableSize = reader.readArrayCount()
        m_PreloadTable = Array(m_PreloadTableSize) { PPtr(reader) }

        val m_ContainerSize = reader.readArrayCount()
        m_Container = Array(m_ContainerSize) { Pair(reader.readAlignedString(), AssetInfo(reader)) }
    }
}
