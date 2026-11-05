package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.nuvio.tv.core.subsync.LiveSpeechTap
import java.nio.ByteBuffer

/**
 * RCTV: lets the subtitle sync hear the decoded sound as the player sends it ([LiveSpeechTap]). Each
 * buffer is read once, before the sink takes it, and passed on untouched: the sound, its channels,
 * its passthrough and its timing don't change. Sound sent undecoded (passthrough) isn't heard.
 */
internal class SpeechTapAudioSink(
    sink: AudioSink,
    private val tap: LiveSpeechTap
) : ForwardingAudioSink(sink) {

    /** Added to the movie's time in the buffers' times (see [AudioSink.setOutputStreamOffsetUs]). */
    @Volatile
    private var streamOffsetUs = 0L

    /** The last buffer heard (the player hands a buffer again while the sink is full). */
    private var lastHeardUs = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val encoding = inputFormat.pcmEncoding
        val decoded = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW &&
            (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)
        runCatching {
            tap.configure(
                pcm = decoded,
                sampleRate = inputFormat.sampleRate,
                channels = inputFormat.channelCount,
                isFloat = encoding == C.ENCODING_PCM_FLOAT
            )
        }
        lastHeardUs = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (tap.listening && presentationTimeUs != lastHeardUs) {
            lastHeardUs = presentationTimeUs
            runCatching { tap.hear(buffer, presentationTimeUs - streamOffsetUs) }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        lastHeardUs = Long.MIN_VALUE
        runCatching { tap.flush() }
        super.flush()
    }

    override fun reset() {
        lastHeardUs = Long.MIN_VALUE
        runCatching { tap.flush() }
        super.reset()
    }
}
