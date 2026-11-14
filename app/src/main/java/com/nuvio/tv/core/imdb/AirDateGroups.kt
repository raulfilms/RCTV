package com.nuvio.tv.core.imdb

import com.nuvio.tv.domain.model.Video

/**
 * RCTV: a cartoon's halves put together by the day they went on the air, as IMDb would tell them
 * ("Help Wanted/Reef Blower/Tea at the Treedome"), for when IMDb can't be asked. A season is only
 * told so when several of its days had two to [MAX_PARTS] halves; one whose episodes all came out
 * the same day (a streaming season) or with no such days is left one by one.
 */
internal object AirDateGroups {
    private const val MAX_PARTS = 4
    private const val MIN_JOINED_SHARE = 0.3

    fun episodes(videos: List<Video>): List<ImdbEpisode>? {
        val out = ArrayList<ImdbEpisode>()
        var anyJoined = false
        videos.filter { (it.season ?: 0) > 0 && it.episode != null }
            .groupBy { it.season!! }
            .toSortedMap()
            .forEach { (season, list) ->
                val sorted = list.sortedBy { it.episode }
                val groups = ArrayList<MutableList<Video>>()
                for (video in sorted) {
                    val day = dayOf(video)
                    val last = groups.lastOrNull()
                    if (last != null && day != null && day == dayOf(last.first())) last += video else groups += mutableListOf(video)
                }
                val joined = groups.count { it.size > 1 }
                val usable = joined >= 2 &&
                    joined >= groups.size * MIN_JOINED_SHARE &&
                    groups.all { it.size <= MAX_PARTS } &&
                    sorted.all { it.title.isNotBlank() && '/' !in it.title }
                if (usable) anyJoined = true
                groups.forEachIndexed { index, group ->
                    if (usable) {
                        out += ImdbEpisode(season, index + 1, group.joinToString("/") { it.title.trim() })
                    } else {
                        group.forEach { out += ImdbEpisode(season, it.episode!!, it.title.trim()) }
                    }
                }
            }
        return out.takeIf { anyJoined }
    }

    /** "1999-05-01T00:00:00.000Z" → "1999-05-01". */
    private fun dayOf(video: Video): String? = video.released?.trim()?.take(10)?.takeIf { it.length == 10 && it[4] == '-' }
}
