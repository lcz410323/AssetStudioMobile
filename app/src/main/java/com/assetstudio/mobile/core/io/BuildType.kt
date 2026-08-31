package com.assetstudio.mobile.core.io

class BuildType(private val buildType: String) {
    val isAlpha: Boolean get() = buildType == "a"
    val isPatch: Boolean get() = buildType == "p"
    override fun toString(): String = buildType
}
