package com.assetstudio.mobile.core.bundle

/**
 * 来自 AssetStudio/StreamFile.cs。
 * C# 中持有 Stream，Android 内存模型改为持有 ByteArray；
 * data 为 var 以支持替换资源后的重打包。
 */
class StreamFile(
    var path: String,
    var fileName: String,
    var data: ByteArray = ByteArray(0)
)
