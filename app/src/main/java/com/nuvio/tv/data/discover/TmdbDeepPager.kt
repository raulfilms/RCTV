package com.nuvio.tv.data.discover

import java.time.LocalDate

/**
 * Pages through every title a TMDB discover query matches, with no limit: 10,000 or a million.
 *
 * TMDB stops any one query at page 500 (10,000 titles). Past that, the same query is asked again
 * one stretch of time at a time (a year, or a month or a single day where a year has more than
 * 10,000 titles), so every title is reached however many there are.
 *
 * [dateOrder]: how the query is sorted.
 * - [DateOrder.NEWEST_FIRST] / [DateOrder.OLDEST_FIRST]: by date, so the stretches go in that
 *   order and the whole list keeps its order exactly.
 * - [DateOrder.NONE] (most popular, most voted...): the first 10,000 come in the query's own order,
 *   then everything else, a year at a time from the newest, each year in that order. Titles already
 *   listed are skipped.
 *
 * [from] / [to]: the query's own date limits (a Year filter, "released up to today"); null for none.
 * [fetch] asks TMDB for one page of the query within the given dates (null: no date limit) and
 * returns its titles and TMDB's page count; it throws when TMDB can't be reached, and that page is
 * asked again on the next [next].
 */
class TmdbDeepPager<T>(
    from: LocalDate?,
    to: LocalDate?,
    private val dateOrder: DateOrder,
    private val idOf: (T) -> Any,
    private val fetch: suspend (page: Int, from: LocalDate?, to: LocalDate?) -> DeepPage<T>
) {
    enum class DateOrder { NONE, NEWEST_FIRST, OLDEST_FIRST }

    data class DeepPage<T>(val items: List<T>, val totalPages: Int)

    private data class Window(val from: LocalDate?, val to: LocalDate?)

    private class Cursor(val window: Window, val isTop: Boolean) {
        var page = 1
    }

    private val lowest: LocalDate = from ?: EARLIEST
    private val highest: LocalDate = to ?: LocalDate.now().plusYears(FUTURE_YEARS)

    /** Stretches still to list, the next one first. */
    private val pending = ArrayDeque<Window>()
    private var cursor: Cursor? = Cursor(Window(from, to), isTop = true)
    private val seen = HashSet<Any>()

    /** True once every title has been listed. */
    var isDone: Boolean = false
        private set

    /**
     * The next page's titles that weren't listed before. Can be empty while it skips titles already
     * listed or splits a stretch that's too big; ask again until [isDone].
     */
    suspend fun next(): List<T> {
        if (isDone) return emptyList()
        val current = cursor ?: pending.removeFirstOrNull()?.let { Cursor(it, isTop = false) }?.also { cursor = it }
        if (current == null) {
            isDone = true
            return emptyList()
        }
        val result = fetch(current.page, current.window.from, current.window.to)
        val total = result.totalPages
        // A date-sorted list must go stretch by stretch from the start to keep its order; a stretch
        // other than the first is split as soon as it's known to be too big.
        val splitNow = total > MAX_PAGE && current.page == 1 && (!current.isTop || dateOrder != DateOrder.NONE)
        if (splitNow) {
            val parts = split(current.window)
            if (parts.isNotEmpty()) {
                cursor = null
                pending.addAll(0, parts)
                return emptyList()
            }
        }
        val fresh = result.items.filter { seen.add(idOf(it)) }
        val lastPage = minOf(total, MAX_PAGE)
        if (current.page >= lastPage || result.items.isEmpty()) {
            cursor = null
            // The first 10,000 of a list that has more: now the rest, a stretch of time at a time.
            if (current.isTop && total > MAX_PAGE) pending.addAll(0, split(Window(lowest, highest)))
            if (pending.isEmpty()) isDone = true
        } else {
            current.page += 1
        }
        return fresh
    }

    /**
     * A stretch cut into years, else months, else days, in the list's order (newest first unless
     * the list is oldest first). Empty when it's a single day (it can't be cut further).
     */
    private fun split(window: Window): List<Window> {
        val start = maxOf(window.from ?: lowest, lowest)
        val end = minOf(window.to ?: highest, highest)
        if (end < start) return emptyList()
        val parts = when {
            start.year != end.year -> (start.year..end.year).map { year ->
                Window(maxOf(start, LocalDate.of(year, 1, 1)), minOf(end, LocalDate.of(year, 12, 31)))
            }
            start.monthValue != end.monthValue -> (start.monthValue..end.monthValue).map { month ->
                val first = LocalDate.of(start.year, month, 1)
                Window(maxOf(start, first), minOf(end, first.plusMonths(1).minusDays(1)))
            }
            start != end -> generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }
                .map { Window(it, it) }.toList()
            else -> emptyList()
        }
        return if (dateOrder == DateOrder.OLDEST_FIRST) parts else parts.reversed()
    }

    companion object {
        /** TMDB's last page for any one query. */
        const val MAX_PAGE = 500
        /** The earliest date to look from (TMDB's oldest films are from the 1870s). */
        val EARLIEST: LocalDate = LocalDate.of(1870, 1, 1)
        /** How far ahead to look for titles already announced. */
        const val FUTURE_YEARS = 15L
    }
}
