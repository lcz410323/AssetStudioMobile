package com.assetstudio.mobile.core.manager

import com.assetstudio.mobile.core.bundle.BundleFile
import com.assetstudio.mobile.core.bundle.WebFile

/*
 * 来源容器引用（贴图替换的回填路径）。
 *
 * AssetsManager 递归加载（zip → bundle → .assets）时记录每一层容器，
 * 替换对象数据后从最内层容器向外逐层回填并重打包，最终得到可保存的完整文件。
 */

/** 载入的 .assets 所在的 UnityFS 系 bundle（持有可变 fileList） */
class BundleSourceRef(val bundle: BundleFile, val entryPath: String)

/** 载入的 .assets 所在的 UnityWebData 容器 */
class WebSourceRef(val web: WebFile, val entryPath: String)

/** 载入的 .assets 所在的 zip 条目 */
class ZipSourceRef(val zipEntries: LinkedHashMap<String, ByteArray>, val entryName: String)

/** 统一容器引用 */
sealed class SourceRef {
    class Bundle(val ref: BundleSourceRef) : SourceRef()
    class Web(val ref: WebSourceRef) : SourceRef()
    class Zip(val ref: ZipSourceRef) : SourceRef()
}
