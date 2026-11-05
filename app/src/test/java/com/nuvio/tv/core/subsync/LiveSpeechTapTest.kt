package com.nuvio.tv.core.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class LiveSpeechTapTest {

    private val rate = 48_000

    private fun dialogue(seed: Int, fromMs: Long, toMs: Long): List<CueSpan> {
        val random = Random(seed)
        val lines = ArrayList<CueSpan>()
        var t = fromMs
        while (t < toMs) {
            val length = random.nextLong(1_000, 5_000)
            lines += CueSpan(t, t + length)
            t += length + random.nextLong(500, 9_000)
        }
        return lines
    }

    /**
     * The movie's sound from [fromMs] for [lengthMs], as the player hands it over (20 ms buffers,
     * 16-bit, [channels] channels): voices where [dialogue] talks (in the center channel when there
     * is one), a low hum and a little noise everywhere.
     */
    private fun play(tap: LiveSpeechTap, dialogue: List<CueSpan>, fromMs: Long, lengthMs: Long, channels: Int, seed: Int) {
        val random = Random(seed)
        val perBuffer = rate / 50
        val buffer = ByteBuffer.allocate(perBuffer * channels * 2).order(ByteOrder.nativeOrder())
        var sample = fromMs * rate / 1000
        val end = (fromMs + lengthMs) * rate / 1000
        var line = 0
        while (sample < end) {
            buffer.clear()
            val startUs = sample * 1_000_000L / rate
            for (s in 0 until perBuffer) {
                val ms = (sample + s) * 1000 / rate
                while (line < dialogue.size && dialogue[line].endMs <= ms) line++
                val talking = line < dialogue.size && ms >= dialogue[line].startMs
                // Syllables: a voice at 900 Hz that comes and goes 5 times a second.
                val voice = if (talking) 0.3 * sin(2 * PI * 900 * (sample + s) / rate) * (0.5 + 0.5 * sin(2 * PI * 5 * (sample + s) / rate)) else 0.0
                val hum = 0.2 * sin(2 * PI * 60 * (sample + s) / rate)
                val noise = (random.nextDouble() - 0.5) * 0.004
                for (c in 0 until channels) {
                    val value = if (channels >= 3 && c != 2) hum + noise else voice + hum + noise
                    buffer.putShort((value * 32767).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            buffer.flip()
            tap.hear(buffer, startUs)
            assertEquals("the player's buffer isn't moved", 0, buffer.position())
            sample += perBuffer
        }
    }

    @Test
    fun `the speech heard as the movie plays puts a subtitle in place`() {
        val dialogue = dialogue(1, 20L * 60 * 1000, 26L * 60 * 1000)
        val tap = LiveSpeechTap()
        tap.start()
        tap.configure(pcm = true, sampleRate = rate, channels = 6, isFloat = false)
        play(tap, dialogue, 20L * 60 * 1000 + 10_000, 240_000, channels = 6, seed = 2)
        assertTrue(tap.hearsPcm)
        assertEquals(240_000L, tap.heardMs())
        val speech = tap.speech()
        assertNotNull(speech)
        assertEquals(1, speech!!.size)
        // The subtitle file's lines are 2.35 s early (at P - 2350 in it).
        val subtitle = dialogue.map { CueSpan(it.startMs - 2_350, it.endMs - 2_350) }
        val found = SubtitleAligner.align(subtitle, speech)
        assertNotNull(found)
        assertTrue("offset ${found!!.offsetMs}", abs(found.offsetMs - 2_350) <= 60)
        // And near the shift in use, only the last part heard.
        val recent = tap.speech(fromMs = 20L * 60 * 1000 + 100_000, toMs = 20L * 60 * 1000 + 250_000)
        val near = SubtitleAligner.alignNear(subtitle, recent!!, 1.0, aroundMs = 2_000)
        assertNotNull(near)
        assertTrue("offset ${near!!.offsetMs}", abs(near.offsetMs - 2_350) <= 100)
    }

    @Test
    fun `a seek makes two stretches, stereo works too`() {
        val dialogue = dialogue(3, 0, 50L * 60 * 1000)
        val tap = LiveSpeechTap()
        tap.start()
        tap.configure(pcm = true, sampleRate = rate, channels = 2, isFloat = false)
        play(tap, dialogue, 5L * 60 * 1000, 60_000, channels = 2, seed = 4)
        tap.flush()
        play(tap, dialogue, 30L * 60 * 1000, 60_000, channels = 2, seed = 5)
        val speech = tap.speech()
        assertEquals(listOf(5L * 60 * 1000, 30L * 60 * 1000), speech!!.map { it.startMs })
        assertEquals(60_000L, tap.heardMs(fromMs = 30L * 60 * 1000))
    }

    @Test
    fun `sound sent undecoded is not heard, and only while listening`() {
        val dialogue = dialogue(6, 0, 10L * 60 * 1000)
        val tap = LiveSpeechTap()
        tap.configure(pcm = true, sampleRate = rate, channels = 2, isFloat = false)
        play(tap, dialogue, 0, 20_000, channels = 2, seed = 7)
        assertEquals("not listening", 0L, tap.heardMs())
        val first = tap.start()
        tap.configure(pcm = false, sampleRate = rate, channels = 2, isFloat = false)
        play(tap, dialogue, 0, 20_000, channels = 2, seed = 8)
        assertFalse(tap.hearsPcm)
        assertEquals(0L, tap.heardMs())
        // A newer sync started: the old one's stop doesn't end it.
        val second = tap.start()
        tap.stop(first)
        assertTrue(tap.listening)
        tap.stop(second)
        assertFalse(tap.listening)
    }
}
