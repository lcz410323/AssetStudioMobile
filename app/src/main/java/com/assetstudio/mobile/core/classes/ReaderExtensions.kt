package com.assetstudio.mobile.core.classes

/*
 * 数学结构读取扩展：对应 C# 中带 reader 构造函数的 AABB / xform
 * （C# 里分别定义于 AnimationClip.cs / Mesh.cs 引用的公共类型）
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.AABB
import com.assetstudio.mobile.core.math.Vector3
import com.assetstudio.mobile.core.math.XForm

/** 对应 C# `new AABB(reader)` */
fun ObjectReader.readAABB(): AABB = AABB(readVector3(), readVector3())

/**
 * 对应 C# `version > 5.4 ? ReadVector3() : (Vector3)ReadVector4()`
 * 5.4 及以上为 Vector3；更早版本序列化为 Vector4，丢弃 w 分量
 */
fun ObjectReader.readVector3Compat(): Vector3 {
    return if (version[0] > 5 || (version[0] == 5 && version[1] >= 4)) { //5.4 and up
        readVector3()
    } else {
        val v = readVector4()
        Vector3(v.x, v.y, v.z)
    }
}

/** 对应 C# `new xform(reader)` */
fun ObjectReader.readXForm(): XForm = XForm(
    t = readVector3Compat(),
    q = readQuaternion(),
    s = readVector3Compat()
)
