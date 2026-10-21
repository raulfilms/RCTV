package com.nuvio.tv.core.imdb

import com.nuvio.tv.domain.model.Video
import java.text.Normalizer
import java.util.Locale

/** One of IMDb's episodes: its season, its number and its title ("Help Wanted/Reef Blower/Tea at the Treedome"). */
data class ImdbEpisode(val season: Int, val episode: Int, val title: String)

/** One of IMDb's episodes and the numbers of the app's episodes that make it up, in order. */
data class ImdbEpisodeGroup(val imdbEpisode: Int, val imdbTitle: String, val episodes: List<Int>)

/** How one season is told apart: IMDb's episodes ([groups]) for the app's [originalCount] episodes. */
data class ImdbSeasonOrder(val originalCount: Int, val groups: List<ImdbEpisodeGroup>)

/**
 * RCTV: cartoons whose half-episodes (11-minute segments) TMDB, TVDB and Cinemeta list one by one
 * while IMDb, the streaming services and the files the stream addons find list them together
 * (SpongeBob's season 1: 41 on TMDB, 20 on IMDb; its first episode "Help Wanted/Reef Blower/Tea at
 * the Treedome"). Works out which of the app's episodes make up each of IMDb's, by their titles,
 * and rebuilds such a season the IMDb way, so the stream addons are asked for the episode they
 * know ("tt0206512:1:2" is "Bubblestand/Ripped Pants" to them, not "Reef Blower").
 */
object ImdbEpisodeOrderMatcher {

    /** At least this share of the app's episodes must be found by title in IMDb's (else it's left as it is). */
    private const val MIN_MATCHED_SHARE = 0.6

    /** At least this share of IMDb's episodes must be several in one ("A/B"). */
    private const val MIN_JOINED_SHARE = 0.3

    /** How many of IMDb's episodes ahead an episode's title is looked for. */
    private const val LOOKAHEAD = 4

