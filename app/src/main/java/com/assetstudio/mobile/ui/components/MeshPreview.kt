package com.assetstudio.mobile.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.assetstudio.mobile.core.classes.Mesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Mesh 实体预览：纯 Kotlin 软件光栅化（无 OpenGL 依赖）。
 *
 * 提取阶段（Default 线程，一次性）：
 *   三角形采样（上限 4 万个，超出降采样）→ 引用顶点紧凑化 → 归一化到 [-0.9, 0.9]³
 *
 * 渲染阶段（每帧，Default 线程）：
 *   yaw/pitch 旋转 → 正交投影 → 逐三角形边函数光栅化：
 *   - z-buffer 逐像素消隐（正确处理任意凹凸几何）
 *   - 面法线自动朝向相机（兼容任意环绕方向，开放网格也能正确着色）
 *   - Lambert 定向光 + 环境光（0.22 ~ 1.0）
 *   - 双 Bitmap 交替写入，避免渲染线程写像素与 UI 绘制读像素的撕裂
 *
 * 交互：拖拽旋转（拖拽后停止自转），空闲时不重渲染省电。
 */

/** 预览几何数据（已归一化、已紧凑化） */
class MeshGeometry(
    /** 顶点坐标（x,y,z 交错，已居中归一化） */
    val positions: FloatArray,
    /** 三角形顶点索引（紧凑化后，3 个一组） */
    val triangles: IntArray,
    /** 原始顶点总数 */
    val vertexCount: Int,
    /** 原始三角形总数 */
    val triangleCount: Int,
    /** 降采样比例（1.0 = 全量） */
    val sampleRatio: Float
)

/** 参与渲染的三角形上限（超出自动降采样，保证手机上流畅） */
private const val MAX_TRIANGLES = 40_000

/** 离屏渲染分辨率（性能与清晰度的平衡点，绘制时双线性放大） */
private const val RENDER_SIZE = 512

