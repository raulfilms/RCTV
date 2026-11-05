package com.nuvio.tv.core.subsync

import java.util.TreeMap

/**
 * RCTV: a subtitle's shift along the movie. Usually one shift fits all of it; a subtitle made for
 * another cut (a scene more or less, a longer studio logo) needs another shift from some point on.
 * Each point holds from where it was found until the next one.
 */
class SubtitleOffsetMap {
    private val points = TreeMap<Long, Long>()

    val isEmpty: Boolean get() = points.isEmpty()

    /** One shift for the whole movie. */
    fun reset(offsetMs: Long) {
        points.clear()
        points[0L] = offsetMs
    }

    /** From [atMs] of the movie on (until the next point), [offsetMs]. */
    fun put(atMs: Long, offsetMs: Long) {
        points[atMs.coerceAtLeast(0L)] = offsetMs
    }

    /** The shift at [movieMs] (before the first point: the first one's); null when none was found. */
    fun offsetAt(movieMs: Long): Long? = (points.floorEntry(movieMs) ?: points.firstEntry())?.value
}
