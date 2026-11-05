package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine

/**
 * RCTV Smart Player: which engine plays what (docs: "RCTV Smart Player: auditoría y plan").
 *
 * 1. YouTube: YouTube's official IFrame player, always ([com.nuvio.tv.ui.components.YouTubeIframePlayer]).
 * 2. Everything else: ExoPlayer, the only engine here that sends Dolby Digital, Dolby Digital Plus
 *    and DTS to the receiver as they are (passthrough).
 * 3. libmpv only when ExoPlayer can't: a startup error that says the format can't be played
 *    (container or decoder) switches once to libmpv, at the same point with the same tracks
 *    ([com.nuvio.tv.ui.screens.player.maybeAutoSwitchInternalPlayerOnStartupError]). A network or
 *    server error never does: another engine would meet the same network.
 *
 * Plain Kotlin, tested on the JVM.
 */
object SmartPlayerPolicy {

    /**
     * The engine "Auto" starts with: ExoPlayer for everything. Before, Auto sent anime to libmpv
     * (for its subtitles), which loses passthrough; ExoPlayer draws ASS subtitles with libass too,
     * and a format it can't play still goes to libmpv (3 above).
     */
    fun autoStartEngine(): InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER

    /**
     * Media3's error codes (androidx.media3.common.PlaybackException) that mean the format can't
     * be played here: the container (3001–3004) or the decoder (4001–4005). Network (2xxx), DRM
     * (6xxx) and the rest are not.
     */
    fun isFormatError(errorCode: Int?): Boolean =
        errorCode != null && (errorCode in PARSING_CONTAINER_MALFORMED..PARSING_MANIFEST_UNSUPPORTED ||
            errorCode in DECODER_INIT_FAILED..DECODING_FORMAT_UNSUPPORTED)

    /**
     * Whether a startup error switches engines: a format error always (3 above); any other error
     * only when the owner turned on "switch engine on error" in Playback settings, as before.
     */
    fun shouldSwitchEngine(switchOnAnyErrorSetting: Boolean, errorCode: Int?): Boolean =
        switchOnAnyErrorSetting || isFormatError(errorCode)

    // androidx.media3.common.PlaybackException (Media3 1.8.0), copied so this stays plain Kotlin.
    const val PARSING_CONTAINER_MALFORMED = 3001
    const val PARSING_MANIFEST_UNSUPPORTED = 3004
    const val DECODER_INIT_FAILED = 4001
    const val DECODING_FORMAT_UNSUPPORTED = 4005
}
