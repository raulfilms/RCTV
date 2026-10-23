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
 * What this TV can show and decode: its biggest picture, which HDR it shows (Dolby Vision, HDR10,
 * HDR10+, HLG), and whether it decodes AV1/HEVC in hardware. Read once.
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var cached: DeviceProfile? = null

    fun profile(): DeviceProfile = cached ?: detect().also { cached = it }

    private fun detect(): DeviceProfile = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val maxHeight = display?.supportedModes
            ?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) }
            ?: 0
        @Suppress("DEPRECATION")
        val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun hasDecoder(mime: String, hardwareOnly: Boolean): Boolean = decoders.any { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (!hardwareOnly || isHardware(info))
        }
        val dolbyVisionDecoder = hasDecoder("video/dolby-vision", hardwareOnly = false)
        DeviceProfile(
            maxResolution = if (maxHeight > 0) VideoResolution.ofHeight(maxHeight) else VideoResolution.UHD_2160,
            supportsHdr10 = HDR_TYPE_HDR10 in hdrTypes || HDR_TYPE_HDR10_PLUS in hdrTypes,
            supportsHdr10Plus = HDR_TYPE_HDR10_PLUS in hdrTypes,
            supportsDolbyVision = HDR_TYPE_DOLBY_VISION in hdrTypes && dolbyVisionDecoder,
            supportsHlg = HDR_TYPE_HLG in hdrTypes,
            hasHardwareAv1 = hasDecoder("video/av01", hardwareOnly = true),
            hasHardwareHevc = hasDecoder("video/hevc", hardwareOnly = true),
            isKnown = display != null
        ).also { Log.i(TAG, "Device: $it") }
    }.getOrElse {
        Log.w(TAG, "Couldn't read the device's capabilities: ${it.message}")
        DeviceProfile.UNKNOWN
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
