package com.nuvio.tv.core.smartscraper

import java.text.Normalizer
import java.util.Locale

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

    /** "Squidward's  Tiki-Land!" → "squidwards tiki land"; "Amélie & Co" → "amelie and co". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(APOSTROPHES, "")
            .replace(NOT_ALPHANUMERIC, " ")
            .replace(SPACES, " ")
            .trim()

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

    private val MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['’`´]")
    private val NOT_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    private val ARTICLES = setOf("the", "a", "an", "el", "la", "los", "las", "le", "les", "der", "die", "das", "il", "lo", "o", "os", "as")
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "uno" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5"
    )
}
