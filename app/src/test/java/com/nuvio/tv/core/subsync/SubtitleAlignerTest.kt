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

    /** A subtitle of another cut: [extraMs] more of the movie (a scene) from [cutAtMs] of the movie on. */
    private fun otherCut(dialogue: List<CueSpan>, offsetMs: Long, cutAtMs: Long, extraMs: Long, seed: Int): List<CueSpan> =
        subtitleFor(dialogue, offsetMs, 1.0, seed).map { cue ->
            if (cue.startMs + offsetMs >= cutAtMs) CueSpan(cue.startMs - extraMs, cue.endMs - extraMs) else cue
        }

    @Test
    fun `along the movie, a scene of another cut is found where it starts`() {
        val dialogue = dialogue(21)
        val subtitle = otherCut(dialogue, 1_200, cutAtMs = 50L * 60 * 1000, extraMs = 4_300, seed = 22)
        // Before the scene: the shift in use holds.
        val before = heard(dialogue, listOf((40L * 60 * 1000)..(40L * 60 * 1000 + 150_000)), 23)
        val same = SubtitleAligner.alignNear(subtitle, before, 1.0, aroundMs = 1_200)
        assertNotNull(same)
        assertTrue("offset ${same!!.offsetMs}", kotlin.math.abs(same.offsetMs - 1_200) <= 150)
        // After it: 4.3 s later.
        val after = heard(dialogue, listOf((55L * 60 * 1000)..(55L * 60 * 1000 + 150_000)), 24)
        val moved = SubtitleAligner.alignNear(subtitle, after, 1.0, aroundMs = 1_200)
        assertNotNull(moved)
        assertTrue("offset ${moved!!.offsetMs}", kotlin.math.abs(moved.offsetMs - 5_500) <= 150)
    }

    @Test
    fun `along the movie, a stretch without dialogue changes nothing`() {
        val dialogue = dialogue(31).filter { it.startMs < 30L * 60 * 1000 || it.startMs > 34L * 60 * 1000 }
        val quiet = heard(dialogue, listOf((31L * 60 * 1000)..(31L * 60 * 1000 + 150_000)), 32)
        assertNull(SubtitleAligner.alignNear(subtitleFor(dialogue, 900, 1.0, 33), quiet, 1.0, aroundMs = 900))
    }

    @Test
    fun `along the movie, a subtitle of another movie isn't moved`() {
        val heardHere = heard(dialogue(41), listOf((20L * 60 * 1000)..(20L * 60 * 1000 + 150_000)), 42)
        assertNull(SubtitleAligner.alignNear(subtitleFor(dialogue(43), 0, 1.0, 44), heardHere, 1.0, aroundMs = 0, strict = true))
    }

    @Test
    fun `each part of the movie keeps its own shift`() {
        val map = SubtitleOffsetMap()
        assertNull(map.offsetAt(10_000))
        map.reset(1_200)
        map.put(50L * 60 * 1000, 5_500)
        assertEquals(1_200L, map.offsetAt(0))
        assertEquals(1_200L, map.offsetAt(49L * 60 * 1000))
        assertEquals(5_500L, map.offsetAt(51L * 60 * 1000))
        map.reset(-300)
        assertEquals(-300L, map.offsetAt(51L * 60 * 1000))
    }

    @Test
    fun `of the files of a language, the one made for this cut fits the movie best`() {
        val dialogue = dialogue(51)
        val speech = heard(dialogue, windows, 52)
        val thisCut = SubtitleAligner.align(subtitleFor(dialogue, 800, 1.0, 53), speech)
        // Another cut: from the middle of the movie on, its lines are 6 s later.
        val otherCut = SubtitleAligner.align(otherCut(dialogue, 800, cutAtMs = 45L * 60 * 1000, extraMs = -6_000, seed = 54), speech)
        assertNotNull(thisCut)
        assertTrue(
            "this cut ${thisCut!!.confidence}, other cut ${otherCut?.confidence}",
            otherCut == null || thisCut.confidence >= otherCut.confidence + 1.0
        )
    }
}
