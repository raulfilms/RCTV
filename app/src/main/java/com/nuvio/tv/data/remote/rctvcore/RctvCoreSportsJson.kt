package com.nuvio.tv.data.remote.rctvcore

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam

/** One league's games from RCTV Core: what EspnSportsClient.FeedResult holds, without its Feed. */
internal data class CoreFeed(
    val leagueId: String,
    val league: SportsLeague,
    val events: List<SportsEvent>,
    val teams: List<SportsTeam>
)

/** RCTV Core's /api/v1/sports/scoreboards answer: the leagues it answered, and the ones the app loads itself. */
internal data class CoreScoreboards(val results: Map<String, CoreFeed>, val failed: Set<String>)

/**
 * RCTV Core (Oracle): its answers read as they come in, straight into the app's own models (the
 * server already turned ESPN's answers into them, field for field, see rctv-core/src/sports/normalize.js).
 * No JSON tree and no ESPN text is held: only the models the pages use. A field left out is the
 * model's default (null, or an empty list). Unknown fields are skipped, so the server can add some.
 */
internal object RctvCoreSportsJson {

    fun readScoreboards(reader: JsonReader): CoreScoreboards {
        val results = LinkedHashMap<String, CoreFeed>()
        val failed = LinkedHashSet<String>()
        obj(reader) { key ->
            when (key) {
                "results" -> arr(reader) { readFeed(reader)?.let { results[it.leagueId] = it } }
                "failed" -> arr(reader) { string(reader)?.let(failed::add) }
                else -> reader.skipValue()
            }
        }
        return CoreScoreboards(results, failed)
    }

    /** /api/v1/client-config: whether RCTV Core may be used for Sports (null when it doesn't say). */
    fun readSportsEnabled(reader: JsonReader): Boolean? {
        var enabled: Boolean? = null
        obj(reader) { key ->
            if (key == "sports") obj(reader) { inner -> if (inner == "enabled") enabled = bool(reader) else reader.skipValue() }
            else reader.skipValue()
        }
        return enabled
    }

    private fun readFeed(reader: JsonReader): CoreFeed? {
        var leagueId: String? = null
        var league: SportsLeague? = null
        val events = ArrayList<SportsEvent>()
        val teams = ArrayList<SportsTeam>()
        obj(reader) { key ->
            when (key) {
                "leagueId" -> leagueId = string(reader)
                "league" -> league = readLeague(reader)
                "events" -> arr(reader) { readEvent(reader)?.let(events::add) }
                "teams" -> arr(reader) { readTeam(reader)?.let(teams::add) }
                else -> reader.skipValue()
            }
        }
        val id = leagueId ?: return null
        return CoreFeed(id, league ?: return null, events, teams)
    }

    private fun readLeague(reader: JsonReader): SportsLeague? {
        val f = fields(reader)
        return SportsLeague(
            id = f["id"] ?: return null,
            name = f["name"] ?: return null,
            sportName = f["sportName"] ?: return null,
            badgeUrl = f["badgeUrl"],
            country = f["country"]
        )
    }

    private fun readTeam(reader: JsonReader): SportsTeam? {
        val f = fields(reader)
        return SportsTeam(
            id = f["id"] ?: return null,
            name = f["name"] ?: "",
            shortName = f["shortName"],
            sportName = f["sportName"],
            leagueId = f["leagueId"],
            leagueName = f["leagueName"],
            badgeUrl = f["badgeUrl"],
            color = f["color"]
        )
    }

    private fun readEvent(reader: JsonReader): SportsEvent? {
        val f = HashMap<String, String?>()
        var homeAliases: List<String> = emptyList()
        var awayAliases: List<String> = emptyList()
        obj(reader) { key ->
            when (key) {
                "homeTeamAliases" -> homeAliases = strings(reader)
                "awayTeamAliases" -> awayAliases = strings(reader)
                else -> f[key] = string(reader)
            }
        }
        val status = when (f["status"]) {
            "LIVE" -> SportsEventStatus.LIVE
            "FINISHED" -> SportsEventStatus.FINISHED
            "UPCOMING" -> SportsEventStatus.UPCOMING
            else -> return null
        }
        return SportsEvent(
            id = f["id"] ?: return null,
            name = f["name"] ?: return null,
            sportName = f["sportName"],
            leagueId = f["leagueId"],
            leagueName = f["leagueName"],
            homeTeamId = f["homeTeamId"],
            homeTeamName = f["homeTeamName"] ?: "",
            homeTeamBadgeUrl = f["homeTeamBadgeUrl"],
            awayTeamId = f["awayTeamId"],
            awayTeamName = f["awayTeamName"] ?: "",
            awayTeamBadgeUrl = f["awayTeamBadgeUrl"],
            homeScore = f["homeScore"]?.toIntOrNull(),
            awayScore = f["awayScore"]?.toIntOrNull(),
            startTimeMs = f["startTimeMs"]?.toLongOrNull(),
            venue = f["venue"],
            status = status,
            statusDetail = f["statusDetail"],
            homeTeamAbbreviation = f["homeTeamAbbreviation"],
            awayTeamAbbreviation = f["awayTeamAbbreviation"],
            homeTeamColor = f["homeTeamColor"],
            awayTeamColor = f["awayTeamColor"],
            homeTeamAliases = homeAliases,
            awayTeamAliases = awayAliases,
            leaderName = f["leaderName"],
            leaderScore = f["leaderScore"],
            endTimeMs = f["endTimeMs"]?.toLongOrNull(),
            imageUrl = f["imageUrl"]
        )
    }

    // ---- Small streaming helpers ----

    /** An object's text and number fields (anything else skipped). */
    private fun fields(reader: JsonReader): Map<String, String?> {
        val out = HashMap<String, String?>()
        obj(reader) { key -> out[key] = string(reader) }
        return out
    }

    private inline fun obj(reader: JsonReader, onField: (String) -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) onField(reader.nextName())
        reader.endObject()
    }

    private inline fun arr(reader: JsonReader, onItem: () -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) onItem()
        reader.endArray()
    }

    private fun strings(reader: JsonReader): List<String> {
        val out = ArrayList<String>()
        arr(reader) { string(reader)?.let(out::add) }
        return out
    }

    /** A text or a number as text; null for null; anything else skipped (null). */
    private fun string(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun bool(reader: JsonReader): Boolean? = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean()
        else -> {
            reader.skipValue()
            null
        }
    }
}
