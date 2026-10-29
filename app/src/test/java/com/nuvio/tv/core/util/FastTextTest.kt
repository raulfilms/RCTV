package com.nuvio.tv.core.util

import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * MEM-07: the regex-free normalizers give exactly what the regular expressions they replace gave
 * (checked against them, on names from a TV guide, teams, and the awkward cases).
 */
class FastTextTest {

    private val samples = listOf(
        "", " ", "Chicago Bulls", "  Atlético de Madrid!! ", "Atlético-Madrid FC", "Man Utd vs. Man City",
        "ESPN2 HD (Alternate)", "US: ESPN FHD", "Fox News & Co.", "Real Madrid C.F. — Barcelona",
        "Niño, Peña y Ñandú", "Straße München", "İstanbul Başakşehir", "Ελλάδα ΑΕΚ", "Спартак Москва",
        "東京ヴェルディ FC", "الأهلي", "😀 Party 🎉 Time", "Ⅻ ² ½ 3rd", "tab\tnew\nline\rend",
        "é combining", " no-break space", "___under_score___", "café-crème brûlée",
        "NBA: Lakers @ Celtics (Live)", "Liga MX • América vs Chivas", "Ü-18 Länderspiel", "ﬁnal ﬂight"
    )

    @Test
    fun `asciiWords is the sports guide normalize`() {
        fun regex(text: String): String {
            val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()
        }
        samples.forEach { assertEquals("\"$it\"", regex(it), FastText.asciiWords(it)) }
        assertEquals("atletico de madrid", FastText.asciiWords("  Atlético de Madrid!! "))
    }

    @Test
    fun `asciiCompact is the guide library's title key`() {
        fun regex(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")
        samples.forEach { assertEquals("\"$it\"", regex(it), FastText.asciiCompact(it)) }
        assertEquals("foxnewsandco", FastText.asciiCompact("Fox News & Co."))
    }

    @Test
    fun `letterDigitWords is Search's normalize, in any script`() {
        fun regex(text: String): String {
            val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return plain.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        }
        samples.forEach { assertEquals("\"$it\"", regex(it), FastText.letterDigitWords(it)) }
        assertEquals("東京 fc", FastText.letterDigitWords("東京 FC!"))
    }

    @Test
    fun `letterDigitSplit and whitespace helpers match their expressions`() {
        samples.forEach { text ->
            assertEquals("\"$text\"", text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }, FastText.letterDigitSplit(text))
            // The JVM's \s is ASCII-only (ICU's, on Android, also takes Unicode spaces): compared on ASCII spaces.
            val ascii = text.replace(' ', ' ')
            assertEquals("\"$text\"", ascii.split(Regex("\\s+")).filter { it.isNotBlank() }, FastText.whitespaceSplit(ascii))
            assertEquals("\"$text\"", ascii.replace(Regex("\\s+"), " "), FastText.collapseWhitespace(ascii))
            assertEquals("\"$text\"", text.replace(Regex("""\([^)]*\)"""), " "), FastText.withoutParentheses(text))
        }
        assertEquals("no-break space", FastText.collapseWhitespace("no-break space"))
        assertEquals("a   d)", FastText.withoutParentheses("a (b (c) d)"))
        assertEquals("x (unclosed", FastText.withoutParentheses("x (unclosed"))
    }

    @Test
    fun `withoutWords drops whole words only, wordsOf finds regex words`() {
        fun regex(text: String) = text.replace(Regex("\\b(fc|cf|afc|sc)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        listOf("arsenal fc", "fc barcelona", "afc bournemouth", "sc freiburg", "fcx united", "real sociedad", "fc", "")
            .forEach { assertEquals(it, regex(it), FastText.withoutWords(it, setOf("fc", "cf", "afc", "sc"))) }
        val adult = Regex("(?i)\\b(xxx|adults?|porn\\w*|erotic\\w*)\\b")
        fun fast(text: String) = FastText.wordsOf(text).any {
            it == "xxx" || it == "adult" || it == "adults" || it.startsWith("porn") || it.startsWith("erotic")
        }
        listOf(
            "XXX Channel", "Adults only", "ADULT TV", "Pornhub", "Erotica Night", "xxxtentacion", "Adulthood",
            "Sports XXL", "Teleporno", "kids", "erotic", "EN | Adult", "PORN_HD", "Adults-18"
        ).forEach { assertEquals(it, adult.containsMatchIn(it), fast(it)) }
    }

    @Test
    fun `ASCII text isn't decomposed`() {
        val text = "Chicago Bulls 2026"
        assertSame(text, FastText.stripAccents(text))
        assertEquals("Atletico", FastText.stripAccents("Atlético"))
    }
}
