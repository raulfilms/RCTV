package com.nuvio.tv.core.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher

/**
 * RCTV (Phase 2, F4): a regular expression that gives the same answers as [Regex], without leaving
 * native memory behind on every call.
 *
 * On Android, `java.util.regex` is ICU's C++ engine: every `Regex.find`, `containsMatchIn`,
 * `replace` and every step of `findAll` creates a new `Matcher`, and each one holds a native ICU
 * matcher plus a native copy of the text it searched. Android counts each of them as a few bytes,
 * so they pile up until an unrelated collection runs. Parsing thousands of stream names with
 * dozens of patterns each (the Smart Scraper, Auto Stream, Detail's editions) left hundreds of
 * megabytes of such garbage on a Mi Box 4.
 *
 * Here each pattern keeps a few matchers and reuses them: `Matcher.reset(text)` replaces the
 * previous text's native copy in place (freed at once), and the text is dropped again after each
 * use, so nothing large stays behind. Results are copied out ([Match]) before the matcher is
 * reused, so a result never changes afterwards. Thread-safe: a matcher is used by one call at a
 * time. Semantics are java.util.regex's, exactly as [Regex] uses them.
 */
class PooledRegex(pattern: String, options: Set<RegexOption> = emptySet()) {

    constructor(pattern: String, option: RegexOption) : this(pattern, setOf(option))

    /** The compiled pattern, as [Regex] compiles it. */
    private val compiled = Regex(pattern, options).toPattern()
    private val idle = ConcurrentLinkedQueue<Matcher>()
    private val idleCount = AtomicInteger(0)

    /** A match copied out of the matcher: what [MatchResult] gives, detached from it. */
    class Match(val value: String, val range: IntRange, val groupValues: List<String>)

    fun containsMatchIn(input: CharSequence): Boolean = use(input) { it.find() }

    fun matches(input: CharSequence): Boolean = use(input) { it.matches() }

    fun find(input: CharSequence, startIndex: Int = 0): Match? {
        if (startIndex < 0 || startIndex > input.length) {
            throw IndexOutOfBoundsException("Start index out of bounds: $startIndex, input length: ${input.length}")
        }
        return use(input) { matcher -> if (matcher.find(startIndex)) matcher.toMatch() else null }
    }

    /** Every match from [startIndex], as [Regex.findAll] finds them (an empty match moves one character on). */
    fun findAll(input: CharSequence, startIndex: Int = 0): Sequence<Match> {
        if (startIndex < 0 || startIndex > input.length) {
            throw IndexOutOfBoundsException("Start index out of bounds: $startIndex, input length: ${input.length}")
        }
        val found = use(input) { matcher ->
            val matches = ArrayList<Match>()
            var from = startIndex
            while (from <= input.length && matcher.find(from)) {
                matches += matcher.toMatch()
                val end = matcher.end()
                from = if (end == matcher.start()) end + 1 else end
            }
            matches
        }
        return found.asSequence()
    }

    /** [input] with every match replaced by [replacement] (`$1` and `\` work as in [Regex.replace]). */
    fun replace(input: CharSequence, replacement: String): String = use(input) { it.replaceAll(replacement) }

    /** [input] with every match replaced by what [transform] makes of it, as [Regex.replace] does. */
    fun replace(input: CharSequence, transform: (Match) -> CharSequence): String {
        val matches = findAll(input).toList()
        if (matches.isEmpty()) return input.toString()
        val out = StringBuilder(input.length)
        var last = 0
        for ((index, match) in matches.withIndex()) {
            // As Regex.replace: after the first, a match counts only while text is left.
            if (index > 0 && last >= input.length) break
            out.append(input, last, match.range.first)
            out.append(transform(match))
            last = match.range.last + 1
        }
        if (last < input.length) out.append(input, last, input.length)
        return out.toString()
    }

    private inline fun <T> use(input: CharSequence, block: (Matcher) -> T): T {
        val matcher = idle.poll()?.also { idleCount.decrementAndGet() }?.reset(input) ?: compiled.matcher(input)
        try {
            return block(matcher)
        } finally {
            // The text's native copy goes now, not with a later collection.
            matcher.reset("")
            if (idleCount.incrementAndGet() <= MAX_IDLE) idle.offer(matcher) else idleCount.decrementAndGet()
        }
    }

    private fun Matcher.toMatch(): Match {
        val groups = ArrayList<String>(groupCount() + 1)
        for (i in 0..groupCount()) groups += group(i).orEmpty()
        return Match(value = group(), range = start() until end(), groupValues = groups)
    }

    override fun toString(): String = compiled.pattern()

    private companion object {
        /** Matchers kept per pattern: enough for the threads that parse at once. */
        const val MAX_IDLE = 4
    }
}

/** `text.replace(regex, replacement)` for a [PooledRegex]. */
fun CharSequence.replace(regex: PooledRegex, replacement: String): String = regex.replace(this, replacement)

/** `regex in text` for a [PooledRegex]. */
operator fun CharSequence.contains(regex: PooledRegex): Boolean = regex.containsMatchIn(this)
