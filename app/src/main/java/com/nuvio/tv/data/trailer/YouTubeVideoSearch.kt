package com.nuvio.tv.data.trailer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val SEARCH_TAG = "YouTubeVideoSearch"
private const val SEARCH_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
private const val WEB_CLIENT_VERSION = "2.20250925.01.00"

/** One video of a YouTube search: what's needed to choose it and play it. */
data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val channel: String?,
    /** Its length in seconds; null for a live stream or an upcoming premiere. */
    val durationSeconds: Int?,
    /** How long ago it was published, in days ("3 days ago" → 3.0); null when YouTube doesn't say. */
    val ageDays: Double?,
    /**
     * RCTV: the unit of [ageDays] in days (1 for "3 days ago", 7 for "2 weeks ago", 30 for
     * "1 month ago"): it was published between [ageDays] and [ageDays] + this many days ago.
     */
    val ageUnitDays: Double? = null,
    /**
     * RCTV: the channel's handle ("@NBA") when it's known for sure: the video came from a search
     * of that channel only, or its uploads. Null when it isn't known.
     */
    val channelHandle: String? = null,
    /** RCTV: YouTube says it's a Short (a vertical video). */
    val isShort: Boolean = false
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Searches YouTube. RCTV Smart Player: first YouTube's official Data API through RCTV Core, which
 * holds the key (/api/v1/youtube/search); while RCTV Core has no key, is out of quota or can't be
 * reached, the way YouTube's website does (no account, no key): its search API, else its results
 * page. Gives the videos found, in YouTube's order.
 */
