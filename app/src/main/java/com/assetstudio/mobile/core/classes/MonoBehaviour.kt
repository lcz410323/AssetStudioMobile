package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/MonoBehaviour.cs
 * 注意: MonoBehaviour 拥有自己的 m_Name（覆盖基类 NamedObject 已读的那个），
 * displayName 使用子类字段。
 */

import com.assetstudio.mobile.core.ObjectReader

class MonoBehaviour(reader: ObjectReader) : Behaviour(reader) {
    val m_Script: PPtr = PPtr(reader) // PPtr<MonoScript>
    val mName: String = reader.readAlignedString()

    override val displayName: String get() = mName
}
