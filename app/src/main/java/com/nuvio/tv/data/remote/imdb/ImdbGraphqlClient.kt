package com.nuvio.tv.data.remote.imdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A title of an IMDb watchlist or of the user's ratings. */
data class ImdbTitle(
    /** "tt0111161". */
    val id: String,
    val name: String,
    /** "movie", "tvSeries", "tvMiniSeries", "tvMovie", "tvEpisode", "short"... */
    val titleType: String,
    val isSeries: Boolean,
    val isEpisode: Boolean,
    val year: Int?,
    val posterUrl: String?,
    val genres: List<String>,
    val imdbRating: Float?,
    /** When it went into the watchlist (or was rated), epoch ms; 0 when IMDb doesn't say. */
    val addedAtMs: Long
)

/** The user's IMDb account, as IMDb names it. */
data class ImdbProfile(val userId: String, val nickname: String?)

/** What IMDb answered when it can't give what was asked. */
class ImdbException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        /** Not an IMDb profile id or link. */
        BAD_ID,
        /** IMDb has no such user. */
        NOT_FOUND,
        /** The watchlist isn't public. */
        PRIVATE,
        /** The IMDb sign-in (needed to mark titles watched) expired or was refused. */
        SIGNED_OUT,
        /** IMDb couldn't be reached, or answered something else. */
        FAILED
    }
}

/**
 * A user's IMDb watchlist and ratings, from IMDb's own GraphQL API (what imdb.com itself uses):
 * no account, key or login. The watchlist must be public on IMDb; the ratings are read as they
 * are. Only for the user's own account, kept on the device and asked again now and then.
 */
