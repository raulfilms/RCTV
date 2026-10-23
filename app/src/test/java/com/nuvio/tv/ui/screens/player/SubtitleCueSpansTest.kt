package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.subsync.CueSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCueSpansTest {

    @Test
    fun `srt lines, without the ones that are only music or sounds`() {
        val srt = """
            1
            00:00:01,500 --> 00:00:03,000
            Hello there.

            2
            00:00:04,000 --> 00:00:05,250
            ♪ Soft music ♪

            3
            00:00:06,000 --> 00:00:07,000
            [DOOR SLAMS]

            4
            01:02:03,040 --> 01:02:04,500
            <i>(whispering)</i> Over here.
        """.trimIndent()
        assertEquals(
            listOf(CueSpan(1_500, 3_000), CueSpan(3_723_040, 3_724_500)),
            subtitleCueSpansOf(srt, "https://example.com/sub.srt")
        )
    }

    @Test
    fun `ass dialogue lines by their format`() {
        val ass = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,Hello, there.\Nhow are you?
            Dialogue: 0,0:01:02.04,0:01:04.5,Default,,0,0,0,,{\i1}Fine{\i0}
            Comment: 0,0:01:05.00,0:01:06.00,Default,,0,0,0,,Not shown
            Dialogue: 0,0:01:07.00,0:01:08.00,Default,,0,0,0,,♪♪
        """.trimIndent()
        assertEquals(
            listOf(CueSpan(1_500, 3_000), CueSpan(62_040, 64_500)),
            subtitleCueSpansOf(ass, "https://example.com/sub.ass")
        )
    }
}
