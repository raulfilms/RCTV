package com.nuvio.tv.data.livetv

import java.text.Normalizer
import java.util.Locale
import kotlin.math.log10

/**
 * RCTV (owner's rule): which of the titles of the same name a guide program is ("Dead Heat" of 1988
 * or of 2002; "Shōgun" the movie or the series), from what the guide says of it:
 * - a movie or an episode (its category, an episode name, "S1 E4", how long it runs);
 * - a year it mentions;
 * - its description's words found in the title's TMDB summary;
 * - a cast member it names ("A retired policeman (Kiefer Sutherland)...").
 * How well known a title is only settles a tie.
 */
internal class LiveTitleClues(
    private val looksLikeMovie: Boolean,
    private val looksLikeEpisode: Boolean,
    private val slotMinutes: Long,
    private val years: Set<Int>,
    private val words: Set<String>,
    /** Names the description gives ("kiefer sutherland"), to find among a title's cast. */
    val names: Set<String>
) {
    /** How well a title fits; [cast] (its cast's names, as [normalizeName] makes them) when looked up. */
    fun score(isTv: Boolean, year: Int?, overview: String?, voteCount: Int?, cast: List<String>?): Double {
        var score = 0.0
        if (looksLikeMovie) score += if (isTv) -3.0 else 3.0
        if (looksLikeEpisode) score += if (isTv) 4.0 else -4.0
        if (!looksLikeEpisode && slotMinutes >= MOVIE_SLOT_MINUTES) score += if (isTv) -1.0 else 1.0
        if (!looksLikeMovie && slotMinutes in 1..EPISODE_SLOT_MINUTES) score += if (isTv) 1.0 else -0.5
        if (year != null && year in years) score += 6.0
        val summary = wordsOf(overview)
        score += minOf(9.0, 1.5 * words.count { it in summary })
        if (cast != null && names.any { it in cast }) score += 8.0
        score += 0.5 * log10((voteCount ?: 0) + 1.0)
        return score
    }

    companion object {
        /** The best-scored title must lead the next by this much, or the cast is looked up. */
        const val CLEAR_LEAD = 6.0
        private const val MOVIE_SLOT_MINUTES = 85L
        private const val EPISODE_SLOT_MINUTES = 65L

        private val EPISODE_MARK = Regex("""(?i)\bS\d{1,2}\s?E\d{1,3}\b|\bseason\s+\d|\bepisode\s+\d|\bep\.?\s?\d""")
        private val YEAR = Regex("""\b(19[2-9]\d|20[0-4]\d)\b""")
        private val NAME = Regex("""\b([A-Z][a-z'’]+(?:[ -][A-Z][a-z'’]+){1,2})\b""")
        private val STOP = setOf(
            "about", "after", "again", "against", "along", "among", "another", "around", "because", "before",
            "being", "between", "comes", "could", "during", "every", "first", "found", "their", "there", "these",
            "thing", "things", "those", "through", "under", "until", "which", "while", "where", "whose", "would",
            "should", "other", "story", "years", "young", "takes", "become", "becomes", "finds", "gets", "tries",
            "trying", "after", "later", "himself", "herself", "themselves", "world", "people", "together"
        )

        fun of(
            title: String,
            subtitle: String?,
            description: String?,
            categoryWords: List<String>,
            startMs: Long,
            endMs: Long
        ): LiveTitleClues {
            val text = description.orEmpty()
            val looksLikeMovie = categoryWords.any { "movie" in it || "film" in it }
            val looksLikeEpisode = !subtitle.isNullOrBlank() ||
                EPISODE_MARK.containsMatchIn(title) || EPISODE_MARK.containsMatchIn(text) ||
                categoryWords.any { "series" in it || "episode" in it || "sitcom" in it }
            return LiveTitleClues(
                looksLikeMovie = looksLikeMovie,
                looksLikeEpisode = looksLikeEpisode && !looksLikeMovie,
                slotMinutes = ((endMs - startMs) / 60_000L).coerceAtLeast(0L),
                years = YEAR.findAll(text).mapNotNull { it.value.toIntOrNull() }.toSet(),
                words = wordsOf(text),
                names = NAME.findAll(text).map { normalizeName(it.value) }.filter { it.contains(' ') }.toSet()
            )
        }

        fun wordsOf(text: String?): Set<String> =
            normalizeName(text.orEmpty()).split(' ').filterTo(HashSet()) { it.length >= 5 && it !in STOP }

        /** "Kiefer  Sutherland" → "kiefer sutherland" (no accents, no punctuation). */
        fun normalizeName(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}
