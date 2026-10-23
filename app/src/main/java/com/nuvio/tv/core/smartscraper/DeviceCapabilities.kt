package com.nuvio.tv.core.smartscraper

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the TV on the HDMI can show and this box can decode: the TV's biggest picture (what it
 * reports over HDMI, and the mode it's in now), which HDR it shows (Dolby Vision, HDR10, HDR10+,
 * HLG), and whether AV1/HEVC are decoded in hardware. The TV is read again every time (the box
 * may be on another TV now); the decoders once.
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private class Decoders(val av1: Boolean, val hevc: Boolean, val dolbyVision: Boolean)

    @Volatile private var decoders: Decoders? = null
    @Volatile private var lastLogged: DeviceProfile? = null

    fun profile(): DeviceProfile = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DeviceProfile.UNKNOWN
        val codecs = decoders ?: readDecoders().also { decoders = it }
        // The TV's modes over HDMI (the biggest it takes) and the one it's in now.
        val modeHeights = display.supportedModes.map { minOf(it.physicalWidth, it.physicalHeight) } +
            listOfNotNull(display.mode?.let { minOf(it.physicalWidth, it.physicalHeight) })
        val maxHeight = modeHeights.maxOrNull() ?: 0
        val hdrTypes = hdrTypesOf(display)
        DeviceProfile(
            maxResolution = if (maxHeight > 0) VideoResolution.ofHeight(maxHeight) else VideoResolution.UHD_2160,
            supportsHdr10 = HDR_TYPE_HDR10 in hdrTypes || HDR_TYPE_HDR10_PLUS in hdrTypes,
            supportsHdr10Plus = HDR_TYPE_HDR10_PLUS in hdrTypes,
            supportsDolbyVision = HDR_TYPE_DOLBY_VISION in hdrTypes && codecs.dolbyVision,
            supportsHlg = HDR_TYPE_HLG in hdrTypes,
            hasHardwareAv1 = codecs.av1,
            hasHardwareHevc = codecs.hevc,
            isKnown = true
        ).also { profile ->
            if (profile != lastLogged) {
                lastLogged = profile
                Log.i(TAG, "TV: up to ${profile.maxResolution.label}, HDR10=${profile.supportsHdr10} " +
                    "HDR10+=${profile.supportsHdr10Plus} DV=${profile.supportsDolbyVision} HLG=${profile.supportsHlg} " +
                    "AV1=${profile.hasHardwareAv1} HEVC=${profile.hasHardwareHevc}")
            }
        }
    }.getOrElse {
        Log.w(TAG, "Couldn't read the TV's capabilities: ${it.message}")
        DeviceProfile.UNKNOWN
    }

    /** The HDR types the TV takes (from its mode on newer Android, else from the display). */
    private fun hdrTypesOf(display: Display): Set<Int> {
        val types = HashSet<Int>()
        if (Build.VERSION.SDK_INT >= 34) {
            display.mode?.supportedHdrTypes?.let { modeTypes -> types += modeTypes.toList() }
            display.supportedModes.forEach { mode -> types += mode.supportedHdrTypes.toList() }
        }
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes?.let { types += it.toList() }
        return types
    }

    private fun readDecoders(): Decoders {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun has(mime: String, hardwareOnly: Boolean): Boolean = infos.any { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && (!hardwareOnly || isHardware(info))
        }
        return Decoders(
            av1 = has("video/av01", hardwareOnly = true),
            hevc = has("video/hevc", hardwareOnly = true),
            dolbyVision = has("video/dolby-vision", hardwareOnly = false)
        )
    }

    private fun isHardware(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
        }

    private companion object {
        const val TAG = "SmartScraper"
        // Display.HdrCapabilities' types (as numbers: some are newer than the oldest Android the app runs on).
        const val HDR_TYPE_DOLBY_VISION = 1
        const val HDR_TYPE_HDR10 = 2
        const val HDR_TYPE_HLG = 3
        const val HDR_TYPE_HDR10_PLUS = 4
    }
}
