package com.nuvio.tv.data.remote.sportsdb

import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsShowDetails
import okhttp3.OkHttpClient

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * PPV Events: boxing's fights and the big wrestling shows (WWE's Premium Live Events, AEW's
 * pay-per-views), from TheSportsDB (ESPN has neither). Its free key allows a few requests a minute,
 * so the shows found are kept (also on disk) and looked at again every few hours, in the background:
 * a league's next show says which "round" it's at, and the rounds around it are its shows.
 */
internal class PpvEventsSource(
    private val okHttpClient: OkHttpClient,
    /** Where the shows are kept between launches; null: only while the app runs. */
    private val cacheFile: File?
) {

    /** A promotion (one of TheSportsDB's leagues) whose shows go in PPV Events. */
    private enum class Promotion(val leagueId: Int, val label: String, val sport: String, val back: Int, val ahead: Int) {
        BOXING(4445, "Boxing", SPORT_BOXING, back = 4, ahead = 16),
        WWE(4444, "WWE", SPORT_WRESTLING, back = 4, ahead = 12),
        AEW(4563, "AEW", SPORT_WRESTLING, back = 3, ahead = 10)
    }

    /** A show: a fight card or a wrestling event (TheSportsDB's event). */
    data class Show(
        val id: String,
        val promotion: String,
        val sport: String,
        val name: String,
        val startMs: Long?,
        /** Only its day is known (TheSportsDB has no time for it yet). */
        val timeTba: Boolean,
        val venue: String?,
        val imageUrl: String?,
        val posterUrl: String?,
        val badgeUrl: String?,
        val description: String?,
        /** The card: the fights or matches, one a line (and their results, once it's over). */
        val card: String?,
        val finished: Boolean,
        /** TheSportsDB's "round": its league's shows are numbered in order. */
        val round: Int = 0
    ) {
        /** Over for sure: marked so, or two days past. */
        fun isSettled(now: Long): Boolean = finished || (startMs != null && now > startMs + 2L * 24 * 60 * 60 * 1000)
    }

    /** One promotion's shows as last looked at. */
    private data class Saved(val savedAt: Long, val nextRound: Int, val season: String, val shows: List<Show>)

    private val lock = Any()
    @Volatile private var saved: Map<String, Saved>? = null
    private val refresher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ppv-events").apply { isDaemon = true }
    }
    @Volatile private var refreshing: Future<*>? = null

    /** PPV Events itself, or one of boxing's bodies or promoters (their fights). */
    fun league(leaguePath: String): SportsLeague {
        val boxing = boxingLeagueOf(leaguePath)
        return SportsLeague(
            id = "$SPORT_PATH:$leaguePath",
            name = boxing?.name ?: LEAGUE_NAME,
            sportName = SPORT_LABEL,
            badgeUrl = boxing?.logo ?: LEAGUE_LOGO,
            country = null
        )
    }

    /**
     * The shows in [datesRange] (ESPN's "yyyyMMdd[-yyyyMMdd]", UTC days); none given, from three
     * days ago on. Answers with what's kept (looking again in the background when it's old); the
     * very first time, waits a few seconds for the first shows.
     */
    fun fetchFeed(feed: EspnSportsClient.Feed, datesRange: String?): EspnSportsClient.FeedResult {
        val shows = shows(waitWhenEmpty = true)
        val now = System.currentTimeMillis()
        val (from, to) = rangeOf(datesRange)
        val league = league(feed.leaguePath)
        // A body's or promoter's league: the fights its name is in (their titles, the card, the story).
        val boxing = boxingLeagueOf(feed.leaguePath)
        val events = shows
            .filter { show -> boxing == null || (show.sport == SPORT_BOXING && boxing.matches(show)) }
            .filter { show -> inRange(show.startMs, from, to, now) }
            .sortedBy { it.startMs ?: Long.MAX_VALUE }
            .map { eventOf(it, now).copy(leagueId = league.id, leagueName = league.name) }
        return EspnSportsClient.FeedResult(feed = feed, league = league, events = events, teams = emptyList())
    }

    /** A show by its id (its card and description for its page): kept, else looked up. */
    fun show(showId: String): Show? {
        shows(waitWhenEmpty = false).firstOrNull { it.id == showId }?.let { return it }
        return runCatching {
            val json = JSONObject(SportsDbRequests.get(okHttpClient, "lookupevent.php?id=$showId") ?: return null)
            val raw = json.optJSONArray("events")?.optJSONObject(0) ?: return null
            val promotion = Promotion.values().firstOrNull { it.leagueId == raw.optInt("idLeague") } ?: Promotion.BOXING
            showOf(raw, promotion)
        }.getOrNull()
    }

    /** A show's page: its poster, description and card (each fight or match a line). */
    fun details(showId: String): SportsShowDetails? {
        val show = show(showId) ?: return null
        return SportsShowDetails(
            promotion = show.promotion,
            posterUrl = show.posterUrl,
            imageUrl = show.imageUrl,
            description = show.description,
            card = show.card.orEmpty().split(Regex("""\r?\n""")).map { it.trim() }.filter { it.isNotBlank() }
        )
    }

    // ------------------------------------------------------------------ keeping the shows

    private fun shows(waitWhenEmpty: Boolean): List<Show> {
        val current = loaded()
        val now = System.currentTimeMillis()
        val stale = Promotion.values().any { promotion ->
            val kept = current[promotion.name]
            kept == null || now - kept.savedAt > REFRESH_MS
        }
        if (stale) {
            val job = synchronized(lock) {
                refreshing?.takeUnless { it.isDone } ?: refresher.submit(Runnable { refresh() }).also { refreshing = it }
            }
            if (waitWhenEmpty && current.values.all { it.shows.isEmpty() }) {
                runCatching { job.get(FIRST_WAIT_MS, TimeUnit.MILLISECONDS) }
            }
        }
        return loaded().values.flatMap { it.shows }.distinctBy { it.id }
    }

    private fun loaded(): Map<String, Saved> {
        saved?.let { return it }
        synchronized(lock) {
            saved?.let { return it }
            val fromDisk = runCatching { readCache() }.getOrDefault(emptyMap())
            saved = fromDisk
            return fromDisk
        }
    }

    /** Each promotion's next show and the rounds around it; stops for now when the minute's requests are used. */
    private fun refresh() {
        for (promotion in Promotion.values()) {
            val kept = loaded()[promotion.name]
            if (kept != null && System.currentTimeMillis() - kept.savedAt <= REFRESH_MS) continue
            val updated = runCatching { refreshPromotion(promotion, kept) }.getOrNull() ?: return
            synchronized(lock) {
                saved = (saved ?: emptyMap()) + (promotion.name to updated)
                runCatching { writeCache(saved!!) }
            }
        }
    }

    private fun refreshPromotion(promotion: Promotion, kept: Saved?): Saved? {
        val now = System.currentTimeMillis()
        val nextJson = SportsDbRequests.get(okHttpClient, "eventsnextleague.php?id=${promotion.leagueId}") ?: return null
        val next = JSONObject(nextJson).optJSONArray("events")?.optJSONObject(0)
        if (next == null) {
            // None coming (between seasons): what was kept, looked at again later.
            return Saved(now, kept?.nextRound ?: 0, kept?.season.orEmpty(), kept?.shows.orEmpty())
        }
        val round = next.optString("intRound").toIntOrNull() ?: 0
        val season = next.str("strSeason") ?: LocalDate.now(ZoneOffset.UTC).year.toString()
        if (round <= 0) {
            val show = showOf(next, promotion)
            return Saved(now, 0, season, listOfNotNull(show))
        }
        // The rounds already kept that are over stay as they are; the rest are looked at again.
        val keptShows = if (kept != null && kept.season == season) kept.shows else emptyList()
        val shows = ArrayList<Show>()
        var emptyInARow = 0
        for (r in (round - promotion.back).coerceAtLeast(1)..(round + promotion.ahead)) {
            val oldShows = keptShows.filter { it.round == r }
            if (r < round - 1 && oldShows.isNotEmpty() && oldShows.all { it.isSettled(now) }) {
                shows += oldShows
                continue
            }
            val body = SportsDbRequests.get(okHttpClient, "eventsround.php?id=${promotion.leagueId}&r=$r&s=$season")
            if (body == null) {
                // Out of requests for this minute: what was kept for the rest, the next look finishes it.
                shows += keptShows.filter { it.round >= r }
                return Saved(now - REFRESH_MS + RETRY_MS, round, season, shows.distinctBy { it.id })
            }
            val events = JSONObject(body).optJSONArray("events")
            val found = (0 until (events?.length() ?: 0)).mapNotNull { index ->
                events?.optJSONObject(index)?.let { showOf(it, promotion) }
            }
            // No event at all in the round (not just weekly TV filtered out): past the last one.
            if ((events?.length() ?: 0) == 0 && r > round) {
                emptyInARow++
                if (emptyInARow >= 2) break
            } else {
                emptyInARow = 0
            }
            shows += found
        }
        return Saved(now, round, season, shows.distinctBy { it.id })
    }

    /** A TheSportsDB event as a show; null for a promotion's weekly TV shows (not PPV). */
    private fun showOf(raw: JSONObject, promotion: Promotion): Show? {
        val id = raw.str("idEvent") ?: return null
        val name = raw.str("strEvent") ?: return null
        if (promotion.sport == SPORT_WRESTLING && isWeeklyShow(name)) return null
        val (start, tba) = startOf(raw)
        val status = raw.str("strStatus")?.lowercase(Locale.US)
        val finished = status in FINISHED_STATUSES
        return Show(
            id = id,
            promotion = promotion.label,
            sport = promotion.sport,
            name = name.trim(),
            startMs = start,
            timeTba = tba,
            venue = listOfNotNull(raw.str("strVenue"), raw.str("strCity")).distinct().joinToString(", ").ifBlank { raw.str("strCountry") },
            imageUrl = raw.str("strThumb") ?: raw.str("strFanart") ?: raw.str("strBanner"),
            posterUrl = raw.str("strPoster"),
            badgeUrl = raw.str("strLeagueBadge"),
            description = raw.str("strDescriptionEN"),
            card = raw.str("strResult"),
            finished = finished,
            round = raw.optString("intRound").toIntOrNull() ?: 0
        )
    }

    /**
     * When it starts: TheSportsDB's time (UTC). "00:00" with no local time is a day with no time
     * yet: its day then, put in the evening (Eastern), and marked TBA.
     */
    private fun startOf(raw: JSONObject): Pair<Long?, Boolean> {
        val time = raw.str("strTime")
        val localTime = raw.str("strTimeLocal")
        val timeKnown = (time != null && !time.startsWith("00:00")) || (localTime != null && !localTime.startsWith("00:00"))
        if (timeKnown) {
            parseUtc(raw.str("strTimestamp"))?.let { return it to false }
            val day = raw.str("dateEvent")
            if (day != null && time != null) parseUtc("${day.take(10)}T${time.take(8)}")?.let { return it to false }
        }
        val day = raw.str("dateEventLocal") ?: raw.str("dateEvent") ?: return null to true
        val date = runCatching { LocalDate.parse(day.take(10)) }.getOrNull() ?: return null to true
        return date.atTime(DEFAULT_HOUR, 0).atZone(EASTERN).toInstant().toEpochMilli() to true
    }

    // ------------------------------------------------------------------ the show as an event

    private fun eventOf(show: Show, now: Long): SportsEvent {
        val start = show.startMs
        val status = when {
            show.finished -> SportsEventStatus.FINISHED
            start == null -> SportsEventStatus.UPCOMING
            now >= start + SHOW_MS -> SportsEventStatus.FINISHED
            !show.timeTba && now >= start -> SportsEventStatus.LIVE
            else -> SportsEventStatus.UPCOMING
        }
        // A fight: its two fighters (what the TV guide names); a wrestling show: the show and the promotion.
        val fighters = if (show.sport == SPORT_BOXING) {
            show.name.split(VS, limit = 2).map { it.trim() }.takeIf { it.size == 2 && it.all { name -> name.isNotBlank() } }
        } else {
            null
        }
        val homeName = fighters?.get(0) ?: show.name
        val awayName = fighters?.get(1) ?: show.promotion
        return SportsEvent(
            id = "espn:$SPORT_PATH:$LEAGUE_PATH:${show.id}",
            name = show.name,
            sportName = show.sport,
            leagueId = LEAGUE_ID,
            leagueName = LEAGUE_NAME,
            homeTeamId = null,
            homeTeamName = homeName,
            homeTeamBadgeUrl = show.badgeUrl ?: LEAGUE_LOGO,
            awayTeamId = null,
            awayTeamName = awayName,
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = start,
            venue = show.venue,
            status = status,
            statusDetail = when {
                status == SportsEventStatus.UPCOMING && show.timeTba -> com.nuvio.tv.data.remote.espn.BananaBallSource.TIME_TBA
                status == SportsEventStatus.FINISHED -> "Final"
                else -> null
            },
            homeTeamAliases = if (fighters != null) listOfNotNull(lastName(homeName)) else listOf("${show.promotion} ${show.name}"),
            awayTeamAliases = if (fighters != null) listOfNotNull(lastName(awayName)) else promotionAliases(show.promotion),
            imageUrl = show.imageUrl ?: show.posterUrl
        )
    }

    // ------------------------------------------------------------------ the disk

    private fun readCache(): Map<String, Saved> {
        val file = cacheFile?.takeIf { it.exists() } ?: return emptyMap()
        val json = JSONObject(file.readText())
        if (json.optInt("version") != CACHE_VERSION) return emptyMap()
        val result = HashMap<String, Saved>()
        val promotions = json.optJSONObject("promotions") ?: return emptyMap()
        promotions.keys().forEach { key ->
            val entry = promotions.optJSONObject(key) ?: return@forEach
            val shows = entry.optJSONArray("shows") ?: JSONArray()
            result[key] = Saved(
                savedAt = entry.optLong("savedAt"),
                nextRound = entry.optInt("nextRound"),
                season = entry.optString("season"),
                shows = (0 until shows.length()).mapNotNull { index -> shows.optJSONObject(index)?.let(::showFromJson) }
            )
        }
        return result
    }

    private fun writeCache(map: Map<String, Saved>) {
        val file = cacheFile ?: return
        val promotions = JSONObject()
        map.forEach { (key, value) ->
            promotions.put(
                key,
                JSONObject()
                    .put("savedAt", value.savedAt)
                    .put("nextRound", value.nextRound)
                    .put("season", value.season)
                    .put("shows", JSONArray().apply { value.shows.forEach { put(showToJson(it)) } })
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("version", CACHE_VERSION).put("promotions", promotions).toString())
    }

    private fun showToJson(show: Show): JSONObject = JSONObject()
        .put("id", show.id)
        .put("promotion", show.promotion)
        .put("sport", show.sport)
        .put("name", show.name)
        .putOpt("startMs", show.startMs)
        .put("timeTba", show.timeTba)
        .putOpt("venue", show.venue)
        .putOpt("imageUrl", show.imageUrl)
        .putOpt("posterUrl", show.posterUrl)
        .putOpt("badgeUrl", show.badgeUrl)
        .putOpt("description", show.description)
        .putOpt("card", show.card)
        .put("finished", show.finished)
        .put("round", show.round)

    private fun showFromJson(json: JSONObject): Show? {
        val id = json.str("id") ?: return null
        return Show(
            id = id,
            promotion = json.str("promotion").orEmpty(),
            sport = json.str("sport") ?: SPORT_BOXING,
            name = json.str("name") ?: return null,
            startMs = if (json.has("startMs")) json.optLong("startMs") else null,
            timeTba = json.optBoolean("timeTba"),
            venue = json.str("venue"),
            imageUrl = json.str("imageUrl"),
            posterUrl = json.str("posterUrl"),
            badgeUrl = json.str("badgeUrl"),
            description = json.str("description"),
            card = json.str("card"),
            finished = json.optBoolean("finished"),
            round = json.optInt("round", 0)
        )
    }

    private fun JSONObject.str(key: String): String? = optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val SPORT_PATH = "ppv"
        const val LEAGUE_PATH = "ppv-events"

        /** Boxing's sanctioning bodies and big promoters: each a league of the fights it's in. */
        data class BoxingLeague(val leaguePath: String, val name: String, val logo: String, private val words: Regex) {
            fun matches(show: Show): Boolean =
                words.containsMatchIn(listOfNotNull(show.name, show.description, show.card).joinToString("\n"))
        }

        val BOXING_LEAGUES = listOf(
            BoxingLeague("boxing-wbc", "WBC", favicon("wbcboxing.com"), Regex("""\bWBC\b|World Boxing Council""", RegexOption.IGNORE_CASE)),
            BoxingLeague("boxing-wba", "WBA", favicon("wbaboxing.com"), Regex("""\bWBA\b|World Boxing Association""", RegexOption.IGNORE_CASE)),
            BoxingLeague("boxing-ibf", "IBF", favicon("ibf-usba-boxing.com"), Regex("""\bIBF\b|International Boxing Federation""", RegexOption.IGNORE_CASE)),
            BoxingLeague("boxing-wbo", "WBO", favicon("wboboxing.com"), Regex("""\bWBO\b|World Boxing Organi[sz]ation""", RegexOption.IGNORE_CASE)),
            BoxingLeague("boxing-top-rank", "Top Rank", favicon("toprank.com"), Regex("""\bTop\s*Rank\b""", RegexOption.IGNORE_CASE)),
            BoxingLeague("boxing-pbc", "PBC", favicon("premierboxingchampions.com"), Regex("""\bPBC\b|Premier Boxing Champions""", RegexOption.IGNORE_CASE)),
            BoxingLeague("boxing-matchroom", "Matchroom Boxing", favicon("matchroomboxing.com"), Regex("""\bMatchroom\b""", RegexOption.IGNORE_CASE))
        )

        /** PPV Events and the boxing leagues: every league this source answers for. */
        val LEAGUE_PATHS: Set<String> = setOf(LEAGUE_PATH) + BOXING_LEAGUES.map { it.leaguePath }

        fun boxingLeagueOf(leaguePath: String): BoxingLeague? = BOXING_LEAGUES.firstOrNull { it.leaguePath == leaguePath }

        /** A site's own icon, big (the bodies and promoters have no logo anywhere open). */
        private fun favicon(domain: String): String = "https://www.google.com/s2/favicons?domain=$domain&sz=256"
        const val LEAGUE_ID = "$SPORT_PATH:$LEAGUE_PATH"
        const val LEAGUE_NAME = "PPV Events"
        const val SPORT_LABEL = "PPV"
        const val SPORT_BOXING = "Boxing"
        const val SPORT_WRESTLING = "Wrestling"
        /** TheSportsDB's boxing badge. */
        const val LEAGUE_LOGO = "https://r2.thesportsdb.com/images/media/league/badge/j14hx41784791003.png"

        private const val CACHE_VERSION = 1
        private const val REFRESH_MS = 3L * 60 * 60 * 1000
        private const val RETRY_MS = 2L * 60 * 1000
        private const val FIRST_WAIT_MS = 8_000L
        /** A fight card or a wrestling show: about four hours, five at most. */
        private const val SHOW_MS = 5L * 60 * 60 * 1000
        private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        private const val DEFAULT_HOUR = 20
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")
        private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
        private val FINISHED_STATUSES = setOf("ft", "match finished", "finished", "aet", "final")
        private val VS = Regex("""\s+(?:vs\.?|v\.?)\s+""", RegexOption.IGNORE_CASE)
        private val NAME_SUFFIXES = setOf("jr", "jr.", "sr", "sr.", "ii", "iii", "iv")

        /** WWE's and AEW's weekly TV ("RAW #1743", "Collision #164", "SmackDown"): not PPV. */
        private val WEEKLY_SHOW = Regex(
            """^(wwe\s+)?(raw|smackdown|nxt|main event|speed|dynamite|collision|rampage|dark|elevation|evolve|lfg|aew dark)(\s*#?\s*\d+)?$""",
            RegexOption.IGNORE_CASE
        )

        fun isWeeklyShow(name: String): Boolean {
            val trimmed = name.trim()
            if (Regex("""#\s*\d+""").containsMatchIn(trimmed)) return true
            if (trimmed.startsWith("Saturday Night", ignoreCase = true)) return true
            return WEEKLY_SHOW.matches(trimmed)
        }

        /** "Sebastian Fundora" → "Fundora"; "Raul Curiel II" → "Curiel". */
        fun lastName(name: String): String? {
            val words = name.split(' ').filter { it.isNotBlank() }
            if (words.size < 2) return null
            val last = words.lastOrNull { it.lowercase(Locale.US) !in NAME_SUFFIXES } ?: return null
            return last.takeIf { it.length >= 3 && it != words.first() }
        }

        private fun promotionAliases(promotion: String): List<String> = when (promotion) {
            "WWE" -> listOf("WWE Premium Live Event", "WWE PLE")
            "AEW" -> listOf("All Elite Wrestling", "AEW PPV")
            else -> emptyList()
        }

        private fun parseUtc(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            return runCatching { LocalDateTime.parse(text.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }

        private fun rangeOf(datesRange: String?): Pair<String?, String?> {
            if (datesRange.isNullOrBlank()) return null to null
            val parts = datesRange.split('-')
            return parts.first() to parts.last()
        }

        private fun inRange(startMs: Long?, from: String?, to: String?, now: Long): Boolean {
            val start = startMs ?: return false
            if (from == null || to == null) return start >= now - THREE_DAYS_MS
            val day = DAY_KEY.format(Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC))
            return day in from..to
        }
    }
}
