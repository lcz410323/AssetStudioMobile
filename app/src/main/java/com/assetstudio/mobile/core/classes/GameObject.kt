package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/GameObject.cs
 * m_Transform / m_MeshRenderer 等引用在 processAssets 阶段由外部填充。
 */

import com.assetstudio.mobile.core.ObjectReader

class GameObject(reader: ObjectReader) : EditorExtension(reader) {

    val m_Components: Array<PPtr>
    val m_Layer: Int

    // ===== 以下引用由资源处理阶段（processAssets）填充 =====
    var m_Transform: Transform? = null
    var m_MeshRenderer: MeshRenderer? = null
    var m_MeshFilter: MeshFilter? = null
    var m_SkinnedMeshRenderer: SkinnedMeshRenderer? = null
    var m_Animator: Animator? = null
    var m_Animation: Animation? = null

    init {
        val m_Component_size = reader.readArrayCount()
        m_Components = Array(m_Component_size) {
            if ((version[0] == 5 && version[1] < 5) || version[0] < 5) { //5.5 down
                val first = reader.readInt32()
            }
            PPtr(reader)
        }

        m_Layer = reader.readInt32()
        m_Name = reader.readAlignedString()
    }

    override val displayName: String get() = m_Name ?: type.name
}
