package com.assetstudio.mobile.core.serialized

class SerializedFileHeader {
    var m_MetadataSize: Long = 0
    var m_FileSize: Long = 0
    var m_Version: SerializedFileFormatVersion = SerializedFileFormatVersion.Unsupported
    var m_DataOffset: Long = 0
    var m_Endianess: Byte = 0
    var m_Reserved: ByteArray? = null

    // ===== 供文件重写（贴图替换）使用的字段绝对偏移 =====
    /** m_FileSize 字段在文件中的绝对偏移 */
    var fileSizeFieldOffset: Int = 0
    var fileSizeFieldSize: Int = 4
    /** m_MetadataSize 字段在文件中的绝对偏移（LargeFilesSupport 下为第二个） */
    var metadataSizeFieldOffset: Int = 0
    /** m_DataOffset 字段在文件中的绝对偏移 */
    var dataOffsetFieldOffset: Int = 0
    var dataOffsetFieldSize: Int = 4
    /** 元数据段在文件中的起始绝对偏移 */
    var metadataStart: Int = 0
    /** 元数据段结束（对象数据开始）位置 */
    var metadataEnd: Int = 0
}

class ObjectInfo {
    var m_PathID: Long = 0
    /** 对象数据起始（已加 dataOffset 的绝对位置） */
    var byteStart: Long = 0
    /** 原始存储的 byteStart（相对 dataOffset） */
    var storedByteStart: Long = 0
    var byteSize: Long = 0
    var typeID: Int = 0
    var classID: Int = 0
    var serializedType: SerializedType? = null
    var isDestroyed: Int = 0
    var stripped: Byte = 0

    // ===== 供文件重写（贴图替换）使用的字段位置 =====
    /** byteStart 字段在文件中的绝对偏移 */
    var byteStartFieldOffset: Int = 0
    var byteStartFieldSize: Int = 4
    /** byteSize 字段在文件中的绝对偏移 */
    var byteSizeFieldOffset: Int = 0
    /** 对齐方式（依据原始存储值推断：16 的倍数则 16，否则 1） */
    var alignment: Int = 1
}

class TypeTreeNode {
    var m_Level: Int = 0
    var m_Type: String = ""
    var m_Name: String = ""
    var m_ByteSize: Int = 0
    var m_Index: Int = 0
    var m_TypeFlags: Int = 0
    var m_Version: Int = 0
    var m_MetaFlag: Int = 0
    var m_RefTypeHash: Long = 0
    var m_TypeStrOffset: Long = 0
    var m_NameStrOffset: Long = 0
}

class TypeTree {
    var m_Nodes: MutableList<TypeTreeNode> = arrayListOf()
    var m_StringBuffer: ByteArray? = null
}

class SerializedType {
    var classID: Int = 0
    var m_IsStrippedType: Boolean = false
    var m_ScriptTypeIndex: Short = 0
    var m_ScriptID: ByteArray? = null
    var m_OldTypeHash: ByteArray? = null
    var m_Type: TypeTree? = null
    var m_TypeDependencies: IntArray? = null
    var m_KlassName: String? = null
    var m_NameSpace: String? = null
    var m_AsmName: String? = null
}

class FileIdentifier {
    var tempEmpty: String? = null
    var guid: ByteArray? = null
    var type: Int = 0
    var pathName: String = ""
    var fileName: String = ""
}

class LocalSerializedObjectIdentifier {
    var localSerializedFileIndex: Int = 0
    var localIdentifierInFile: Long = 0
}
