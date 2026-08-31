package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/MeshFilter.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class MeshFilter(reader: ObjectReader) : Component(reader) {
    val m_Mesh: PPtr = PPtr(reader) // PPtr<Mesh>
}
