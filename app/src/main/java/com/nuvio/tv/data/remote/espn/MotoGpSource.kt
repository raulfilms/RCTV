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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * MotoGP, from the championship's own results API (what motogp.com reads; ESPN doesn't have it):
 * the season's Grands Prix with their circuits, each one's race (its start, its winner) and the
 * race's classification.
 */
internal class MotoGpSource(private val okHttpClient: OkHttpClient) {

    /** A Grand Prix (not a test). */
    private data class GrandPrix(
        val id: String,
        val name: String,
        val sponsoredName: String?,
        val country: String?,
        val countryIso: String?,
        val circuit: String?,
        val start: LocalDate?,
        val end: LocalDate?,
        val finished: Boolean
    )

    @Volatile private var seasons: Pair<Long, Map<Int, String>>? = null
    private val events = ConcurrentHashMap<Int, Pair<Long, List<GrandPrix>>>()
    /** Each Grand Prix's race session: its id and start (UTC), once looked up. */
    private val races = ConcurrentHashMap<String, Pair<Long, RaceSession?>>()
    private val classifications = ConcurrentHashMap<String, Pair<Long, List<SportsRaceResult>>>()

    private data class RaceSession(val id: String, val startMs: Long?, val finished: Boolean)

    val league: SportsLeague = SportsLeague(
        id = LEAGUE_ID,
        name = LEAGUE_NAME,
        sportName = SPORT_LABEL,
        badgeUrl = LOGO,
        country = null
    )

    /** The Grands Prix whose race falls in [datesRange] (ESPN's "yyyyMMdd[-yyyyMMdd]"); none given, from three days ago on. */
    fun fetchFeed(feed: EspnSportsClient.Feed, datesRange: String?): EspnSportsClient.FeedResult {
        val now = System.currentTimeMillis()
        val (from, to) = rangeOf(datesRange)
        val years = yearsOf(from, to, now)
        val inRange = years.flatMap { grandsPrix(it) }
            .filter { gp -> inRange(estimatedRaceStart(gp), from, to, now) }
            .sortedBy { estimatedRaceStart(it) ?: Long.MAX_VALUE }
        var lookups = 0
        val result = inRange.map { gp ->
            // The race's real start (and winner) for the ones near now; the rest by their days.
            val near = estimatedRaceStart(gp)?.let { kotlin.math.abs(it - now) < NEAR_MS } == true
            val session = if (near && lookups < MAX_LOOKUPS) {
                if (races[gp.id] == null) lookups++
                raceSession(gp)
            } else {
                races[gp.id]?.second
            }
            val start = session?.startMs ?: estimatedRaceStart(gp)
            val over = gp.finished || session?.finished == true || (start != null && now > start + RACE_MS)
            val winner = if (over && session != null && lookups < MAX_LOOKUPS) {
                if (classifications[session.id] == null) lookups++
                runCatching { classification(session.id) }.getOrDefault(emptyList()).firstOrNull()
            } else {
                null
            }
            eventOf(gp, start, over, winner, now)
        }
        return EspnSportsClient.FeedResult(feed = feed, league = league, events = result, teams = emptyList())
    }

    /** A Grand Prix's race classification (MotoGP class): empty before it's run. */
    fun raceResults(rawEventId: String): List<SportsRaceResult> {
        val year = LocalDate.now(ZoneOffset.UTC).year
        val gp = (grandsPrix(year) + grandsPrix(year - 1)).firstOrNull { it.id == rawEventId }
            ?: GrandPrix(rawEventId, "", null, null, null, null, null, null, false)
        val session = raceSession(gp) ?: return emptyList()
        return classification(session.id)
    }

    // ------------------------------------------------------------------ the API

