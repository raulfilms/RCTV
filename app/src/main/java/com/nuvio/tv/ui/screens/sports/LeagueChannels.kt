package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.LiveTvChannel
import java.text.Normalizer
import java.util.Locale

/** RCTV (league page): a channel of the IPTV lineup shown in a league's rows, with the name to show. */
data class LeagueChannelItem(val channel: LiveTvChannel, val title: String)

/**
 * RCTV (league page, owner's rows): a league's own channels in the IPTV lineup (NFL RedZone, NFL
 * Network, NBA TV, MLB Network...) and its game replay channels ("NFL | Replay": NFL Replay 1, 2...).
 * Only the league's official channels: a league not listed here has none, and its Channels row
 * isn't shown. Each one is the lineup's best match (a US channel first).
 */
object LeagueChannels {

    /** An official channel: the name shown, and words a lineup name with it can't have. */
    private data class Wanted(val title: String, val notWords: Set<String> = emptySet())

    private val CONFERENCE_NETWORKS = listOf(
        Wanted("SEC Network"), Wanted("ACC Network"), Wanted("Big Ten Network"), Wanted("Big 12 Now")
    )

    private val OFFICIAL: Map<String, List<Wanted>> = mapOf(
        "football:nfl" to listOf(Wanted("NFL RedZone"), Wanted("NFL Network"), Wanted("NFL Channel")),
        "basketball:nba" to listOf(Wanted("NBA TV")),
        "hockey:nhl" to listOf(Wanted("NHL Network")),
        "baseball:mlb" to listOf(Wanted("MLB Network"), Wanted("MLB Strike Zone")),
        "soccer:eng.1" to listOf(Wanted("Premier League TV")),
        "soccer:esp.1" to listOf(Wanted("LaLiga TV", notWords = setOf("hypermotion"))),
        "mma:ufc" to listOf(Wanted("UFC Fight Pass"), Wanted("Fight Pass")),
        "racing:f1" to listOf(Wanted("F1 TV")),
        "golf:pga" to listOf(Wanted("Golf Channel")),
        "golf:lpga" to listOf(Wanted("Golf Channel")),
        "tennis:atp" to listOf(Wanted("Tennis Channel")),
        "tennis:wta" to listOf(Wanted("Tennis Channel")),
        "football:college-football" to CONFERENCE_NETWORKS,
        "basketball:mens-college-basketball" to CONFERENCE_NETWORKS,
        "basketball:womens-college-basketball" to CONFERENCE_NETWORKS,
        "baseball:college-baseball" to CONFERENCE_NETWORKS
    )

    /** The words a league's replay channels (or their group) have: "NFL" in "NFL | Replay". */
    private val REPLAY_TAGS: Map<String, List<String>> = mapOf(
        "football:nfl" to listOf("nfl"),
        "basketball:nba" to listOf("nba"),
        "basketball:wnba" to listOf("wnba"),
        "hockey:nhl" to listOf("nhl"),
        "baseball:mlb" to listOf("mlb"),
        "mma:ufc" to listOf("ufc"),
        "golf:liv" to listOf("liv", "golf")
    )

