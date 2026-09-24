package com.nuvio.tv.data.remote.stalker

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.StalkerSeasonItem
import com.nuvio.tv.domain.model.StalkerSeriesItem
import com.nuvio.tv.domain.model.StalkerVodItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Client for the Stalker/Ministra portal protocol used by many IPTV providers as an alternative
 * to Xtream Codes: MAC-address-based auth (no username/password) against a `server/load.php`
 * endpoint. Unlike Xtream, most playback links are single-use tokens ("cmd") that must be
 * exchanged for a real URL via `create_link` right before playback starts, so `fetchChannels`/
 * `fetchVod`/`fetchSeriesCatalog` return portal identifiers, and callers resolve a playable URL
 * on demand via [resolveStreamUrl]/[resolveVodStreamUrl].
 */
@Singleton
class StalkerClient @Inject constructor(
    @Named("addonPermissive") private val okHttpClient: OkHttpClient
) {
    class StalkerException(message: String) : Exception(message)

    /** One authenticated session against a portal; callers keep this around for the lifetime of a screen/refresh. */
    class Session internal constructor(
        internal val apiBase: String,
        internal val macAddress: String,
        internal var token: String
    )

    private fun baseHeaders(session: Session): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
        "Cookie" to "mac=${session.macAddress}; stb_lang=en; timezone=Europe/London",
        "X-User-Agent" to "Model: MAG250; Link: WiFi",
        "Authorization" to "Bearer ${session.token}"
    )

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        okHttpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw StalkerException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    /**
     * Authenticates against the portal, probing common base paths (`/`, `/stalker_portal`,
     * `/portal`, `/ministra`) since providers disagree on where the API actually lives even when
     * the user-facing portal is served under `/c`. Throws [StalkerException] if none respond.
     */
    fun authenticate(connection: LiveTvConnection.Stalker): Session {
        val portal = connection.portalUrl.trim().trimEnd('/')
        val mac = connection.macAddress.trim().uppercase()
        if (portal.isBlank() || mac.isBlank()) {
            throw StalkerException("Missing portal URL or MAC address")
        }
        val root = portal.removeSuffix("/c").removeSuffix("/")
        val candidates = listOf(portal, root, "$root/stalker_portal", "$root/portal", "$root/ministra").distinct()
        for (base in candidates) {
            val token = runCatching { handshake(base, mac) }.getOrNull()
            if (!token.isNullOrBlank()) {
                val session = Session(apiBase = base, macAddress = mac, token = token)
                // get_profile isn't required for a token to work, but some portals only
                // fully activate a session (channel/VOD lists included) after it's called.
                runCatching { get("$base/server/load.php?type=stb&action=get_profile&JsHttpRequest=1-xml", baseHeaders(session)) }
                return session
            }
        }
        throw StalkerException("Couldn't authenticate with this portal (check the URL and MAC address)")
    }

    private fun handshake(base: String, mac: String): String? {
        val response = get(
            "$base/server/load.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                "Cookie" to "mac=$mac; stb_lang=en; timezone=Europe/London",
                "X-User-Agent" to "Model: MAG250; Link: WiFi"
            )
        )
        return runCatching { JSONObject(response).optJSONObject("js")?.optString("token") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** Fetches every live channel the portal offers, grouped by its genre (category) name. */
    fun fetchChannels(session: Session): List<LiveTvChannel> {
        val headers = baseHeaders(session)
        val genreMap = runCatching {
            val array = JSONObject(get("${session.apiBase}/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml", headers))
                .optJSONArray("js") ?: JSONArray()
            buildMap {
                for (i in 0 until array.length()) {
                    val genre = array.optJSONObject(i) ?: continue
                    val id = genre.optString("id")
                    if (id.isNotBlank()) put(id, genre.optString("title").ifBlank { id })
                }
            }
        }.getOrDefault(emptyMap())

        val channels = mutableListOf<LiveTvChannel>()
        val seenIds = HashSet<String>()
        var page = 1
        var hasMore = true
        while (hasMore) {
            val body = get(
                "${session.apiBase}/server/load.php?type=itv&action=get_all_channels&p=$page&JsHttpRequest=1-xml",
                headers
            )
            val js = runCatching { JSONObject(body).optJSONObject("js") }.getOrNull() ?: break
            val data = js.optJSONArray("data") ?: break

            var newCount = 0
            for (i in 0 until data.length()) {
                val channel = data.optJSONObject(i) ?: continue
                val id = channel.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (!seenIds.add(id)) continue
                newCount++
                val cmd = channel.optString("cmd").takeIf { it.isNotBlank() } ?: continue
                val genreId = channel.optString("tv_genre_id")
                channels += LiveTvChannel(
                    id = "stalker_$id",
                    name = channel.optString("name").ifBlank { "Channel $id" },
                    // Stalker "cmd" tokens are typically short-lived, so the raw cmd is stored
                    // here and resolved to a real URL via resolveStreamUrl right before playback.
                    streamUrl = cmd,
                    logoUrl = channel.optString("logo").takeIf { it.isNotBlank() },
                    groupTitle = genreMap[genreId] ?: genreId.takeIf { it.isNotBlank() },
                    epgChannelId = id
                )
            }

            val totalItems = js.optInt("total_items", 0)
            val maxPageItems = js.optInt("max_page_items", 20).coerceAtLeast(1)
            hasMore = newCount > 0 && page * maxPageItems < totalItems && data.length() < totalItems
            page++
        }
        return channels
    }

    /** Exchanges a live channel's `cmd` token for a playable stream URL. Call right before playback starts. */
    fun resolveStreamUrl(session: Session, cmd: String): String? = resolveLink(session, type = "itv", cmd = cmd)

    /** Exchanges a VOD/episode `cmd` token for a playable stream URL. [episode] selects an episode number within a season `cmd`; omit it to resolve a movie. */
    fun resolveVodStreamUrl(session: Session, cmd: String, episode: Int? = null): String? =
        resolveLink(session, type = "vod", cmd = cmd, episode = episode)

    private fun resolveLink(session: Session, type: String, cmd: String, episode: Int? = null): String? {
        val encodedCmd = java.net.URLEncoder.encode(cmd, "UTF-8")
        val seriesParam = episode?.takeIf { it > 0 }?.let { "&series=$it" }.orEmpty()
        val body = get(
            "${session.apiBase}/server/load.php?type=$type&action=create_link&cmd=$encodedCmd$seriesParam&forced_storage=undefined&disable_ad=0&JsHttpRequest=1-xml",
            baseHeaders(session)
        )
        val rawCmd = runCatching { JSONObject(body).optJSONObject("js")?.optString("cmd") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return sanitizePlaybackCommand(rawCmd)
    }

    /** Portals prefix the playable URL with the player they expect ("ffmpeg http://...") — strip that hint. */
    private fun sanitizePlaybackCommand(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val separator = trimmed.indexOf(' ')
        if (separator <= 0) return trimmed
        if (trimmed.substring(0, separator).contains("://")) return trimmed
        return trimmed.substring(separator + 1).trim().ifBlank { null }
    }

    /** The portal's VOD categories (the movie counterpart of [fetchChannels]'s genres). */
    fun fetchVodCategories(session: Session): Map<String, String> = fetchCategories(session, "vod")

    /** The portal's series categories. */
    fun fetchSeriesCategories(session: Session): Map<String, String> = fetchCategories(session, "series")

    private fun fetchCategories(session: Session, type: String): Map<String, String> = runCatching {
        val array = JSONObject(get("${session.apiBase}/server/load.php?type=$type&action=get_categories&JsHttpRequest=1-xml", baseHeaders(session)))
            .optJSONArray("js") ?: JSONArray()
        buildMap {
            for (i in 0 until array.length()) {
                val category = array.optJSONObject(i) ?: continue
                val id = category.optString("id").trim()
                // "*" is the portal's "all categories" pseudo-entry, never a real category id.
                if (id.isBlank() || id == "*") continue
                put(id, category.optString("title").trim().ifBlank { id })
            }
        }
    }.getOrDefault(emptyMap())

    /**
     * Pages through the portal's VOD catalog. [categoryId] narrows to one category, or pass
     * `null`/blank for every category. Most portals only serve ~14 items per page, so callers
     * doing a full catalog browse should page through this as the user scrolls rather than
     * fetching every page up front.
     */
    fun fetchVod(session: Session, categoryId: String? = null, page: Int = 1): List<StalkerVodItem> {
        val category = categoryId?.takeIf { it.isNotBlank() } ?: "*"
        val body = get(
            "${session.apiBase}/server/load.php?type=vod&action=get_ordered_list&category=$category&sortby=added&p=$page&JsHttpRequest=1-xml",
            baseHeaders(session)
        )
        val data = runCatching { JSONObject(body).optJSONObject("js")?.optJSONArray("data") }.getOrNull() ?: return emptyList()
        val items = mutableListOf<StalkerVodItem>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val cmd = entry.optString("cmd").takeIf { it.isNotBlank() } ?: continue
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: cmd
            items += StalkerVodItem(
                id = "stalker_vod_$id",
                name = entry.optString("name").ifBlank { "Movie $id" },
                cmd = cmd,
                posterUrl = entry.optString("screenshot_uri").takeIf { it.isNotBlank() },
                year = entry.optString("year").takeIf { it.isNotBlank() },
                categoryName = null,
                imdbId = null,
                tmdbId = entry.optString("tmdb_id").takeIf { it.isNotBlank() }
                    ?: entry.optString("tmdb").takeIf { it.isNotBlank() }
            )
        }
        return items
    }

    /** Searches the portal's VOD catalog server-side (avoids paging through the whole catalog for a lookup). */
    fun searchVod(session: Session, query: String, maxPages: Int = 3): List<StalkerVodItem> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val encodedTerm = java.net.URLEncoder.encode(term, "UTF-8")
        val results = mutableListOf<StalkerVodItem>()
        val seenKeys = HashSet<String>()
        var page = 1
        while (page <= maxPages) {
            val body = get(
                "${session.apiBase}/server/load.php?type=vod&action=get_ordered_list&category=0&sortby=name&search=$encodedTerm&p=$page&JsHttpRequest=1-xml",
                baseHeaders(session)
            )
            val js = runCatching { JSONObject(body).optJSONObject("js") }.getOrNull() ?: break
            val data = js.optJSONArray("data") ?: break
            if (data.length() == 0) break

            var newEntries = 0
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: continue
                val cmd = entry.optString("cmd").takeIf { it.isNotBlank() } ?: continue
                val id = entry.optString("id").takeIf { it.isNotBlank() } ?: cmd
                if (!seenKeys.add(id)) continue
                newEntries++
                results += StalkerVodItem(
                    id = "stalker_vod_$id",
                    name = entry.optString("name").ifBlank { "Movie $id" },
                    cmd = cmd,
                    posterUrl = entry.optString("screenshot_uri").takeIf { it.isNotBlank() },
                    year = entry.optString("year").takeIf { it.isNotBlank() },
                    categoryName = null,
                    imdbId = null,
                    tmdbId = entry.optString("tmdb_id").takeIf { it.isNotBlank() }
                )
            }
            val totalItems = js.optInt("total_items", 0)
            if (newEntries == 0) break
            if (totalItems > 0 && data.length() >= totalItems) break
            page++
        }
        return results
    }

    /** Fetches the portal's series catalog (shows only — call [fetchSeasons] for one show's seasons/episodes). */
    fun fetchSeriesCatalog(session: Session, categoryId: String? = null, page: Int = 1): List<StalkerSeriesItem> {
        val category = categoryId?.takeIf { it.isNotBlank() } ?: "*"
        val body = get(
            "${session.apiBase}/server/load.php?type=series&action=get_ordered_list&category=$category&sortby=added&p=$page&JsHttpRequest=1-xml",
            baseHeaders(session)
        )
        val data = runCatching { JSONObject(body).optJSONObject("js")?.optJSONArray("data") }.getOrNull() ?: return emptyList()
        val items = mutableListOf<StalkerSeriesItem>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: continue
            items += StalkerSeriesItem(
                id = "stalker_series_$id",
                name = entry.optString("name").ifBlank { "Series $id" },
                posterUrl = entry.optString("screenshot_uri").takeIf { it.isNotBlank() },
                categoryName = null,
                imdbId = null,
                tmdbId = entry.optString("tmdb_id").takeIf { it.isNotBlank() }
            )
        }
        return items
    }

    /**
     * Fetches the seasons of one show (`get_ordered_list&movie_id=...`). Each season carries its
     * own `cmd` plus the episode numbers available in it; there's no per-episode id — an episode
     * is addressed as (season `cmd`, episode number) via [resolveVodStreamUrl].
     */
    fun fetchSeasons(session: Session, seriesId: String): List<StalkerSeasonItem> {
        val rawId = seriesId.removePrefix("stalker_series_")
        val encodedId = java.net.URLEncoder.encode(rawId, "UTF-8")
        val body = get(
            "${session.apiBase}/server/load.php?type=series&action=get_ordered_list&movie_id=$encodedId&JsHttpRequest=1-xml",
            baseHeaders(session)
        )
        val data = runCatching { JSONObject(body).optJSONObject("js")?.optJSONArray("data") }.getOrNull() ?: return emptyList()
        val seasons = mutableListOf<StalkerSeasonItem>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val cmd = entry.optString("cmd").takeIf { it.isNotBlank() } ?: continue
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: cmd
            val episodeNumbers = mutableListOf<Int>()
            val seriesField = entry.opt("series")
            if (seriesField is JSONArray) {
                for (j in 0 until seriesField.length()) {
                    seriesField.optString(j).toIntOrNull()?.let { if (it > 0) episodeNumbers += it }
                }
            }
            seasons += StalkerSeasonItem(
                id = "stalker_season_$id",
                name = entry.optString("name").ifBlank { "Season" },
                cmd = cmd,
                episodeNumbers = episodeNumbers.distinct().sorted()
            )
        }
        return seasons
    }
}
