package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.core.util.FastText

/**
 * Whether a release's title is the one asked for: the same words ("The Batman"), the same once
 * punctuation, articles and numbering are set aside ("Mission: Impossible – Part One" /
 * "Mission.Impossible.Part.1"), another of its names (a localized title), one inside the other, or
 * another title altogether. The year and the season/episode are checked by [SmartScoreEngine].
 */
object TitleMatcher {

    fun match(releaseTitle: String?, request: SmartSourceRequest): TitleMatch {
        val wanted = request.title?.takeIf { it.isNotBlank() } ?: return TitleMatch.UNKNOWN
        val found = releaseTitle?.takeIf { it.isNotBlank() } ?: return TitleMatch.UNKNOWN
        val foundNormal = normalize(found)
        if (foundNormal.isEmpty()) return TitleMatch.UNKNOWN
        val wantedNormal = normalize(wanted)
        if (foundNormal == wantedNormal) return TitleMatch.EXACT
        val foundLoose = loose(found)
        if (foundLoose.isNotEmpty() && foundLoose == loose(wanted)) return TitleMatch.NORMALIZED
        for (alias in request.alternateTitles) {
            if (alias.isBlank()) continue
            if (normalize(alias) == foundNormal || (foundLoose.isNotEmpty() && loose(alias) == foundLoose)) return TitleMatch.ALIAS
        }
        val candidates = listOf(wanted) + request.alternateTitles
        if (candidates.any { contains(loose(it), foundLoose) || contains(foundLoose, loose(it)) }) return TitleMatch.PARTIAL
        return TitleMatch.MISMATCH
    }

    /**
     * RCTV (owner's rule): a series' release whose title holds the one asked for, or is held in it
     * ([TitleMatch.PARTIAL]), is another show when the words that differ come after it: a spin-off
     * ("Below Deck" for "Below", "NCIS: Los Angeles" for "NCIS", "Daredevil: Born Again" for
     * "Daredevil") or the show it comes from ("NCIS" for "NCIS: Los Angeles"). Words before it are
     * its brand ("Marvel's Daredevil", "Star Wars: Andor" / "Andor"); a country, a number or an
     * episode's words after it are the same show ("The Office US", "Show Episode 4", "Frieren - 05").
     */
    fun anotherShow(releaseTitle: String?, request: SmartSourceRequest): Boolean {
        val found = releaseTitle?.let(::wordsOf)?.takeIf { it.isNotEmpty() } ?: return false
        val names = (listOfNotNull(request.title) + request.alternateTitles).map(::wordsOf).filter { it.isNotEmpty() }
        if (names.isEmpty()) return false
        return names.none { wanted -> sameShow(found, wanted) }
    }

    private fun sameShow(found: List<String>, wanted: List<String>): Boolean {
        if (found == wanted) return true
        val inFound = indexOf(found, wanted)
        if (inFound >= 0) return found.drop(inFound + wanted.size).all(::sameShowWord)
        val inWanted = indexOf(wanted, found)
        if (inWanted >= 0) return wanted.drop(inWanted + found.size).all(::sameShowWord)
        // Neither holds the other word for word: nothing here to tell them apart.
        return true
    }

    private fun sameShowWord(word: String): Boolean =
        word in COUNTRIES || word in EPISODE_WORDS || word.all(Char::isDigit)

    private fun wordsOf(text: String): List<String> = loose(text).split(' ').filter { it.isNotEmpty() }

    private fun indexOf(outer: List<String>, inner: List<String>): Int {
        if (inner.isEmpty() || inner.size > outer.size) return -1
        for (start in 0..outer.size - inner.size) {
            if (outer.subList(start, start + inner.size) == inner) return start
        }
        return -1
    }

    private val COUNTRIES = setOf("us", "usa", "uk", "au", "nz", "ca")
    private val EPISODE_WORDS = setOf(
        "episode", "ep", "episodio", "capitulo", "cap", "chapter", "part", "parte",
        "season", "temporada", "series", "serie", "vol", "volume", "complete", "completa"
    )

    /**
     * "Squidward's  Tiki-Land!" → "squidwards tiki land"; "Amélie & Co" → "amelie and co". RCTV
     * (MEM-07): without regular expressions (it runs for every source, each time the list changes;
     * see [FastText]), the same as
     * `NFD.replace("\p{Mn}+", "").lowercase().replace("&", " and ").replace("['’`´]", "")
     * .replace("[^\p{L}\p{N}]+", " ").replace("\s+", " ").trim()`.
     */
    fun normalize(text: String): String =
        FastText.letterDigitWords(text.replace("&", " and ").filterNot { it in APOSTROPHES })

    /** [normalize]d, without articles and "and", numbers as digits ("the matrix part ii" → "matrix part 2"). */
    fun loose(text: String): String =
        normalize(text).split(' ')
            .filter { it.isNotEmpty() && it !in ARTICLES && it != "and" && it != "y" }
            .map { ROMAN[it] ?: NUMBER_WORDS[it] ?: it }
            .joinToString(" ")

    /** [inner]'s words, in order, inside [outer]'s (a whole word at least three letters long). */
    private fun contains(outer: String, inner: String): Boolean {
        if (inner.length < 3 || outer.length <= inner.length) return false
        return " $outer ".contains(" $inner ")
    }

    private const val APOSTROPHES = "'’`´"
    private val ARTICLES = setOf("the", "a", "an", "el", "la", "los", "las", "le", "les", "der", "die", "das", "il", "lo", "o", "os", "as")
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "uno" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5"
    )
}
