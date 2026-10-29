package com.nuvio.tv.core.util

import java.text.Normalizer
import java.util.Locale

/**
 * RCTV (MEM-07): text made comparable without regular expressions.
 *
 * On Android a Regex is ICU's, and its pattern and each match it runs live in native memory that the
 * garbage collector barely sees: they're counted as a few bytes, so collections don't come any
 * sooner for them. Run over a TV guide's tens of thousands of programs (Sports looking for the
 * channel showing each game, Search indexing the guide), that left over 1 GB of native garbage on
 * a 2 GB Mi Box 4 within seconds, and the system closed the app (it fell to ~350 MB once a
 * collection finally ran). These do the same in plain Kotlin: what they leave is ordinary Java
 * memory, collected as it should be. Each says which expression it stands for.
 */
object FastText {

    /**
     * Accents off ("Atlético" → "Atletico"): the canonical decomposition, its combining marks
     * dropped. [allMarks]: every mark (`\p{M}`); else only the non-spacing ones (`\p{Mn}`).
     * ASCII text is returned as it is (nothing in it decomposes).
     */
    fun stripAccents(text: String, allMarks: Boolean = false): String {
        if (isAscii(text)) return text
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val codePoint = decomposed.codePointAt(i)
            if (!isMark(codePoint, allMarks)) out.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /**
     * "Atlético-Madrid FC!" → "atletico madrid fc": accents off (`\p{Mn}`), lowercase, every run of
     * anything but `a-z0-9` one space, trimmed. Stands for
     * `NFD.replace("\p{Mn}+", "").lowercase().replace("[^a-z0-9]+", " ").replace("\s+", " ").trim()`.
     */
    fun asciiWords(text: String): String =
        joinRuns(stripAccents(text).lowercase(Locale.ROOT), ' ') { c -> c in 'a'..'z' || c in '0'..'9' }

    /**
     * "Fox News & Co." → "foxnewsandco": accents off (`\p{M}`), lowercase, "&" as "and", only `a-z0-9`
     * left. Stands for `NFD.replace("\p{M}+", "").lowercase().replace("&", "and").replace("[^a-z0-9]+", "")`.
     */
    fun asciiCompact(text: String): String {
        val lower = stripAccents(text, allMarks = true).lowercase(Locale.US).replace("&", "and")
        val out = StringBuilder(lower.length)
        lower.forEach { c -> if (c in 'a'..'z' || c in '0'..'9') out.append(c) }
        return out.toString()
    }

    /**
     * "Atlético de Madrid!" → "atletico de madrid", in any script ("東京 FC" → "東京 fc"): accents off
     * (`\p{Mn}`), lowercase, every run of anything but letters and numbers one space, trimmed.
     * Stands for `NFD.replace("\p{Mn}+", "").lowercase().replace("[^\p{L}\p{N}]+", " ").trim()`.
     */
    fun letterDigitWords(text: String): String {
        val lower = stripAccents(text).lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        var pendingSpace = false
        var i = 0
        while (i < lower.length) {
            val codePoint = lower.codePointAt(i)
            if (isLetterOrNumber(codePoint)) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(codePoint)
            } else {
                pendingSpace = true
            }
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /** [text]'s runs of letters and numbers, as they are (`split("[^\p{L}\p{N}]+")`, empty ones dropped). */
    fun letterDigitSplit(text: String): List<String> {
        val words = ArrayList<String>()
        var start = -1
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (isLetterOrNumber(codePoint)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                words += text.substring(start, i)
                start = -1
            }
            i += Character.charCount(codePoint)
        }
        if (start >= 0) words += text.substring(start)
        return words
    }

    /** [text] split on runs of white space (`split("\s+")`, empty ones dropped). */
    fun whitespaceSplit(text: String): List<String> {
        val words = ArrayList<String>()
        var start = -1
        text.forEachIndexed { i, c ->
            if (isSpace(c)) {
                if (start >= 0) {
                    words += text.substring(start, i)
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) words += text.substring(start)
        return words
    }

    /** Every run of white space as one space (`replace("\s+", " ")`), not trimmed. */
    fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var inSpace = false
        text.forEach { c ->
            if (isSpace(c)) {
                if (!inSpace) out.append(' ')
                inSpace = true
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString()
    }

    /**
     * Words of [text] (already [asciiWords]: one space between them) without [drop]'s:
     * "arsenal fc" without "fc" → "arsenal". Stands for `replace("\b(fc|...)\b", " ")` then
     * collapsing and trimming the spaces.
     */
    fun withoutWords(text: String, drop: Set<String>): String =
        text.split(' ').filter { it.isNotEmpty() && it !in drop }.joinToString(" ")

    /**
     * The words of [text], lowercased, as a regular expression's `\w+` runs would find them
     * (letters, digits, marks, `_`).
     */
    fun wordsOf(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        var start = -1
        var i = 0
        while (i < lower.length) {
            val codePoint = lower.codePointAt(i)
            if (isWordChar(codePoint)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                words += lower.substring(start, i)
                start = -1
            }
            i += Character.charCount(codePoint)
        }
        if (start >= 0) words += lower.substring(start)
        return words
    }

    /** The parts of [text] inside parentheses taken out, each as one space (`replace("\([^)]*\)", " ")`). */
    fun withoutParentheses(text: String): String {
        if ('(' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '(') {
                val close = text.indexOf(')', i + 1)
                if (close < 0) {
                    out.append(text, i, text.length)
                    break
                }
                out.append(' ')
                i = close + 1
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun joinRuns(text: String, separator: Char, keep: (Char) -> Boolean): String {
        val out = StringBuilder(text.length)
        var pendingSeparator = false
        text.forEach { c ->
            if (keep(c)) {
                if (pendingSeparator && out.isNotEmpty()) out.append(separator)
                pendingSeparator = false
                out.append(c)
            } else {
                pendingSeparator = true
            }
        }
        return out.toString()
    }

    private fun isAscii(text: String): Boolean {
        for (c in text) if (c.code >= 0x80) return false
        return true
    }

    private fun isMark(codePoint: Int, allMarks: Boolean): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            (allMarks && (type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()))
    }

    /** `\p{L}` or `\p{N}`. */
    private fun isLetterOrNumber(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** `\w` as ICU has it: letters, digits, marks, connector punctuation (`_`). */
    private fun isWordChar(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.LETTER_NUMBER.toInt() -> true
            else -> false
        }

    /** `\s` as ICU has it: tabs, line breaks and every Unicode space (a no-break space too). */
    private fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)
}
