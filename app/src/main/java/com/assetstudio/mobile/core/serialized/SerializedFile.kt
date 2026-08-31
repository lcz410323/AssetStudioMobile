package com.assetstudio.mobile.core.serialized

/*
 * 来源: AssetStudio/SerializedFile.cs
 *
 * Unity 序列化文件（.assets）解析。
 * 与 C# 的差异（Android 内存模型）：
 * - C# 基于 FileReader(Stream)，这里直接持有原始字节 fileBytes，
 *   内部用 EndianBinaryReader 解析；ObjectReader 也基于 fileBytes 构造。
 * - 新增"重写用字段"记录：header 的 fileSize/metadataSize/dataOffset 字段
 *   绝对偏移与宽度、metadataStart/metadataEnd、每个 ObjectInfo 的
 *   byteStart/byteSize 字段绝对偏移与宽度、alignment、storedByteStart，
 *   供贴图替换后原位重写文件使用（C# 版无此逻辑）。
 */

import com.assetstudio.mobile.core.classes.UnityObject
import com.assetstudio.mobile.core.io.BuildTarget
import com.assetstudio.mobile.core.io.BuildType
import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.io.EndianType
import com.assetstudio.mobile.core.io.getFileName
import com.assetstudio.mobile.core.manager.AssetsManager

