package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/MonoScript.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class MonoScript(reader: ObjectReader) : NamedObject(reader) {
    val m_ClassName: String
    val m_Namespace: String
    val m_AssemblyName: String

    init {
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 4)) { //3.4 and up
            val m_ExecutionOrder = reader.readInt32()
        }
        if (version[0] < 5) { //5.0 down
            val m_PropertiesHash = reader.readUInt32()
        } else {
            val m_PropertiesHash = reader.readBytes(16)
        }
        if (version[0] < 3) { //3.0 down
            val m_PathName = reader.readAlignedString()
        }
        m_ClassName = reader.readAlignedString()
        if (version[0] >= 3) { //3.0 and up
            m_Namespace = reader.readAlignedString()
        } else {
            m_Namespace = ""
        }
        m_AssemblyName = reader.readAlignedString()
        if (version[0] < 2018 || (version[0] == 2018 && version[1] < 2)) { //2018.2 down
            val m_IsEditorScript = reader.readBoolean()
        }
    }
}
