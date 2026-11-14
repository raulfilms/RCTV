package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/**
 * RCTV (owner): the IPTV provider has no programs for Puerto Rico's channels: they get San Juan's
 * over-the-air TV guide (ZIP 00901, the guide TV-listing sites show: Gracenote's public grid), by
 * station. Only the owner's Puerto Rico channels ([LiveTvChannel.country] "PR") the provider's
 * guide left empty; a channel the provider does have programs for keeps the provider's.
 *
 * Read from the TV (no server in between), a few hours at a time, with the rest of the guide (every
 * few hours); a failure just leaves those channels as they were.
 */
internal object PuertoRicoOtaGuide {

    /** The guide key a supplied channel's programs go under ([LiveTvChannel.epgChannelId]). */
    private const val KEY_PREFIX = "ota-pr:"

    /**
     * The owner's Puerto Rico channels (their name in the Guide, any case, accents or spacing) and
     * the San Juan station each one is. A name not here keeps the provider's guide only.
     */
    private val STATIONS: Map<String, String> = mapOf(
        // The owner's guide of 14-nov-2026.
        "telemundopr" to "WKAQDT",
        "punto2pr" to "WKAQDT2",
        "wapapr" to "WAPADT",
        "wapadeportespr" to "WAPADT2",
        "teleoncepr" to "WLIIDT",
        // His earlier names.
        "telemundo" to "WKAQDT", // Telemundo Puerto Rico, 2.1
        "telemundo2" to "WKAQDT", // the same (canal 2)
        "punto2tv" to "WKAQDT2", // Punto 2, 2.2
        "nbc" to "WKAQDT3", // NBC on Telemundo's 2.3
        "wapa" to "WAPADT", // WAPA, 4.1
        "wapadeportes" to "WAPADT2", // WAPA 2 Deportes, 4.2
        "wiprcanal6" to "WIPRDT", // WIPR, 6.1
        "wipr" to "WIPRDT",
        "canaltiva" to "WWXYLD", // TIVA TV, 8.1
        "canaltivafuente2" to "WWXYLD",
        "canal11" to "WLIIDT", // Teleonce, 11.1
        "teleonce" to "WLIIDT",
        "americateve" to "WKPVDT", // América TeVe, 20.1
        "canal34" to "WRUADT" // 34.1
    )

    /** San Juan's over-the-air lineup on the public grid. */
    private const val GRID_URL = "https://tvlistings.gracenote.com/api/grid?aid=orbebb&country=USA" +
        "&postalCode=00901&isOverride=true&userId=-&lineupId=USA-OTA00901-DEFAULT&headendId=lineupId"
    private const val HOUR_MS = 60L * 60 * 1000
    /** Hours per ask (what the grid answers at once). */
    private const val HOURS_PER_ASK = 3
    /** From the hour before now, this many hours ahead. */
    private const val HOURS_AHEAD = 27

    /** The Puerto Rico channels that are one of San Juan's stations: channel id → station. */
    fun stationsOf(channels: List<LiveTvChannel>): Map<String, String> {
        val out = HashMap<String, String>()
        for (channel in channels) {
            if (!channel.country.equals("PR", ignoreCase = true)) continue
            STATIONS[nameKey(channel.name)]?.let { out[channel.id] = it }
        }
        return out
    }

    /**
     * [channels] with their Puerto Rico stations pointed at the station's programs, for those the
     * provider's guide ([epg]) has nothing for and the station's programs are in [epg].
     */
    fun pointAtStations(channels: List<LiveTvChannel>, epg: Map<String, List<EpgProgram>>): List<LiveTvChannel> {
        val stations = stationsOf(channels)
        if (stations.isEmpty()) return channels
        return channels.map { channel ->
            val station = stations[channel.id] ?: return@map channel
            val key = keyOf(station)
            val own = channel.epgChannelId?.takeIf { it != key }?.let { epg[it] }
            if (own.isNullOrEmpty() && !epg[key].isNullOrEmpty()) channel.copy(epgChannelId = key) else channel
        }
    }

    /** The stations [channels] need (none of the provider's programs): their programs, by guide key. */
    suspend fun programsFor(
        client: OkHttpClient,
        channels: List<LiveTvChannel>,
        epg: Map<String, List<EpgProgram>>,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, List<EpgProgram>> {
        val wanted = HashSet<String>()
        val stations = stationsOf(channels)
        for (channel in channels) {
            val station = stations[channel.id] ?: continue
            val own = channel.epgChannelId?.takeIf { !it.startsWith(KEY_PREFIX) }?.let { epg[it] }
            if (own.isNullOrEmpty()) wanted += station
        }
        if (wanted.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val byStation = HashMap<String, MutableList<EpgProgram>>()
            val firstHour = (nowMs / HOUR_MS - 1) * HOUR_MS
            var askAt = firstHour
            while (askAt < firstHour + HOURS_AHEAD * HOUR_MS) {
                val answer = try {
                    ask(client, askAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                answer?.let { readGrid(it, wanted, byStation) }
                askAt += HOURS_PER_ASK * HOUR_MS
            }
            byStation.entries.associate { (station, programs) ->
                keyOf(station) to programs.distinctBy { it.startMs }.sortedBy { it.startMs }
            }
        }
    }

    private fun ask(client: OkHttpClient, fromMs: Long): String? {
        val url = "$GRID_URL&time=${fromMs / 1000}&timespan=$HOURS_PER_ASK"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** The grid's programs for the [wanted] stations, into [into]. */
    internal fun readGrid(text: String, wanted: Set<String>, into: MutableMap<String, MutableList<EpgProgram>>) {
        val channels = JSONObject(text).optJSONArray("channels") ?: return
        for (i in 0 until channels.length()) {
            val channel = channels.optJSONObject(i) ?: continue
            val station = channel.optString("callSign").uppercase(Locale.ROOT)
            if (station !in wanted) continue
            val events = channel.optJSONArray("events") ?: continue
            val list = into.getOrPut(station) { ArrayList() }
            for (j in 0 until events.length()) {
                val event = events.optJSONObject(j) ?: continue
                val program = event.optJSONObject("program") ?: continue
                val title = program.optString("title").trim().takeIf { it.isNotEmpty() && it != "null" } ?: continue
                val start = timeOf(event.optString("startTime")) ?: continue
                val end = timeOf(event.optString("endTime")) ?: continue
                if (end <= start) continue
                val flags = event.optJSONArray("flag")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
                list += EpgProgram(
                    channelId = keyOf(station),
                    title = title,
                    description = program.textOrNull("shortDesc"),
                    startMs = start,
                    endMs = end,
                    subtitle = program.textOrNull("episodeTitle"),
                    isLive = flags.any { it.equals("Live", ignoreCase = true) },
                    isNew = flags.any { it.equals("New", ignoreCase = true) || it.equals("Premiere", ignoreCase = true) },
                    seasonNumber = program.textOrNull("season")?.toIntOrNull() ?: 0,
                    episodeNumber = program.textOrNull("episode")?.toIntOrNull() ?: 0
                )
            }
        }
    }

    private fun JSONObject.textOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun timeOf(text: String): Long? =
        runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrNull()

    private fun keyOf(station: String) = KEY_PREFIX + station

    /** A guide key of San Juan's programs (kept from the last load when they can't be read now). */
    fun isOwnKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** "Wapa DEPORTES" → "wapadeportes"; "PRI: CANAL 11" → "canal11"; "AméricaTeVe" → "americateve". */
    internal fun nameKey(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("^\\s*(pri|pr|puerto rico)\\s*[:|\\-]\\s*"), "")
        return plain.filter { it.isLetterOrDigit() }
    }
}
