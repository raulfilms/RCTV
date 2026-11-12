package com.nuvio.tv.data.livetv

import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (owner's report): "Dead Heat" on Starz Encore Action was the 2002 one, not the 1988 one. */
class LiveTitleCluesTest {

    private val start = 1_762_000_000_000L

    @Test
    fun `the description's summary and cast pick the right movie of the name, not the best known`() {
        val clues = LiveTitleClues.of(
            title = "Dead Heat",
            subtitle = null,
            description = "A retired policeman (Kiefer Sutherland) purchases a lucrative racehorse that catches the eye of mobsters.",
            categoryWords = emptyList(),
            startMs = start,
            endMs = start + 101 * 60_000L
        )
        val old = clues.score(
            isTv = false, year = 1988,
            overview = "A detective who returns from the dead teams with his partner to stop a mad scientist's zombies.",
            voteCount = 310, cast = listOf("treat williams", "joe piscopo")
        )
        val right = clues.score(
            isTv = false, year = 2002,
            overview = "A retired cop buys a racehorse and attracts the mobsters' attention.",
            voteCount = 25, cast = listOf("kiefer sutherland", "anthony lapaglia")
        )
        assertTrue("2002 $right > 1988 $old", right > old)
        // Even before the cast is looked up, the summary leads.
        assertTrue(
            clues.score(false, 2002, "A retired cop buys a racehorse and attracts the mobsters' attention.", 25, null) >
                clues.score(false, 1988, "A detective who returns from the dead teams with his partner.", 310, null)
        )
    }

    @Test
    fun `a year in the description picks that year's title`() {
        val clues = LiveTitleClues.of("Scarface", null, "Al Pacino stars in the 1983 remake.", emptyList(), start, start + 170 * 60_000L)
        assertTrue(clues.score(false, 1983, null, 20000, null) > clues.score(false, 1932, null, 1000, null))
    }

    @Test
    fun `an episode is the series of the name, a movie slot the movie`() {
        val episode = LiveTitleClues.of("Shogun", "Anjin", "S1 E1: A mysterious ship arrives.", emptyList(), start, start + 60 * 60_000L)
        assertTrue(episode.score(isTv = true, year = 2024, overview = null, voteCount = 100, cast = null) >
            episode.score(isTv = false, year = 1980, overview = null, voteCount = 5000, cast = null))
        val movie = LiveTitleClues.of("Fargo", null, "A car salesman hires two criminals.", listOf("movie"), start, start + 98 * 60_000L)
        assertTrue(movie.score(isTv = false, year = 1996, overview = null, voteCount = 100, cast = null) >
            movie.score(isTv = true, year = 2014, overview = null, voteCount = 5000, cast = null))
    }
}
