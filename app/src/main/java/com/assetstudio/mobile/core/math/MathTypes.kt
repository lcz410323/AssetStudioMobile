package com.assetstudio.mobile.core.math

data class Vector2(var x: Float = 0f, var y: Float = 0f)

data class Vector3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f)

data class Vector4(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 0f)

data class Quaternion(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 0f)

data class Color(var r: Float = 0f, var g: Float = 0f, var b: Float = 0f, var a: Float = 0f) {
    companion object {
        fun fromRGBA32(r: Int, g: Int, b: Int, a: Int) =
            Color(r / 255f, g / 255f, b / 255f, a / 255f)
    }
}

class Matrix4x4(val m: FloatArray) {
    constructor(
        m00: Float, m10: Float, m20: Float, m30: Float,
        m01: Float, m11: Float, m21: Float, m31: Float,
        m02: Float, m12: Float, m22: Float, m32: Float,
        m03: Float, m13: Float, m23: Float, m33: Float
    ) : this(floatArrayOf(m00, m10, m20, m30, m01, m11, m21, m31, m02, m12, m22, m32, m03, m13, m23, m33))

    operator fun get(row: Int, col: Int): Float = m[col * 4 + row]

    override fun toString(): String {
        val sb = StringBuilder()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                sb.append(this[row, col]).append(", ")
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}

data class AABB(
    var m_Center: Vector3 = Vector3(),
    var m_Extent: Vector3 = Vector3()
)

data class XForm(
    var t: Vector3 = Vector3(),
    var q: Quaternion = Quaternion(),
    var s: Vector3 = Vector3()
)

data class Rectanglef(
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f
)

/**
 * IEEE 754 半精度浮点数 <-> float 转换。
 */
object Half {
    fun toFloat(halfData: Int): Float {
        val h = halfData and 0xFFFF
        val sign = if (h and 0x8000 != 0) -1 else 1
        val exponent = (h shr 10) and 0x1F
        val mantissa = h and 0x3FF

        val value = when (exponent) {
            0 -> (mantissa / 1024f) * 2f.pow2(-14) * sign
            31 -> if (mantissa != 0) Float.NaN else sign * Float.POSITIVE_INFINITY
            else -> {
                val exp = exponent - 15
                val m = 1f + mantissa / 1024f
                sign * m * 2f.pow2(exp)
            }
        }
        return value
    }

    private fun Float.pow2(e: Int): Float = Math.pow(2.0, e.toDouble()).toFloat()
}
