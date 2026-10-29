package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.util.FastText
import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam

/** RCTV: Search's rows besides the movies and series, in the order they show when equally good. */
enum class SearchExtraRowKind { CHANNELS, TEAMS, LEAGUES, GAMES, PROGRAMS }

/** A channel of the Live TV guide that matches: its name as shown ("ESPN2"), and what's on it now. */
@Immutable
data class SearchChannelResult(
    val channel: LiveTvChannel,
    val displayName: String,
    val onNow: String?
)

/** A program of the Live TV guide that matches, on its channel (named as shown). */
@Immutable
data class SearchProgramResult(
    val channel: LiveTvChannel,
    val channelName: String,
    val program: EpgProgram
) {
    val key: String get() = "${channel.id}|${program.startMs}|${program.title}"
}

/** A league, under the name the app shows ("NBA", "La Liga"). */
@Immutable
data class SearchLeagueResult(val league: SportsLeague)

/** A team, with its league's short name ("NBA") under it. */
@Immutable
data class SearchTeamResult(val team: SportsTeam, val leagueLabel: String?) {
    /** Its name and short name ready to compare (worked out once, when the teams load). */
    internal val searchName: String = SearchMatching.normalize(team.name)
    internal val searchShortName: String? = team.shortName?.let(SearchMatching::normalize)
}

/**
 * What Search found besides the movies and series: channels and programs of the Live TV guide,
 * sports teams, leagues and games. [strong] rows (a name that is what was typed, starts with it or
 * has it as a whole word; never the guide's programs) show above the movies and series; the rest
 * under them.
 */
