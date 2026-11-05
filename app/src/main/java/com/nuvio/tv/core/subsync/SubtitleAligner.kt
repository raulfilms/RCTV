package com.nuvio.tv.core.subsync

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** A subtitle line's time on screen, as its file has it (ms). */
data class CueSpan(val startMs: Long, val endMs: Long)

/**
 * Where speech is heard in a stretch of the movie: one value per [SubtitleAligner.FRAME_MS] from
 * [startMs] (the movie's time), how likely it is that someone is talking (0..1).
 */
class SpeechWindow(val startMs: Long, val speech: FloatArray) {
    val endMs: Long get() = startMs + speech.size.toLong() * SubtitleAligner.FRAME_MS
}

/**
 * A subtitle's timing fixed to the movie's audio: a line at T in its file shows at
 * [scale] * T + [offsetMs]. [confidence]: how far the match stands above any other (in standard
 * deviations of all the tries); [score]: how well the lines and the speech agree there.
 */
data class SubtitleAlignment(
    val offsetMs: Long,
    val scale: Double,
    val score: Double,
    val confidence: Double
)

/**
 * RCTV: lines a subtitle up with the speech heard in the movie, the way ffsubsync/alass do: the
 * subtitle's lines and the movie's speech are two on/off signals; the shift (and, for a subtitle made
 * for another frame rate, the stretch) where they agree best is the fix. Only taken when that match
 * clearly stands above every other (else the subtitle is left as it is).
 */
object SubtitleAligner {
    const val FRAME_MS = 10
    private const val COARSE_MS = 100
    private const val FINE_RANGE_MS = 200L
    private const val MIN_CUES = 20
    /** The best match must stand this many standard deviations above the average try. */
    private const val MIN_Z = 5.0
    /** ...and this many above the best try elsewhere (another shift more than [PEAK_WIDTH_MS] away). */
    private const val MIN_PROMINENCE = 1.0
    private const val PEAK_WIDTH_MS = 1_500L
    /** Following a subtitle along the movie: shifts this far around the one in use are tried. */
    const val NEAR_RANGE_MS = 12_000L
    /** ...with at least this many of its lines in the stretch heard, standing out this much. */
    private const val MIN_NEAR_CUES = 8
    /*
     * Few shifts are tried there, so the match stands fewer deviations above the average try than in
     * a search of the whole subtitle; what tells it is how far above the best try elsewhere it is.
     */
    private const val MIN_Z_NEAR = 3.0
    private const val MIN_PROMINENCE_NEAR = 1.5
    private const val MIN_Z_NEAR_STRICT = 3.3
    private const val MIN_PROMINENCE_STRICT = 2.0
    /** Another frame rate is taken only when it matches clearly better than the subtitle's own. */
    private const val SCALE_PREFERENCE = 0.15

    /** The subtitle's own speed first, then the frame-rate mixes that happen (PAL 25, 24, 23.976). */
    val SCALES: DoubleArray = doubleArrayOf(
        1.0,
        25.0 / 23.976, 23.976 / 25.0,
        24.0 / 23.976, 23.976 / 24.0,
        25.0 / 24.0, 24.0 / 25.0
    )

    private class Candidate(val scale: Double, val offsetMs: Long, val score: Double, val z: Double, val prominence: Double)

    /**
     * The fix for [cues] against the speech in [windows], shifting up to [maxOffsetMs] either way;
     * null when no match is clear enough (too few lines, too little speech, or the subtitle doesn't
     * belong to this movie).
     */
    fun align(cues: List<CueSpan>, windows: List<SpeechWindow>, maxOffsetMs: Long = 180_000L): SubtitleAlignment? {
        val lines = cues.filter { it.endMs > it.startMs && it.startMs >= 0 }.sortedBy { it.startMs }
        if (lines.size < MIN_CUES || windows.isEmpty()) return null
        val subtitleEnd = lines.maxOf { it.endMs } + 1_000L

        val coarseSub = subtitleSignal(lines, COARSE_MS, 0L, subtitleEnd)
        val coarseSpeech = speechSignal(windows, COARSE_MS) ?: return null

        val candidates = SCALES.toList().mapNotNull { scale -> search(scale, coarseSpeech, coarseSub, COARSE_MS, -maxOffsetMs, maxOffsetMs) }
        if (candidates.isEmpty()) return null
        val own = candidates.first { it.scale == 1.0 }
        val other = candidates.filter { it.scale != 1.0 }.maxByOrNull { it.score }
        val chosen = if (other != null && other.score > own.score + SCALE_PREFERENCE * abs(own.score) && other.z >= MIN_Z) other else own
        if (chosen.z < MIN_Z || chosen.prominence < MIN_PROMINENCE || chosen.score <= 0.0) return null

        // Then to the 10 ms around it.
        val fineSub = subtitleSignal(lines, FRAME_MS, 0L, subtitleEnd)
        val fineSpeech = speechSignal(windows, FRAME_MS) ?: return null
        return refined(chosen, fineSpeech, fineSub)
    }

