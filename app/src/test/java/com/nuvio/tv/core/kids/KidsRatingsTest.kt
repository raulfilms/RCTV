package com.nuvio.tv.core.kids

import com.nuvio.tv.core.kids.KidsRatings.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsRatingsTest {

    @Test
    fun `US movies up to PG-13`() {
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("US" to listOf("PG"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("US" to listOf("PG-13"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("US" to listOf("R"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("US" to listOf("NC-17"))))
    }

    @Test
    fun `US shows up to TV-PG`() {
        assertEquals(Verdict.ALLOWED, KidsRatings.show(mapOf("US" to listOf("TV-Y7"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.show(mapOf("US" to listOf("TV-PG"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.show(mapOf("US" to listOf("TV-14"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.show(mapOf("US" to listOf("TV-MA"))))
    }

    @Test
    fun `the strictest rating in a country counts`() {
        // A PG-13 movie with an R release (say, an unrated cut rated R) isn't for kids.
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("US" to listOf("PG-13", "R"))))
        // Unrated releases don't count.
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("US" to listOf("", "NR", "PG"))))
    }

    @Test
    fun `the US decides first, then other countries`() {
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("US" to listOf("PG"), "DE" to listOf("16"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("US" to listOf("NR"), "GB" to listOf("12A"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("GB" to listOf("15"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.show(mapOf("DE" to listOf("16"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.show(mapOf("DE" to listOf("6"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("MX" to listOf("B15"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("MX" to listOf("A"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("AU" to listOf("MA15+"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.movie(mapOf("BR" to listOf("L"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.movie(mapOf("BR" to listOf("14"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.show(mapOf("FR" to listOf("-10"))))
        assertEquals(Verdict.BLOCKED, KidsRatings.show(mapOf("KR" to listOf("15"))))
        assertEquals(Verdict.ALLOWED, KidsRatings.show(mapOf("KR" to listOf("12"))))
    }

    @Test
    fun `nobody rated it`() {
        assertEquals(Verdict.UNKNOWN, KidsRatings.movie(emptyMap()))
        assertEquals(Verdict.UNKNOWN, KidsRatings.show(mapOf("US" to listOf("NR"))))
        assertEquals(Verdict.UNKNOWN, KidsRatings.show(mapOf("XX" to listOf("R"))))
    }

    @Test
    fun `kids and family genres`() {
        assertTrue(KidsRatings.isKidsGenre(listOf("Animation", "Family")))
        assertTrue(KidsRatings.isKidsGenre(listOf("Kids")))
        assertTrue(KidsRatings.isKidsGenre(listOf("Familia")))
        // Animated isn't enough (South Park is animated too).
        assertFalse(KidsRatings.isKidsGenre(listOf("Animation", "Comedy")))
    }
}
