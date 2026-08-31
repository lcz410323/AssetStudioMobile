package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/TextAsset.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class TextAsset(reader: ObjectReader) : NamedObject(reader) {
    val m_Script: ByteArray = reader.readUInt8Array()

    /** 文本内容（UTF-8 解码） */
    fun getScriptText(): String = String(m_Script, Charsets.UTF_8)
}
