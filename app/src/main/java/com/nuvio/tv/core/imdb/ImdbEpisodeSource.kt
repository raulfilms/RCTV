package com.nuvio.tv.core.imdb

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ImdbEpisodeSource"

/**
 * RCTV: a show's episodes as IMDb lists them (season, number, title): from IMDb's GraphQL API (what
 * imdb.com itself reads), else from the show's episodes pages on imdb.com, one season at a time.
 * Null when IMDb can't be reached or answers in a way that isn't understood.
 */
internal class ImdbEpisodeSource(private val client: OkHttpClient) {

    /** The episodes of [imdbId] ("tt0206512"); [seasons]: the ones wanted from the pages when the API doesn't answer. */
    fun episodes(imdbId: String, seasons: Collection<Int>): List<ImdbEpisode>? {
        if (!IMDB_ID.matches(imdbId)) return null
        runCatching { fromGraphQl(imdbId) }
            .onFailure { Log.w(TAG, "GraphQL for $imdbId: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val fromPages = ArrayList<ImdbEpisode>()
        for (season in seasons.filter { it > 0 }.distinct().sorted().take(MAX_PAGE_SEASONS)) {
            val found = runCatching { fromSeasonPage(imdbId, season) }
                .onFailure { Log.w(TAG, "Page of $imdbId season $season: ${it.message}") }
                .getOrNull() ?: return fromPages.takeIf { it.isNotEmpty() }
            fromPages += found
        }
        return fromPages.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ GraphQL

    private fun fromGraphQl(imdbId: String): List<ImdbEpisode>? {
        val all = ArrayList<ImdbEpisode>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val afterArgument = after?.let { ", after: ${JSONObject.quote(it)}" }.orEmpty()
            val query = """
                query {
                  title(id: ${JSONObject.quote(imdbId)}) {
                    episodes {
                      episodes(first: $PAGE_SIZE$afterArgument) {
                        pageInfo { hasNextPage endCursor }
                        edges {
                          node {
                            id
                            titleText { text }
                            series { displayableEpisodeNumber { displayableSeason { text } episodeNumber { text } } }
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
            val body = JSONObject().put("query", query).toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .post(body)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", USER_AGENT)
                .header("x-imdb-user-country", "US")
                .header("x-imdb-user-language", "en-US")
                .build()
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val connection = JSONObject(text).optJSONObject("data")?.optJSONObject("title")
                ?.optJSONObject("episodes")?.optJSONObject("episodes")
                ?: return all.takeIf { it.isNotEmpty() }
            val edges = connection.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val number = node.optJSONObject("series")?.optJSONObject("displayableEpisodeNumber") ?: continue
                val season = number.optJSONObject("displayableSeason")?.optString("text")?.trim()?.toIntOrNull() ?: continue
                val episode = number.optJSONObject("episodeNumber")?.optString("text")?.trim()?.toIntOrNull() ?: continue
                val title = node.optJSONObject("titleText")?.optString("text")?.trim().orEmpty()
                if (title.isNotEmpty()) all += ImdbEpisode(season, episode, title)
            }
            val pageInfo = connection.optJSONObject("pageInfo")
            val next = pageInfo?.optString("endCursor")?.takeIf { it.isNotBlank() && it != "null" }
            if (pageInfo?.optBoolean("hasNextPage") != true || next == null || next == after) return all
            after = next
        }
        return all
    }

    // ------------------------------------------------------------------ imdb.com's pages

    /** A season's episodes from its page's data (Next.js's __NEXT_DATA__), wherever in it they are. */
    private fun fromSeasonPage(imdbId: String, season: Int): List<ImdbEpisode> {
        val request = Request.Builder()
            .url("https://www.imdb.com/title/$imdbId/episodes/?season=$season")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", USER_AGENT)
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val start = html.indexOf(NEXT_DATA_MARK)
        if (start < 0) error("no page data")
        val jsonStart = html.indexOf('>', start) + 1
        val jsonEnd = html.indexOf("</script>", jsonStart)
        if (jsonStart <= 0 || jsonEnd < 0) error("no page data")
        val data = JSONObject(html.substring(jsonStart, jsonEnd))
        val found = LinkedHashMap<Int, ImdbEpisode>()
        collectEpisodes(data, season, found, depth = 0)
        return found.values.toList()
    }

    /** Objects shaped like an episode of [season] ({"season": "1", "episode": "2", "titleText": ...}), anywhere in [node]. */
    private fun collectEpisodes(node: Any?, season: Int, out: MutableMap<Int, ImdbEpisode>, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (node) {
            is JSONObject -> {
                val itsSeason = node.opt("season")?.toString()?.trim()?.toIntOrNull()
                val itsEpisode = node.opt("episode")?.toString()?.trim()?.toIntOrNull()
                val title = when (val titleText = node.opt("titleText")) {
                    is JSONObject -> titleText.optString("text")
                    is String -> titleText
                    else -> null
                }?.trim()
                if (itsSeason == season && itsEpisode != null && !title.isNullOrEmpty()) {
                    out.putIfAbsent(itsEpisode, ImdbEpisode(season, itsEpisode, title))
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) collectEpisodes(node.opt(keys.next()), season, out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) collectEpisodes(node.opt(i), season, out, depth + 1)
        }
    }

    private companion object {
        const val GRAPHQL_URL = "https://api.graphql.imdb.com/"
        const val PAGE_SIZE = 250
        const val MAX_PAGES = 12
        const val MAX_PAGE_SEASONS = 40
        const val MAX_DEPTH = 40
        const val NEXT_DATA_MARK = "id=\"__NEXT_DATA__\""
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val JSON = "application/json".toMediaType()
        val IMDB_ID = Regex("tt\\d{5,10}")
    }
}