    private val MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['’`´]")
    private val NOT_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    /** "Squidward's  Tiki-Land!" → "squidwards tiki land". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(APOSTROPHES, "")
            .replace(NOT_ALPHANUMERIC, " ")
            .replace(SPACES, " ")
            .trim()

    /** The parts of an IMDb title: "Help Wanted/Reef Blower/Tea at the Treedome" → its three, normalized. */
    fun segmentsOf(title: String): List<String> =
        title.split('/').map(::normalize).filter { it.isNotEmpty() }.ifEmpty { listOf(normalize(title)) }

    /** "Help Wanted/Reef Blower" → "Help Wanted / Reef Blower". */
    fun displayTitle(imdbTitle: String): String =
        imdbTitle.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { imdbTitle }

    /**
     * Which of the app's episodes of a season ([episodes]: number to title, in order) make up each of
     * IMDb's ([imdb]), by their titles (or, titles in another language, by counting IMDb's halves when
     * they add up to the season exactly); null when the season is the same on both, or when they
     * can't be lined up surely (then it's left as it is).
     */
    fun groupSeason(episodes: List<Pair<Int, String>>, imdb: List<ImdbEpisode>): List<ImdbEpisodeGroup>? {
        val imdbEpisodes = imdb.filter { it.episode > 0 }.distinctBy { it.episode }.sortedBy { it.episode }
        if (imdbEpisodes.isEmpty() || episodes.size <= imdbEpisodes.size) return null
        val segments = imdbEpisodes.map { segmentsOf(it.title) }
        if (segments.count { it.size > 1 } < imdbEpisodes.size * MIN_JOINED_SHARE) return null

        val assigned = IntArray(episodes.size) { -1 }
        var cursor = 0
        var matched = 0
        episodes.forEachIndexed { index, (_, title) ->
            val wanted = normalize(title)
            if (wanted.isEmpty()) return@forEachIndexed
            val window = cursor until minOf(imdbEpisodes.size, cursor + LOOKAHEAD)
            // The same title first ("Mermaid Man and Barnacle Boy" isn't "...Barnacle Boy II"), then one in the other.
            val found = window.firstOrNull { m -> segments[m].any { it == wanted } }
                ?: window.firstOrNull { m -> segments[m].any { overlaps(it, wanted) } }
                ?: return@forEachIndexed
            assigned[index] = found
            cursor = found
            matched++
        }
        if (matched < episodes.size * MIN_MATCHED_SHARE) {
            // Titles in another language (or none): IMDb's halves counted, when they add up to the season exactly.
            if (segments.sumOf { it.size } != episodes.size) return null
            var index = 0
            segments.forEachIndexed { m, parts -> repeat(parts.size) { assigned[index++] = m } }
        } else {
            fillUnmatched(assigned, segments)
        }
        // In order, or it's not the same season told another way.
        for (i in 1 until assigned.size) if (assigned[i] < assigned[i - 1]) return null

        return imdbEpisodes.mapIndexed { m, episode ->
            ImdbEpisodeGroup(
                imdbEpisode = episode.episode,
                imdbTitle = episode.title,
                episodes = episodes.indices.filter { assigned[it] == m }.map { episodes[it].first }
            )
        }
    }

    /** One title in the other ("Plankton" and "Plankton!"), when it's long enough to be sure. */
    private fun overlaps(a: String, b: String): Boolean {
        val shorter = if (a.length <= b.length) a else b
        val longer = if (shorter === a) b else a
        return shorter.length >= 6 && longer.contains(shorter)
    }

    /** The episodes not found by title go with their neighbours: the one before while it has room, else the next. */
    private fun fillUnmatched(assigned: IntArray, segments: List<List<String>>) {
        val counts = IntArray(segments.size)
        assigned.forEach { if (it >= 0) counts[it]++ }
        for (i in assigned.indices) {
            if (assigned[i] >= 0) continue
            val previous = (i - 1 downTo 0).firstOrNull { assigned[it] >= 0 }?.let { assigned[it] }
            val next = (i + 1 until assigned.size).firstOrNull { assigned[it] >= 0 }?.let { assigned[it] }
            val target = when {
                previous == null && next == null -> 0
                previous == null -> next!!
                next == null -> if (counts[previous] < segments[previous].size) previous else minOf(previous + 1, segments.lastIndex)
                previous == next -> previous
                counts[previous] < segments[previous].size -> previous
                next - previous > 1 -> previous + 1
                else -> next
            }
            assigned[i] = target
            counts[target]++
        }
    }

    /**
     * [season]'s episodes rebuilt the IMDb way from the app's ([videos], that season's), each named
     * after its parts ("Help Wanted / Reef Blower / Tea at the Treedome"), with the first one's
     * picture and date and every part's story; its id the addons' "[idPrefix]:season:episode".
     */
    fun rebuildSeason(videos: List<Video>, season: Int, groups: List<ImdbEpisodeGroup>, idPrefix: String): List<Video> {
        val byNumber = videos.associateBy { it.episode }
        return groups.map { group ->
            val parts = group.episodes.mapNotNull { byNumber[it] }
            val segments = segmentsOf(group.imdbTitle)
            // The addon's titles when they're the halves IMDb names (else IMDb's: no "Episode 7" in it).
            val named = parts.size == segments.size && parts.all { part ->
                val title = normalize(part.title)
                segments.any { it == title || overlaps(it, title) }
            }
            val title = if (named) {
                parts.joinToString(" / ") { it.title.trim() }
            } else {
                displayTitle(group.imdbTitle)
            }
            val stories = parts.mapNotNull { part -> part.overview?.trim()?.takeIf { it.isNotEmpty() }?.let { part to it } }
            val overview = when {
                stories.isEmpty() -> null
                stories.size == 1 -> stories.single().second
                else -> stories.joinToString("\n\n") { (part, story) -> "${part.title.trim()}: $story" }
            }
            val runtimes = parts.map { it.runtime }
            Video(
                id = "$idPrefix:$season:${group.imdbEpisode}",
                title = title,
                released = parts.firstNotNullOfOrNull { it.released },
                thumbnail = parts.firstNotNullOfOrNull { it.thumbnail },
                streams = parts.firstOrNull()?.streams.orEmpty(),
                season = season,
                episode = group.imdbEpisode,
                overview = overview,
                runtime = if (runtimes.isNotEmpty() && runtimes.all { it != null }) runtimes.sumOf { it ?: 0 } else null,
                rating = parts.singleOrNull()?.rating,
                available = parts.firstOrNull()?.available
            )
        }
    }

    /** The part of a video's id before ":season:episode" ("tt0206512" of "tt0206512:1:2"); null when it isn't shaped so. */
    fun idPrefixOf(video: Video): String? {
        val season = video.season ?: return null
        val episode = video.episode ?: return null
        val suffix = ":$season:$episode"
        if (!video.id.endsWith(suffix)) return null
        return video.id.removeSuffix(suffix).takeIf { it.isNotBlank() }
    }

    /**
     * [videos] with every season IMDb tells another way rebuilt as IMDb has it ([imdb]: all of the
     * show's episodes there), in their place; and how each rebuilt season was told apart (by season).
     */
    fun reorder(videos: List<Video>, imdb: List<ImdbEpisode>): Pair<List<Video>, Map<Int, ImdbSeasonOrder>> {
        val imdbBySeason = imdb.groupBy { it.season }
        val rebuilt = LinkedHashMap<Int, List<Video>>()
        val orders = LinkedHashMap<Int, ImdbSeasonOrder>()
        videos.filter { (it.season ?: 0) > 0 && it.episode != null }
            .groupBy { it.season!! }
            .forEach { (season, seasonVideos) ->
                val imdbSeason = imdbBySeason[season] ?: return@forEach
                val ordered = seasonVideos.sortedBy { it.episode }.distinctBy { it.episode }
                // The addons' ids must all be "<show>:season:episode", the same show.
                val prefixes = ordered.map(::idPrefixOf)
                val prefix = prefixes.firstOrNull() ?: return@forEach
                if (prefixes.any { it != prefix }) return@forEach
                val groups = groupSeason(ordered.map { it.episode!! to it.title }, imdbSeason) ?: return@forEach
                rebuilt[season] = rebuildSeason(ordered, season, groups, prefix)
                orders[season] = ImdbSeasonOrder(originalCount = ordered.size, groups = groups)
            }
        if (rebuilt.isEmpty()) return videos to emptyMap()
        val out = ArrayList<Video>(videos.size)
        val placed = HashSet<Int>()
        for (video in videos) {
            val season = video.season
            val replacement = season?.let { rebuilt[it] }
            if (replacement == null) {
                out += video
            } else if (placed.add(season)) {
                out += replacement
            }
        }
        return out to orders
    }
}
