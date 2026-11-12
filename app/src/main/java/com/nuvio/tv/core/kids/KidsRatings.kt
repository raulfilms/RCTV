package com.nuvio.tv.core.kids

import java.util.Locale

/**
 * RCTV, Kids profiles: what a child up to 13 can watch, from a title's age ratings.
 *
 * Movies: G, PG and PG-13 (US); shows: TV-Y, TV-Y7, TV-G and TV-PG (TV-14 and up are not).
 * Without a US rating the first country that rates it decides (Canada, UK, Australia, Germany,
 * France, Spain, Mexico, Brazil, then any other rating that is an age). Within a country the
 * strictest rating counts. A title nobody rated is [Verdict.UNKNOWN]; the gate lets it through only
 * when it's a kids or family title ([isKidsGenre]).
 */
object KidsRatings {
    /** The oldest age a Kids profile is for. */
    const val MAX_AGE = 13

    enum class Verdict { ALLOWED, BLOCKED, UNKNOWN }

    private val COUNTRY_ORDER = listOf("US", "CA", "GB", "AU", "DE", "FR", "ES", "MX", "BR")

    private val US_MOVIE_OK = setOf("G", "PG", "PG-13")
    private val US_MOVIE_NO = setOf("R", "NC-17", "X")
    private val US_TV_OK = setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")
    private val US_TV_NO = setOf("TV-14", "TV-MA")

    /** Letter ratings of other countries (ages are read as numbers). */
    private val LETTERS: Map<String, Pair<Set<String>, Set<String>>> = mapOf(
        "CA" to (setOf("G", "PG", "C", "C8") to setOf("14A", "18A", "R", "A", "14+", "18+")),
        "GB" to (setOf("U", "UC", "PG", "12A") to setOf("R18")),
        "AU" to (setOf("G", "PG", "P", "C") to setOf("M", "MA15+", "MA 15+", "R18+", "X18+", "AV15+", "RC")),
        "FR" to (setOf("U", "TP") to emptySet()),
        "ES" to (setOf("A", "APTA", "TP", "AI", "7I") to setOf("X")),
        "MX" to (setOf("AA", "A", "B") to setOf("B15", "C", "D")),
        "BR" to (setOf("L", "LIVRE", "ER") to emptySet())
    )

    /** A movie's certifications by country (every release's). */
    fun movie(byCountry: Map<String, List<String>>): Verdict = decide(byCountry, tv = false)

    /** A show's ratings by country. */
    fun show(byCountry: Map<String, List<String>>): Verdict = decide(byCountry, tv = true)

    private fun decide(byCountry: Map<String, List<String>>, tv: Boolean): Verdict {
        val countries = byCountry.mapKeys { it.key.uppercase(Locale.ROOT) }
        val ordered = COUNTRY_ORDER.filter { it in countries } + countries.keys.filterNot { it in COUNTRY_ORDER }.sorted()
        for (country in ordered) {
            val verdicts = countries[country].orEmpty().map { verdict(country, it, tv) }
            if (Verdict.BLOCKED in verdicts) return Verdict.BLOCKED
            if (Verdict.ALLOWED in verdicts) return Verdict.ALLOWED
        }
        return Verdict.UNKNOWN
    }

    /** One rating, in its country. */
    fun verdict(country: String, rating: String, tv: Boolean): Verdict {
        val value = rating.trim().uppercase(Locale.ROOT)
        if (value.isEmpty() || value == "NR" || value == "UNRATED" || value == "NOT RATED") return Verdict.UNKNOWN
        when (country.uppercase(Locale.ROOT)) {
            "US" -> {
                val ok = if (tv) US_TV_OK else US_MOVIE_OK
                val no = if (tv) US_TV_NO else US_MOVIE_NO
                return when (value) {
                    in ok -> Verdict.ALLOWED
                    in no -> Verdict.BLOCKED
                    // A US show rated like a movie, or the other way round.
                    in US_MOVIE_OK, in US_TV_OK -> Verdict.ALLOWED
                    in US_MOVIE_NO, in US_TV_NO -> Verdict.BLOCKED
                    else -> Verdict.UNKNOWN
                }
            }
        }
        LETTERS[country.uppercase(Locale.ROOT)]?.let { (ok, no) ->
            if (value in ok) return Verdict.ALLOWED
            if (value in no) return Verdict.BLOCKED
        }
        return ageVerdict(value)
    }

    /** An age ("12", "+12", "-12", "12+", "TV-14"): up to [MAX_AGE] is allowed. */
    private fun ageVerdict(value: String): Verdict {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 2) return Verdict.UNKNOWN
        // Only a plain age: "12", "+12", "-12", "12+", "TV-14", "12A" (not "B15" or "MA15+", handled above).
        val letters = value.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters != "TV" && letters != "A") return Verdict.UNKNOWN
        return if (digits.toInt() <= MAX_AGE) Verdict.ALLOWED else Verdict.BLOCKED
    }

    private val KIDS_GENRES = setOf(
        "kids", "family", "children", "familia", "familiar", "infantil", "niños", "ninos", "infantiles"
    )

    /** A kids or family title by its genres (TMDB's "Kids" and "Family"). */
    fun isKidsGenre(genres: List<String>): Boolean =
        genres.any { it.trim().lowercase(Locale.ROOT) in KIDS_GENRES }
}