    /**
     * RCTV: the shift within [rangeMs] of [aroundMs] where [cues] (stretched by [scale]) fit the speech
     * in [windows] (a few minutes of the movie): to follow a subtitle along the movie, where a scene of
     * another cut, or a drift, moves its lines. Null when that stretch doesn't settle it clearly (little
     * dialogue there). [strict]: a single look decides, so it must stand out more.
     */
    fun alignNear(
        cues: List<CueSpan>,
        windows: List<SpeechWindow>,
        scale: Double,
        aroundMs: Long,
        rangeMs: Long = NEAR_RANGE_MS,
        strict: Boolean = false
    ): SubtitleAlignment? {
        if (windows.isEmpty()) return null
        val heardFrom = windows.minOf { it.startMs }
        val heardTo = windows.maxOf { it.endMs }
        // The part of the subtitle those shifts can bring there (and a little room).
        val from = (((heardFrom - aroundMs - rangeMs) / scale).toLong() - 2_000L).coerceAtLeast(0L)
        val to = ((heardTo - aroundMs + rangeMs) / scale).toLong() + 2_000L
        val lines = cues.filter { it.endMs > it.startMs && it.startMs >= 0 && it.endMs > from && it.startMs < to }.sortedBy { it.startMs }
        // Lines that land in what was heard (at the shift expected): enough to tell.
        val landing = lines.count { cue ->
            val at = (cue.startMs * scale + aroundMs).toLong()
            at in heardFrom until heardTo
        }
        if (landing < MIN_NEAR_CUES) return null
        val coarseSpeech = speechSignal(windows, COARSE_MS) ?: return null
        val coarseSub = subtitleSignal(lines, COARSE_MS, from, to)
        val found = search(scale, coarseSpeech, coarseSub, COARSE_MS, aroundMs - rangeMs, aroundMs + rangeMs) ?: return null
        val minZ = if (strict) MIN_Z_NEAR_STRICT else MIN_Z_NEAR
        val minProminence = if (strict) MIN_PROMINENCE_STRICT else MIN_PROMINENCE_NEAR
        if (found.z < minZ || found.prominence < minProminence || found.score <= 0.0) return null
        val fineSpeech = speechSignal(windows, FRAME_MS) ?: return null
        return refined(found, fineSpeech, subtitleSignal(lines, FRAME_MS, from, to))
    }

    /** [chosen] to the 10 ms around it. */
    private fun refined(chosen: Candidate, fineSpeech: Signal, fineSub: SubSignal): SubtitleAlignment {
        var bestOffset = chosen.offsetMs
        var bestScore = Double.NEGATIVE_INFINITY
        var offset = chosen.offsetMs - FINE_RANGE_MS
        while (offset <= chosen.offsetMs + FINE_RANGE_MS) {
            val score = score(chosen.scale, offset, fineSpeech, fineSub, FRAME_MS)
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
            offset += FRAME_MS
        }
        return SubtitleAlignment(offsetMs = bestOffset, scale = chosen.scale, score = bestScore, confidence = chosen.z)
    }

    /** Speech as a signal: each step's time in the movie (its middle) and value (centered on 0). */
    private class Signal(val timesMs: LongArray, val values: DoubleArray)

    private fun speechSignal(windows: List<SpeechWindow>, stepMs: Int): Signal? {
        val perStep = stepMs / FRAME_MS
        val times = ArrayList<Long>()
        val values = ArrayList<Double>()
        for (window in windows) {
            var i = 0
            while (i + perStep <= window.speech.size) {
                var sum = 0.0
                for (k in i until i + perStep) sum += window.speech[k]
                times += window.startMs + i.toLong() * FRAME_MS + stepMs / 2
                values += sum / perStep
                i += perStep
            }
        }
        if (values.size < 50) return null
        val mean = values.average()
        val centered = DoubleArray(values.size) { values[it] - mean }
        // Silence (or the same noise) everywhere: nothing to line up with.
        if (centered.all { abs(it) < 1e-6 }) return null
        return Signal(times.toLongArray(), centered)
    }

