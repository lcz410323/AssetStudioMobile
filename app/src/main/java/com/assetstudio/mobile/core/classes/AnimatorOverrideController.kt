package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/AnimatorOverrideController.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class AnimationClipOverride(reader: ObjectReader) {
    val m_OriginalClip: PPtr = PPtr(reader)
    val m_OverrideClip: PPtr = PPtr(reader)
}

class AnimatorOverrideController(reader: ObjectReader) : RuntimeAnimatorController(reader) {
    val m_Controller: PPtr = PPtr(reader)
    val m_Clips: Array<AnimationClipOverride>

    init {
        m_Clips = Array(reader.readArrayCount()) { AnimationClipOverride(reader) }
    }
}