@Immutable
data class SearchExtraResults(
    /** The search these are for (trimmed). */
    val query: String = "",
    val channels: List<SearchChannelResult> = emptyList(),
    val programs: List<SearchProgramResult> = emptyList(),
    val teams: List<SearchTeamResult> = emptyList(),
    val leagues: List<SearchLeagueResult> = emptyList(),
    val games: List<SportsEvent> = emptyList(),
    /** The rows to show, best first. */
    val rows: List<SearchExtraRowKind> = emptyList(),
    /** Of [rows], the ones that go above the movies and series. */
    val strong: Set<SearchExtraRowKind> = emptySet()
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** A league Search knows: how it's shown, and every name it goes by ("NBA", "National Basketball Association", "nba"). */
data class SearchableLeague(val league: SportsLeague, val names: List<String>) {
    internal val searchNames: List<String> = names.map(SearchMatching::normalize).distinct()
}

/** A game Search knows, with its teams' and its own names ready to compare (worked out once, when the games load). */
class SearchableGame(val event: SportsEvent) {
    internal val searchNames: List<String> = buildList {
        add(event.homeTeamName)
        add(event.awayTeamName)
        addAll(event.homeTeamAliases)
        addAll(event.awayTeamAliases)
        add(event.name)
    }.filter { it.isNotBlank() }.map(SearchMatching::normalize).distinct()
}

/**
 * Finds what's typed in Search among the guide's channels and programs and the sports teams,
 * leagues and games. Pure: everything it looks through is handed in.
 */
internal object SearchExtrasFinder {

    const val MAX_CHANNELS = 20
    const val MAX_PROGRAMS = 30
    const val MAX_TEAMS = 20
    const val MAX_LEAGUES = 12
    const val MAX_GAMES = 20

    /** How long a finished game still shows (yesterday's games). */
    private const val FINISHED_GAME_WINDOW_MS = 36L * 60 * 60 * 1000

    /** How far ahead the guide's programs are looked through. */
    private const val PROGRAMS_AHEAD_MS = 7L * 24 * 60 * 60 * 1000

    private val ADULT_CATEGORIES = setOf("adults only", "erotic", "adult")

    /** A sport by name, in English and Spanish: its leagues show when it's typed ("futbol", "basketball"). */
    private val SPORT_WORDS: Map<String, List<String>> = mapOf(
        "Soccer" to listOf("soccer", "futbol", "football soccer", "futbol soccer"),
        "Basketball" to listOf("basketball", "baloncesto", "basket", "basquet", "basquetbol"),
        "Football" to listOf("american football", "futbol americano", "football"),
        "Baseball" to listOf("baseball", "beisbol", "pelota"),
        "Hockey" to listOf("hockey", "hockey sobre hielo", "ice hockey"),
        "Golf" to listOf("golf"),
        "Tennis" to listOf("tennis", "tenis"),
        "Motorsport" to listOf("motorsport", "racing", "carreras", "automovilismo", "motor"),
        "MMA" to listOf("mma", "artes marciales mixtas"),
        // Boxing and wrestling's big shows: the app's "PPV" leagues.
        "PPV" to listOf("ppv", "pay per view", "boxing", "boxeo", "wrestling", "lucha libre")
    )

    /**
     * Everything found for [rawQuery]: in the [guide] (null: no guide to look through), the
     * [teams], [leagues] and [games]. [clock] gives a game as it stands at [now] (live once its
     * start time has come).
     */
    fun find(
        rawQuery: String,
        guide: GuideSearchIndex?,
        teams: List<SearchTeamResult>,
        leagues: List<SearchableLeague>,
        games: List<SearchableGame>,
        clock: (SportsEvent) -> SportsEvent,
        now: Long
    ): SearchExtraResults {
        val query = SearchMatching.normalize(rawQuery)
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return SearchExtraResults(query = rawQuery.trim())

        val best = HashMap<SearchExtraRowKind, Int>()

        // ---- Channels: one per name ("ESPN HD" and "ESPN FHD" are one: a copy with a logo rather
        // than one without), the best matches first, then in the guide's order.
        val channelHits = LinkedHashMap<String, Pair<Int, GuideSearchIndex.Entry>>()
        guide?.channels?.forEach { entry ->
            val rank = SearchMatching.rank(entry.normalized, query) ?: return@forEach
            val kept = channelHits[entry.normalized]
            if (kept != null && (!kept.second.channel.logoUrl.isNullOrBlank() || entry.channel.logoUrl.isNullOrBlank())) return@forEach
            channelHits[entry.normalized] = rank to (if (kept != null) entry.withOrder(kept.second.order) else entry)
        }
        val channelResults = channelHits.values
            .sortedWith(compareBy({ it.first }, { it.second.order }))
            .take(MAX_CHANNELS)
            .map { (rank, entry) ->
                val onNow = guide?.programsOf(entry.channel)?.firstOrNull { it.isAiringAt(now) }?.title
                rank to SearchChannelResult(entry.channel, entry.shown, onNow)
            }
        channelResults.firstOrNull()?.let { best[SearchExtraRowKind.CHANNELS] = it.first }

        // ---- Programs: on now and coming up, one showing per channel and time.
        val programResults = if (guide != null) findPrograms(query, guide, now) else emptyList()
        programResults.firstOrNull()?.let { best[SearchExtraRowKind.PROGRAMS] = it.first }

        // ---- Leagues: by any of their names, or by their sport ("futbol": every soccer league).
        val leagueHits = ArrayList<Triple<Int, Int, SearchLeagueResult>>()
        val sportRanks = SPORT_WORDS.mapNotNull { (sport, words) ->
            SearchMatching.bestRank(words, query)?.takeIf { it <= SearchMatching.WHOLE_WORDS }?.let { sport to it }
        }.toMap()
        leagues.forEachIndexed { index, entry ->
            val byName = SearchMatching.bestRank(entry.searchNames, query)
            val bySport = sportRanks[entry.league.sportName]?.let { maxOf(it, SearchMatching.WORD_STARTS) }
            val rank = listOfNotNull(byName, bySport).minOrNull() ?: return@forEachIndexed
            leagueHits += Triple(rank, index, SearchLeagueResult(entry.league))
        }
        val leagueResults = leagueHits.sortedWith(compareBy({ it.first }, { it.second })).take(MAX_LEAGUES)
        leagueResults.firstOrNull()?.let { best[SearchExtraRowKind.LEAGUES] = it.first }
        // Their games too: of a league named ("NBA") or of a sport ("boxeo": the PPV fights).
        val leagueRanks = leagueHits.filter { it.first <= SearchMatching.WORD_STARTS }
            .associate { it.third.league.id to it.first }

        // ---- Teams: by name, or by their short name typed whole ("LAL").
        val teamHits = ArrayList<Triple<Int, Int, SearchTeamResult>>()
        val seenTeams = HashSet<String>()
        teams.forEachIndexed { index, entry ->
            val byName = SearchMatching.rank(entry.searchName, query)
            val byShort = if (entry.searchShortName == query) SearchMatching.EXACT else null
            val rank = listOfNotNull(byName, byShort).minOrNull() ?: return@forEachIndexed
            val team = entry.team
            val sport = team.leagueId?.substringBefore(':').orEmpty()
            if (!seenTeams.add("$sport|${team.id}|${team.name}")) return@forEachIndexed
            teamHits += Triple(rank, index, entry)
        }
        val teamResults = teamHits.sortedWith(compareBy({ it.first }, { it.second })).take(MAX_TEAMS)
        teamResults.firstOrNull()?.let { best[SearchExtraRowKind.TEAMS] = it.first }

        // ---- Games: of a team that matches, or of a league that does; live, then next, then just over.
        val gameHits = ArrayList<Pair<Int, SportsEvent>>()
        val seenGames = HashSet<String>()
        games.forEach { game ->
            val byTeam = SearchMatching.bestRank(game.searchNames, query)
            val byLeague = game.event.leagueId?.let { leagueRanks[it] }
            val rank = listOfNotNull(byTeam, byLeague).minOrNull() ?: return@forEach
            val event = clock(game.event)
            if (!isCurrent(event, now)) return@forEach
            if (!seenGames.add(event.id)) return@forEach
            gameHits += rank to event
        }
        val gameResults = gameHits.sortedWith(
            compareBy<Pair<Int, SportsEvent>>(
                { if (it.first <= SearchMatching.STRONG) 0 else 1 },
                { statusOrder(it.second.status) },
                {
                    val start = it.second.startTimeMs ?: Long.MAX_VALUE
                    // Just over: the latest first.
                    if (it.second.status == SportsEventStatus.FINISHED) -start else start
                }
            )
        ).take(MAX_GAMES)
        gameResults.minOfOrNull { it.first }?.let { best[SearchExtraRowKind.GAMES] = it }

        val rows = best.entries
            .sortedWith(compareBy({ it.value }, { it.key.ordinal }))
            .map { it.key }
        // Two letters only count as strong when they're the whole name ("F1"; "MLB" needs three).
        val strongLimit = if (query.length < 3) SearchMatching.EXACT else SearchMatching.STRONG
        // The guide's programs are mostly the movies and series themselves ("The Office" airing on
        // TBS): they stay under the movies and series found (on top only when there are none).
        val strong = best.filterValues { it <= strongLimit }.keys - SearchExtraRowKind.PROGRAMS
        return SearchExtraResults(
            query = rawQuery.trim(),
            channels = channelResults.map { it.second },
            programs = programResults.map { it.second },
            teams = teamResults.map { it.third },
            leagues = leagueResults.map { it.third },
            games = gameResults.map { it.second },
            rows = rows,
            strong = strong
        )
    }

    private fun statusOrder(status: SportsEventStatus): Int = when (status) {
        SportsEventStatus.LIVE -> 0
        SportsEventStatus.UPCOMING -> 1
        SportsEventStatus.FINISHED -> 2
    }

    /** On now, coming up, or over within the last day and a half. */
    private fun isCurrent(event: SportsEvent, now: Long): Boolean = when (event.status) {
        SportsEventStatus.LIVE, SportsEventStatus.UPCOMING -> true
        SportsEventStatus.FINISHED -> {
            val start = event.startTimeMs ?: return false
            now - start <= FINISHED_GAME_WINDOW_MS
        }
    }

    fun isAdult(channel: LiveTvChannel): Boolean =
        hasAdultWord(channel.name) || channel.groupTitle?.let { hasAdultWord(it) } == true

    /**
     * "XXX", "Adult(s)", "Porn…", "Erotic…" as a word. RCTV (MEM-07): without a regular expression:
     * it runs for every channel of the guide, and on Android each match leaves native memory.
     */
    private fun hasAdultWord(text: String): Boolean = FastText.wordsOf(text).any { word ->
        word == "xxx" || word == "adult" || word == "adults" || word.startsWith("porn") || word.startsWith("erotic")
    }

    private fun isAdult(program: EpgProgram): Boolean =
        program.categories.any { it.lowercase() in ADULT_CATEGORIES } || program.category?.lowercase() in ADULT_CATEGORIES

    /**
     * The guide's programs whose title matches, each with its rank: the strong matches first, then
     * on now, then by start time. A guide entry several channels share shows on one of them.
     */
    private fun findPrograms(query: String, guide: GuideSearchIndex, now: Long): List<Pair<Int, SearchProgramResult>> {
        // Which titles match, once each (the same title airs many times).
        val titleRanks = HashMap<String, Int>()
        guide.forEachTitle { title, normalized ->
            SearchMatching.rank(normalized, query)?.let { titleRanks[title] = it }
        }
        if (titleRanks.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Int, SearchProgramResult>>()
        val seen = HashSet<String>()
        guide.forEachEntry { entry, programs ->
            for (program in programs) {
                if (program.endMs <= now || program.startMs > now + PROGRAMS_AHEAD_MS) continue
                val rank = titleRanks[program.title] ?: continue
                if (isAdult(program)) continue
                if (!seen.add("${entry.normalized}|${program.startMs}|${program.title}")) continue
                found += rank to SearchProgramResult(entry.channel, entry.shown, program)
            }
        }
        return found.sortedWith(
            compareBy<Pair<Int, SearchProgramResult>>(
                { if (it.first <= SearchMatching.STRONG) 0 else 1 },
                { if (it.second.program.isAiringAt(now)) 0 else 1 },
                { it.second.program.startMs }
            )
        ).take(MAX_PROGRAMS)
    }
}

/**
 * One loaded guide ready for Search, built once (on the first search after it loads, on a
 * background thread) rather than on every letter typed: its channels with their names as shown and
 * ready to compare (adult channels left out), one channel per guide entry, and every program title
 * once with its searchable form.
 */
internal class GuideSearchIndex private constructor(
    val channels: List<Entry>,
    private val epgByChannel: Map<String, List<EpgProgram>>,
    private val channelOfEntry: Map<String, Entry>,
    private val titles: HashMap<String, String>
) {
    class Entry(val channel: LiveTvChannel, val shown: String, val normalized: String, val order: Int) {
        fun withOrder(order: Int): Entry = Entry(channel, shown, normalized, order)
    }

    val titleCount: Int get() = titles.size

    /** What's on [channel] (its guide entry's programs). */
    fun programsOf(channel: LiveTvChannel): List<EpgProgram>? = epgByChannel[channel.epgChannelId ?: channel.id]

    fun forEachTitle(action: (title: String, normalized: String) -> Unit) {
        for ((title, normalized) in titles) action(title, normalized)
    }

    /** Each guide entry that has a channel, with its programs. */
    fun forEachEntry(action: (entry: Entry, programs: List<EpgProgram>) -> Unit) {
        for ((key, programs) in epgByChannel) {
            val entry = channelOfEntry[key] ?: continue
            action(entry, programs)
        }
    }

    companion object {
        /** [channelName]: a channel's name as shown ("US: ESPN FHD" → "ESPN"). */
        fun build(
            channels: List<LiveTvChannel>,
            epgByChannel: Map<String, List<EpgProgram>>,
            channelName: (LiveTvChannel) -> String
        ): GuideSearchIndex {
            val entries = ArrayList<Entry>(channels.size)
            val byEntry = HashMap<String, Entry>()
            channels.forEachIndexed { index, channel ->
                if (SearchExtrasFinder.isAdult(channel)) return@forEachIndexed
                val shown = channelName(channel)
                val entry = Entry(channel, shown, SearchMatching.normalize(shown), index)
                entries += entry
                // One channel per guide entry: the first with a logo.
                val key = channel.epgChannelId ?: channel.id
                val current = byEntry[key]
                if (current == null || (current.channel.logoUrl.isNullOrBlank() && !channel.logoUrl.isNullOrBlank())) {
                    byEntry[key] = entry
                }
            }
            val titles = HashMap<String, String>()
            for ((key, programs) in epgByChannel) {
                if (!byEntry.containsKey(key)) continue
                for (program in programs) {
                    if (!titles.containsKey(program.title)) titles[program.title] = SearchMatching.normalize(program.title)
                }
            }
            return GuideSearchIndex(entries, epgByChannel, byEntry, titles)
        }
    }
}
