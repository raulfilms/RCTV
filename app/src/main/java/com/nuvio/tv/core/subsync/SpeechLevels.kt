package com.nuvio.tv.core.subsync

import kotlin.math.log10

/**
 * RCTV: the voice band (about 250 Hz to 3.5 kHz) of a run of audio samples, a high-pass then a
 * low-pass: what's left is mostly dialogue (music's lows and effects' highs go).
 */
internal class VoiceBandFilter {
    private var rate = 0.0
    private var a = 0.0
    private var b = 0.0
    private var highPassOut = 0.0
    private var highPassIn = 0.0
    private var lowPassOut = 0.0

    fun setSampleRate(sampleRate: Int) {
        val r = sampleRate.coerceAtLeast(8_000).toDouble()
        if (r == rate) return
        rate = r
        val dt = 1.0 / r
        val highPass = 1.0 / (2 * Math.PI * HIGH_PASS_HZ)
        val lowPass = 1.0 / (2 * Math.PI * LOW_PASS_HZ)
        a = highPass / (highPass + dt)
        b = dt / (lowPass + dt)
    }

    /** A new run of audio (after a seek): the filter forgets the last one. */
    fun reset() {
        highPassOut = 0.0
        highPassIn = 0.0
        lowPassOut = 0.0
    }

    /** The voice band's power at this sample (-1..1). */
    fun power(value: Double): Double {
        val high = a * (highPassOut + value - highPassIn)
        highPassIn = value
        highPassOut = high
        lowPassOut += b * (high - lowPassOut)
        return lowPassOut * lowPassOut
    }

    companion object {
        const val HIGH_PASS_HZ = 250.0
        const val LOW_PASS_HZ = 3_500.0
    }
}

/** RCTV: loudness per 10 ms as how likely it is that someone talks. */
internal object SpeechLevels {

    /** A stretch's loudness in dB per 10 ms (NaN: nothing heard there), from its power and samples. */
    fun levelsOf(energy: DoubleArray, counts: IntArray): DoubleArray =
        DoubleArray(energy.size) { i ->
            if (counts[i] > 0) 10 * log10(energy[i] / counts[i] + 1e-12) else Double.NaN
        }

    /**
     * Each 10 ms's loudness ([levels], each stretch from its [starts]) as how likely it is that someone
     * talks (0..1), against the loudness of all the stretches together (so a quiet stretch with no
     * dialogue counts as quiet). Null: too little heard, or all of it the same loudness.
     */
    fun normalize(starts: List<Long>, levels: List<DoubleArray>, minFrames: Int = 1_000): List<SpeechWindow>? {
        val all = levels.flatMap { level -> level.filter { !it.isNaN() } }.sorted()
        if (all.size < minFrames) return null
        val floor = all[(all.size * 0.20).toInt()]
        val peak = all[(all.size * 0.95).toInt().coerceAtMost(all.size - 1)]
        if (peak - floor < 6.0) return null
        return levels.mapIndexed { n, level ->
            val raw = FloatArray(level.size) { i ->
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
            SpeechWindow(starts[n], smooth)
        }
    }
}