@Singleton
class YouTubeVideoSearch @Inject constructor() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** RCTV: youtube.com/shorts/ asked as is: it answers a Short there, and sends any other video to /watch. */
    private val shortsCheckClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** RCTV: what [isShort] found, by video. */
    private val shortsChecked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * RCTV (owner's rule: recaps always 16:9, never vertical): whether a video is a YouTube Short.
     * youtube.com/shorts/{id} stays for a Short and sends any other video on to /watch. Null when
     * YouTube didn't say (no connection, another answer).
     */
    suspend fun isShort(videoId: String): Boolean? = withContext(Dispatchers.IO) {
        shortsChecked[videoId]?.let { return@withContext it }
        val answer = try {
            val request = Request.Builder()
                .url("https://www.youtube.com/shorts/$videoId")
                .head()
                .header("User-Agent", SEARCH_USER_AGENT)
                .header("Cookie", CONSENT_COOKIE)
                .build()
            shortsCheckClient.newCall(request).execute().use { response ->
                when {
                    response.code in 300..399 -> {
                        val location = response.header("Location").orEmpty()
                        if ("/shorts/" in location) null else false
                    }
                    response.isSuccessful -> true
                    else -> null
                }
            }
        } catch (error: Exception) {
            Log.w(SEARCH_TAG, "Shorts check failed: ${error.javaClass.simpleName}")
            null
        }
        answer?.also { shortsChecked[videoId] = it }
    }

    /**
     * [spanish]: results as YouTube shows them to Spanish speakers in the US (Spanish channels
     * first). [liveOnly]: only what's streaming live now (a live stream has no length).
     */
    suspend fun search(query: String, spanish: Boolean = false, liveOnly: Boolean = false): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val language = if (spanish) "es" else "en"
        searchCore(query, language, liveOnly, channel = null)?.let { return@withContext it }
        val filter = if (liveOnly) LIVE_FILTER else VIDEOS_FILTER
        val fromApi = runCatching { searchApi(query, language, filter) }
            .onFailure { Log.w(SEARCH_TAG, "Search API failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (fromApi.isNotEmpty()) return@withContext fromApi
        runCatching { searchPage(query, language, filter) }
            .onFailure { Log.w(SEARCH_TAG, "Search page failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * RCTV: only [channel]'s videos ("@NBA"), each one marked with its [YouTubeSearchResult.channelHandle].
     * Through RCTV Core when it can search one channel; else that channel's own search page on YouTube.
     */
    suspend fun searchChannel(query: String, channel: String, spanish: Boolean = false): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !HANDLE.matches(channel)) return@withContext emptyList()
        val language = if (spanish) "es" else "en"
        searchCore(query, language, liveOnly = false, channel = channel)?.let { return@withContext it }
        runCatching { channelPage(channel, "search?query=" + URLEncoder.encode(query, "UTF-8"), language) }
            .onFailure { Log.w(SEARCH_TAG, "Channel search failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** RCTV: [channel]'s newest uploads, the newest first (through RCTV Core, else its videos page). */
    suspend fun latestOfChannel(channel: String): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (!HANDLE.matches(channel)) return@withContext emptyList()
        latestCore(channel)?.let { return@withContext it }
        runCatching { channelPage(channel, "videos", "en") }
            .onFailure { Log.w(SEARCH_TAG, "Channel videos failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Until when RCTV Core's search is left alone (it has no key, is out of quota, or failed). */
    @Volatile private var coreRestUntilMs = 0L

    private val coreClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * YouTube's official search through RCTV Core; null when it can't answer (then the app's own
     * search). An empty list is an answer: nothing found.
     */
    private fun searchCore(query: String, language: String, liveOnly: Boolean, channel: String?): List<YouTubeSearchResult>? {
        val now = System.currentTimeMillis()
        if (now < coreRestUntilMs) return null
        if (channel != null && now < coreChannelsRestUntilMs) return null
        val url = com.nuvio.tv.core.network.RctvServers.CORE + "/api/v1/youtube/search" +
            "?q=" + URLEncoder.encode(query.take(120), "UTF-8") +
            "&lang=" + language + "&live=" + (if (liveOnly) "1" else "0") + "&limit=15" +
            (channel?.let { "&channel=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return try {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            coreClient.newCall(request).execute().use { response ->
                if (response.code == 503) {
                    // No key yet, or the day's quota is spent: asked again in 30 minutes.
                    coreRestUntilMs = now + CORE_REST_UNAVAILABLE_MS
                    return null
                }
                if (!response.isSuccessful) {
                    coreRestUntilMs = now + CORE_REST_FAILED_MS
                    return null
                }
                val body = JSONObject(response.body?.string().orEmpty())
                if (channel != null && body.optString("channel") != channel) {
                    // An RCTV Core that can't search one channel yet: that channel's page meanwhile.
                    coreChannelsRestUntilMs = now + CORE_REST_UNAVAILABLE_MS
                    return null
                }
                coreResultsOf(body.optJSONArray("data") ?: JSONArray(), channel)
                    .filter { !liveOnly || it.durationSeconds == null }
            }
        } catch (error: Exception) {
            Log.w(SEARCH_TAG, "RCTV Core search failed: ${error.javaClass.simpleName}")
            coreRestUntilMs = now + CORE_REST_FAILED_MS
            null
        }
    }

    /** RCTV Core's newest uploads of [channel]; null when it can't answer. */
    private fun latestCore(channel: String): List<YouTubeSearchResult>? {
        val now = System.currentTimeMillis()
        if (now < coreRestUntilMs || now < coreChannelsRestUntilMs) return null
        val url = com.nuvio.tv.core.network.RctvServers.CORE + "/api/v1/youtube/latest" +
            "?channel=" + URLEncoder.encode(channel, "UTF-8") + "&limit=50"
        return try {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            coreClient.newCall(request).execute().use { response ->
                if (response.code == 503) {
                    coreRestUntilMs = now + CORE_REST_UNAVAILABLE_MS
                    return null
                }
                if (!response.isSuccessful) {
                    // Not on this RCTV Core yet (404), or it failed: only the channels part rests.
                    coreChannelsRestUntilMs = now + if (response.code == 404) CORE_REST_UNAVAILABLE_MS else CORE_REST_FAILED_MS
                    return null
                }
                val body = JSONObject(response.body?.string().orEmpty())
                if (body.optString("channel") != channel) return null
                coreResultsOf(body.optJSONArray("data") ?: JSONArray(), channel)
            }
        } catch (error: Exception) {
            Log.w(SEARCH_TAG, "RCTV Core channel videos failed: ${error.javaClass.simpleName}")
            coreChannelsRestUntilMs = now + CORE_REST_FAILED_MS
            null
        }
    }

    /** Until when RCTV Core's one-channel search is left alone (an RCTV Core that can't do it yet). */
    @Volatile private var coreChannelsRestUntilMs = 0L

    /** RCTV Core's videos; [channel]: each one that channel's. */
    private fun coreResultsOf(data: JSONArray, channel: String?): List<YouTubeSearchResult> =
        (0 until data.length()).mapNotNull { index ->
            val video = data.optJSONObject(index) ?: return@mapNotNull null
            val videoId = video.optString("videoId").takeIf { YouTubeVideo.isId(it) } ?: return@mapNotNull null
            val live = video.optBoolean("live", false)
            YouTubeSearchResult(
                videoId = videoId,
                title = video.optString("title"),
                channel = video.optString("channel").takeIf { it.isNotBlank() },
                durationSeconds = if (live) null else video.optInt("durationSeconds", 0).takeIf { it > 0 },
                ageDays = video.optDouble("ageDays").takeIf { !it.isNaN() },
                // The Data API gives the exact time it was published.
                ageUnitDays = 0.0,
                channelHandle = channel
            )
        }

    /**
     * A page of [channel] on YouTube ("search?query=...", "videos"): only that channel's videos,
     * the way its website shows them (no account, no key).
     */
    private fun channelPage(channel: String, page: String, language: String): List<YouTubeSearchResult> {
        val separator = if ('?' in page) '&' else '?'
        val url = "https://www.youtube.com/$channel/$page${separator}hl=$language&gl=US"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SEARCH_USER_AGENT)
            .header("Accept-Language", "$language-US,$language;q=0.9")
            .header("Cookie", CONSENT_COOKIE)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            // Redirected elsewhere (no such channel): none of its videos.
            if (!response.request.url.encodedPath.startsWith("/$channel", ignoreCase = true)) return emptyList()
            val html = response.body?.string().orEmpty()
            val start = INITIAL_DATA_MARKERS.firstNotNullOfOrNull { marker ->
                html.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length }
            } ?: return emptyList()
            val end = html.indexOf(";</script>", start).takeIf { it > start } ?: return emptyList()
            return videosIn(JSONObject(html.substring(start, end))).map { it.copy(channelHandle = channel) }
        }
    }

    /** YouTube's search API (what its website calls), videos only. */
    private fun searchApi(query: String, language: String, filter: String): List<YouTubeSearchResult> {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", WEB_CLIENT_VERSION)
                        .put("hl", language)
                        .put("gl", "US")
                )
            )
            .put("query", query)
            // "Videos" (or "Live") filter: no channels, playlists or shorts shelves.
            .put("params", filter)
            .toString()
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", SEARCH_USER_AGENT)
            .header("Accept-Language", "$language-US,$language;q=0.9")
            .header("Origin", "https://www.youtube.com")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", WEB_CLIENT_VERSION)
            .header("Cookie", CONSENT_COOKIE)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val text = response.body?.string().orEmpty()
            return videosIn(JSONObject(text))
        }
    }

    /** YouTube's results page: the same data, inside the page (`ytInitialData`). */
    private fun searchPage(query: String, language: String, filter: String): List<YouTubeSearchResult> {
        val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&sp=${URLEncoder.encode(URLEncoder.encode(filter, "UTF-8"), "UTF-8")}&hl=$language&gl=US"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SEARCH_USER_AGENT)
            .header("Accept-Language", "$language-US,$language;q=0.9")
            .header("Cookie", CONSENT_COOKIE)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            val start = INITIAL_DATA_MARKERS.firstNotNullOfOrNull { marker ->
                html.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length }
            } ?: return emptyList()
            val end = html.indexOf(";</script>", start).takeIf { it > start } ?: return emptyList()
            return videosIn(JSONObject(html.substring(start, end)))
        }
    }

    /** Every `videoRenderer` in a search response, in order. */
    private fun videosIn(root: JSONObject): List<YouTubeSearchResult> {
        val found = ArrayList<YouTubeSearchResult>()
        val seen = HashSet<String>()
        fun walk(node: Any?, depth: Int) {
            if (depth > 40 || found.size >= MAX_RESULTS) return
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("videoRenderer")?.let { renderer ->
                        resultOf(renderer)?.let { result -> if (seen.add(result.videoId)) found += result }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key == "videoRenderer") continue
                        walk(node.opt(key), depth + 1)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            }
        }
        walk(root, 0)
        return found
    }

    private fun resultOf(renderer: JSONObject): YouTubeSearchResult? {
        val videoId = renderer.optString("videoId").takeIf { it.length == 11 } ?: return null
        val title = textOf(renderer.optJSONObject("title")) ?: return null
        val channel = textOf(renderer.optJSONObject("ownerText")) ?: textOf(renderer.optJSONObject("longBylineText"))
        val length = textOf(renderer.optJSONObject("lengthText"))
        val published = textOf(renderer.optJSONObject("publishedTimeText"))
        // RCTV: a Short opens at /shorts/ (or YouTube's Shorts player): a vertical video.
        val endpoint = renderer.optJSONObject("navigationEndpoint")
        val link = endpoint?.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
        val short = link.startsWith("/shorts/") || endpoint?.has("reelWatchEndpoint") == true
        return YouTubeSearchResult(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSeconds = length?.let(::secondsOf),
            ageDays = published?.let(::ageDaysOf),
            ageUnitDays = published?.let(::ageUnitDaysOf),
            isShort = short
        )
    }

    private fun textOf(node: JSONObject?): String? {
        if (node == null) return null
        node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return null
        val text = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        return text.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val MAX_RESULTS = 25
        private const val CORE_REST_UNAVAILABLE_MS = 30 * 60_000L
        private const val CORE_REST_FAILED_MS = 5 * 60_000L
        /** YouTube's search filters: videos; live now. */
        private const val VIDEOS_FILTER = "EgIQAQ=="
        private const val LIVE_FILTER = "EgJAAQ=="
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val CONSENT_COOKIE = "CONSENT=YES+1; SOCS=CAI"
        /** A channel's handle: "@NBA", "@tudn_usa". */
        internal val HANDLE = Regex("""@[A-Za-z0-9._-]{3,30}""")
        private val INITIAL_DATA_MARKERS = listOf("var ytInitialData = ", "window[\"ytInitialData\"] = ", "ytInitialData = ")

        /** "1:02:03" / "10:23" → seconds. */
        internal fun secondsOf(length: String): Int? {
            val parts = length.trim().split(':').map { it.toIntOrNull() ?: return null }
            return parts.fold(0) { total, part -> total * 60 + part }
        }

        /** "3 days ago" → 1.0, "2 weeks ago" → 7.0, "hace 1 mes" → 30.0: the unit the age is given in, in days. */
        internal fun ageUnitDaysOf(published: String): Double? = ageOf(published)?.second

        /** "3 days ago", "Streamed 2 weeks ago", "hace 3 días", "Transmitido hace 2 semanas" → days. */
        internal fun ageDaysOf(published: String): Double? = ageOf(published)?.first

        /** The age in days and its unit in days. */
        private fun ageOf(published: String): Pair<Double, Double>? {
            val english = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
                .find(published)
            val spanish = if (english == null) {
                Regex("""hace\s+(\d+)\s+(segundo|minuto|hora|d[ií]a|semana|mes|año|ano)""", RegexOption.IGNORE_CASE).find(published)
            } else {
                null
            }
            val match = english ?: spanish ?: return null
            val count = match.groupValues[1].toDoubleOrNull() ?: return null
            val unitDays = when (match.groupValues[2].lowercase()) {
                "second", "segundo" -> 1.0 / 86_400
                "minute", "minuto" -> 1.0 / 1_440
                "hour", "hora" -> 1.0 / 24
                "day", "dia", "día" -> 1.0
                "week", "semana" -> 7.0
                "month", "mes" -> 30.0
                else -> 365.0
            }
            return count * unitDays to unitDays
        }
    }
}
