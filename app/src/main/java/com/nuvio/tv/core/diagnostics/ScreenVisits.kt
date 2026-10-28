package com.nuvio.tv.core.diagnostics

/**
 * RCTV (RCTV-AUD-001): how many times each screen was opened this session, with the native memory at
 * its first and latest opening. Repeating Home → Sports → Teams → team → Back, memory that climbs
 * from one visit to the next ("sports_teams ×7 380→1105") is held across visits; a screen that
 * starts high every time is a peak of its own. Only the most recently opened screens are kept.
 */
internal class ScreenVisits(private val kept: Int = 8) {

    private class Visits(var count: Int, val firstMb: Long, var latestMb: Long)

    /** Most recently opened last. */
    private val visits = LinkedHashMap<String, Visits>()

    @Synchronized
    fun record(screen: String, nativeMb: Long) {
        val seen = visits.remove(screen)
        visits[screen] = seen?.apply {
            count++
            latestMb = nativeMb
        } ?: Visits(1, nativeMb, nativeMb)
        while (visits.size > kept) visits.remove(visits.keys.first())
    }

    /** "sports_teams ×7 380→1105, sports ×7 300→1000" (most recent first); blank when none. */
    @Synchronized
    fun line(): String = visits.entries.reversed().joinToString(", ") { (screen, seen) ->
        if (seen.count == 1) "$screen ×1 ${seen.firstMb}" else "$screen ×${seen.count} ${seen.firstMb}→${seen.latestMb}"
    }
}
