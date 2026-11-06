package dev.alder

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.view.Display

/** What this TV can decode and show, checked once per launch (the codec list takes a few ms). */
class Device internal constructor(val hevc: Boolean, val av1: Boolean, val dolbyVision: Boolean, val hdr: Boolean, val height: Int) {
    companion object {
        @Volatile private var known: Device? = null

        fun of(context: Context): Device = known ?: run {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            val types = decoders.flatMap { it.supportedTypes.asList() }.map { it.lowercase() }.toSet()
            // Dolby Vision means profile 5 at 4K (web releases with no HDR10 base). Having a Dolby Vision decoder isn't
            // enough: the test TV has four and none takes dvhe.05.06, so those files never showed a picture.
            val dv5 = decoders.filter { "video/dolby-vision" in it.supportedTypes }.any { c ->
                c.getCapabilitiesForType("video/dolby-vision").profileLevels.any {
                    it.profile == CodecProfileLevel.DolbyVisionProfileDvheStn && it.level >= CodecProfileLevel.DolbyVisionLevelUhd24
                }
            }
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            val hdr = display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
            // The biggest mode the TV offers, not the one in use: the launcher often runs at 1080p on a 4K panel.
            val height = display.supportedModes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
            Device("video/hevc" in types, "video/av01" in types, dv5, hdr, height)
                .also { known = it }
        }
    }
}
