package com.nuvio.tv.core.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SubtitleAlignerTest {

    /** A movie's dialogue: lines of 1-5 s with 0.5-12 s between them, over [lengthMs]. */
    private fun dialogue(seed: Int, lengthMs: Long = 100L * 60 * 1000): List<CueSpan> {
        val random = Random(seed)
        val lines = ArrayList<CueSpan>()
        var t = 20_000L
        while (t < lengthMs - 10_000) {
            val length = random.nextLong(1_000, 5_000)
            lines += CueSpan(t, t + length)
            t += length + random.nextLong(500, 12_000)
        }
        return lines
    }

    /**
     * What the analyzer would hear in [windows] of a movie with [dialogue]: speech where people talk
     * (not all of it caught), music and effects mistaken for speech now and then, and noise.
     */
    private fun heard(dialogue: List<CueSpan>, windows: List<LongRange>, seed: Int): List<SpeechWindow> {
        val random = Random(seed)
        return windows.map { range ->
            val frames = ((range.last - range.first) / SubtitleAligner.FRAME_MS).toInt()
            val speech = FloatArray(frames)
            for (i in 0 until frames) {
                val t = range.first + i.toLong() * SubtitleAligner.FRAME_MS
                val talking = dialogue.any { t >= it.startMs && t < it.endMs }
                // Pauses between words, music taken for speech.
                val p = when {
                    talking -> if (random.nextFloat() < 0.75f) 0.8f + random.nextFloat() * 0.2f else random.nextFloat() * 0.4f
                    else -> if (random.nextFloat() < 0.2f) 0.5f + random.nextFloat() * 0.5f else random.nextFloat() * 0.3f
                }
                speech[i] = p
            }
            SpeechWindow(range.first, speech)
        }
    }

    /** Five 36-second stretches spread over a 100-minute movie. */
    private val windows = listOf(0.12, 0.30, 0.48, 0.66, 0.84).map { fraction ->
        val start = (fraction * 100 * 60 * 1000).toLong()
        start..(start + 36_000)
    }

    /** The subtitle file's lines: a line at movie time P is at (P - offset) / scale in the file, a bit off each. */
    private fun subtitleFor(dialogue: List<CueSpan>, offsetMs: Long, scale: Double, seed: Int): List<CueSpan> {
        val random = Random(seed)
        return dialogue.filter { random.nextFloat() > 0.08f }.map { line ->
            val start = ((line.startMs - offsetMs) / scale).toLong() + random.nextLong(-120, 120)
            val end = ((line.endMs - offsetMs) / scale).toLong() + random.nextLong(0, 400)
            CueSpan(start, end)
        }.filter { it.startMs >= 0 }
    }

    @Test
    fun `a subtitle 2 35 s early is put back in place`() {
        val dialogue = dialogue(1)
        val result = SubtitleAligner.align(subtitleFor(dialogue, 2_350, 1.0, 2), heard(dialogue, windows, 3))
        assertNotNull(result)
        assertEquals(1.0, result!!.scale, 0.0)
        assertTrue("offset ${result.offsetMs}", kotlin.math.abs(result.offsetMs - 2_350) <= 80)
    }

    @Test
    fun `a subtitle late by over a minute is found too`() {
        val dialogue = dialogue(4)
        val result = SubtitleAligner.align(subtitleFor(dialogue, -71_400, 1.0, 5), heard(dialogue, windows, 6))
        assertNotNull(result)
        assertTrue("offset ${result!!.offsetMs}", kotlin.math.abs(result.offsetMs + 71_400) <= 80)
    }

    @Test
    fun `a subtitle in sync stays as it is`() {
        val dialogue = dialogue(7)
        val result = SubtitleAligner.align(subtitleFor(dialogue, 0, 1.0, 8), heard(dialogue, windows, 9))
        assertNotNull(result)
        assertTrue("offset ${result!!.offsetMs}", kotlin.math.abs(result.offsetMs) <= 80)
    }

    @Test
    fun `a subtitle made for 25 fps is stretched to the 23 976 movie`() {
        val dialogue = dialogue(10)
        val scale = 25.0 / 23.976
        val result = SubtitleAligner.align(subtitleFor(dialogue, 800, scale, 11), heard(dialogue, windows, 12))
        assertNotNull(result)
        assertEquals(scale, result!!.scale, 1e-9)
        assertTrue("offset ${result.offsetMs}", kotlin.math.abs(result.offsetMs - 800) <= 120)
    }

    @Test
    fun `a subtitle of another movie is left alone`() {
        val result = SubtitleAligner.align(subtitleFor(dialogue(13), 0, 1.0, 14), heard(dialogue(15), windows, 16))
        assertNull(result)
    }

    @Test
    fun `too few lines are left alone`() {
        val dialogue = dialogue(17).take(10)
        assertNull(SubtitleAligner.align(dialogue, heard(dialogue(17), windows, 18)))
    }
}
