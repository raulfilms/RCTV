package com.nuvio.tv.core.subsync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

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
    private const val HIGH_PASS_HZ = 250.0
    private const val LOW_PASS_HZ = 3_500.0

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

    /** The first audio track a decoder here can play (most files have the main one first). */
    private fun pickTrack(extractor: MediaExtractor): Track? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            val decoder = runCatching { codecs.findDecoderForFormat(format) }.getOrNull()
                ?: codecs.codecInfos.firstOrNull { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }?.name
                ?: continue
            return Track(index, format, decoder)
        }
        return null
    }

    private fun MediaFormat.intOr(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private class PcmLayout(var sampleRate: Int, var channels: Int, var isFloat: Boolean)

    /** The voice band's loudness per 10 ms of one stretch. */
    private class LoudnessMeter(val startMs: Long, endMs: Long) {
        val frames = ((endMs - startMs) / SubtitleAligner.FRAME_MS).toInt().coerceAtLeast(1)
        val energy = DoubleArray(frames)
        val counts = IntArray(frames)
        private var highPassOut = 0.0
        private var highPassIn = 0.0
        private var lowPassOut = 0.0

        fun add(buffer: ByteBuffer, offset: Int, size: Int, timeUs: Long, pcm: PcmLayout) {
            val data = buffer.duplicate().order(ByteOrder.nativeOrder())
            data.position(offset)
            data.limit(offset + size)
            val channels = pcm.channels.coerceAtLeast(1)
            val bytesPerSample = if (pcm.isFloat) 4 else 2
            val sampleCount = size / (bytesPerSample * channels)
            val rate = pcm.sampleRate.coerceAtLeast(8_000).toDouble()
            // Dialogue is in the center channel (3rd, as decoders lay out 5.1 and 7.1); else all mixed.
            val center = if (channels >= 3) 2 else -1
            val dt = 1.0 / rate
            val highPass = 1.0 / (2 * Math.PI * HIGH_PASS_HZ)
            val lowPass = 1.0 / (2 * Math.PI * LOW_PASS_HZ)
            val a = highPass / (highPass + dt)
            val b = dt / (lowPass + dt)
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
                // Voice band: a high-pass, then a low-pass.
                val high = a * (highPassOut + value - highPassIn)
                highPassIn = value
                highPassOut = high
                lowPassOut += b * (high - lowPassOut)
                val sampleUs = timeUs + (s * 1_000_000L / rate).toLong()
                val frame = ((sampleUs - startUs) / 1_000L / SubtitleAligner.FRAME_MS).toInt()
                if (frame in 0 until frames) {
                    energy[frame] += lowPassOut * lowPassOut
                    counts[frame]++
                }
            }
        }
    }

    /**
     * Each 10 ms's loudness as how likely it is that someone talks (0..1), against the loudness of all
     * the stretches together (so a quiet stretch with no dialogue counts as quiet).
     */
    private fun speechOf(meters: List<LoudnessMeter>): List<SpeechWindow>? {
        val levels = meters.map { meter ->
            DoubleArray(meter.frames) { i ->
                if (meter.counts[i] > 0) 10 * log10(meter.energy[i] / meter.counts[i] + 1e-12) else Double.NaN
            }
        }
        val all = levels.flatMap { level -> level.filter { !it.isNaN() } }.sorted()
        if (all.size < 1_000) return null
        val floor = all[(all.size * 0.20).toInt()]
        val peak = all[(all.size * 0.95).toInt().coerceAtMost(all.size - 1)]
        if (peak - floor < 6.0) return null
        return meters.mapIndexed { n, meter ->
            val level = levels[n]
            val raw = FloatArray(meter.frames) { i ->
                val value = level[i]
                if (value.isNaN()) 0f else ((value - floor) / (peak - floor)).coerceIn(0.0, 1.0).toFloat()
            }
            // A little smoothing (30 ms): single loud frames aren't speech.
            val smooth = FloatArray(raw.size) { i ->
                val from = (i - 1).coerceAtLeast(0)
                val to = (i + 1).coerceAtMost(raw.size - 1)
                var sum = 0f
                for (k in from..to) sum += raw[k]
                sum / (to - from + 1)
            }
            SpeechWindow(meter.startMs, smooth)
        }
    }
}
