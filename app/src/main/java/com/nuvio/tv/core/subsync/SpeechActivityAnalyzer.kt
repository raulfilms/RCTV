package com.nuvio.tv.core.subsync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RCTV: hears where people talk in stretches of a movie, from its own audio. Each stretch is read
 * from the stream (the way the player reads it, by ranges), decoded on the device, and the loudness of
 * the voice band (about 250 Hz to 3.5 kHz) taken every 10 ms, from the center channel when the audio
 * has one (that's where dialogue is). Independent of the player: it works with the player decoding the
 * audio or sending it to the TV as is, and with either player engine.
 */
internal object SpeechActivityAnalyzer {
    private const val TAG = "SubtitleAutoSync"
    private const val WAIT_US = 5_000L
    /** One stretch gives up after this long (a slow server): the others still count. */
    private const val WINDOW_TIMEOUT_MS = 60_000L

    /** Whether [url] can be read here (a file on a server, not a playlist, not this device's own torrent server). */
    fun canAnalyze(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = lower.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.startsWith("[")) return false
        val path = lower.substringBefore('?')
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".m3u")
    }

    /**
     * The speech heard in each of [windowsMs] (the movie's times, ms) of the stream at [url]; null when
     * its audio can't be read or decoded here. [cancelled] is checked as it goes.
     */
    fun analyze(
        url: String,
        headers: Map<String, String>,
        windowsMs: List<LongRange>,
        cancelled: () -> Boolean
    ): List<SpeechWindow>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(url, headers)
            if (cancelled()) return null
            val track = pickTrack(extractor) ?: run {
                Log.i(TAG, "No audio track this device can decode")
                return null
            }
            extractor.selectTrack(track.index)
            val decoder = MediaCodec.createByCodecName(track.decoder)
            codec = decoder
            decoder.configure(track.format, null, null, 0)
            decoder.start()

            val pcm = PcmLayout(
                sampleRate = track.format.intOr(MediaFormat.KEY_SAMPLE_RATE, 48_000),
                channels = track.format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2),
                isFloat = false
            )
            val meters = ArrayList<LoudnessMeter>()
            val info = MediaCodec.BufferInfo()
            windowsMs.forEachIndexed { n, window ->
                if (n > 0) decoder.flush()
                extractor.seekTo(window.first * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val meter = LoudnessMeter(window.first, window.last)
                val startedAt = SystemClock.elapsedRealtime()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (cancelled()) return null
                    if (SystemClock.elapsedRealtime() - startedAt > WINDOW_TIMEOUT_MS) {
                        Log.w(TAG, "A stretch took too long to read: skipped")
                        break
                    }
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(WAIT_US)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)
                            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                            val timeUs = extractor.sampleTime
                            if (size < 0 || timeUs < 0 || timeUs > window.last * 1_000L) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, WAIT_US)
                    when {
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                decoder.getOutputBuffer(outIndex)?.let { out ->
                                    meter.add(out, info.offset, info.size, info.presentationTimeUs, pcm)
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            pcm.sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, pcm.sampleRate)
                            pcm.channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, pcm.channels)
                            pcm.isFloat = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
                meters += meter
            }
            return speechOf(meters)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the movie's audio: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class Track(val index: Int, val format: MediaFormat, val decoder: String)

    /**
     * The first audio track a decoder here can play (most files have the main one first). RCTV: on
     * Android 9 and older (the Mi Box), only with Android's own software decoders (AAC, MP3, Opus...):
     * a TV maker's decoder (Dolby, DTS) opened beside the player's can crash and close the app.
     */
    private fun pickTrack(extractor: MediaExtractor): Track? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            val decoders = codecs.codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            val decoder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                decoders.firstOrNull { isAndroidsOwn(it.name) }?.name
            } else {
                runCatching { codecs.findDecoderForFormat(format) }.getOrNull() ?: decoders.firstOrNull()?.name
            } ?: continue
            return Track(index, format, decoder)
        }
        return null
    }

    /** One of Android's own software decoders. */
    private fun isAndroidsOwn(decoder: String): Boolean =
        decoder.startsWith("OMX.google.", ignoreCase = true) || decoder.startsWith("c2.android.", ignoreCase = true)

    private fun MediaFormat.intOr(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private class PcmLayout(var sampleRate: Int, var channels: Int, var isFloat: Boolean)

    /** The voice band's loudness per 10 ms of one stretch. */
    private class LoudnessMeter(val startMs: Long, endMs: Long) {
        val frames = ((endMs - startMs) / SubtitleAligner.FRAME_MS).toInt().coerceAtLeast(1)
        val energy = DoubleArray(frames)
        val counts = IntArray(frames)
        private val filter = VoiceBandFilter()

        fun add(buffer: ByteBuffer, offset: Int, size: Int, timeUs: Long, pcm: PcmLayout) {
            val data = buffer.duplicate().order(ByteOrder.nativeOrder())
            data.position(offset)
            data.limit(offset + size)
            val channels = pcm.channels.coerceAtLeast(1)
            val bytesPerSample = if (pcm.isFloat) 4 else 2
            val sampleCount = size / (bytesPerSample * channels)
            val rate = pcm.sampleRate.coerceAtLeast(8_000).toDouble()
            filter.setSampleRate(pcm.sampleRate)
            // Dialogue is in the center channel (3rd, as decoders lay out 5.1 and 7.1); else all mixed.
            val center = if (channels >= 3) 2 else -1
            val startUs = startMs * 1_000L
            for (s in 0 until sampleCount) {
                var value = 0.0
                if (center >= 0) {
                    for (c in 0 until channels) {
                        val sample = if (pcm.isFloat) data.float.toDouble() else data.short / 32768.0
                        if (c == center) value = sample
                    }
                } else {
                    for (c in 0 until channels) {
                        value += if (pcm.isFloat) data.float.toDouble() else data.short / 32768.0
                    }
                    value /= channels
                }
                val power = filter.power(value)
                val sampleUs = timeUs + (s * 1_000_000L / rate).toLong()
                val frame = ((sampleUs - startUs) / 1_000L / SubtitleAligner.FRAME_MS).toInt()
                if (frame in 0 until frames) {
                    energy[frame] += power
                    counts[frame]++
                }
            }
        }
    }

    /** The stretches' speech (see [SpeechLevels.normalize]). */
    private fun speechOf(meters: List<LoudnessMeter>): List<SpeechWindow>? =
        SpeechLevels.normalize(meters.map { it.startMs }, meters.map { SpeechLevels.levelsOf(it.energy, it.counts) })
}
