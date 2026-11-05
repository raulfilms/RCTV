package com.nuvio.tv.core.player

import androidx.media3.common.PlaybackException
import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlayerPolicyTest {

    @Test
    fun `auto starts every title with ExoPlayer`() {
        assertEquals(InternalPlayerEngine.EXOPLAYER, SmartPlayerPolicy.autoStartEngine())
    }

    @Test
    fun `the copied error codes are Media3's`() {
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, SmartPlayerPolicy.PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, SmartPlayerPolicy.PARSING_MANIFEST_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, SmartPlayerPolicy.DECODER_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, SmartPlayerPolicy.DECODING_FORMAT_UNSUPPORTED)
    }

    @Test
    fun `a format ExoPlayer can't play switches to libmpv`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ).forEach { code -> assertTrue("$code", SmartPlayerPolicy.shouldSwitchEngine(false, code)) }
    }

    @Test
    fun `a network or server error doesn't switch unless the setting says so`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            null
        ).forEach { code ->
            assertFalse("$code", SmartPlayerPolicy.shouldSwitchEngine(false, code))
            assertTrue("$code", SmartPlayerPolicy.shouldSwitchEngine(true, code))
        }
    }
}
