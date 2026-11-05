package com.nuvio.tv.core.subsync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RCTV: hears where people talk in the movie as it plays, from the sound the player itself decodes,
 * for the subtitle sync. It works with any source the player plays (a file on a server, HLS, this
 * device's torrent server), and costs nothing to download. It only reads the sound and never changes
 * it. It hears nothing when the sound goes out undecoded (passthrough to a receiver or the TV).
 *
 * Kept: the last [MAX_CHUNKS] stretches of [CHUNK_FRAMES] × 10 ms that played (about 15 minutes,
 * well under 1 MB).
 */
class LiveSpeechTap {

    /** Whether the player should pass its sound here (only while a subtitle is being synced). */
    @Volatile
    var listening: Boolean = false
        private set

    /** Whether decoded sound came since [start] (false: passthrough, or nothing has played yet). */
    @Volatile
    var hearsPcm: Boolean = false
        private set

    private val lock = Any()
    private var session = 0
    private val filter = VoiceBandFilter()
    private var pcm = false
    private var isFloat = false
    private var sampleRate = 48_000
    private var channels = 2
    private var expectedNextUs = NO_TIME

    private class Chunk {
        val energy = FloatArray(CHUNK_FRAMES)
        val counts = ShortArray(CHUNK_FRAMES)
    }

    /** By their place in the movie; the least recently heard one goes first when there are too many. */
    private val chunks = object : LinkedHashMap<Long, Chunk>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Chunk>?): Boolean = size > MAX_CHUNKS
    }

    /** Starts hearing afresh; the number to [stop] it with. */
    fun start(): Int = synchronized(lock) {
        session++
        chunks.clear()
        filter.reset()
        expectedNextUs = NO_TIME
        hearsPcm = false
        listening = true
        session
    }

    /** Stops hearing (when [session] is still the one started last). */
    fun stop(session: Int) = synchronized(lock) {
        if (session != this.session) return@synchronized
        listening = false
        chunks.clear()
    }

    /** The sound the player is about to send: decoded ([pcm], 16-bit or [isFloat]) or not. */
    fun configure(pcm: Boolean, sampleRate: Int, channels: Int, isFloat: Boolean) = synchronized(lock) {
        this.pcm = pcm && sampleRate > 0 && channels > 0
        this.isFloat = isFloat
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        this.channels = channels.coerceAtLeast(1)
        filter.setSampleRate(this.sampleRate)
        filter.reset()
        expectedNextUs = NO_TIME
    }

    /** A seek: the next sound is from somewhere else. */
    fun flush() = synchronized(lock) {
        filter.reset()
        expectedNextUs = NO_TIME
    }

    /**
     * The decoded sound in [data] (from its position to its limit; it isn't moved), its first sample
     * at [mediaTimeUs] of the movie.
     */
    fun hear(data: ByteBuffer, mediaTimeUs: Long) {
        if (!listening || mediaTimeUs < 0) return
        synchronized(lock) {
            if (!listening || !pcm) return
            val bytesPerSample = if (isFloat) 4 else 2
            val frameBytes = bytesPerSample * channels
            val start = data.position()
            val count = (data.limit() - start) / frameBytes
            if (count <= 0) return
            // A jump the player didn't announce: the filter starts over.
            if (expectedNextUs != NO_TIME && kotlin.math.abs(mediaTimeUs - expectedNextUs) > JUMP_US) filter.reset()
            val buffer = data.duplicate().order(ByteOrder.nativeOrder())
            // Dialogue is in the center channel (3rd, as decoders lay out 5.1 and 7.1); else all mixed.
            val center = if (channels >= 3) 2 else -1
            val usPerSample = 1_000_000.0 / sampleRate
            var chunkIndex = Long.MIN_VALUE
            var chunk: Chunk? = null
            for (s in 0 until count) {
                val at = start + s * frameBytes
                val value = if (center >= 0) {
                    sampleAt(buffer, at + center * bytesPerSample)
                } else {
                    var sum = 0.0
                    for (c in 0 until channels) sum += sampleAt(buffer, at + c * bytesPerSample)
                    sum / channels
                }
                val power = filter.power(value)
                val frame = (mediaTimeUs + (s * usPerSample).toLong()) / 1_000L / SubtitleAligner.FRAME_MS
                val index = frame / CHUNK_FRAMES
                if (index != chunkIndex || chunk == null) {
                    chunkIndex = index
                    chunk = chunks.getOrPut(index) { Chunk() }
                }
                val i = (frame - index * CHUNK_FRAMES).toInt()
                if (chunk.counts[i] < Short.MAX_VALUE) {
                    chunk.energy[i] += power.toFloat()
                    chunk.counts[i]++
                }
            }
            expectedNextUs = mediaTimeUs + (count * usPerSample).toLong()
            hearsPcm = true
        }
    }

    private fun sampleAt(buffer: ByteBuffer, index: Int): Double =
        if (isFloat) buffer.getFloat(index).toDouble() else buffer.getShort(index) / 32768.0

    /** How much of the movie from [fromMs] to [toMs] has been heard (ms). */
    fun heardMs(fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): Long = synchronized(lock) {
        var frames = 0L
        for ((index, chunk) in chunks) {
            for (i in 0 until CHUNK_FRAMES) {
                if (chunk.counts[i] <= 0) continue
                val ms = (index * CHUNK_FRAMES + i) * SubtitleAligner.FRAME_MS
                if (ms in fromMs until toMs) frames++
            }
        }
        frames * SubtitleAligner.FRAME_MS
    }

    /**
     * The speech heard from [fromMs] to [toMs] of the movie, one stretch per run heard without a gap
     * (null: too little, or all the same loudness).
     */
    fun speech(fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE, minFrames: Int = 1_000): List<SpeechWindow>? {
        val starts = ArrayList<Long>()
        val levels = ArrayList<DoubleArray>()
        synchronized(lock) {
            val run = ArrayList<Double>()
            var runStart = 0L
            var lastFrame = Long.MIN_VALUE
            var gap = 0
            fun close() {
                // Not the silence the run ended on.
                while (run.isNotEmpty() && run.last().isNaN()) run.removeAt(run.size - 1)
                if (run.size >= MIN_RUN_FRAMES) {
                    starts += runStart
                    levels += run.toDoubleArray()
                }
                run.clear()
                gap = 0
            }
            for (index in chunks.keys.sorted()) {
                val chunk = chunks[index] ?: continue
                for (i in 0 until CHUNK_FRAMES) {
                    val frame = index * CHUNK_FRAMES + i
                    val ms = frame * SubtitleAligner.FRAME_MS
                    if (ms < fromMs || ms >= toMs) continue
                    val count = chunk.counts[i].toInt()
                    if (count <= 0) {
                        if (run.isEmpty()) continue
                        gap++
                        // A second with nothing heard (a seek): the run ends.
                        if (gap >= GAP_FRAMES) close() else run += Double.NaN
                        lastFrame = frame
                        continue
                    }
                    if (run.isNotEmpty() && frame != lastFrame + 1) close()
                    if (run.isEmpty()) runStart = ms
                    gap = 0
                    run += 10 * kotlin.math.log10(chunk.energy[i].toDouble() / count + 1e-12)
                    lastFrame = frame
                }
            }
            close()
        }
        return SpeechLevels.normalize(starts, levels, minFrames)
    }

    companion object {
        const val CHUNK_FRAMES = 1_000
        const val MAX_CHUNKS = 90
        private const val NO_TIME = Long.MIN_VALUE
        private const val JUMP_US = 200_000L
        private const val GAP_FRAMES = 100
        private const val MIN_RUN_FRAMES = 500
    }
}
