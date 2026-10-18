package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsEvent
import java.time.LocalDate
import java.time.MonthDay

/**
 * Leagues made of other leagues' events: tennis' Grand Slams (from the ATP's and the WTA's) and
 * golf's majors (from the PGA Tour's). They have no feed of their own, so they're left out where
 * every league is gathered (their events are already there, under their tour).
 */
internal object VirtualLeagues {

    /**
     * A tournament as a league: the tours it's in ([parents], league paths of the same sport), how
     * its events are known ([pattern], on the tournament's name), and when it's played every year
     * ([window], to find its last edition between them).
     */
    data class VirtualLeague(
        val sportPath: String,
        val leaguePath: String,
        val name: String,
        val parents: List<String>,
        private val pattern: Regex,
        val logo: String,
        val window: Pair<MonthDay, MonthDay>?
    ) {
        /** The event is this tournament's: a golf tournament by its name, a tennis match by its tournament's. */
        fun matches(event: SportsEvent): Boolean {
            val tournament = if (sportPath == TENNIS) event.venue?.substringBefore(" • ") else event.name
            return tournament != null && pattern.containsMatchIn(tournament.trim())
        }

        /** Its last edition's days (to show its final days between editions). */
        fun lastEdition(today: LocalDate): Pair<LocalDate, LocalDate>? {
            val (start, end) = window ?: return null
            var year = today.year
            if (end.atYear(year).isAfter(today)) year--
            return start.atYear(year) to end.atYear(year)
        }
    }

    const val TENNIS = "tennis"

    private fun favicon(domain: String): String = "https://www.google.com/s2/favicons?domain=$domain&sz=256"

    private fun words(text: String) = Regex(text, RegexOption.IGNORE_CASE)

    val LEAGUES: List<VirtualLeague> = listOf(
        // Tennis' Grand Slams: the men's and women's singles.
        VirtualLeague(
            TENNIS, "australian-open", "Australian Open", listOf("atp", "wta"),
            words("""^Australian Open\b"""), favicon("ausopen.com"),
            MonthDay.of(1, 10) to MonthDay.of(2, 2)
        ),
        VirtualLeague(
            TENNIS, "roland-garros", "Roland-Garros", listOf("atp", "wta"),
            words("""^(Roland[- ]Garros|French Open)\b"""), favicon("rolandgarros.com"),
            MonthDay.of(5, 18) to MonthDay.of(6, 10)
        ),
        VirtualLeague(
            TENNIS, "wimbledon", "Wimbledon", listOf("atp", "wta"),
            words("""^(Wimbledon|The Championships)\b"""), favicon("wimbledon.com"),
            MonthDay.of(6, 26) to MonthDay.of(7, 16)
        ),
        VirtualLeague(
            TENNIS, "us-open", "US Open", listOf("atp", "wta"),
            words("""^U\.?S\.? Open\b"""), favicon("usopen.org"),
            MonthDay.of(8, 22) to MonthDay.of(9, 14)
        ),
        // Golf's majors (the men's).
        VirtualLeague(
            EspnSportsClient.GOLF, "masters", "The Masters", listOf("pga"),
            words("""^(The )?Masters( Tournament)?$"""), favicon("masters.com"), null
        ),
        VirtualLeague(
            EspnSportsClient.GOLF, "pga-championship", "PGA Championship", listOf("pga"),
            words("""^PGA Championship$"""), favicon("pgachampionship.com"), null
        ),
        VirtualLeague(
            EspnSportsClient.GOLF, "golf-us-open", "U.S. Open (Golf)", listOf("pga"),
            words("""^U\.?S\.? Open( Championship)?$"""), favicon("usopen.com"), null
        ),
        VirtualLeague(
            EspnSportsClient.GOLF, "the-open", "The Open Championship", listOf("pga"),
            words("""^The Open( Championship)?$"""), favicon("theopen.com"), null
        )
    )

    val LEAGUE_PATHS: Set<String> = LEAGUES.map { it.leaguePath }.toSet()

    fun of(sportPath: String, leaguePath: String): VirtualLeague? =
        LEAGUES.firstOrNull { it.sportPath == sportPath && it.leaguePath == leaguePath }
}