@Singleton
class ImdbGraphqlClient @Inject constructor() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** The account behind what the user typed: "ur12345678", "p.abc…", or a link to the profile. */
    suspend fun profileFor(input: String): ImdbProfile {
        val key = profileKeyOf(input) ?: throw ImdbException(ImdbException.Reason.BAD_ID, "Not an IMDb profile: $input")
        if (UR_ID.matches(key)) return ImdbProfile(userId = key, nickname = null)
        val data = query(PROFILE_QUERY, JSONObject().put("profileId", key))
        val profile = data.optJSONObject("userProfile")
            ?: throw ImdbException(ImdbException.Reason.NOT_FOUND, "No IMDb profile $key")
        val userId = profile.optString("userId").takeIf { UR_ID.matches(it) }
            ?: throw ImdbException(ImdbException.Reason.NOT_FOUND, "No IMDb profile $key")
        return ImdbProfile(userId = userId, nickname = profile.optString("nickName").takeIf { it.isNotBlank() })
    }

    /**
     * The watchlist, in its own order, with its owner's name. Throws [ImdbException] with
     * [ImdbException.Reason.PRIVATE] when it isn't public.
     */
    suspend fun watchlist(userId: String, cookies: String? = null): Pair<List<ImdbTitle>, String?> {
        val titles = ArrayList<ImdbTitle>()
        val seen = HashSet<String>()
        var owner: String? = null
        var after: String? = null
        repeat(MAX_PAGES) {
            val variables = JSONObject().put("userId", userId).put("first", PAGE_SIZE).put("after", after ?: JSONObject.NULL)
            val list = query(WATCHLIST_QUERY, variables, cookies).optJSONObject("predefinedList")
                ?: throw ImdbException(ImdbException.Reason.PRIVATE, "No public watchlist for $userId")
            owner = owner ?: list.optJSONObject("author")?.optJSONObject("username")?.optString("text")?.takeIf { it.isNotBlank() }
            val connection = list.optJSONObject("titleListItemSearch") ?: return titles to owner
            val edges = connection.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edges.length()) {
                val edge = edges.optJSONObject(i) ?: continue
                val title = titleOf(edge.optJSONObject("title"), addedAt = edge.optJSONObject("node")?.optString("createdDate"))
                if (title != null && seen.add(title.id)) titles += title
            }
            after = nextCursor(connection, edges.length()) ?: return titles to owner
        }
        return titles to owner
    }

    /**
     * Every title the user rated. Read a page at a time when IMDb pages them; otherwise all at
     * once (IMDb's window is as wide as asked).
     */
    suspend fun ratedTitles(userId: String): List<ImdbTitle> {
        // Paging is how IMDb's other lists work; if IMDb won't take it here, the window below.
        val paged = try {
            ratedTitlesPaged(userId)
        } catch (e: ImdbException) {
            if (e.reason == ImdbException.Reason.PRIVATE) throw e
            null
        }
        if (paged != null && paged.first.size >= paged.second) return paged.first
        // Not paged (or the pages stopped short): the whole window at once.
        val total = paged?.second ?: 0
        val first = total.coerceAtLeast(PAGE_SIZE).coerceAtMost(MAX_RATINGS)
        val data = query(RATINGS_WINDOW_QUERY, JSONObject().put("userId", userId).put("first", first))
        val ratings = data.optJSONObject("userRatings") ?: return paged?.first.orEmpty()
        val titles = titlesOfRatings(ratings.optJSONArray("edges"))
        return if (paged != null && paged.first.size > titles.size) paged.first else titles
    }

    /** The ratings a page at a time, and how many IMDb says there are. */
    private suspend fun ratedTitlesPaged(userId: String): Pair<List<ImdbTitle>, Int> {
        val titles = ArrayList<ImdbTitle>()
        val seen = HashSet<String>()
        var total = 0
        var after: String? = null
        repeat(MAX_PAGES) {
            val variables = JSONObject().put("userId", userId).put("first", PAGE_SIZE).put("after", after ?: JSONObject.NULL)
            val ratings = query(RATINGS_PAGED_QUERY, variables).optJSONObject("userRatings") ?: return titles to total
            total = maxOf(total, ratings.optInt("total", 0))
            val edges = ratings.optJSONArray("edges") ?: JSONArray()
            titlesOfRatings(edges).forEach { if (seen.add(it.id)) titles += it }
            after = nextCursor(ratings, edges.length()) ?: return titles to total
        }
        return titles to total
    }

    private fun titlesOfRatings(edges: JSONArray?): List<ImdbTitle> {
        if (edges == null) return emptyList()
        val titles = ArrayList<ImdbTitle>(edges.length())
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val ratedAt = node.optJSONObject("userRating")?.optString("date")
            titleOf(node.optJSONObject("title"), addedAt = ratedAt)?.let { titles += it }
        }
        return titles
    }

    private fun nextCursor(connection: JSONObject, edgeCount: Int): String? {
        val pageInfo = connection.optJSONObject("pageInfo") ?: return null
        if (!pageInfo.optBoolean("hasNextPage") || edgeCount == 0) return null
        return pageInfo.optString("endCursor").takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Marks the title watched on the signed-in IMDb account (what IMDb's "Mark as watched" does).
     * Throws [ImdbException] with [ImdbException.Reason.SIGNED_OUT] when the sign-in is no good.
     */
    suspend fun markWatched(titleId: String, cookies: String): Boolean {
        val data = query(
            ADD_WATCHED_MUTATION,
            JSONObject().put("titleId", titleId),
            cookies,
            operationName = ADD_WATCHED_OPERATION
        )
        val result = data.optJSONObject("addWatchedTitle") ?: return false
        return result.optBoolean("success")
    }

    /**
     * Asks IMDb's GraphQL API. [cookies]: the user's IMDb sign-in (as imdb.com's own pages send
     * it), for what only the account itself can see or change.
     */
    private suspend fun query(
        document: String,
        variables: JSONObject,
        cookies: String? = null,
        operationName: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .apply { operationName?.let { put("operationName", it) } }
            .put("query", document)
            .put("variables", variables)
            .toString()
        val builder = Request.Builder()
            .url(ENDPOINT)
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Accept", "application/graphql+json, application/json")
            .header("x-imdb-user-country", "US")
            .header("x-imdb-user-language", "en-US")
            .header("User-Agent", USER_AGENT)
        if (cookies.isNullOrBlank()) {
            builder.header("x-imdb-client-name", "imdb-web-next")
        } else {
            // Sent the way imdb.com sends them: its sign-in cookies ("at-main=Atza|…") as they are.
            builder.header("x-imdb-client-name", "imdb-web-next-localized")
                .header("Cookie", cookieHeader(cookies))
                .header("Origin", "https://www.imdb.com")
                .header("Referer", "https://www.imdb.com/")
        }
        val request = builder.build()
        val text = try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful && !text.trimStart().startsWith("{")) {
                    val reason = if (!cookies.isNullOrBlank() && (response.code == 401 || response.code == 403)) {
                        ImdbException.Reason.SIGNED_OUT
                    } else {
                        ImdbException.Reason.FAILED
                    }
                    throw ImdbException(reason, "IMDb answered ${response.code}")
                }
                text
            }
        } catch (e: ImdbException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ImdbException(ImdbException.Reason.FAILED, e.message ?: "IMDb couldn't be reached")
        }
        ensureActive()
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw ImdbException(ImdbException.Reason.FAILED, "IMDb answered something else")
        }
        // IMDb answers 200 even to a sign-in it no longer takes, and says so here ("token.invalid").
        val authToken = json.optJSONObject("extensions")?.optString("authToken").orEmpty()
        if (!cookies.isNullOrBlank() && Regex("invalid|expired", RegexOption.IGNORE_CASE).containsMatchIn(authToken)) {
            throw ImdbException(ImdbException.Reason.SIGNED_OUT, "IMDb sign-in $authToken")
        }
        val errors = json.optJSONArray("errors")
        val data = json.optJSONObject("data")
        if (errors != null && errors.length() > 0) {
            val message = errors.optJSONObject(0)?.let { error ->
                listOfNotNull(
                    error.optString("message"),
                    error.optJSONObject("extensions")?.optString("code")
                ).joinToString(" ")
            }.orEmpty()
            throw when {
                // A field or argument IMDb doesn't have: the query, not the account.
                QUERY_SHAPE_ERROR.containsMatchIn(message) -> ImdbException(ImdbException.Reason.FAILED, message)
                !cookies.isNullOrBlank() && Regex("Authentication required|UNAUTHENTICATED|not authenticated|sign.?in", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                    ImdbException(ImdbException.Reason.SIGNED_OUT, message)
                Regex("FORBIDDEN|Permission denied|private", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                    ImdbException(ImdbException.Reason.PRIVATE, message)
                Regex("RESOURCE_NOT_FOUND|Not found|Internal server error", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                    ImdbException(ImdbException.Reason.NOT_FOUND, message)
                else -> ImdbException(ImdbException.Reason.FAILED, message)
            }
        }
        data ?: throw ImdbException(ImdbException.Reason.FAILED, "IMDb answered no data")
    }

    companion object {
        private const val ENDPOINT = "https://api.graphql.imdb.com/"
        private val JSON = "application/json".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        /** IMDb gives at most this many a page. */
        private const val PAGE_SIZE = 250
        private const val MAX_PAGES = 40
        private const val MAX_RATINGS = 10_000

        val UR_ID = Regex("ur\\d{4,12}")
        private val PROFILE_ID = Regex("p\\.[a-z0-9_-]{8,60}", RegexOption.IGNORE_CASE)

        private val TITLE_FIELDS = """
            id
            titleText { text }
            originalTitleText { text }
            titleType { id isSeries isEpisode }
            releaseYear { year }
            primaryImage { url }
            ratingsSummary { aggregateRating }
            genres { genres { text } }"""

        private val PROFILE_QUERY = """query RctvImdbProfile(${'$'}profileId: ID!) {
  userProfile(input: { profileId: ${'$'}profileId }) { userId nickName }
}"""

        private val WATCHLIST_QUERY = """query RctvImdbWatchlist(${'$'}userId: ID!, ${'$'}first: Int!, ${'$'}after: String) {
  predefinedList(classType: WATCH_LIST, userId: ${'$'}userId) {
    author { username { text } }
    titleListItemSearch(first: ${'$'}first, after: ${'$'}after, sort: { by: LIST_ORDER, order: ASC }) {
      pageInfo { hasNextPage endCursor }
      edges {
        node { createdDate }
        title { $TITLE_FIELDS }
      }
    }
  }
}"""

        private val RATINGS_PAGED_QUERY = """query RctvImdbRatings(${'$'}userId: ID!, ${'$'}first: Int!, ${'$'}after: String) {
  userRatings(userId: ${'$'}userId, first: ${'$'}first, after: ${'$'}after) {
    total
    pageInfo { hasNextPage endCursor }
    edges { node { userRating { date } title { $TITLE_FIELDS } } }
  }
}"""

        /** IMDb's "Mark as watched" (a rating marks a title watched too), as imdb.com asks it. */
        private const val ADD_WATCHED_OPERATION = "UserAddSeenTitleMutation"
        private val ADD_WATCHED_MUTATION = """mutation UserAddSeenTitleMutation(${'$'}titleId: ID!) {
  addWatchedTitle(titleId: ${'$'}titleId) {
    success
    message { __typename ...LocalizedStringFragment }
  }
}
fragment LocalizedStringFragment on LocalizedString { language value }"""

        /** One cookie's value in a Cookie header ("a=1; session-id=123-456; b=2"). */
        fun cookieValue(cookies: String, name: String): String? =
            cookies.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }

        /**
         * The Cookie header as imdb.com sends it: a sign-in token kept escaped ("Atza%7C…") is
         * sent as IMDb set it ("Atza|…"), which is the only way IMDb takes it.
         */
        fun cookieHeader(cookies: String): String =
            cookies.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ") { part ->
                val name = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                if (value.startsWith("At") && value.contains("%7C", ignoreCase = true)) {
                    "$name=" + value.replace("%7C", "|").replace("%7c", "|")
                        .replace("%2F", "/").replace("%2f", "/")
                        .replace("%2B", "+").replace("%2b", "+")
                        .replace("%3D", "=").replace("%3d", "=")
                } else {
                    part
                }
            }

        /** The cookies say someone is signed in to IMDb. */
        fun isSignedIn(cookies: String?): Boolean =
            !cookies.isNullOrBlank() && (cookieValue(cookies, "at-main") != null || cookieValue(cookies, "sess-at-main") != null)

        private val RATINGS_WINDOW_QUERY = """query RctvImdbRatingsWindow(${'$'}userId: ID!, ${'$'}first: Int!) {
  userRatings(userId: ${'$'}userId, first: ${'$'}first) {
    total
    edges { node { userRating { date } title { $TITLE_FIELDS } } }
  }
}"""

        /** "ur12345678", "p.abc…", or a profile link ("imdb.com/user/ur12345678/watchlist") → its id. */
        fun profileKeyOf(input: String): String? {
            val text = input.trim()
            if (text.isEmpty()) return null
            Regex("""/user/([^/?#\s]+)""").find(text)?.groupValues?.get(1)?.let { fromLink ->
                return fromLink.takeIf { UR_ID.matches(it) || PROFILE_ID.matches(it) }
            }
            val plain = text.removePrefix("@")
            return when {
                UR_ID.matches(plain.lowercase()) -> plain.lowercase()
                PROFILE_ID.matches(plain) -> plain
                Regex("\\d{4,12}").matches(plain) -> "ur$plain"
                else -> null
            }
        }

        /** A smaller copy of an IMDb picture (its full size is several megapixels). */
        fun resizedImage(url: String?, width: Int = 380): String? {
            if (url.isNullOrBlank()) return null
            val marker = url.indexOf("._V1_")
            return if (marker >= 0) {
                url.substring(0, marker) + "._V1_QL75_UX${width}_.jpg"
            } else {
                url
            }
        }

        internal fun titleOf(json: JSONObject?, addedAt: String?): ImdbTitle? {
            if (json == null) return null
            val id = json.optString("id").takeIf { it.startsWith("tt") } ?: return null
            val type = json.optJSONObject("titleType")
            val genres = json.optJSONObject("genres")?.optJSONArray("genres")
            return ImdbTitle(
                id = id,
                name = json.optJSONObject("titleText")?.optString("text")?.takeIf { it.isNotBlank() }
                    ?: json.optJSONObject("originalTitleText")?.optString("text")?.takeIf { it.isNotBlank() }
                    ?: id,
                titleType = type?.optString("id").orEmpty(),
                isSeries = type?.optBoolean("isSeries") == true,
                isEpisode = type?.optBoolean("isEpisode") == true,
                year = json.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 },
                posterUrl = json.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.startsWith("http") },
                genres = if (genres == null) emptyList() else (0 until genres.length()).mapNotNull { index ->
                    genres.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
                },
                imdbRating = json.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                addedAtMs = addedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            )
        }

        /** What IMDb says to a query it didn't take as written (a field or argument it doesn't have). */
        private val QUERY_SHAPE_ERROR =
            Regex("validation|unknown argument|cannot query field|undefined|not defined|FieldUndefined|UnknownArgument", RegexOption.IGNORE_CASE)
    }
}