    private val REPLAY_WORDS = setOf("replay", "replays", "repeticion", "repeticiones")
    private val NOISE = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "raw", "vip", "60fps", "50fps")
    private val COUNTRY_PREFIX = Regex("""^\s*[A-Za-z]{2,4}\s*[:|]\s*""")
    private const val MAX_REPLAYS = 30

    /** Whether [leagueId] has official channels (else its Channels row is never shown). */
    fun hasOfficialChannels(leagueId: String): Boolean = OFFICIAL.containsKey(leagueId)

    /** Whether [leagueId]'s replay channels can be looked for in a lineup. */
    fun hasReplays(leagueId: String): Boolean = REPLAY_TAGS.containsKey(leagueId)

    /** [leagueId]'s official channels in the lineup, in the league's order, each once. */
    fun official(leagueId: String, channels: List<LiveTvChannel>): List<LeagueChannelItem> {
        val wanted = OFFICIAL[leagueId] ?: return emptyList()
        if (channels.isEmpty()) return emptyList()
        val prepared = channels.map { it to wordsOf(stripPrefix(it.name)) }
        val used = HashSet<String>()
        return wanted.mapNotNull { want ->
            val wantedWords = wordsOf(want.title)
            prepared
                .filter { (channel, words) ->
                    channel.id !in used &&
                        words.none { it in REPLAY_WORDS || it == "backup" } &&
                        words.none { it in want.notWords } &&
                        containsAtWords(words, wantedWords)
                }
                .maxByOrNull { (channel, words) -> rank(channel, words, wantedWords) }
                ?.let { (channel, _) ->
                    used += channel.id
                    LeagueChannelItem(channel, want.title)
                }
        }.distinctBy { it.title }
    }

    /** [leagueId]'s game replay channels in the lineup ("NFL Replay 1", 2...; its highlights one last). */
    fun replays(leagueId: String, channels: List<LiveTvChannel>): List<LeagueChannelItem> {
        val tag = REPLAY_TAGS[leagueId] ?: return emptyList()
        return channels.asSequence()
            .filter { channel ->
                val name = wordsOf(stripPrefix(channel.name))
                val group = wordsOf(channel.groupTitle.orEmpty())
                if ("info" in name) return@filter false
                val isReplay = name.any { it in REPLAY_WORDS } || group.any { it in REPLAY_WORDS }
                isReplay && (containsAtWords(name, tag) || containsAtWords(group, tag))
            }
            .map { LeagueChannelItem(it, displayName(it.name)) }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .sortedWith(compareBy<LeagueChannelItem>({ "highlight" in it.title.lowercase(Locale.ROOT) }, { numberIn(it.title) ?: Int.MAX_VALUE }, { it.title }))
            .take(MAX_REPLAYS)
            .toList()
    }

    /** A lineup name as shown: no country tag, no quality marks ("USA: NFL Redzone ᴴᴰ" → "NFL Redzone"). */
    fun displayName(name: String): String {
        val withoutPrefix = stripPrefix(name)
        val words = withoutPrefix.split(Regex("\\s+")).filter { word ->
            val plain = wordsOf(word).joinToString("")
            plain.isNotEmpty() && plain !in NOISE
        }
        return words.joinToString(" ").trim().ifBlank { withoutPrefix.trim() }
    }

    // ------------------------------------------------------------------ Helpers

    private fun stripPrefix(name: String): String = name.replaceFirst(COUNTRY_PREFIX, "")

    /** Plain lowercase words, accents and symbols (and quality marks) out. */
    internal fun wordsOf(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in NOISE }

    /**
     * Whether [wanted] is in [words] starting and ending at whole words, spaces aside ("NFL Red Zone"
     * has "NFL RedZone"; "WNBA TV" doesn't have "NBA TV").
     */
    internal fun containsAtWords(words: List<String>, wanted: List<String>): Boolean {
        if (wanted.isEmpty() || words.isEmpty()) return false
        val target = wanted.joinToString("")
        val joined = StringBuilder()
        val starts = HashSet<Int>()
        val ends = HashSet<Int>()
        for (word in words) {
            starts += joined.length
            joined.append(word)
            ends += joined.length
        }
        var from = 0
        while (true) {
            val at = joined.indexOf(target, from)
            if (at < 0) return false
            if (at in starts && (at + target.length) in ends) return true
            from = at + 1
        }
    }

    /** The best of the lineup's matches: a US one, then the one with the fewest other words, with a logo. */
    private fun rank(channel: LiveTvChannel, words: List<String>, wanted: List<String>): Int {
        val prefix = COUNTRY_PREFIX.find(channel.name)?.value?.let { wordsOf(it).firstOrNull() }
        val group = wordsOf(channel.groupTitle.orEmpty())
        var score = 0
        if (channel.country.equals("US", ignoreCase = true) || prefix == "usa" || prefix == "us" || group.firstOrNull() == "usa") score += 100
        if (prefix == null) score += 20
        score -= (words.size - wanted.size).coerceAtLeast(0) * 5
        if (!channel.logoUrl.isNullOrBlank()) score += 3
        return score
    }

    private fun numberIn(title: String): Int? = Regex("""\d+""").find(title)?.value?.toIntOrNull()
}

/**
 * RCTV (league page, by week): moving through a football league's weeks as ESPN numbers them: the
 * regular season (2) into the postseason (3) after its last week, and back; the preseason (1) before.
 */
object FootballWeeks {
    private val REGULAR_WEEKS = mapOf("nfl" to 18, "college-football" to 15, "ufl" to 10)
    private val POST_WEEKS = mapOf("nfl" to 5, "college-football" to 5, "ufl" to 2)
    private val PRE_WEEKS = mapOf("nfl" to 4, "college-football" to 1, "ufl" to 1)

    /** [delta] weeks from [week] of [seasonType]: the week and season part it lands on (kept in range). */
    fun step(leaguePath: String, week: Int, seasonType: Int, delta: Int): Pair<Int, Int> {
        var w = week
        var type = seasonType.coerceIn(1, 3)
        repeat(kotlin.math.abs(delta)) {
            if (delta > 0) {
                val last = weeksOf(leaguePath, type)
                when {
                    w < last -> w++
                    type < 3 -> { type++; w = 1 }
                }
            } else {
                when {
                    w > 1 -> w--
                    type > 1 -> { type--; w = weeksOf(leaguePath, type) }
                }
            }
        }
        return w to type
    }

    private fun weeksOf(leaguePath: String, seasonType: Int): Int = when (seasonType) {
        1 -> PRE_WEEKS[leaguePath] ?: 1
        3 -> POST_WEEKS[leaguePath] ?: 5
        else -> REGULAR_WEEKS[leaguePath] ?: 18
    }
}
