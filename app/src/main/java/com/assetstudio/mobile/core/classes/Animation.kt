package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Animation.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class Animation(reader: ObjectReader) : Behaviour(reader) {
    val m_Animations: Array<PPtr>

    init {
        val m_Animation = PPtr(reader)
        m_Animations = Array(reader.readArrayCount()) { PPtr(reader) }
    }
}
