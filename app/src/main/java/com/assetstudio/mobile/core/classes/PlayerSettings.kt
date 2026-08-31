package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/PlayerSettings.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class PlayerSettings(reader: ObjectReader) : UnityObject(reader) {
    var companyName: String = ""
    var productName: String = ""

    init {
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 4)) { //5.4.0 and up
            val productGUID = reader.readBytes(16)
        }

        val androidProfiler = reader.readBoolean()
        //bool AndroidFilterTouchesWhenObscured 2017.2 and up
        //bool AndroidEnableSustainedPerformanceMode 2018 and up
        reader.alignStream()
        val defaultScreenOrientation = reader.readInt32()
        val targetDevice = reader.readInt32()
        if (version[0] < 5 || (version[0] == 5 && version[1] < 3)) { //5.3 down
            if (version[0] < 5) { //5.0 down
                val targetPlatform = reader.readInt32() //4.0 and up targetGlesGraphics
                if (version[0] > 4 || (version[0] == 4 && version[1] >= 6)) { //4.6 and up
                    val targetIOSGraphics = reader.readInt32()
                }
            }
            val targetResolution = reader.readInt32()
        } else {
            val useOnDemandResources = reader.readBoolean()
            reader.alignStream()
        }
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) { //3.5 and up
            val accelerometerFrequency = reader.readInt32()
        }
        companyName = reader.readAlignedString()
        productName = reader.readAlignedString()
    }
}
