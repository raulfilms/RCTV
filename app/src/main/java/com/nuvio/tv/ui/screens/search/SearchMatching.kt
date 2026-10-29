package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.util.FastText

/**
 * RCTV: how well a name (a channel, a team, a league, a program in the guide) matches what's typed
 * in Search, without accents, case or punctuation ("atletico" finds "Atlético de Madrid", "espn"
 * finds "ESPN2"). Lower is better; null is no match.
 */
internal object SearchMatching {

    /** The name is what's typed ("NBA" for "nba"). */
    const val EXACT = 0

    /** The name starts with it ("ESPN Deportes" for "espn"). */
    const val PREFIX = 1

    /** It's one or more of the name's whole words ("Los Angeles Lakers" for "lakers"). */
    const val WHOLE_WORDS = 2

    /** Each word typed starts one of the name's words ("Los Angeles Lakers" for "la lak"). */
    const val WORD_STARTS = 3

    /** It's somewhere in the name (3 letters or more: "Manchester United" for "chester"). */
    const val INSIDE = 4

    /** A match this good puts its row above the movies and series. */
    const val STRONG = WHOLE_WORDS

    /**
     * "Atlético de Madrid!" → "atletico de madrid".
     *
     * RCTV (MEM-07): without regular expressions: Search runs this for every channel and every
     * program title of the guide (tens of thousands), and on Android each match a Regex runs leaves
     * native memory the garbage collector barely sees (over 1 GB in seconds on a Mi Box 4).
     */
    fun normalize(text: String): String = FastText.letterDigitWords(text)

    /** How [name] matches [query] (both already [normalize]d); null when it doesn't. */
    fun rank(name: String, query: String): Int? {
        if (query.isEmpty() || name.isEmpty()) return null
        if (name == query) return EXACT
        if (name.startsWith(query)) return PREFIX
        val padded = " $name "
        if (padded.contains(" $query ")) return WHOLE_WORDS
        val queryWords = query.split(' ')
        val nameWords = name.split(' ').toMutableList()
        val everyWord = queryWords.all { word ->
            val index = nameWords.indexOfFirst { it.startsWith(word) }
            if (index >= 0) nameWords.removeAt(index)
            index >= 0
        }
        if (everyWord) return WORD_STARTS
        if (query.length >= 3 && name.contains(query)) return INSIDE
        return null
    }

    /** The best of [names]' matches (a team's name, its short name...); null when none matches. */
    fun bestRank(names: Iterable<String>, query: String): Int? =
        names.mapNotNull { rank(it, query) }.minOrNull()
}