    private fun seasonId(year: Int): String? {
        val now = System.currentTimeMillis()
        val cached = seasons?.takeIf { now - it.first < SEASONS_CACHE_MS }?.second
        val map = cached ?: runCatching {
            val array = JSONArray(get("$API/results/seasons"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .associate { it.optInt("year") to it.optString("id") }
        }.getOrNull()?.also { seasons = now to it } ?: return null
        return map[year]
    }

    private fun grandsPrix(year: Int): List<GrandPrix> {
        val now = System.currentTimeMillis()
        events[year]?.let { (savedAt, list) -> if (now - savedAt < EVENTS_CACHE_MS) return list }
        val season = seasonId(year) ?: return events[year]?.second.orEmpty()
        val list = runCatching {
            val array = JSONArray(get("$API/results/events?seasonUuid=$season"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filterNot { it.optBoolean("test") }
                .mapNotNull(::grandPrixOf)
        }.getOrNull()
        if (list != null) events[year] = now to list
        return list ?: events[year]?.second.orEmpty()
    }

    private fun grandPrixOf(json: JSONObject): GrandPrix? {
        val id = json.str("id") ?: return null
        val country = json.optJSONObject("country")
        return GrandPrix(
            id = id,
            name = titleCase(json.str("name") ?: return null),
            sponsoredName = json.str("sponsored_name")?.let(::titleCase),
            country = country?.str("name"),
            countryIso = country?.str("iso"),
            circuit = json.optJSONObject("circuit")?.str("name"),
            start = json.str("date_start")?.let(::parseDay),
            end = json.str("date_end")?.let(::parseDay),
            finished = json.str("status").equals("FINISHED", ignoreCase = true)
        )
    }

    /** The Grand Prix's MotoGP race session (its start in the circuit's own time, made UTC). */
    private fun raceSession(gp: GrandPrix): RaceSession? {
        val now = System.currentTimeMillis()
        races[gp.id]?.let { (savedAt, session) ->
            val fresh = if (session?.finished == true) true else now - savedAt < SESSION_CACHE_MS
            if (fresh) return session
        }
        val session = runCatching {
            val array = JSONArray(get("$API/results/sessions?eventUuid=${gp.id}&categoryUuid=$MOTOGP_CATEGORY"))
            val race = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .firstOrNull { it.str("type").equals("RAC", ignoreCase = true) }
                ?: return@runCatching null
            RaceSession(
                id = race.str("id") ?: return@runCatching null,
                startMs = race.str("date")?.let { localStart(it, gp.countryIso) },
                finished = race.str("status").equals("FINISHED", ignoreCase = true)
            )
        }.getOrElse { return races[gp.id]?.second }
        races[gp.id] = now to session
        return session
    }

    private fun classification(sessionId: String): List<SportsRaceResult> {
        val now = System.currentTimeMillis()
        classifications[sessionId]?.let { (savedAt, rows) ->
            if (rows.isNotEmpty() || now - savedAt < SESSION_CACHE_MS) return rows
        }
        val rows = runCatching {
            val json = JSONObject(get("$API/results/session/$sessionId/classification?test=false"))
            val array = json.optJSONArray("classification") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapIndexedNotNull { index, entry ->
                val rider = entry.optJSONObject("rider") ?: return@mapIndexedNotNull null
                val iso = rider.optJSONObject("country")?.str("iso")
                val gap = entry.optJSONObject("gap")?.str("first")
                SportsRaceResult(
                    position = entry.str("position")?.toIntOrNull() ?: (index + 1),
                    driver = rider.str("full_name") ?: return@mapIndexedNotNull null,
                    carNumber = rider.str("number").orEmpty(),
                    carBadgeUrl = iso?.let(::flagOf),
                    team = entry.optJSONObject("team")?.str("name"),
                    laps = entry.str("total_laps")?.toIntOrNull(),
                    lapsLed = null,
                    status = when {
                        entry.str("position") == null -> entry.str("status")?.let(::statusLabel)
                        gap == null || gap.trim('0', '.', ':').isEmpty() -> entry.str("time")
                        else -> "+$gap"
                    },
                    points = entry.str("points")?.toIntOrNull()
                )
            }.sortedBy { it.position }
        }.getOrNull()
        if (rows != null) classifications[sessionId] = now to rows
        return rows ?: emptyList()
    }

    // ------------------------------------------------------------------ the Grand Prix as an event

    private fun eventOf(gp: GrandPrix, start: Long?, over: Boolean, winner: SportsRaceResult?, now: Long): SportsEvent {
        val status = when {
            over -> SportsEventStatus.FINISHED
            start != null && now >= start -> SportsEventStatus.LIVE
            else -> SportsEventStatus.UPCOMING
        }
        // "Grand Prix of Australia" is called "Australian Grand Prix" too.
        val countryName = gp.name.substringAfter(" of ", "").substringAfter(" de ", "").trim()
        return SportsEvent(
            id = "espn:$SPORT_PATH:$LEAGUE_PATH:${gp.id}",
            name = gp.name,
            sportName = SPORT_LABEL,
            leagueId = LEAGUE_ID,
            leagueName = LEAGUE_NAME,
            homeTeamId = null,
            homeTeamName = gp.name,
            homeTeamBadgeUrl = LOGO,
            awayTeamId = null,
            awayTeamName = LEAGUE_NAME,
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = start,
            venue = listOfNotNull(gp.circuit, gp.country).distinct().joinToString(", ").ifBlank { null },
            status = status,
            statusDetail = if (status == SportsEventStatus.FINISHED) "Final" else null,
            homeTeamAliases = listOfNotNull(
                gp.sponsoredName,
                gp.circuit,
                countryName.takeIf { it.length >= 4 }?.let { "$it Grand Prix" },
                countryName.takeIf { it.length >= 4 }?.let { "Gran Premio de $it" }
            ).distinct(),
            awayTeamAliases = listOf("Moto GP"),
            leaderName = winner?.driver,
            leaderScore = winner?.carNumber?.takeIf { it.isNotBlank() }?.let { "#$it" }
        )
    }

    /** Before its race is looked up: the last day of the Grand Prix, 2 PM at the circuit. */
    private fun estimatedRaceStart(gp: GrandPrix): Long? {
        val day = gp.end ?: gp.start ?: return null
        return day.atTime(14, 0).atZone(zoneOf(gp.countryIso)).toInstant().toEpochMilli()
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EspnSportsClient.EspnException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun JSONObject.str(key: String): String? = optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val SPORT_PATH = "racing"
        const val LEAGUE_PATH = "motogp"
        const val LEAGUE_ID = "$SPORT_PATH:$LEAGUE_PATH"
        const val LEAGUE_NAME = "MotoGP"
        const val SPORT_LABEL = "Motorsport"
        const val LOGO = "https://www.google.com/s2/favicons?domain=motogp.com&sz=256"

        private const val API = "https://api.motogp.pulselive.com/motogp/v1"
        /** The MotoGP class (not Moto2 or Moto3). */
        private const val MOTOGP_CATEGORY = "e8c110ad-64aa-4e8e-8a86-f2f152f6a942"
        private const val SEASONS_CACHE_MS = 24L * 60 * 60 * 1000
        private const val EVENTS_CACHE_MS = 60L * 60 * 1000
        private const val SESSION_CACHE_MS = 10L * 60 * 1000
        private const val RACE_MS = 2L * 60 * 60 * 1000
        private const val NEAR_MS = 10L * 24 * 60 * 60 * 1000
        private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        /** Requests per load for the races near now (their starts, their winners); they're kept after. */
        private const val MAX_LOOKUPS = 6
        private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

        /** Where the Grands Prix are: their time zones (the API's times are the circuit's own). */
        private val ZONES = mapOf(
            "TH" to "Asia/Bangkok", "BR" to "America/Sao_Paulo", "US" to "America/Chicago", "AR" to "America/Argentina/Buenos_Aires",
            "ES" to "Europe/Madrid", "FR" to "Europe/Paris", "IT" to "Europe/Rome", "SM" to "Europe/Rome", "HU" to "Europe/Budapest",
            "CZ" to "Europe/Prague", "NL" to "Europe/Amsterdam", "DE" to "Europe/Berlin", "GB" to "Europe/London",
            "AT" to "Europe/Vienna", "JP" to "Asia/Tokyo", "ID" to "Asia/Makassar", "AU" to "Australia/Melbourne",
            "MY" to "Asia/Kuala_Lumpur", "QA" to "Asia/Qatar", "PT" to "Europe/Lisbon", "IN" to "Asia/Kolkata",
            "KZ" to "Asia/Almaty", "SA" to "Asia/Riyadh", "TR" to "Europe/Istanbul", "MX" to "America/Mexico_City"
        )

        private fun zoneOf(iso: String?): ZoneId =
            iso?.uppercase(Locale.US)?.let(ZONES::get)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

        /** "2026-10-11T15:00:00+00:00": the circuit's clock (whatever the offset says), made UTC. */
        private fun localStart(text: String, iso: String?): Long? = runCatching {
            LocalDateTime.parse(text.take(19)).atZone(zoneOf(iso)).toInstant().toEpochMilli()
        }.getOrNull()

        private fun flagOf(iso: String): String = "https://flagcdn.com/w80/${iso.lowercase(Locale.US)}.png"

        /** "DNF", "DNS", "DSQ"... as they read. */
        private fun statusLabel(status: String): String = when (status.uppercase(Locale.US)) {
            "OUTSTND" -> "DNF"
            "NOTSTND" -> "DNS"
            "DSQ" -> "DSQ"
            "INSTND" -> "Running"
            else -> status
        }

        /** "GRAND PRIX OF AUSTRALIA" → "Grand Prix of Australia". */
        private fun titleCase(text: String): String {
            if (text.any { it.isLowerCase() }) return text
            val small = setOf("of", "de", "the", "del", "la", "di")
            return text.lowercase(Locale.US).split(' ').mapIndexed { index, word ->
                if (index > 0 && word in small) word
                else word.split('-').joinToString("-") { part -> part.replaceFirstChar { it.titlecase(Locale.US) } }
            }.joinToString(" ")
        }

        private fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

        private fun rangeOf(datesRange: String?): Pair<String?, String?> {
            if (datesRange.isNullOrBlank()) return null to null
            val parts = datesRange.split('-')
            return parts.first() to parts.last()
        }

        private fun yearsOf(from: String?, to: String?, now: Long): List<Int> {
            val thisYear = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
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
