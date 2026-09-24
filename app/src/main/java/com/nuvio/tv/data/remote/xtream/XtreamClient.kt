package com.nuvio.tv.data.remote.xtream

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
    @Named("addonPermissive") private val okHttpClient: OkHttpClient
) {
    class XtreamException(message: String) : Exception(message)

    private fun normalizedServerUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
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

    private fun get(url: okhttp3.HttpUrl): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
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

        val authBody = get(playerApiUrl(server, user, pass))
        val authJson = runCatching { JSONObject(authBody) }.getOrNull()
            ?: throw XtreamException("Unexpected response from server")
        val userInfo = authJson.optJSONObject("user_info")
        val authStatus = userInfo?.optString("auth")
        if (userInfo == null || authStatus == "0") {
            throw XtreamException("Invalid Xtream credentials")
        }

        val categoryNames = runCatching {
            val array = JSONArray(get(playerApiUrl(server, user, pass, "get_live_categories")))
            buildMap {
                for (i in 0 until array.length()) {
                    val category = array.getJSONObject(i)
                    val id = category.optString("category_id")
                    val name = category.optString("category_name")
                    if (id.isNotBlank()) put(id, name.ifBlank { id })
                }
            }
        }.getOrDefault(emptyMap())

        val streamsBody = get(playerApiUrl(server, user, pass, "get_live_streams"))
        val streamsArray = runCatching { JSONArray(streamsBody) }.getOrNull()
            ?: throw XtreamException("Couldn't read the channel list from this server")

        val channels = mutableListOf<LiveTvChannel>()
        for (i in 0 until streamsArray.length()) {
            val stream = streamsArray.optJSONObject(i) ?: continue
            val streamId = stream.optString("stream_id").takeIf { it.isNotBlank() }
                ?: stream.optInt("stream_id", -1).takeIf { it >= 0 }?.toString()
                ?: continue
            val name = stream.optString("name").ifBlank { "Channel $streamId" }
            val categoryId = stream.optString("category_id")
            val logo = stream.optString("stream_icon").takeIf { it.isNotBlank() }
            val epgChannelId = stream.optString("epg_channel_id").takeIf { it.isNotBlank() }
            val streamUrl = "$server/live/$user/$pass/$streamId.m3u8"
            channels += LiveTvChannel(
                id = "xtream_$streamId",
                name = name,
                streamUrl = streamUrl,
                logoUrl = logo,
                groupTitle = categoryNames[categoryId] ?: categoryId.takeIf { it.isNotBlank() },
                epgChannelId = epgChannelId
            )
        }
        return channels
    }

    /** Verifies credentials and returns the account's auth JSON (throws [XtreamException] on failure). */
    private fun authenticate(server: String, user: String, pass: String): JSONObject {
        val authBody = get(playerApiUrl(server, user, pass))
        val authJson = runCatching { JSONObject(authBody) }.getOrNull()
            ?: throw XtreamException("Unexpected response from server")
        val userInfo = authJson.optJSONObject("user_info")
        val authStatus = userInfo?.optString("auth")
        if (userInfo == null || authStatus == "0") {
            throw XtreamException("Invalid Xtream credentials")
        }
        return authJson
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
    fun fetchXmltv(connection: LiveTvConnection.Xtream): String {
        val server = normalizedServerUrl(connection.serverUrl)
        val user = connection.username.trim()
        val pass = connection.password.trim()
        val base = "$server/xmltv.php".toHttpUrlOrNull() ?: throw XtreamException("Invalid server URL")
        val url = base.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build()
        return get(url)
    }
}
