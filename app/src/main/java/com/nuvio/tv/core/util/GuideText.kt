package com.nuvio.tv.core.util

import java.util.Locale

/**
 * RCTV (MEM-07): the TV guide's text checks without regular expressions. Building the guide runs
 * these for every channel (its logo, country and categories, ~35 matches each) and every program
 * (its [LIVE]/[NEW] tags); on Android each match a Regex runs leaves native memory the garbage
 * collector barely sees (see [FastText]), and with a big IPTV list that alone filled a 2 GB TV.
 * Each one gives exactly what the expression it replaces gave (checked against it in the tests).
 */
object GuideText {

    /**
     * [name] (lowercase) in [textLower] as a whole word: no letter or number right before or after
     * it. Stands for `(?<![\p{L}\p{N}])(name)(?![\p{L}\p{N}])` (ignoring case: both lowercase).
     */
    fun containsWord(textLower: String, name: String): Boolean {
        if (name.isEmpty()) return false
        var from = 0
        while (true) {
            val index = textLower.indexOf(name, from)
            if (index < 0) return false
            val end = index + name.length
            val before = index == 0 || !isLetterOrNumber(textLower.codePointBefore(index))
            val after = end >= textLower.length || !isLetterOrNumber(textLower.codePointAt(end))
            if (before && after) return true
            from = index + 1
        }
    }

