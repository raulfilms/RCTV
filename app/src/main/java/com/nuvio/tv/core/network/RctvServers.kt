package com.nuvio.tv.core.network

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * RCTV: the app's own servers (public addresses; nothing secret in them or in what they're sent).
 *
 * - [DATA] (Google Cloud Run, rctv-datos): ESPN's scores, lists and news, trimmed to what the app
 *   reads and kept for a moment (see servers/cloud-run).
 * - [LEAGUES] (Google Cloud Run, rctv-ligas): the same for leagues and teams (teams, standings,
 *   schedules, rosters, statistics).
 * - [IMAGES] (Cloudflare Worker, rctv-imagenes): sports logos and photos and channel logos shrunk
 *   to the size of their tile. ESPN's own images are shrunk by ESPN instead ([espnImage]).
 *
 * Whenever a server fails, the app asks ESPN or the image's own site directly, as it did before, and
 * leaves that server alone for a minute.
 */
object RctvServers {

    const val DATA = "https://rctv-datos-362306500557.us-east1.run.app"
    const val LEAGUES = "https://rctv-ligas-362306500557.us-east1.run.app"
    const val IMAGES = "https://rctv-imagenes.raulfilmspr.workers.dev"

    /** How long a server that failed is left alone. */
    private const val DOWN_FOR_MS = 60_000L

    private val ESPN_API_PREFIXES = listOf("https://site.api.espn.com/apis/", "https://site.web.api.espn.com/apis/")

    /** ESPN's league and team pages (rctv-ligas); the rest (scores, news, games) go to rctv-datos. */
    private val LEAGUE_PARTS = listOf("/teams", "/groups", "/standings", "/roster", "/schedule", "/statistics")

    /** Sites of sports images shrunk by rctv-imagenes (ESPN's are shrunk by ESPN). */
    private val SPORTS_IMAGE_HOSTS = setOf(
        "r2.thesportsdb.com", "www.thesportsdb.com", "flagcdn.com",
        "img.mlbstatic.com", "www.mlbstatic.com", "cf.nascar.com"
    )

    /** Address parameters that may carry a login: an image with one never goes to a server. */
    private val SECRET_PARAMS = setOf(
        "user", "username", "pass", "password", "pwd", "token", "key", "apikey", "api_key", "auth", "signature", "sig"
    )

    /** Sizes an image is shrunk to (the smallest that covers its tile): few sizes, few versions to make. */
    private val IMAGE_SIDES = intArrayOf(64, 96, 128, 192, 256, 384, 512)

    /** Marks a channel logo to be shrunk by rctv-imagenes ([channelLogo]). */
    const val SHRINK_MARK = "rctv-shrink:"

    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---- ESPN's data ----

    /** [espnUrl] asked through the right server; null when it isn't ESPN's API or that server is resting. */
    fun espn(espnUrl: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (ESPN_API_PREFIXES.none { espnUrl.startsWith(it) }) return null
        val path = espnUrl.substringAfter("/apis/").substringBefore('?')
        val server = if (LEAGUE_PARTS.any { path.contains(it) }) LEAGUES else DATA
        if (isDown(server, nowMs)) return null
        return "$server/espn?u=" + URLEncoder.encode(espnUrl, "UTF-8")
    }

    /** The server behind [serverUrl] failed (no answer, or a server error): left alone for a minute. */
    fun failed(serverUrl: String, nowMs: Long = System.currentTimeMillis()) {
        val server = listOf(DATA, LEAGUES, IMAGES).firstOrNull { serverUrl.startsWith(it) } ?: return
        downUntil[server] = nowMs + DOWN_FOR_MS
    }

    private fun isDown(server: String, nowMs: Long): Boolean = (downUntil[server] ?: 0L) > nowMs

    // ---- Images ----

    /** A channel logo, marked to be shrunk on the server when its address is safe to send; else as it is. */
    fun channelLogo(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return if (isSafeImageAddress(url)) SHRINK_MARK + url else url
    }

    /**
     * Where to load [url] from for a tile [widthPx] × [heightPx] (0: not known): ESPN's own resizer for
     * ESPN's images, rctv-imagenes for other sports sites and marked channel logos ([forced]); null to
     * load it as it is.
     */
    fun shrunkImage(url: String, widthPx: Int, heightPx: Int, forced: Boolean = false, nowMs: Long = System.currentTimeMillis()): String? {
        val wanted = maxOf(widthPx, heightPx)
        val side = if (wanted <= 0) IMAGE_SIDES.last() else IMAGE_SIDES.firstOrNull { it >= wanted } ?: return null
        if (!isSafeImageAddress(url)) return null
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (host == "a.espncdn.com") return espnImage(url, side)
        if (!forced && host !in SPORTS_IMAGE_HOSTS) return null
        if (isDown(IMAGES, nowMs)) return null
        return "$IMAGES/?url=" + URLEncoder.encode(url, "UTF-8") + "&w=$side&h=$side"
    }

    /** An ESPN image ("https://a.espncdn.com/i/teamlogos/nba/500/bos.png") from ESPN's resizer, [side] px. */
    internal fun espnImage(url: String, side: Int): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        if (!path.startsWith("/i/") || url.contains('?')) return null
        return "https://a.espncdn.com/combiner/i?img=" + URLEncoder.encode(path, "UTF-8") + "&w=$side&h=$side"
    }

    /** https, no login in the address and no parameter that may carry one. */
    private fun isSafeImageAddress(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
        if (uri.rawUserInfo != null) return false
        val query = uri.rawQuery ?: return true
        return query.split('&').none { part ->
            part.substringBefore('=').lowercase(Locale.ROOT) in SECRET_PARAMS
        }
    }
}
