package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsRaceResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * NASCAR's three national series (Cup, O'Reilly Auto Parts and Craftsman Truck) from NASCAR's own
 * feeds (cf.nascar.com, what nascar.com reads; ESPN's only has the schedule): every race of the
 * season with its track, start and TV network; while a race is on, its lap and running order; once
 * it's run, its winner and results.
 */
internal class NascarSource(private val okHttpClient: OkHttpClient) {

    /** One of the series: its league on the Sports pages (ESPN's slugs, so news comes from ESPN). */
    data class Series(val leaguePath: String, val seriesId: Int, val name: String, val aliases: List<String>)

    /** A race of the season (the race list's). */
    data class Race(
        val id: Long,
        val seriesId: Int,
        val season: Int,
        val name: String,
        val track: String,
        val startMs: Long?,
        val tv: String?,
        val winnerDriverId: Long?,
        val laps: Int?
    )

    /** The race on now, per NASCAR's live feed. */
    private data class LiveRace(val raceId: Long, val lap: Int, val laps: Int, val order: List<SportsRaceResult>)

    @Volatile private var seasons: Map<Int, Pair<Long, List<Race>>> = emptyMap()
    private val seasonLock = Any()
    private val results = ConcurrentHashMap<Long, Pair<Long, List<SportsRaceResult>>>()
    @Volatile private var live: Pair<Long, LiveRace?>? = null

    fun league(series: Series): SportsLeague = SportsLeague(
        id = "$SPORT_PATH:${series.leaguePath}",
        name = series.name,
        sportName = SPORT_LABEL,
        badgeUrl = LOGO,
        country = "USA"
    )

    /** The series' races in [datesRange] (ESPN's "yyyyMMdd[-yyyyMMdd]", UTC days); none given, from three days ago on. */
    fun fetchFeed(feed: EspnSportsClient.Feed, datesRange: String?): EspnSportsClient.FeedResult {
        val series = seriesOf(feed.leaguePath) ?: throw EspnSportsClient.EspnException("No NASCAR series")
        val now = System.currentTimeMillis()
        val (from, to) = BananaBallSchedule.rangeOf(datesRange)
        val years = yearsOf(from, to, now)
        val races = years.flatMap { season(it) }
            .filter { it.seriesId == series.seriesId }
            .filter { race -> inRange(race.startMs, from, to, now) }
            .sortedBy { it.startMs ?: Long.MAX_VALUE }
        // The race on now: its lap and leader. The ones run: their winners (a few at most).
        val liveRace = if (races.any { isOnNow(it, now) }) liveRace() else null
        var lookups = 0
        val events = races.map { race ->
            val running = liveRace?.takeIf { it.raceId == race.id }
            val winner = if (running == null && isOver(race, now) && lookups < MAX_RESULT_LOOKUPS) {
                if (results[race.id] == null) lookups++
                runCatching { resultsOf(race) }.getOrDefault(emptyList()).firstOrNull()
            } else {
                null
            }
            eventOf(race, series, now, running, winner)
        }
        return EspnSportsClient.FeedResult(feed = feed, league = league(series), events = events, teams = emptyList())
    }

    /**
     * A race's places: the running order while it's on (NASCAR's live feed), else its results
     * (empty before it's run).
     */
    fun raceResults(leaguePath: String, raceId: Long, startTimeMs: Long?): List<SportsRaceResult> {
        val series = seriesOf(leaguePath) ?: return emptyList()
        liveRace()?.takeIf { it.raceId == raceId && it.order.isNotEmpty() }?.let { return it.order }
        val now = System.currentTimeMillis()
        val year = startTimeMs?.let { Instant.ofEpochMilli(it).atZone(EASTERN).year } ?: Instant.ofEpochMilli(now).atZone(EASTERN).year
        val race = season(year).firstOrNull { it.id == raceId }
            ?: Race(raceId, series.seriesId, year, "", "", startTimeMs, null, null, null)
        return resultsOf(race)
    }

    // ------------------------------------------------------------------ the season

    private fun season(year: Int): List<Race> {
        seasons[year]?.let { (savedAt, races) -> if (System.currentTimeMillis() - savedAt < CACHE_MS) return races }
        synchronized(seasonLock) {
            val now = System.currentTimeMillis()
            val saved = seasons[year]
            if (saved != null && now - saved.first < CACHE_MS) return saved.second
            val fetched = runCatching { parseSeason(get("$BASE/cacher/$year/race_list_basic.json"), year) }.getOrNull()
            // Not reachable (or no season yet): what was found before, tried again in a few minutes.
            val races = fetched ?: saved?.second ?: emptyList()
            val savedAt = if (fetched != null) now else now - CACHE_MS + RETRY_MS
            seasons = seasons + (year to (savedAt to races))
            return races
        }
    }

    private fun parseSeason(body: String, year: Int): List<Race> {
        val json = JSONObject(body)
        val races = ArrayList<Race>()
        SERIES.forEach { series ->
            val array = json.optJSONArray("series_${series.seriesId}") ?: return@forEach
            for (i in 0 until array.length()) {
                val race = array.optJSONObject(i) ?: continue
                raceOf(race, series.seriesId, year)?.let { races += it }
            }
        }
        return races
    }

    private fun raceOf(json: JSONObject, seriesId: Int, year: Int): Race? {
        val id = json.optLong("race_id", 0L).takeIf { it > 0 } ?: return null
        val name = json.str("race_name") ?: return null
        return Race(
            id = id,
            seriesId = json.optInt("series_id", seriesId),
            season = json.optInt("race_season", year),
            name = name,
            track = json.str("track_name").orEmpty(),
            startMs = raceStartOf(json),
            tv = json.str("television_broadcaster"),
            winnerDriverId = json.optLong("winner_driver_id", 0L).takeIf { it > 0 },
            laps = json.optInt("scheduled_laps", 0).takeIf { it > 0 }
        )
    }

    /** The race's start: the "Race" of its weekend's schedule (UTC); else its day and time (Eastern). */
    private fun raceStartOf(json: JSONObject): Long? {
        val schedule = json.optJSONArray("schedule")
        if (schedule != null) {
            for (i in 0 until schedule.length()) {
                val entry = schedule.optJSONObject(i) ?: continue
                if (entry.optInt("run_type") != RUN_TYPE_RACE) continue
                parseLocal(entry.str("start_time_utc"), ZoneOffset.UTC)?.let { return it }
            }
        }
        return parseLocal(json.str("date_scheduled") ?: json.str("race_date"), EASTERN)
    }

    // ------------------------------------------------------------------ results and the race on now

    private fun resultsOf(race: Race): List<SportsRaceResult> {
        val now = System.currentTimeMillis()
        results[race.id]?.let { (savedAt, rows) ->
            // Results don't change once they're in; until then, looked at again now and then.
            if (rows.isNotEmpty() || now - savedAt < RETRY_MS) return rows
        }
        val rows = runCatching {
            val json = JSONObject(get("$BASE/cacher/${race.season}/${race.seriesId}/${race.id}/weekend-feed.json"))
            val weekendRace = json.optJSONArray("weekend_race")?.optJSONObject(0)
            resultRowsOf(weekendRace?.optJSONArray("results"), race.seriesId)
        }.getOrNull()
        if (rows != null) results[race.id] = now to rows
        return rows ?: emptyList()
    }

    private fun resultRowsOf(array: JSONArray?, seriesId: Int): List<SportsRaceResult> {
        array ?: return emptyList()
        val rows = ArrayList<SportsRaceResult>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val driver = row.str("driver_fullname") ?: continue
            val number = row.str("car_number") ?: row.str("official_car_number").orEmpty()
            rows += SportsRaceResult(
                position = row.optInt("finishing_position", i + 1),
                driver = cleanDriverName(driver),
                carNumber = number,
                carBadgeUrl = number.takeIf { it.isNotBlank() }?.let { carBadgeUrl(seriesId, it) },
                team = row.str("team_name"),
                laps = row.optInt("laps_completed", -1).takeIf { it >= 0 },
                lapsLed = row.optInt("laps_led", -1).takeIf { it >= 0 },
                status = row.str("finishing_status"),
                points = row.optInt("points_earned", -1).takeIf { it >= 0 }
            )
        }
        return rows.sortedBy { it.position }
    }

    /** The race on track now (a race, not practice or qualifying), as NASCAR's live feed has it. */
    private fun liveRace(): LiveRace? {
        val now = System.currentTimeMillis()
        live?.let { (savedAt, race) -> if (now - savedAt < LIVE_CACHE_MS) return race }
        val race = runCatching {
            val json = JSONObject(get("$BASE/live/feeds/live-feed.json"))
            val raceId = json.optLong("race_id", 0L)
            val seriesId = json.optInt("series_id", 0)
            if (raceId <= 0 || json.optInt("run_type") != RUN_TYPE_RACE || SERIES.none { it.seriesId == seriesId }) {
                null
            } else {
                LiveRace(
                    raceId = raceId,
                    lap = json.optInt("lap_number", 0),
                    laps = json.optInt("laps_in_race", 0),
                    order = runningOrderOf(json.optJSONArray("vehicles"), seriesId)
                )
            }
        }.getOrNull()
        live = now to race
        return race
    }

    private fun runningOrderOf(vehicles: JSONArray?, seriesId: Int): List<SportsRaceResult> {
        vehicles ?: return emptyList()
        val rows = ArrayList<SportsRaceResult>(vehicles.length())
        for (i in 0 until vehicles.length()) {
            val vehicle = vehicles.optJSONObject(i) ?: continue
            val driverJson = vehicle.optJSONObject("driver")
            val driver = driverJson?.str("full_name") ?: vehicle.str("driver_name") ?: continue
            val number = vehicle.str("vehicle_number").orEmpty()
            val position = vehicle.optInt("running_position", i + 1)
            val delta = vehicle.optDouble("delta")
            rows += SportsRaceResult(
                position = position,
                driver = cleanDriverName(driver),
                carNumber = number,
                carBadgeUrl = number.takeIf { it.isNotBlank() }?.let { carBadgeUrl(seriesId, it) },
                team = null,
                laps = vehicle.optInt("laps_completed", -1).takeIf { it >= 0 },
                lapsLed = null,
                status = when {
                    position == 1 || delta.isNaN() -> null
                    // Laps down come as whole negative numbers; else seconds behind.
                    delta < 0 && delta == Math.floor(delta) -> "${delta.toInt()} L"
                    else -> String.format(Locale.US, "+%.3f", kotlin.math.abs(delta))
                },
                points = null
            )
        }
        return rows.sortedBy { it.position }
    }

    // ------------------------------------------------------------------ the race as an event

    private fun eventOf(race: Race, series: Series, now: Long, running: LiveRace?, winner: SportsRaceResult?): SportsEvent {
        val status = when {
            running != null -> SportsEventStatus.LIVE
            race.winnerDriverId != null || winner != null -> SportsEventStatus.FINISHED
            isOver(race, now) -> SportsEventStatus.FINISHED
            isOnNow(race, now) -> SportsEventStatus.LIVE
            else -> SportsEventStatus.UPCOMING
        }
        val leader = running?.order?.firstOrNull() ?: winner
        val trackShort = shortTrackName(race.track)
        return SportsEvent(
            id = "espn:$SPORT_PATH:${series.leaguePath}:${race.id}",
            name = race.name,
            sportName = SPORT_LABEL,
            leagueId = "$SPORT_PATH:${series.leaguePath}",
            leagueName = series.name,
            homeTeamId = null,
            // The race and the series stand for the two "teams": what the TV guide names.
            homeTeamName = race.name,
            homeTeamBadgeUrl = LOGO,
            awayTeamId = null,
            awayTeamName = series.name,
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = race.startMs,
            // Where, and on what ("Talladega Superspeedway • FS1").
            venue = listOfNotNull(race.track.takeIf { it.isNotBlank() }, race.tv).joinToString(" • ").ifBlank { null },
            status = status,
            statusDetail = when (status) {
                SportsEventStatus.LIVE -> running?.takeIf { it.lap > 0 }?.let { lap ->
                    if (lap.laps in 1..998) "Lap ${lap.lap}/${lap.laps}" else "Lap ${lap.lap}"
                }
                SportsEventStatus.FINISHED -> "Final"
                SportsEventStatus.UPCOMING -> null
            },
            homeTeamAliases = listOfNotNull(race.track.takeIf { it.isNotBlank() }, trackShort).distinct(),
            awayTeamAliases = series.aliases + "NASCAR",
            leaderName = leader?.driver,
            leaderScore = leader?.carNumber?.takeIf { it.isNotBlank() }?.let { "#$it" }
        )
    }

    private fun isOnNow(race: Race, now: Long): Boolean {
        val start = race.startMs ?: return false
        return race.winnerDriverId == null && now >= start && now < start + RACE_MS
    }

    private fun isOver(race: Race, now: Long): Boolean {
        if (race.winnerDriverId != null) return true
        val start = race.startMs ?: return false
        return now >= start + RACE_MS
    }

    // ------------------------------------------------------------------ helpers

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EspnSportsClient.EspnException("HTTP ${response.code}")
            return boundedBody(response)
        }
    }

    private fun JSONObject.str(key: String): String? = optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val SPORT_PATH = "racing"
        const val SPORT_LABEL = "Motorsport"

        val SERIES = listOf(
            Series("nascar-premier", 1, "NASCAR Cup Series", listOf("NASCAR Cup", "Cup Series")),
            Series(
                "nascar-secondary", 2, "NASCAR O'Reilly Auto Parts Series",
                listOf("O'Reilly Auto Parts Series", "NASCAR O'Reilly", "NASCAR Xfinity Series", "Xfinity Series")
            ),
            Series(
                "nascar-truck", 3, "NASCAR Craftsman Truck Series",
                listOf("NASCAR Truck Series", "Craftsman Truck Series", "Truck Series")
            )
        )

        val LEAGUE_PATHS: Set<String> = SERIES.map { it.leaguePath }.toSet()

        const val LOGO = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-NASCAR.png"

        private const val BASE = "https://cf.nascar.com"
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")
        private const val RUN_TYPE_RACE = 3
        /** How long a race lasts by the clock, at most (rain delays aside). */
        private const val RACE_MS = 4L * 60 * 60 * 1000 + 30 * 60 * 1000
        private const val CACHE_MS = 30L * 60 * 1000
        private const val RETRY_MS = 3L * 60 * 1000
        private const val LIVE_CACHE_MS = 20L * 1000
        private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        /** Winners looked up per load (each is one request; they're kept after). */
        private const val MAX_RESULT_LOOKUPS = 6
        private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

        fun seriesOf(leaguePath: String): Series? = SERIES.firstOrNull { it.leaguePath == leaguePath }

        /** A car's number badge, as nascar.com shows it. */
        fun carBadgeUrl(seriesId: Int, carNumber: String): String = "$BASE/data/images/carbadges/$seriesId/$carNumber.png"

        /** "Talladega Superspeedway" → "Talladega"; "Phoenix Raceway" → "Phoenix". */
        fun shortTrackName(track: String): String? {
            val words = track.split(' ').filter { it.isNotBlank() }
            val end = words.indexOfFirst { it.lowercase(Locale.US) in TRACK_WORDS }
            if (end <= 0) return null
            return words.take(end).joinToString(" ").takeIf { it.length >= 4 && !it.equals(track, ignoreCase = true) }
        }

        private val TRACK_WORDS = setOf(
            "superspeedway", "speedway", "raceway", "motor", "international", "motorsports", "street", "road", "circuit"
        )

        /** "Kyle Larson(i)" / "* Chase Elliott": the name alone. */
        private fun cleanDriverName(name: String): String =
            name.replace(Regex("""\((i|R|P)\)"""), "").replace("*", "").replace("#", "").trim()

        private fun parseLocal(text: String?, zone: ZoneId): Long? {
            if (text.isNullOrBlank()) return null
            return runCatching { LocalDateTime.parse(text.take(19)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        }

        /** The seasons (years) the days asked for fall in. */
        private fun yearsOf(from: String?, to: String?, now: Long): List<Int> {
            val thisYear = Instant.ofEpochMilli(now).atZone(EASTERN).year
            val first = from?.take(4)?.toIntOrNull() ?: thisYear
            val last = to?.take(4)?.toIntOrNull() ?: thisYear
            return (minOf(first, last)..maxOf(first, last)).toList().take(3)
        }

        private fun inRange(startMs: Long?, from: String?, to: String?, now: Long): Boolean {
            val start = startMs ?: return false
            if (from == null || to == null) return start >= now - THREE_DAYS_MS
            val day = DAY_KEY.format(Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC))
            return day in from..to
        }
    }
}