    /** The subtitle's steps from [SubSignal.base] on (its file's time / step). */
    private class SubSignal(val base: Int, val values: DoubleArray)

    /**
     * The subtitle from [fromMs] to [endMs] of its file as a signal: per step, the share of it with a
     * line on screen (centered on 0). Only that part: a whole film at 10 ms would be megabytes.
     */
    private fun subtitleSignal(cues: List<CueSpan>, stepMs: Int, fromMs: Long, endMs: Long): SubSignal {
        val base = (fromMs / stepMs).toInt()
        val steps = (endMs / stepMs + 1).toInt() - base
        val covered = DoubleArray(steps.coerceAtLeast(1))
        for (cue in cues) {
            var t = maxOf(cue.startMs, base.toLong() * stepMs)
            while (t < cue.endMs) {
                val step = (t / stepMs).toInt() - base
                if (step >= covered.size) break
                val stepEnd = (step + base + 1).toLong() * stepMs
                val until = minOf(stepEnd, cue.endMs)
                covered[step] += (until - t).toDouble() / stepMs
                t = until
            }
        }
        for (i in covered.indices) covered[i] = covered[i].coerceAtMost(1.0)
        val mean = covered.average()
        return SubSignal(base, DoubleArray(covered.size) { covered[it] - mean })
    }

    /** How well the speech and the subtitle agree when a line at T shows at scale * T + offset. */
    private fun score(scale: Double, offsetMs: Long, speech: Signal, subtitle: SubSignal, stepMs: Int): Double =
        score(scaledSteps(scale, speech, stepMs), offsetMs / scale / stepMs, speech.values, subtitle)

    /** Each speech step's time in the subtitle's steps (before any shift): times / scale / step. */
    private fun scaledSteps(scale: Double, speech: Signal, stepMs: Int): DoubleArray {
        val factor = 1.0 / scale / stepMs
        return DoubleArray(speech.timesMs.size) { speech.timesMs[it] * factor }
    }

    /** [scaled] shifted back by [shiftSteps] subtitle steps, against the subtitle (out of it: no say). */
    private fun score(scaled: DoubleArray, shiftSteps: Double, values: DoubleArray, subtitle: SubSignal): Double {
        var sum = 0.0
        val sub = subtitle.values
        val size = sub.size
        val base = subtitle.base
        for (i in scaled.indices) {
            val position = scaled[i] - shiftSteps
            if (position < 0.0) continue
            val step = position.toInt() - base
            if (step < 0 || step >= size) continue
            sum += values[i] * sub[step]
        }
        return sum / scaled.size
    }

    /** Every shift from [fromMs] to [toMs] (a step apart) tried; the best, and how far it stands out. */
    private fun search(scale: Double, speech: Signal, subtitle: SubSignal, stepMs: Int, fromMs: Long, toMs: Long): Candidate? {
        val count = ((toMs - fromMs) / stepMs + 1).toInt()
        if (count < 3) return null
        val scores = DoubleArray(count)
        val scaled = scaledSteps(scale, speech, stepMs)
        var bestIndex = 0
        for (k in 0 until count) {
            val offset = fromMs + k.toLong() * stepMs
            scores[k] = score(scaled, offset / scale / stepMs, speech.values, subtitle)
            if (scores[k] > scores[bestIndex]) bestIndex = k
        }
        val mean = scores.average()
        val std = sqrt(scores.sumOf { (it - mean) * (it - mean) } / count)
        if (std <= 1e-12) return null
        val best = scores[bestIndex]
        val peakWidth = (PEAK_WIDTH_MS / stepMs).toInt()
        var second = Double.NEGATIVE_INFINITY
        for (k in 0 until count) {
            if (abs(k - bestIndex) > peakWidth && scores[k] > second) second = scores[k]
        }
        if (second == Double.NEGATIVE_INFINITY) second = mean
        return Candidate(
            scale = scale,
            offsetMs = fromMs + bestIndex.toLong() * stepMs,
            score = best,
            z = (best - mean) / std,
            prominence = (best - second) / std
        )
    }

    /** [offsetMs] as the subtitle delay to set (rounded to the delay control's 10 ms). */
    fun delayMs(alignment: SubtitleAlignment): Int = ((alignment.offsetMs / 10.0).roundToLong() * 10).toInt()
}
