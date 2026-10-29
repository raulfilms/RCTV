package com.nuvio.tv.core.smartscraper

import java.util.Locale

/**
 * RCTV: the title a source's file is of, as the player's list of sources shows it under its quality:
 * "The Batman (2022)", "The Last of Us · S1 E3". The title's own name when the file's is it (the
 * same words, whatever their case or punctuation), else the file's own name; the file's year, else
 * the title's. Read from the file's name only, never from the rest of what its addon wrote.
 */
object SourceTitleLine {

    /**
     * [name] and [year]: the title playing; [season] and [episode]: the episode playing (null for a
     * movie). [clean]: what the file's own name goes through before it's shown (the title's never
     * does). Null when neither the file nor the title has a name.
     */
    fun of(
        release: ParsedRelease,
        name: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        clean: (String) -> String = { it }
    ): String? {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() }
        val fileTitle = release.title?.trim()?.takeIf { it.isNotEmpty() }
        val shown = when {
            fileTitle == null -> wanted
            wanted != null && sameTitle(fileTitle, wanted) -> wanted
            else -> clean(titleCase(fileTitle)).trim().takeIf { it.isNotEmpty() } ?: wanted
        } ?: return null
        if (season != null && episode != null) return "$shown · S$season E$episode"
        val shownYear = release.year ?: year
        return if (shownYear != null) "$shown ($shownYear)" else shown
    }

    private fun sameTitle(a: String, b: String): Boolean {
        if (TitleMatcher.normalize(a) == TitleMatcher.normalize(b)) return true
        val looseA = TitleMatcher.loose(a)
        return looseA.isNotEmpty() && looseA == TitleMatcher.loose(b)
    }

    /** "the batman part ii" → "The Batman Part II" (the parser gives a file's title in lowercase). */
    fun titleCase(text: String): String =
        text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            if (word in ROMAN) word.uppercase(Locale.ROOT)
            else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

    private val ROMAN = setOf("ii", "iii", "iv", "vi", "vii", "viii", "ix", "xi", "xii")
}