/** 从 Mesh 提取实体渲染几何；无顶点/索引数据时返回 null */
fun extractMeshGeometry(mesh: Mesh): MeshGeometry? {
    val vertices = mesh.m_Vertices ?: return null
    val indexList = mesh.m_Indices
    if (vertices.size < 9 || indexList.size < 3) return null
    val maxVertex = vertices.size / 3
    val totalTris = indexList.size / 3
    val stride = max(1, ceil(totalTris / MAX_TRIANGLES.toDouble()).toInt())

    // 采样有效三角形（索引越界 / 退化 / 非有限坐标全部跳过）
    val cap = minOf(totalTris, MAX_TRIANGLES)
    val raw = IntArray(cap * 3)
    var count = 0
    var t = 0
    while (t < totalTris && count < cap) {
        val base = t * 3
        val a = indexList[base].toInt()
        val b = indexList[base + 1].toInt()
        val c = indexList[base + 2].toInt()
        if (a in 0 until maxVertex && b in 0 until maxVertex && c in 0 until maxVertex &&
            a != b && b != c && a != c && triangleFinite(vertices, a, b, c)
        ) {
            raw[count * 3] = a
            raw[count * 3 + 1] = b
            raw[count * 3 + 2] = c
            count++
        }
        t += stride
    }
    if (count == 0) return null

    // 引用顶点紧凑化
    val used = sortedSetOf<Int>()
    for (i in 0 until count * 3) used.add(raw[i])
    val remap = HashMap<Int, Int>(used.size * 2)
    var compact = 0
    for (v in used) remap[v] = compact++

    // 包围盒 → 居中归一化
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (v in used) {
        val x = vertices[v * 3]; val y = vertices[v * 3 + 1]; val z = vertices[v * 3 + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    val cz = (minZ + maxZ) / 2f
    val extent = max(max(maxX - minX, maxY - minY), maxZ - minZ)
    val scale = if (extent > 1e-6f) 1.8f / extent else 1f

    val positions = FloatArray(compact * 3)
    for (v in used) {
        val d = remap[v]!! * 3
        positions[d] = (vertices[v * 3] - cx) * scale
        positions[d + 1] = (vertices[v * 3 + 1] - cy) * scale
        positions[d + 2] = (vertices[v * 3 + 2] - cz) * scale
    }

    val triangles = IntArray(count * 3)
    for (i in 0 until count * 3) triangles[i] = remap[raw[i]]!!

    return MeshGeometry(
        positions = positions,
        triangles = triangles,
        vertexCount = mesh.m_VertexCount,
        triangleCount = totalTris,
        sampleRatio = 1f / stride
    )
}

/** 三个顶点共 9 个分量均为有限值 */
private fun triangleFinite(v: FloatArray, a: Int, b: Int, c: Int): Boolean {
    for (base in intArrayOf(a * 3, b * 3, c * 3)) {
        for (j in 0 until 3) {
            val f = v[base + j]
            if (f.isNaN() || f.isInfinite()) return false
        }
    }
    return true
}

// ============================ 软件光栅化器 ============================

/**
 * 实体渲染器：z-buffer + Lambert 光照。
 * 一个 MeshGeometry 对应一个实例（内部缓存旋转缓冲），可反复 render()。
 */
private class SolidRenderer(private val geometry: MeshGeometry) {

    private val size = RENDER_SIZE
    private val pixels = IntArray(size * size)
    private val zbuf = FloatArray(size * size)

    /** 双缓冲：交替写入，渲染线程写与 UI 线程读互不干扰 */
    private val buffers = arrayOf(
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    )
    private var flip = 0

    private val n = geometry.positions.size / 3
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val vz = FloatArray(n)

    /** 定向光方向（相机侧右上方，归一化） */
    private val lx: Float
    private val ly: Float
    private val lz: Float

    init {
        val len = sqrt(0.35f * 0.35f + 0.5f * 0.5f + 0.8f * 0.8f)
        lx = 0.35f / len; ly = 0.5f / len; lz = -0.8f / len
    }

    /**
     * 渲染一帧。
     * @param yaw 绕 Y 轴旋转角
     * @param pitch 绕 X 轴旋转角
     * @param base 基础颜色（受光照调制）
     * @return 已写好像素的 Bitmap（双缓冲交替）
     */
    fun render(yaw: Float, pitch: Float, base: Color): Bitmap {
        java.util.Arrays.fill(pixels, 0)
        java.util.Arrays.fill(zbuf, Float.MAX_VALUE)
        val out = buffers[flip]
        if (n == 0 || geometry.triangles.isEmpty()) {
            out.setPixels(pixels, 0, size, 0, 0, size, size)
            flip = flip xor 1
            return out
        }

        // ---------- 1. 旋转全部顶点 ----------
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosP = cos(pitch); val sinP = sin(pitch)
        val pos = geometry.positions
        for (i in 0 until n) {
            val x = pos[i * 3]
            val y = pos[i * 3 + 1]
            val z = pos[i * 3 + 2]
            val x1 = x * cosY + z * sinY
            val z1 = -x * sinY + z * cosY
            val y2 = y * cosP - z1 * sinP
            val z2 = y * sinP + z1 * cosP
            vx[i] = x1; vy[i] = y2; vz[i] = z2
        }

        val half = size * 0.44f
        val ccx = size * 0.5f
        val ccy = size * 0.5f
        val baseR = base.red
        val baseG = base.green
        val baseB = base.blue
        val sizeMax = size - 1

        val tris = geometry.triangles
        val tCount = tris.size / 3
        for (t in 0 until tCount) {
            val i0 = tris[t * 3]
            val i1 = tris[t * 3 + 1]
            val i2 = tris[t * 3 + 2]

            // ---------- 2. 面法线（法线自动朝向相机，兼容任意环绕方向） ----------
            val ax = vx[i0]; val ay = vy[i0]; val az = vz[i0]
            val bx = vx[i1]; val by = vy[i1]; val bz = vz[i1]
            val cxx = vx[i2]; val cyy = vy[i2]; val czz = vz[i2]
            var nx = (by - ay) * (czz - az) - (bz - az) * (cyy - ay)
            var ny = (bz - az) * (cxx - ax) - (bx - ax) * (czz - az)
            var nz = (bx - ax) * (cyy - ay) - (by - ay) * (cxx - ax)
            if (nz > 0f) { nx = -nx; ny = -ny; nz = -nz } // 相机在 -z，法线应背向 +z
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-9f) continue

            // ---------- 3. Lambert 光照 ----------
            val ndl = (nx * lx + ny * ly + nz * lz) / nl
            val lit = if (ndl > 0f) ndl else 0f
            val intensity = 0.22f + 0.78f * lit
            val r = (baseR * intensity * 255f).toInt().coerceIn(0, 255)
            val g = (baseG * intensity * 255f).toInt().coerceIn(0, 255)
            val b = (baseB * intensity * 255f).toInt().coerceIn(0, 255)
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

            // ---------- 4. 正交投影到屏幕 ----------
            val x0 = ccx + ax * half; val y0 = ccy - ay * half; val z0 = az
            val x1s = ccx + bx * half; val y1s = ccy - by * half; val z1 = bz
            val x2s = ccx + cxx * half; val y2s = ccy - cyy * half; val z2 = czz

            val area = (x1s - x0) * (y2s - y0) - (y1s - y0) * (x2s - x0)
            if (area > -1e-6f && area < 1e-6f) continue // 退化三角形
            val invArea = 1f / area

            // ---------- 5. 边函数光栅化（包围盒 + 增量步进） ----------
            var minx = min(x0, min(x1s, x2s)); var maxx = max(x0, max(x1s, x2s))
            var miny = min(y0, min(y1s, y2s)); var maxy = max(y0, max(y1s, y2s))
            if (maxx < 0f || maxy < 0f || minx > sizeMax.toFloat() || miny > sizeMax.toFloat()) continue
            val ix0 = max(0, floor(minx).toInt())
            val ix1 = min(sizeMax, ceil(maxx).toInt())
            val iy0 = max(0, floor(miny).toInt())
            val iy1 = min(sizeMax, ceil(maxy).toInt())
            if (ix0 > ix1 || iy0 > iy1) continue

            // 像素中心 (ix+0.5, iy+0.5) 处的三个边函数初值
            val px = ix0 + 0.5f
            val py = iy0 + 0.5f
            var w0r = (x2s - x1s) * (py - y1s) - (y2s - y1s) * (px - x1s)
            var w1r = (x0 - x2s) * (py - y2s) - (y0 - y2s) * (px - x2s)
            var w2r = (x1s - x0) * (py - y0) - (y1s - y0) * (px - x0)
            // x/y 步进增量
            val sx0 = -(y2s - y1s); val sx1 = -(y0 - y2s); val sx2 = -(y1s - y0)
            val sy0 = (x2s - x1s); val sy1 = (x0 - x2s); val sy2 = (x1s - x0)

            var w0 = w0r; var w1 = w1r; var w2 = w2r
            var yy = iy0
            while (yy <= iy1) {
                var wa = w0; var wb = w1; var wc = w2
                val rowBase = yy * size
                var xx = ix0
                while (xx <= ix1) {
                    val l0 = wa * invArea
                    if (l0 >= 0f) {
                        val l1 = wb * invArea
                        if (l1 >= 0f) {
                            val l2 = wc * invArea
                            if (l2 >= 0f) {
                                val z = l0 * z0 + l1 * z1 + l2 * z2
                                val idx = rowBase + xx
                                if (z < zbuf[idx]) {
                                    zbuf[idx] = z
                                    pixels[idx] = argb
                                }
                            }
                        }
                    }
                    wa += sx0; wb += sx1; wc += sx2
                    xx++
                }
                w0 += sy0; w1 += sy1; w2 += sy2
                yy++
            }
        }

        out.setPixels(pixels, 0, size, 0, 0, size, size)
        flip = flip xor 1
        return out
    }
}

// ============================ Compose 组件 ============================

@Composable
fun MeshPreview(mesh: Mesh) {
    // 几何提取（一次性，后台线程）
    val geometry by produceState<MeshGeometry?>(initialValue = null, mesh) {
        value = withContext(Dispatchers.Default) {
            try {
                extractMeshGeometry(mesh)
            } catch (e: Throwable) {
                null
            }
        }
    }

    var yaw by remember { mutableFloatStateOf(0.6f) }
    var pitch by remember { mutableFloatStateOf(-0.35f) }
    var autoRotate by remember { mutableStateOf(true) }

    // 自转相位（线性，2π 周期与 sin/cos 天然连续）
    val spin by rememberInfiniteTransition(label = "meshSpin").animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meshSpinAngle"
    )

    val baseColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.surfaceVariant

    // 渲染循环：每帧读取最新姿态，仅变化时才重渲染（空闲零开销）
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(geometry) {
        val g = geometry ?: return@LaunchedEffect
        val renderer = SolidRenderer(g)
        var lastYaw = Float.NaN
        var lastPitch = Float.NaN
        while (true) {
            withFrameNanos { }
            val a = yaw + if (autoRotate) spin else 0f
            if (a != lastYaw || pitch != lastPitch) {
                lastYaw = a
                lastPitch = pitch
                frame = withContext(Dispatchers.Default) {
                    renderer.render(a, pitch, baseColor).asImageBitmap()
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            val g = geometry
            if (g == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (mesh.m_Vertices == null || mesh.m_Indices.isEmpty())
                            "该网格无顶点/索引数据，无法预览"
                        else "正在提取网格…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val f = frame
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                autoRotate = false
                                yaw += drag.x / 300f
                                pitch = (pitch - drag.y / 300f).coerceIn(-1.5f, 1.5f)
                            }
                        }
                ) {
                    drawRect(bgColor)
                    if (f != null) {
                        drawImage(
                            image = f,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(f.width, f.height),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.Medium
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "拖动旋转 · ${g.vertexCount} 顶点 / ${g.triangleCount} 三角形",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (g.sampleRatio < 1f) {
                        Text(
                            "已降采样 ${(g.sampleRatio * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}
