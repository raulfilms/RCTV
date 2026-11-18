package com.nuvio.tv.data.remote.xtream

import com.nuvio.tv.domain.model.LiveCatchUpProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.XtreamSeriesEpisode
import com.nuvio.tv.domain.model.XtreamSeriesItem
import com.nuvio.tv.domain.model.XtreamVodItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Minimal client for the (de-facto standard, unofficial) Xtream Codes panel API used by most
 * IPTV resellers: `player_api.php` for account/category/stream listings, and a fixed
 * `/live/{user}/{pass}/{stream_id}.{ext}` URL scheme for actual playback.
 */
@Singleton
class XtreamClient @Inject constructor(
    @Named("addon") private val okHttpClient: OkHttpClient
) {
    class XtreamException(message: String) : Exception(message)

    /**
     * RCTV: the same client, presenting itself as a common IPTV player. Many providers' panels only
     * answer the players they know (others get an error or a blocked page); the app's own name is
     * tried first and this one only when the server turns it away.
     */
    private val playerUaClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", PLAYER_USER_AGENT).build())
            }
            .build()
    }

    /** Servers ("host:port") that only answered as a common IPTV player. */
    private val playerUaServers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun clientFor(url: okhttp3.HttpUrl): OkHttpClient =
        if ("${url.host}:${url.port}" in playerUaServers) playerUaClient else okHttpClient

    private fun normalizedServerUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        // RCTV: a whole player_api.php or get.php address pasted as the server: just the server.
        for (page in listOf("/player_api.php", "/get.php", "/xmltv.php")) {
            val at = url.indexOf(page, ignoreCase = true)
            if (at > 0) url = url.substring(0, at)
        }
        return url.trimEnd('/')
    }

    private fun playerApiUrl(server: String, user: String, pass: String, action: String? = null): okhttp3.HttpUrl {
        val base = "$server/player_api.php".toHttpUrlOrNull()
            ?: throw XtreamException("Invalid server URL")
        val builder = base.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build()
    }

    private fun get(url: okhttp3.HttpUrl): String = getWith(clientFor(url), url)

    private fun getWith(client: OkHttpClient, url: okhttp3.HttpUrl): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw XtreamException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    /** Fetches and flattens all live channels for this Xtream account, grouped by category name. */
    fun fetchChannels(connection: LiveTvConnection.Xtream): List<LiveTvChannel> {
        val server = normalizedServerUrl(connection.serverUrl)
        val user = connection.username.trim()
        val pass = connection.password.trim()
        if (user.isBlank() || pass.isBlank()) {
            throw XtreamException("Missing username or password")
        }

        val auth = authenticate(server, user, pass)
        val categoryNames = fetchCategoryNames(server, user, pass, "get_live_categories")
        return readLiveStreams(server, user, pass, categoryNames, liveExtensionOf(auth))
    }

    /**
     * RCTV: the live format the account may play: HLS (m3u8) when it's allowed or not said, else
     * MPEG-TS (an account that only allows "ts" gets nothing from an .m3u8 address).
     */
    private fun liveExtensionOf(auth: JSONObject): String {
        val formats = auth.optJSONObject("user_info")?.optJSONArray("allowed_output_formats")
            ?.let { array -> (0 until array.length()).map { array.optString(it).lowercase() } }
            .orEmpty()
        return when {
            formats.isEmpty() || "m3u8" in formats -> "m3u8"
            "ts" in formats -> "ts"
            else -> "m3u8"
        }
    }

    /**
     * RCTV: the live channel list read as it downloads, keeping only what a channel needs. A big
     * provider's list (tens of thousands of channels, tens of megabytes) held as one piece of text
     * and then as JSON objects is more than a TV box's memory has for the app.
     */
    private fun readLiveStreams(
        server: String,
        user: String,
        pass: String,
        categoryNames: Map<String, String>,
        extension: String
    ): List<LiveTvChannel> {
        val url = playerApiUrl(server, user, pass, "get_live_streams")
        clientFor(url).newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw XtreamException("HTTP ${response.code}")
            val body = response.body ?: throw XtreamException("Empty response")
            val channels = ArrayList<LiveTvChannel>()
            try {
                android.util.JsonReader(body.charStream()).use { reader ->
                    if (reader.peek() != android.util.JsonToken.BEGIN_ARRAY) {
                        throw XtreamException("Couldn't read the channel list from this server")
                    }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        var streamId: String? = null
                        var name: String? = null
                        var categoryId: String? = null
                        var logo: String? = null
                        var epgChannelId: String? = null
                        var tvArchive: String? = null
                        var archiveDuration: String? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stream_id" -> streamId = reader.textOrNull()
                                "name" -> name = reader.textOrNull()
                                "category_id" -> categoryId = reader.textOrNull()
                                "stream_icon" -> logo = reader.textOrNull()
                                "epg_channel_id" -> epgChannelId = reader.textOrNull()
                                // RCTV (owner): the provider keeps its programs to watch again (catch-up).
                                "tv_archive" -> tvArchive = reader.textOrNull()
                                "tv_archive_duration" -> archiveDuration = reader.textOrNull()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        val id = streamId?.trim()?.removeSuffix(".0")?.takeIf { it.isNotBlank() } ?: continue
                        val category = categoryId?.trim().orEmpty()
                        channels += LiveTvChannel(
                            id = "xtream_$id",
                            name = name?.takeIf { it.isNotBlank() } ?: "Channel $id",
                            streamUrl = "$server/live/$user/$pass/$id.$extension",
                            logoUrl = logo?.takeIf { it.isNotBlank() },
                            groupTitle = categoryNames[category] ?: category.takeIf { it.isNotBlank() },
                            epgChannelId = epgChannelId?.takeIf { it.isNotBlank() },
                            archiveDays = archiveDaysOf(tvArchive, archiveDuration)
                        )
                    }
                    reader.endArray()
                }
            } catch (e: IllegalStateException) {
                throw XtreamException("Couldn't read the channel list from this server")
            } catch (e: android.util.MalformedJsonException) {
                throw XtreamException("Couldn't read the channel list from this server")
            }
            return channels
        }
    }

    /** RCTV: the days of catch-up a channel has ("tv_archive" 1 and its "tv_archive_duration"). */
    private fun archiveDaysOf(tvArchive: String?, duration: String?): Int {
        val on = tvArchive?.trim()?.removeSuffix(".0") == "1" || tvArchive.equals("true", ignoreCase = true)
        if (!on) return 0
        return (duration?.trim()?.removeSuffix(".0")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ARCHIVE_DAYS)
    }

    /** A channel's Xtream address in its parts, read from its live address (never logged). */
    private class LiveAddress(
        val server: String,
        val user: String,
        val pass: String,
        val streamId: String,
        val extension: String
    )

    private fun liveAddressOf(streamUrl: String): LiveAddress? {
        val at = streamUrl.indexOf("/live/")
        if (at <= 0) return null
        val parts = streamUrl.substring(at + "/live/".length).split('/')
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return null
        val file = parts[2].substringBefore('?')
        val streamId = file.substringBeforeLast('.').takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) } ?: return null
        val extension = file.substringAfterLast('.', "ts").lowercase().takeIf { it == "m3u8" || it == "ts" } ?: "ts"
        return LiveAddress(streamUrl.substring(0, at), parts[0], parts[1], streamId, extension)
    }

    /**
     * RCTV (owner): the programs the provider recorded on [channel] in its catch-up days (the one on
     * now too, to start it over), newest first. Empty when it keeps none or doesn't say.
     */
    fun fetchCatchUp(channel: LiveTvChannel, nowMs: Long = System.currentTimeMillis()): List<LiveCatchUpProgram> {
        if (channel.archiveDays <= 0) return emptyList()
        val address = liveAddressOf(channel.streamUrl) ?: return emptyList()
        val url = playerApiUrl(address.server, address.user, address.pass, "get_simple_data_table")
            .newBuilder()
            .addQueryParameter("stream_id", address.streamId)
            .build()
        val listings = runCatching { JSONObject(get(url)).optJSONArray("epg_listings") }.getOrNull()
            ?: return emptyList()
        val oldestMs = nowMs - channel.archiveDays * DAY_MS
        val programs = ArrayList<LiveCatchUpProgram>()
        for (i in 0 until listings.length()) {
            val item = listings.optJSONObject(i) ?: continue
            val startMs = item.optString("start_timestamp").trim().toLongOrNull()?.times(1000) ?: continue
            val endMs = item.optString("stop_timestamp").trim().toLongOrNull()?.times(1000) ?: continue
            if (endMs <= startMs || startMs >= nowMs || startMs < oldestMs) continue
            val airing = nowMs < endMs
            val archived = item.opt("has_archive")?.toString()?.trim()
            if (!airing && archived != null && archived != "1" && !archived.equals("true", ignoreCase = true)) continue
            val serverStart = serverStartOf(item.optString("start")) ?: continue
            val title = decodedText(item.optString("title")) ?: continue
            programs += LiveCatchUpProgram(
                title = title,
                description = decodedText(item.optString("description")),
                startMs = startMs,
                endMs = endMs,
                serverStart = serverStart,
                durationMinutes = ((endMs - startMs + 59_999L) / 60_000L).toInt().coerceIn(1, MAX_PROGRAM_MINUTES)
            )
        }
        return programs.distinctBy { it.startMs }.sortedByDescending { it.startMs }.take(MAX_CATCH_UP_PROGRAMS)
    }

    /** RCTV (owner): the address that plays [program] again from the provider's recording. */
    fun catchUpUrl(channel: LiveTvChannel, program: LiveCatchUpProgram): String? {
        val address = liveAddressOf(channel.streamUrl) ?: return null
        return "${address.server}/timeshift/${address.user}/${address.pass}/" +
            "${program.durationMinutes}/${program.serverStart}/${address.streamId}.${address.extension}"
    }

    /** "2026-11-17 21:00:00" (the server's own time) -> "2026-11-17:21-00", as catch-up asks for it. */
    private fun serverStartOf(start: String): String? {
        val match = SERVER_TIME.find(start.trim()) ?: return null
        val (day, hour, minute) = match.destructured
        return "$day:$hour-$minute"
    }

    /** The guide's text, sent in Base64 by most panels (as is by some). */
    private fun decodedText(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length % 4 == 0 && BASE64.matches(text)) {
            val decoded = runCatching {
                String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull()
            if (!decoded.isNullOrEmpty() && decoded.none { it == '\uFFFD' || (it.isISOControl() && it != '\n' && it != '\t' && it != '\r') }) {
                return decoded
            }
        }
        return text
    }

    private fun android.util.JsonReader.textOrNull(): String? = when (peek()) {
        android.util.JsonToken.NULL -> { nextNull(); null }
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> nextString()
        android.util.JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    /**
     * Verifies credentials and returns the account's auth JSON (throws [XtreamException] on failure).
     * RCTV: a server that turns the app away (an error, or a page that isn't the account's answer) is
     * asked again as a common IPTV player, and asked that way from then on.
     */
    private fun authenticate(server: String, user: String, pass: String): JSONObject {
        val url = playerApiUrl(server, user, pass)
        val key = "${url.host}:${url.port}"
        var turnedAway: XtreamException? = null
        val clients = if (key in playerUaServers) listOf(playerUaClient) else listOf(okHttpClient, playerUaClient)
        for (client in clients) {
            val authBody = try {
                getWith(client, url)
            } catch (e: XtreamException) {
                turnedAway = turnedAway ?: e
                continue
            }
            val authJson = runCatching { JSONObject(authBody) }.getOrNull()
            val userInfo = authJson?.optJSONObject("user_info")
            if (authJson == null || userInfo == null) {
                turnedAway = turnedAway ?: XtreamException("Unexpected response from server")
                continue
            }
            if (userInfo.optString("auth") == "0") throw XtreamException("Invalid Xtream credentials")
            if (client === playerUaClient) playerUaServers += key
            return authJson
        }
        throw turnedAway ?: XtreamException("Unexpected response from server")
    }

    private fun fetchCategoryNames(server: String, user: String, pass: String, action: String): Map<String, String> =
        runCatching {
            val array = JSONArray(get(playerApiUrl(server, user, pass, action)))
            buildMap {
                for (i in 0 until array.length()) {
                    val category = array.getJSONObject(i)
                    val id = category.optString("category_id")
                    val name = category.optString("category_name")
                    if (id.isNotBlank()) put(id, name.ifBlank { id })
                }
            }
        }.getOrDefault(emptyMap())

    /** Fetches and flattens the account's VOD (movie) catalog (`get_vod_streams`), grouped by category name. */
    fun fetchVod(connection: LiveTvConnection.Xtream): List<XtreamVodItem> {
        val server = normalizedServerUrl(connection.serverUrl)
        val user = connection.username.trim()
        val pass = connection.password.trim()
        if (user.isBlank() || pass.isBlank()) {
            throw XtreamException("Missing username or password")
        }
        authenticate(server, user, pass)

        val categoryNames = fetchCategoryNames(server, user, pass, "get_vod_categories")

        val streamsBody = get(playerApiUrl(server, user, pass, "get_vod_streams"))
        val streamsArray = runCatching { JSONArray(streamsBody) }.getOrNull()
            ?: throw XtreamException("Couldn't read the movie list from this server")

        val items = mutableListOf<XtreamVodItem>()
        for (i in 0 until streamsArray.length()) {
            val stream = streamsArray.optJSONObject(i) ?: continue
            val streamId = stream.optString("stream_id").takeIf { it.isNotBlank() }
                ?: stream.optInt("stream_id", -1).takeIf { it >= 0 }?.toString()
                ?: continue
            val name = stream.optString("name").ifBlank { "Movie $streamId" }
            val categoryId = stream.optString("category_id")
            val ext = stream.optString("container_extension").takeIf { it.isNotBlank() } ?: "mp4"
            val streamUrl = "$server/movie/$user/$pass/$streamId.$ext"
            items += XtreamVodItem(
                id = "xtream_vod_$streamId",
                name = name,
                streamUrl = streamUrl,
                posterUrl = stream.optString("stream_icon").takeIf { it.isNotBlank() },
                year = stream.optString("year").takeIf { it.isNotBlank() },
                categoryName = categoryNames[categoryId] ?: categoryId.takeIf { it.isNotBlank() },
                imdbId = stream.optString("imdb").takeIf { it.isNotBlank() }
                    ?: stream.optString("imdb_id").takeIf { it.isNotBlank() },
                tmdbId = stream.optString("tmdb").takeIf { it.isNotBlank() }
                    ?: stream.optString("tmdb_id").takeIf { it.isNotBlank() }
            )
        }
        return items
    }

    /** Fetches the account's series catalog (`get_series`), without episodes (fetch those separately per-series via [fetchSeriesEpisodes]). */
    fun fetchSeriesCatalog(connection: LiveTvConnection.Xtream): List<XtreamSeriesItem> {
        val server = normalizedServerUrl(connection.serverUrl)
        val user = connection.username.trim()
        val pass = connection.password.trim()
        if (user.isBlank() || pass.isBlank()) {
            throw XtreamException("Missing username or password")
        }
        authenticate(server, user, pass)

        val categoryNames = fetchCategoryNames(server, user, pass, "get_series_categories")

        val seriesBody = get(playerApiUrl(server, user, pass, "get_series"))
        val seriesArray = runCatching { JSONArray(seriesBody) }.getOrNull()
            ?: throw XtreamException("Couldn't read the series list from this server")

        val items = mutableListOf<XtreamSeriesItem>()
        for (i in 0 until seriesArray.length()) {
            val series = seriesArray.optJSONObject(i) ?: continue
            val seriesId = series.optString("series_id").takeIf { it.isNotBlank() }
                ?: series.optInt("series_id", -1).takeIf { it >= 0 }?.toString()
                ?: continue
            val name = series.optString("name").ifBlank { "Series $seriesId" }
            val categoryId = series.optString("category_id")
            items += XtreamSeriesItem(
                id = "xtream_series_$seriesId",
                name = name,
                posterUrl = series.optString("cover").takeIf { it.isNotBlank() },
                categoryName = categoryNames[categoryId] ?: categoryId.takeIf { it.isNotBlank() },
                imdbId = series.optString("imdb").takeIf { it.isNotBlank() }
                    ?: series.optString("imdb_id").takeIf { it.isNotBlank() },
                tmdbId = series.optString("tmdb").takeIf { it.isNotBlank() }
                    ?: series.optString("tmdb_id").takeIf { it.isNotBlank() }
            )
        }
        return items
    }

    /**
     * Fetches every episode of one series (`get_series_info?series_id=...`). Standard Xtream
     * panels key `episodes` by season number as a JSON object of arrays
     * (`{"1": [...], "2": [...]}`); non-numeric or missing season keys fall back to season 1.
     */
    fun fetchSeriesEpisodes(connection: LiveTvConnection.Xtream, seriesId: String): List<XtreamSeriesEpisode> {
        val server = normalizedServerUrl(connection.serverUrl)
        val user = connection.username.trim()
        val pass = connection.password.trim()
        if (user.isBlank() || pass.isBlank()) {
            throw XtreamException("Missing username or password")
        }
        val rawSeriesId = seriesId.removePrefix("xtream_series_")

        val base = "$server/player_api.php".toHttpUrlOrNull() ?: throw XtreamException("Invalid server URL")
        val url = base.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", "get_series_info")
            .addQueryParameter("series_id", rawSeriesId)
            .build()
        val infoBody = get(url)
        val info = runCatching { JSONObject(infoBody) }.getOrNull()
            ?: throw XtreamException("Couldn't read this series' episode list")
        val episodesBySeason = info.optJSONObject("episodes") ?: return emptyList()

        val episodes = mutableListOf<XtreamSeriesEpisode>()
        val seasonKeys = episodesBySeason.keys()
        while (seasonKeys.hasNext()) {
            val seasonKey = seasonKeys.next()
            val seasonNumber = seasonKey.toIntOrNull() ?: 1
            val episodeArray = episodesBySeason.optJSONArray(seasonKey) ?: continue
            for (i in 0 until episodeArray.length()) {
                val episode = episodeArray.optJSONObject(i) ?: continue
                val episodeStreamId = episode.optString("id").takeIf { it.isNotBlank() }
                    ?: episode.optInt("id", -1).takeIf { it >= 0 }?.toString()
                    ?: continue
                val episodeNumber = episode.optInt("episode_num", i + 1)
                val title = episode.optString("title").ifBlank { "Episode $episodeNumber" }
                val ext = episode.optJSONObject("info")?.optString("container_extension")
                    ?.takeIf { it.isNotBlank() }
                    ?: episode.optString("container_extension").takeIf { it.isNotBlank() }
                    ?: "mp4"
                val streamUrl = "$server/series/$user/$pass/$episodeStreamId.$ext"
                episodes += XtreamSeriesEpisode(
                    id = "xtream_episode_$episodeStreamId",
                    seriesId = seriesId,
                    season = seasonNumber,
                    episode = episodeNumber,
                    title = title,
                    streamUrl = streamUrl
                )
            }
        }
        return episodes
    }

    /** Fetches the account's full XMLTV EPG export (`xmltv.php`), raw, for [XmltvParser] to parse. */
    fun fetchXmltv(connection: LiveTvConnection.Xtream): String = get(xmltvUrl(connection))

    /**
     * The account's guide (XMLTV) as it downloads, to be read as it comes: a provider's guide is
     * often a hundred megabytes or more, far too much to hold as one piece of text. The caller
     * closes [read]'s stream by returning from it.
     */
    fun <T> streamXmltv(connection: LiveTvConnection.Xtream, read: (java.io.InputStream) -> T): T {
        val url = xmltvUrl(connection)
        val request = Request.Builder().url(url).build()
        clientFor(url).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw XtreamException("HTTP ${response.code}")
            val body = response.body ?: throw XtreamException("Empty guide")
            return read(body.byteStream())
        }
    }

    private fun xmltvUrl(connection: LiveTvConnection.Xtream): okhttp3.HttpUrl {
        val server = normalizedServerUrl(connection.serverUrl)
        val user = connection.username.trim()
        val pass = connection.password.trim()
        val base = "$server/xmltv.php".toHttpUrlOrNull() ?: throw XtreamException("Invalid server URL")
        return base.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build()
    }

    private companion object {
        /** A widely accepted IPTV player's name (VLC's). */
        const val PLAYER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        // RCTV: catch-up.
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_ARCHIVE_DAYS = 30
        const val MAX_PROGRAM_MINUTES = 24 * 60
        const val MAX_CATCH_UP_PROGRAMS = 200
        val SERVER_TIME = Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})""")
        val BASE64 = Regex("^[A-Za-z0-9+/=\\s]+$")
    }
}