    /**
     * The US call signs in [text] ("WXYZ", "KABC" of "KABC-TV"), as `findAll` of
     * `(?<!B)([KW][A-Z]{2,3})(-(TV|DT|HD|LD|CD))?(?!B)` finds them, B being a letter or number
     * ([unicodeBoundaries]) or only `A-Za-z0-9`; [suffixes]: null when the expression has none.
     */
    fun callSigns(text: String, unicodeBoundaries: Boolean, suffixes: Set<String>?): List<String> {
        val found = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val match = callSignAt(text, start, unicodeBoundaries, suffixes)
            if (match == null) {
                start++
            } else {
                found += text.substring(start, match.first)
                start = match.second
            }
        }
        return found
    }

    /** The call sign starting at [start]: its letters' end and the match's end; null when none starts there. */
    private fun callSignAt(text: String, start: Int, unicode: Boolean, suffixes: Set<String>?): Pair<Int, Int>? {
        val first = text[start]
        if (first != 'K' && first != 'W') return null
        if (start > 0 && isBoundary(text.codePointBefore(start), unicode)) return null
        for (letters in 3 downTo 2) {
            val lettersEnd = start + 1 + letters
            if (lettersEnd > text.length) continue
            if ((start + 1 until lettersEnd).any { text[it] !in 'A'..'Z' }) continue
            if (suffixes != null && lettersEnd + 3 <= text.length && text[lettersEnd] == '-' &&
                text.substring(lettersEnd + 1, lettersEnd + 3) in suffixes
            ) {
                val end = lettersEnd + 3
                if (end >= text.length || !isBoundary(text.codePointAt(end), unicode)) return lettersEnd to end
            }
            if (lettersEnd >= text.length || !isBoundary(text.codePointAt(lettersEnd), unicode)) return lettersEnd to lettersEnd
        }
        return null
    }

    /** A leading country tag "US |", "|UK|", "USA -": `^[\s|\[(]*([A-Z]{2,3})[\s\])]*[|:\-–]`'s code. */
    fun leadingCountryTag(text: String): String? {
        var i = 0
        while (i < text.length && (isSpace(text[i]) || text[i] == '|' || text[i] == '[' || text[i] == '(')) i++
        val start = i
        while (i < text.length && text[i] in 'A'..'Z') i++
        if (i - start !in 2..3) return null
        var j = i
        while (j < text.length && (isSpace(text[j]) || text[j] == ']' || text[j] == ')')) j++
        return if (j < text.length && text[j] in COUNTRY_SEPARATORS) text.substring(start, i) else null
    }

    /** A leading bracketed country tag "[MX]", "|US|": `^[\s]*[|\[(]([A-Z]{2,3})[|\])]`'s code. */
    fun bracketCountryTag(text: String): String? {
        var i = 0
        while (i < text.length && isSpace(text[i])) i++
        if (i >= text.length || (text[i] != '|' && text[i] != '[' && text[i] != '(')) return null
        i++
        val start = i
        while (i < text.length && text[i] in 'A'..'Z') i++
        if (i - start !in 2..3) return null
        return if (i < text.length && (text[i] == '|' || text[i] == ']' || text[i] == ')')) text.substring(start, i) else null
    }

    /**
     * "US - FOX News": the code and where the name starts after it, as `^([A-Z]{2,3})\s*[-:|]\s+`
     * matches it (the second is the match's end); null when it doesn't.
     */
    fun countryPrefix(text: String): Pair<String, Int>? {
        var i = 0
        while (i < text.length && text[i] in 'A'..'Z') i++
        if (i !in 2..3) return null
        val code = text.substring(0, i)
        var j = i
        while (j < text.length && isSpace(text[j])) j++
        if (j >= text.length || (text[j] != '-' && text[j] != ':' && text[j] != '|')) return null
        j++
        val spaceStart = j
        while (j < text.length && isSpace(text[j])) j++
        return if (j > spaceStart) code to j else null
    }

    /** "[LIVE]", "[ new ]" in [text], any case: `\[\s*word\s*]` (ignoring case). */
    fun hasBracketTag(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val open = text.indexOf('[', from)
            if (open < 0) return false
            var i = open + 1
            while (i < text.length && isSpace(text[i])) i++
            if (text.regionMatches(i, word, 0, word.length, ignoreCase = true)) {
                var j = i + word.length
                while (j < text.length && isSpace(text[j])) j++
                if (j < text.length && text[j] == ']') return true
            }
            from = open + 1
        }
    }

    /** A guide channel number, all of [text]: `\d{1,5}(\.\d{1,3})?` matching it whole. */
    fun isChannelNumber(text: String): Boolean {
        var i = 0
        while (i < text.length && Character.isDigit(text[i])) i++
        if (i !in 1..5) return false
        if (i == text.length) return true
        if (text[i] != '.') return false
        val start = i + 1
        var j = start
        while (j < text.length && Character.isDigit(text[j])) j++
        return j == text.length && j - start in 1..3
    }

    /**
     * An #EXTINF line's attributes (`tvg-logo="…"`), names lowercase, the last one of a name winning:
     * what `findAll("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")` gave.
     */
    fun m3uAttributes(text: String): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        var i = 0
        while (i < text.length) {
            if (!isAttributeNameChar(text[i])) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isAttributeNameChar(text[end])) end++
            if (end + 1 < text.length && text[end] == '=' && text[end + 1] == '"') {
                val close = text.indexOf('"', end + 2)
                if (close >= 0) {
                    attributes[text.substring(i, end).lowercase()] = text.substring(end + 2, close)
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return attributes
    }

    /** "/movie/", "/movies/" or "/series/" in [text], any case. */
    fun hasVodPathSegment(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return VOD_SEGMENTS.any { it in lower }
    }

    /** A video file's extension at the end of [text] (before a query, if any), any case. */
    fun hasVodFileExtension(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return VOD_EXTENSIONS.any { ext -> lower.endsWith(".$ext") || lower.contains(".$ext?") }
    }

    /** The runs of `a-z0-9` in [text] (`split("[^a-z0-9]+")`, empty ones dropped). */
    fun asciiRuns(text: String): List<String> {
        val runs = ArrayList<String>()
        var start = -1
        text.forEachIndexed { i, c ->
            val keep = c in 'a'..'z' || c in '0'..'9'
            if (keep && start < 0) start = i
            if (!keep && start >= 0) {
                runs += text.substring(start, i)
                start = -1
            }
        }
        if (start >= 0) runs += text.substring(start)
        return runs
    }

    /** "espn2" → "espn", "2": split where letters meet digits (`split("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")`). */
    fun letterDigitPieces(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        var start = 0
        for (i in 1 until token.length) {
            val a = token[i - 1]
            val b = token[i]
            val split = (a in 'a'..'z' && b in '0'..'9') || (a in '0'..'9' && b in 'a'..'z')
            if (split) {
                pieces += token.substring(start, i)
                start = i
            }
        }
        pieces += token.substring(start)
        return pieces.filter { it.isNotEmpty() }
    }

    private const val COUNTRY_SEPARATORS = "|:-–"
    private val VOD_SEGMENTS = listOf("/movie/", "/movies/", "/series/")
    private val VOD_EXTENSIONS = listOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")

    private fun isAttributeNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private fun isBoundary(codePoint: Int, unicode: Boolean): Boolean =
        if (unicode) isLetterOrNumber(codePoint)
        else codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code || codePoint in '0'.code..'9'.code

    private fun isLetterOrNumber(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** `\s` as ICU has it (Unicode spaces too). */
    private fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)
}
