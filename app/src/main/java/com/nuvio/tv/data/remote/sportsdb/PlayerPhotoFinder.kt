package com.nuvio.tv.data.remote.sportsdb

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerPhotoFinder"

/**
 * Players' photos ESPN doesn't have (soccer players, mostly): TheSportsDB's cut-outs (the player on
 * a clear background, like ESPN's), found by the player's name and team. Kept on the device: a
 * photo found for a month, "none" for a few days (then it looks again).
 *
 * RCTV: a team's players are looked for together ([findPhotos]): the ones kept on the device right
 * away, then the team's first ten in one request, then the rest one by one, each waiting for its turn
 * among TheSportsDB's 30 requests a minute (shared with PPV Events and the CFL) instead of being
 * turned away, and trying again after a minute when TheSportsDB says it's had too many.
 */
@Singleton
class PlayerPhotoFinder @Inject constructor(
    @ApplicationContext context: Context
) {
    /** A player to look for: ESPN's id (the roster's) and name. */
    data class PlayerQuery(val id: String, val name: String)

    private val prefs = context.getSharedPreferences("sports_player_photos", Context.MODE_PRIVATE)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Looks for the photos of [players] (of [team], in [sportPath]: "soccer", "basketball"...) and
     * hands each one found to [onPhoto] (the player's id and the photo) as soon as it's known, in the
     * order given. Returns once all have been looked for (or when cancelled: the page closed).
     */
    suspend fun findPhotos(
        players: List<PlayerQuery>,
        team: String?,
        sportPath: String?,
        onPhoto: suspend (playerId: String, url: String) -> Unit
    ) {
        val sport = sportName(sportPath) ?: return
        val teamKey = team?.let(::normalize).orEmpty()
        val pending = ArrayList<PlayerQuery>()
        // Kept on the device: right away.
        for (player in players.distinctBy { it.id }) {
            if (player.name.isBlank()) continue
            when (val kept = kept(sport, player.name)) {
                null -> pending += player
                "" -> Unit
                else -> onPhoto(player.id, kept)
            }
        }
        if (pending.isEmpty()) return

        var teamId = teamKey.takeIf { it.isNotEmpty() }?.let { prefs.getString(teamIdKey(sport, it), null) }
        var teamListed = false

        /** The team's first ten players (TheSportsDB's free key gives no more), in one request. */
        suspend fun listTeam() {
            val id = teamId ?: return
            if (teamListed) return
            teamListed = true
            val listed = (request("lookup_all_players.php?id=$id") as? Lookup.Players)?.players ?: return
            val candidates = listed.objects()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val player = iterator.next()
                val match = matchIn(candidates, player, sport, teamKey, teamList = true) ?: continue
                val photo = photoOf(match) ?: continue
                keep(sport, player.name, photo)
                iterator.remove()
                onPhoto(player.id, photo)
            }
        }

        listTeam()
        // The rest, one by one, in order.
        for (player in pending.toList()) {
            if (player !in pending) continue
            pending.remove(player)
            val query = URLEncoder.encode(player.name.trim().replace(' ', '_'), "UTF-8")
            val lookup = request("searchplayers.php?p=$query")
            if (lookup !is Lookup.Players) continue // couldn't be asked: looked for again next time
            val match = matchIn(lookup.players.objects(), player, sport, teamKey, teamList = false)
            val photo = match?.let(::photoOf)
            // Found, or surely none: kept.
            keep(sport, player.name, photo.orEmpty())
            if (photo != null) onPhoto(player.id, photo)
            // The team, once one of its players is found: its first ten in one go.
            if (teamId == null && match != null && teamKey.isNotEmpty() && isSameTeam(match, teamKey)) {
                match.optString("idTeam").takeIf { it.isNotBlank() && it != "null" }?.let { id ->
                    teamId = id
                    prefs.edit().putString(teamIdKey(sport, teamKey), id).apply()
                    listTeam()
                }
            }
        }
    }

    private sealed interface Lookup {
        /** TheSportsDB's players (none: empty). */
        class Players(val players: JSONArray) : Lookup
        /** It couldn't be asked. */
        data object Failed : Lookup
    }

    /** One request: waits for its turn, and once more after a minute when TheSportsDB has had too many. */
    private suspend fun request(path: String): Lookup {
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val reply = SportsDbRequests.getInTurn(httpClient, path)) {
                is SportsDbRequests.Reply.Ok -> {
                    val json = withContext(Dispatchers.Default) { runCatching { JSONObject(reply.body) }.getOrNull() }
                        ?: return Lookup.Failed
                    // "player": null is a sure "none"; any other shape is too.
                    return Lookup.Players(json.optJSONArray("player") ?: JSONArray())
                }
                SportsDbRequests.Reply.Busy -> if (attempt < MAX_ATTEMPTS - 1) delay(BUSY_WAIT_MS)
                SportsDbRequests.Reply.Failed -> {
                    Log.w(TAG, "Lookup failed: $path")
                    return Lookup.Failed
                }
            }
        }
        return Lookup.Failed
    }

    /**
     * Which of TheSportsDB's [candidates] is [player] (of [sport]): the one with ESPN's id, else
     * the one with the same name ("Vinícius Júnior" and "Vinicius Jr" too); else, of the team's own
     * players ([teamList], or ones whose team is [teamKey]), the only one with the same last name or
     * one name in the other ("Rodrygo" and "Rodrygo Goes"). Null when none surely is.
     */
    private fun matchIn(
        candidates: List<JSONObject>,
        player: PlayerQuery,
        sport: String,
        teamKey: String,
        teamList: Boolean
    ): JSONObject? {
        val wanted = normalize(player.name)
        val ofSport = candidates.filter { it.optString("strSport").equals(sport, ignoreCase = true) }
        fun espnIdOf(candidate: JSONObject): String? =
            candidate.optString("idESPN").takeIf { it.isNotBlank() && it != "null" && it != "0" }
        fun isOfTeam(candidate: JSONObject): Boolean = teamList || (teamKey.isNotEmpty() && isSameTeam(candidate, teamKey))
        fun namesOf(candidate: JSONObject): List<String> = listOf("strPlayer", "strPlayerAlternate").mapNotNull { field ->
            candidate.optString(field).takeIf { it.isNotBlank() && it != "null" }?.let(::normalize)
        }

        ofSport.firstOrNull { espnIdOf(it) == player.id }?.let { return it }
        ofSport.firstOrNull { candidate ->
            val espnId = espnIdOf(candidate)
            // Another ESPN player with the same name (on another team): someone else.
            (espnId == null || isOfTeam(candidate)) && namesOf(candidate).any { it == wanted }
        }?.let { return it }

        val wantedWords = wanted.split(' ').filter { it.length > 1 }
        if (wantedWords.isEmpty()) return null
        return ofSport.filter { candidate ->
            isOfTeam(candidate) && espnIdOf(candidate) == null && namesOf(candidate).any { name ->
                val words = name.split(' ').filter { it.length > 1 }
                words.isNotEmpty() && (
                    words.last() == wantedWords.last() ||
                        (wantedWords.size == 1 && wantedWords[0] in words) ||
                        (words.size == 1 && words[0] in wantedWords)
                    )
            }
        }.singleOrNull()
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun isSameTeam(candidate: JSONObject, teamKey: String): Boolean =
        listOf("strTeam", "strTeam2").any { field ->
            val playerTeam = normalize(candidate.optString(field))
            playerTeam.isNotEmpty() && playerTeam != "null" &&
                (playerTeam == teamKey || playerTeam.contains(teamKey) || teamKey.contains(playerTeam))
        }

    private fun photoOf(player: JSONObject): String? =
        listOf("strCutout", "strRender", "strThumb")
            .firstNotNullOfOrNull { field -> player.optString(field).takeIf { it.startsWith("http") } }

    /** The photo kept on the device ("" for a sure none), or null when there's none kept or it's old. */
    private fun kept(sport: String, name: String): String? {
        val cached = prefs.getString(photoKey(sport, name), null) ?: return null
        val url = cached.substringBefore('|')
        val savedAt = cached.substringAfter('|', "0").toLongOrNull() ?: 0L
        val maxAge = if (url.isEmpty()) NONE_MAX_AGE_MS else FOUND_MAX_AGE_MS
        return url.takeIf { System.currentTimeMillis() - savedAt < maxAge }
    }

    private fun keep(sport: String, name: String, url: String) {
        prefs.edit().putString(photoKey(sport, name), "$url|${System.currentTimeMillis()}").apply()
    }

    // v2: what the first version kept (a "none" from a lookup turned away, say) is looked for again.
    private fun photoKey(sport: String, name: String): String = "v2|$sport|${normalize(name)}"

    private fun teamIdKey(sport: String, teamKey: String): String = "team|v2|$sport|$teamKey"

    private fun sportName(sportPath: String?): String? = when (sportPath) {
        "soccer" -> "Soccer"
        "basketball" -> "Basketball"
        "football" -> "American Football"
        "baseball" -> "Baseball"
        "hockey" -> "Ice Hockey"
        else -> null
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NOT_ALPHANUMERIC, " ")
            .replace(CLUB_WORDS, " ")
            .replace(JUNIOR, "junior")
            .replace(SPACES, " ")
            .trim()

    private companion object {
        const val FOUND_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val NONE_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 3
        const val BUSY_WAIT_MS = 61_000L
        val MARKS = Regex("\\p{Mn}+")
        val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val CLUB_WORDS = Regex("\\b(fc|cf|afc|sc|cd|ac|club|de)\\b")
        val JUNIOR = Regex("\\bjr\\b")
        val SPACES = Regex("\\s+")
    }
}
