package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** MEM-07: the guide's regex-free checks give exactly what the expressions they replace gave. */
class GuideTextTest {

    private val channels = listOf(
        "", "ESPN", "ESPN2 HD", "US: ESPN FHD", "US | FOX Sports 1", "|UK| Sky Sports News", "[MX] Canal 5",
        "(AR) TyC Sports", "USA - Discovery", "MTV - Music Television", "CNN International", "KABC-TV 7.1",
        "WJBK-DT Detroit", "WXYZ", "KTLA5", "KBS World", "WWE Network", "WGN America", "NBC Sports Boston",
        "Disney Jr.", "A&E", "E! Entertainment", "Pac-12 Network", "C-SPAN 2", "Música Latina", "Telemundo Noticias",
        "Nick Jr HD", "nickelodeon", "SportsCenter", "ESPNews", "Fox Sports Deportes", "TUDN", "beIN SPORTS",
        "BBC World News", "  [US] HBO", "| CA | CBC", "FR: TF1", "DE – ZDF", "XXX Adults", "Kids & Family",
        "Food Network East", "MLB Network", "NHL Center Ice 1", "UFC FIGHT PASS", "Ⅻ Sports", "東京 MX",
        "WKMG-LD", "KXAS-TV-HD", "W123", "K-ABC", "WABCD", "xWABC", "WABCx"
    )

    @Test
    fun `whole words like the category hints`() {
        val names = listOf("espn", "espn2", "fox sports", "sports", "sport", "nba", "a&e", "e!", "pac-12", "c-span",
            "música", "nick jr", "nick", "news", "kids", "id", "hd", "tv")
        channels.forEach { channel ->
            names.forEach { name ->
                val regex = Regex("""(?<![\p{L}\p{N}])(${Regex.escape(name)})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
                assertEquals("$channel / $name", regex.containsMatchIn(channel), GuideText.containsWord(channel.lowercase(), name))
            }
        }
    }

    @Test
    fun `call signs like the guide and the logo library`() {
        val withSuffix = Regex("""(?<![\p{L}\p{N}])([KW][A-Z]{2,3})(-(TV|DT|HD|LD|CD))?(?![\p{L}\p{N}])""")
        val ascii = Regex("""(?<![A-Za-z0-9])([KW][A-Z]{2,3})(?![A-Za-z0-9])""")
        val suffixes = setOf("TV", "DT", "HD", "LD", "CD")
        channels.forEach { channel ->
            assertEquals(channel, withSuffix.findAll(channel).map { it.groupValues[1] }.toList(), GuideText.callSigns(channel, true, suffixes))
            assertEquals(channel, ascii.findAll(channel).map { it.groupValues[1] }.toList(), GuideText.callSigns(channel, false, null))
        }
    }

    @Test
    fun `country tags like the channel's country and the shown name`() {
        val prefixTag = Regex("""^[\s|\[(]*([A-Z]{2,3})[\s\])]*[|:\-–]""")
        val bracketTag = Regex("""^[\s]*[|\[(]([A-Z]{2,3})[|\])]""")
        val countryPrefix = Regex("^([A-Z]{2,3})\\s*[-:|]\\s+")
        channels.forEach { channel ->
            assertEquals(channel, prefixTag.find(channel)?.groupValues?.get(1), GuideText.leadingCountryTag(channel))
            assertEquals(channel, bracketTag.find(channel)?.groupValues?.get(1), GuideText.bracketCountryTag(channel))
            assertEquals(channel, countryPrefix.find(channel)?.let { it.groupValues[1] to it.range.last + 1 }, GuideText.countryPrefix(channel))
        }
    }

    @Test
    fun `program tags and channel numbers like the guide parser`() {
        val live = Regex("""\[\s*live\s*]""", RegexOption.IGNORE_CASE)
        val new = Regex("""\[\s*new\s*]""", RegexOption.IGNORE_CASE)
        listOf("NBA [LIVE]", "[ live ] Game", "[Live]", "[liv]", "[ new]", "Show [NEW] [LIVE]", "[[live]]", "[", "live]", "[ lives ]")
            .forEach {
                assertEquals(it, live.containsMatchIn(it), GuideText.hasBracketTag(it, "live"))
                assertEquals(it, new.containsMatchIn(it), GuideText.hasBracketTag(it, "new"))
            }
        val number = Regex("""\d{1,5}(\.\d{1,3})?""")
        // (The JVM's \d is ASCII-only; ICU's, on Android, is any decimal digit, as here: "٣" is one.)
        listOf("7", "7.1", "12345", "123456", "7.", ".1", "7.1234", "7.12", "abc", "", "1 2")
            .forEach { assertEquals(it, number.matches(it), GuideText.isChannelNumber(it)) }
    }

    @Test
    fun `playlist attributes and VOD links like the playlist parser`() {
        val attribute = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")
        listOf(
            """#EXTINF:-1 tvg-id="espn.us" tvg-name="ESPN" tvg-logo="http://x/l.png" group-title="Sports"""",
            """#EXTINF:-1 TVG-ID="A" tvg-id="B"""", """#EXTINF:-1 a="unclosed b="x"""", """#EXTINF:0 x= "y" z="""",
            """#EXTINF:-1 tvg-chno="7.1" tvg-country="US;CA"""", "#EXTINF:-1", """ab"c="d"""", """k="""""
        ).forEach { line ->
            val regex = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            assertEquals(line, regex, GuideText.m3uAttributes(line))
        }
        val vodPath = Regex("""/(movie|movies|series)/""", RegexOption.IGNORE_CASE)
        val vodFile = Regex("""\.(mp4|mkv|avi|mov|m4v|wmv|flv|webm)(\?.*)?$""", RegexOption.IGNORE_CASE)
        listOf("live/1/2/3.ts", "movie/u/p/1.mkv", "series/u/p/2.MP4?token=1", "/Movies/x", "a.mkv.ts", "x.webm?",
            "x.avi?a=b?c", "seriesx/1", "MOVIE/", "clip.mov").forEach { path ->
            assertEquals(path, vodPath.containsMatchIn("/$path"), GuideText.hasVodPathSegment("/$path"))
            assertEquals(path, vodFile.containsMatchIn(path), GuideText.hasVodFileExtension(path))
        }
    }

    @Test
    fun `logo name tokens like the logo library`() {
        val separators = Regex("""[^a-z0-9]+""")
        val letterDigit = Regex("""(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])""")
        channels.map { it.lowercase() }.forEach { text ->
            val regexRuns = text.split(separators).filter { it.isNotEmpty() }
            assertEquals(text, regexRuns, GuideText.asciiRuns(text))
            regexRuns.forEach { token ->
                assertEquals(token, token.split(letterDigit).filter { it.isNotEmpty() }, GuideText.letterDigitPieces(token))
            }
        }
        assertEquals(listOf("espn", "2"), GuideText.letterDigitPieces("espn2"))
        assertEquals(listOf("a", "1", "b", "22", "c"), GuideText.letterDigitPieces("a1b22c"))
    }
}
