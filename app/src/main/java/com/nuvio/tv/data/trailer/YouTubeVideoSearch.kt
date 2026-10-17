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
    val ageDays: Double?
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Searches YouTube (no account, no key) the way its website does: YouTube's own search API, else
 * its results page. Gives the videos found, in YouTube's order.
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

    suspend fun search(query: String): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val fromApi = runCatching { searchApi(query) }
            .onFailure { Log.w(SEARCH_TAG, "Search API failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (fromApi.isNotEmpty()) return@withContext fromApi
        runCatching { searchPage(query) }
            .onFailure { Log.w(SEARCH_TAG, "Search page failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** YouTube's search API (what its website calls), videos only. */
    private fun searchApi(query: String): List<YouTubeSearchResult> {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", WEB_CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", "US")
                )
            )
            .put("query", query)
            // "Videos" filter: no channels, playlists or shorts shelves.
            .put("params", "EgIQAQ==")
            .toString()
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", SEARCH_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
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
    private fun searchPage(query: String): List<YouTubeSearchResult> {
        val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&sp=EgIQAQ%253D%253D&hl=en&gl=US"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SEARCH_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
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
        return YouTubeSearchResult(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSeconds = length?.let(::secondsOf),
            ageDays = published?.let(::ageDaysOf)
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
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val CONSENT_COOKIE = "CONSENT=YES+1; SOCS=CAI"
        private val INITIAL_DATA_MARKERS = listOf("var ytInitialData = ", "window[\"ytInitialData\"] = ", "ytInitialData = ")

        /** "1:02:03" / "10:23" → seconds. */
        internal fun secondsOf(length: String): Int? {
            val parts = length.trim().split(':').map { it.toIntOrNull() ?: return null }
            return parts.fold(0) { total, part -> total * 60 + part }
        }

        /** "3 days ago", "Streamed 2 weeks ago", "1 hour ago" → days. */
        internal fun ageDaysOf(published: String): Double? {
            val match = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
                .find(published) ?: return null
            val count = match.groupValues[1].toDoubleOrNull() ?: return null
            val unitDays = when (match.groupValues[2].lowercase()) {
                "second" -> 1.0 / 86_400
                "minute" -> 1.0 / 1_440
                "hour" -> 1.0 / 24
                "day" -> 1.0
                "week" -> 7.0
                "month" -> 30.0
                else -> 365.0
            }
            return count * unitDays
        }
    }
}