class SerializedFile(
    /** 原始文件字节（重写/对象读取都基于它） */
    val fileBytes: ByteArray,
    val fileName: String,
    val fullName: String,
    val assetsManager: AssetsManager,
    unityVersionFromBundle: String? = null
) {
    /** C# originalPath：来自 bundle/web 等容器的原始路径 */
    var originalPath: String = fullName

    /**
     * 用户最初加载的文件名（如 game.bundle / pack.zip）。
     * 贴图替换后另存时按此名作为默认文件名，保证改回原文件即可直接替换游戏文件。
     */
    var rootFileName: String = ""

    /**
     * 来源容器链（贴图替换用）：由外到内排列。
     * 例如 zip 里的 bundle 里的 .assets：[ZipRef, BundleRef]。
     * 重写时从链尾（最内层容器）向外逐层回填替换数据。
     */
    var sourceChain: List<com.assetstudio.mobile.core.manager.SourceRef> = emptyList()

    var version: IntArray = intArrayOf(0, 0, 0, 0)
    var buildType: BuildType? = null
    var unityVersion: String = "2.5.0f5"
    var m_TargetPlatform: BuildTarget = BuildTarget.UnknownPlatform

    val header: SerializedFileHeader = SerializedFileHeader()

    /** 当前解析用读取器的字节序（ObjectReader 按此构造） */
    val readerEndian: EndianType
        get() = reader.endian

    private val reader: EndianBinaryReader = EndianBinaryReader(fileBytes, EndianType.BigEndian)
    private var m_FileEndianess: Byte = 0
    private var m_EnableTypeTree: Boolean = true

    var m_Types: MutableList<SerializedType> = ArrayList()
    var bigIDEnabled: Int = 0
    var m_Objects: MutableList<ObjectInfo> = ArrayList()
    var m_ScriptTypes: MutableList<LocalSerializedObjectIdentifier> = ArrayList()
    var m_Externals: MutableList<FileIdentifier> = ArrayList()
    var m_RefTypes: MutableList<SerializedType> = ArrayList()
    var userInformation: String? = null

    /** 已构造的 Unity 对象（readAssets 阶段填充） */
    val objects: MutableList<UnityObject> = ArrayList()

    /** PathID -> 对象 */
    val objectsDic: LinkedHashMap<Long, UnityObject> = LinkedHashMap()

    val isVersionStripped: Boolean
        get() = unityVersion == STRIPPED_VERSION

    init {
        // ================= ReadHeader =================
        // 固定头部布局：metadataSize@0(4) fileSize@4(4) version@8(4) dataOffset@12(4)
        header.metadataSizeFieldOffset = 0
        header.fileSizeFieldOffset = 4
        header.fileSizeFieldSize = 4
        header.dataOffsetFieldOffset = 12
        header.dataOffsetFieldSize = 4
        header.m_MetadataSize = reader.readUInt32()
        header.m_FileSize = reader.readUInt32()
        header.m_Version = SerializedFileFormatVersion.fromValue(reader.readUInt32().toInt())
        header.m_DataOffset = reader.readUInt32()

        if (header.m_Version >= SerializedFileFormatVersion.Unknown_9) {
            header.m_Endianess = reader.readInt8()
            header.m_Reserved = reader.readBytes(3)
            m_FileEndianess = header.m_Endianess
        } else {
            // 老版本：元数据位于文件尾部 fileSize - metadataSize 处，首字节为 endianess
            val metadataPos = (header.m_FileSize - header.m_MetadataSize).toInt()
            reader.position = metadataPos.coerceIn(0, fileBytes.size)
            m_FileEndianess = reader.readInt8()
        }

        if (header.m_Version >= SerializedFileFormatVersion.LargeFilesSupport) {
            // 大文件头部(>=22)：metadataSize@20(4) fileSize@24(8) dataOffset@32(8) unknown@40(8)
            header.metadataSizeFieldOffset = reader.position
            header.fileSizeFieldOffset = reader.position + 4
            header.fileSizeFieldSize = 8
            header.dataOffsetFieldOffset = reader.position + 12
            header.dataOffsetFieldSize = 8
            header.m_MetadataSize = reader.readUInt32()
            header.m_FileSize = reader.readInt64()
            header.m_DataOffset = reader.readInt64()
            reader.readInt64() // unknown
        }

        // ================= ReadMetadata =================
        if (m_FileEndianess == 0.toByte()) {
            reader.endian = EndianType.LittleEndian
        }
        // 元数据段起始绝对偏移（重写用）
        header.metadataStart = reader.position

        if (header.m_Version >= SerializedFileFormatVersion.Unknown_7) {
            unityVersion = reader.readStringToNull()
            setVersion(unityVersion)
        }
        if (header.m_Version >= SerializedFileFormatVersion.Unknown_8) {
            val targetValue = reader.readInt32()
            // C#: if (!Enum.IsDefined(...)) m_TargetPlatform = UnknownPlatform
            m_TargetPlatform = BuildTarget.entries.firstOrNull { it.value == targetValue }
                ?: BuildTarget.UnknownPlatform
        }
        if (header.m_Version >= SerializedFileFormatVersion.HasTypeTreeHashes) {
            m_EnableTypeTree = reader.readBoolean()
        }

        // ================= Read Types =================
        val typeCount = reader.readInt32()
        m_Types = ArrayList(typeCount)
        for (i in 0 until typeCount) {
            m_Types.add(readSerializedType(false))
        }

        if (header.m_Version >= SerializedFileFormatVersion.Unknown_7 &&
            header.m_Version < SerializedFileFormatVersion.Unknown_14
        ) {
            bigIDEnabled = reader.readInt32()
        }

        // ================= Read Objects =================
        val objectCount = reader.readInt32()
        m_Objects = ArrayList(objectCount)
        for (i in 0 until objectCount) {
            val objectInfo = ObjectInfo()
            if (bigIDEnabled != 0) {
                objectInfo.m_PathID = reader.readInt64()
            } else if (header.m_Version < SerializedFileFormatVersion.Unknown_14) {
                objectInfo.m_PathID = reader.readInt32().toLong()
            } else {
                reader.alignStream()
                objectInfo.m_PathID = reader.readInt64()
            }

            // byteStart 字段位置（重写用）：>=LargeFilesSupport 为 8 字节，否则 4 字节
            objectInfo.byteStartFieldOffset = reader.position
            objectInfo.byteStartFieldSize =
                if (header.m_Version >= SerializedFileFormatVersion.LargeFilesSupport) 8 else 4
            if (header.m_Version >= SerializedFileFormatVersion.LargeFilesSupport) {
                objectInfo.storedByteStart = reader.readInt64()
            } else {
                objectInfo.storedByteStart = reader.readUInt32()
            }
            objectInfo.byteStart = objectInfo.storedByteStart + header.m_DataOffset
            // 对齐方式（重写用）：原始存储值按 16 字节对齐则后续对象也保持 16 对齐，否则 1
            objectInfo.alignment = if (objectInfo.storedByteStart % 16 == 0L) 16 else 1

            objectInfo.byteSizeFieldOffset = reader.position
            objectInfo.byteSize = reader.readUInt32()
            objectInfo.typeID = reader.readInt32()
            if (header.m_Version < SerializedFileFormatVersion.RefactoredClassId) {
                objectInfo.classID = reader.readUInt16()
                objectInfo.serializedType = m_Types.firstOrNull { it.classID == objectInfo.typeID }
            } else {
                val type = m_Types[objectInfo.typeID]
                objectInfo.serializedType = type
                objectInfo.classID = type.classID
            }
            if (header.m_Version < SerializedFileFormatVersion.HasScriptTypeIndex) {
                objectInfo.isDestroyed = reader.readUInt16()
            }
            if (header.m_Version >= SerializedFileFormatVersion.HasScriptTypeIndex &&
                header.m_Version < SerializedFileFormatVersion.RefactorTypeData
            ) {
                val m_ScriptTypeIndex = reader.readInt16()
                objectInfo.serializedType?.m_ScriptTypeIndex = m_ScriptTypeIndex
            }
            if (header.m_Version == SerializedFileFormatVersion.SupportsStrippedObject ||
                header.m_Version == SerializedFileFormatVersion.RefactoredClassId
            ) {
                objectInfo.stripped = reader.readInt8()
            }
            m_Objects.add(objectInfo)
        }

        if (header.m_Version >= SerializedFileFormatVersion.HasScriptTypeIndex) {
            val scriptCount = reader.readInt32()
            m_ScriptTypes = ArrayList(scriptCount)
            for (i in 0 until scriptCount) {
                val m_ScriptType = LocalSerializedObjectIdentifier()
                m_ScriptType.localSerializedFileIndex = reader.readInt32()
                if (header.m_Version < SerializedFileFormatVersion.Unknown_14) {
                    m_ScriptType.localIdentifierInFile = reader.readInt32().toLong()
                } else {
                    reader.alignStream()
                    m_ScriptType.localIdentifierInFile = reader.readInt64()
                }
                m_ScriptTypes.add(m_ScriptType)
            }
        }

        val externalsCount = reader.readInt32()
        m_Externals = ArrayList(externalsCount)
        for (i in 0 until externalsCount) {
            val m_External = FileIdentifier()
            if (header.m_Version >= SerializedFileFormatVersion.Unknown_6) {
                val tempEmpty = reader.readStringToNull()
                m_External.tempEmpty = tempEmpty
            }
            if (header.m_Version >= SerializedFileFormatVersion.Unknown_5) {
                m_External.guid = reader.readBytes(16)
                m_External.type = reader.readInt32()
            }
            m_External.pathName = reader.readStringToNull()
            m_External.fileName = getFileName(m_External.pathName)
            m_Externals.add(m_External)
        }

        if (header.m_Version >= SerializedFileFormatVersion.SupportsRefObject) {
            val refTypesCount = reader.readInt32()
            m_RefTypes = ArrayList(refTypesCount)
            for (i in 0 until refTypesCount) {
                m_RefTypes.add(readSerializedType(true))
            }
        }

        if (header.m_Version >= SerializedFileFormatVersion.Unknown_5) {
            userInformation = reader.readStringToNull()
        }

        // 元数据段结束（对象数据开始）绝对偏移（重写用）
        header.metadataEnd = reader.position

        // 来自 C# AssetsManager.LoadAssetsFromMemory：
        // bundle 的 unityRevision 用于补全低版本(<Unknown_7)文件的版本号
        if (!unityVersionFromBundle.isNullOrEmpty() &&
            header.m_Version < SerializedFileFormatVersion.Unknown_7
        ) {
            setVersion(unityVersionFromBundle)
        }
    }

    /** 对应 C# SetVersion：正则拆出版本数字与 build 类型字符 */
    fun setVersion(stringVersion: String) {
        if (stringVersion != STRIPPED_VERSION) {
            unityVersion = stringVersion
            val buildSplit = Regex("\\d").replace(stringVersion, "")
                .split(".").filter { it.isNotEmpty() }
            buildType = BuildType(buildSplit.getOrElse(0) { "" })
            val versionSplit = Regex("\\D").replace(stringVersion, ".")
                .split(".").filter { it.isNotEmpty() }
            version = versionSplit.map { it.toInt() }.toIntArray()
        }
    }

    /** 对应 C# ReadSerializedType */
    private fun readSerializedType(isRefType: Boolean): SerializedType {
        val type = SerializedType()

        type.classID = reader.readInt32()

        if (header.m_Version >= SerializedFileFormatVersion.RefactoredClassId) {
            type.m_IsStrippedType = reader.readBoolean()
        }

        if (header.m_Version >= SerializedFileFormatVersion.RefactorTypeData) {
            type.m_ScriptTypeIndex = reader.readInt16()
        }

        if (header.m_Version >= SerializedFileFormatVersion.HasTypeTreeHashes) {
            if (isRefType && type.m_ScriptTypeIndex >= 0) {
                type.m_ScriptID = reader.readBytes(16)
            } else if ((header.m_Version < SerializedFileFormatVersion.RefactoredClassId && type.classID < 0) ||
                (header.m_Version >= SerializedFileFormatVersion.RefactoredClassId && type.classID == 114)
            ) {
                type.m_ScriptID = reader.readBytes(16)
            }
            type.m_OldTypeHash = reader.readBytes(16)
        }

        if (m_EnableTypeTree) {
            type.m_Type = TypeTree()
            type.m_Type!!.m_Nodes = ArrayList()
            if (header.m_Version >= SerializedFileFormatVersion.Unknown_12 ||
                header.m_Version == SerializedFileFormatVersion.Unknown_10
            ) {
                typeTreeBlobRead(type.m_Type!!)
            } else {
                readTypeTree(type.m_Type!!)
            }
            if (header.m_Version >= SerializedFileFormatVersion.StoresTypeDependencies) {
                if (isRefType) {
                    type.m_KlassName = reader.readStringToNull()
                    type.m_NameSpace = reader.readStringToNull()
                    type.m_AsmName = reader.readStringToNull()
                } else {
                    type.m_TypeDependencies = reader.readInt32Array()
                }
            }
        }

        return type
    }

    /** 对应 C# ReadTypeTree（老版本的逐节点序列化 typetree） */
    private fun readTypeTree(m_Type: TypeTree, level: Int = 0) {
        val typeTreeNode = TypeTreeNode()
        m_Type.m_Nodes.add(typeTreeNode)
        typeTreeNode.m_Level = level
        typeTreeNode.m_Type = reader.readStringToNull()
        typeTreeNode.m_Name = reader.readStringToNull()
        typeTreeNode.m_ByteSize = reader.readInt32()
        if (header.m_Version == SerializedFileFormatVersion.Unknown_2) {
            val variableCount = reader.readInt32()
        }
        if (header.m_Version != SerializedFileFormatVersion.Unknown_3) {
            typeTreeNode.m_Index = reader.readInt32()
        }
        typeTreeNode.m_TypeFlags = reader.readInt32()
        typeTreeNode.m_Version = reader.readInt32()
        if (header.m_Version != SerializedFileFormatVersion.Unknown_3) {
            typeTreeNode.m_MetaFlag = reader.readInt32()
        }

        val childrenCount = reader.readInt32()
        for (i in 0 until childrenCount) {
            readTypeTree(m_Type, level + 1)
        }
    }

    /** 对应 C# TypeTreeBlobRead（>=12 或 ==10 的 blob 格式 typetree） */
    private fun typeTreeBlobRead(m_Type: TypeTree) {
        val numberOfNodes = reader.readInt32()
        val stringBufferSize = reader.readInt32()
        for (i in 0 until numberOfNodes) {
            val typeTreeNode = TypeTreeNode()
            m_Type.m_Nodes.add(typeTreeNode)
            typeTreeNode.m_Version = reader.readUInt16()
            typeTreeNode.m_Level = reader.readUInt8()
            typeTreeNode.m_TypeFlags = reader.readUInt8()
            typeTreeNode.m_TypeStrOffset = reader.readUInt32()
            typeTreeNode.m_NameStrOffset = reader.readUInt32()
            typeTreeNode.m_ByteSize = reader.readInt32()
            typeTreeNode.m_Index = reader.readInt32()
            typeTreeNode.m_MetaFlag = reader.readInt32()
            if (header.m_Version >= SerializedFileFormatVersion.TypeTreeNodeWithTypeFlags) {
                typeTreeNode.m_RefTypeHash = reader.readUInt64()
            }
        }
        m_Type.m_StringBuffer = reader.readBytes(stringBufferSize)

        val stringBufferReader = EndianBinaryReader(
            m_Type.m_StringBuffer ?: ByteArray(0),
            EndianType.LittleEndian
        )
        for (i in 0 until numberOfNodes) {
            val m_Node = m_Type.m_Nodes[i]
            m_Node.m_Type = readCommonString(stringBufferReader, m_Node.m_TypeStrOffset)
            m_Node.m_Name = readCommonString(stringBufferReader, m_Node.m_NameStrOffset)
        }
    }

    /**
     * 对应 C# TypeTreeBlobRead 内的局部函数 ReadString：
     * 最高位为 0 时 value 是 stringBuffer 内偏移；否则低 31 位是 CommonString 表下标。
     */
    private fun readCommonString(stringBufferReader: EndianBinaryReader, value: Long): String {
        val isOffset = (value and 0x80000000L) == 0L
        if (isOffset) {
            val pos = value.toInt()
            if (pos < 0 || pos > stringBufferReader.length) {
                return ""
            }
            stringBufferReader.position = pos
            return stringBufferReader.readStringToNull()
        }
        val offset = value and 0x7FFFFFFFL
        return CommonString.stringBuffer[offset] ?: offset.toString()
    }

    /** 对应 C# AddObject */
    fun addObject(obj: UnityObject) {
        objects.add(obj)
        objectsDic[obj.m_PathID] = obj
    }

    /** 从原始字节中截取指定对象的原始数据（供导出/重打包使用） */
    fun getRawObjectBytes(info: ObjectInfo): ByteArray {
        val start = info.byteStart.toInt()
        if (start < 0 || start >= fileBytes.size) {
            return ByteArray(0)
        }
        val end = (start + info.byteSize.toInt()).coerceAtMost(fileBytes.size)
        return if (end <= start) ByteArray(0) else fileBytes.copyOfRange(start, end)
    }

    companion object {
        /** C# private const string strippedVersion = "0.0.0" */
        private const val STRIPPED_VERSION = "0.0.0"
    }
}
