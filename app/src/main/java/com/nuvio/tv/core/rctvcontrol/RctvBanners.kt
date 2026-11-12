package com.nuvio.tv.core.rctvcontrol

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** A team on a banner's game (ESPN's id, name and logo). */
data class RctvBannerTeam(val id: String, val name: String, val logoUrl: String?)

/** What a Home banner's button opens. */
sealed interface RctvBannerAction {
    data class Collection(val collectionId: String) : RctvBannerAction
    data class Game(
        val eventId: String,
        val leagueId: String,
        val leagueName: String,
        val sportName: String,
        val name: String,
        val startMs: Long?,
        val home: RctvBannerTeam?,
        val away: RctvBannerTeam?
    ) : RctvBannerAction
    data class Team(val leagueId: String, val teamId: String, val name: String, val logoUrl: String?, val color: String?) : RctvBannerAction
    data class League(val leagueId: String, val name: String, val sportName: String, val logoUrl: String?) : RctvBannerAction
    /** A Live TV channel, by its name as the guide writes it. */
    data class Channel(val name: String) : RctvBannerAction

    /** Sports and Live TV: Full only (Light has neither) and never on Kids profiles. */
    val fullOnly: Boolean get() = this !is Collection
}

/** A banner the owner publishes on the website, shown between Home's rows. */
data class RctvBanner(
    val id: String,
    val title: String,
    val subtitle: String,
    /** The button's text; blank: the app's own for [action]. */
    val button: String,
    val note: String,
    val imageUrl: String?,
    /**
     * The Home row it goes after: [AFTER_CONTINUE_WATCHING], [AFTER_STREAMING_SERVICES] or a Home row's
     * id ("home_trending"). The same for every account.
     */
    val afterRow: String,
    /** A collection banner also on Kids profiles (its page shows only titles for kids). */
    val kids: Boolean,
    val startsAtMs: Long?,
    val endsAtMs: Long?,
    val action: RctvBannerAction
) {
    fun isShownAt(nowMs: Long): Boolean =
        (startsAtMs == null || startsAtMs <= nowMs) && (endsAtMs == null || endsAtMs > nowMs)

    companion object {
        const val AFTER_CONTINUE_WATCHING = "continue_watching"
        const val AFTER_STREAMING_SERVICES = "streaming_services"
    }
}

/** A title the owner picked for a collection (TMDB's). */
data class RctvCollectionItem(
    val isSeries: Boolean,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val backdropPath: String?
)

enum class RctvThemeMedia { ALL, MOVIES, SERIES }
enum class RctvThemeSort { POPULAR, NEWEST, RATING }

/** A collection that fills itself: every movie and series with these TMDB keywords. */
data class RctvCollectionTheme(val keywordIds: List<Int>, val media: RctvThemeMedia, val sort: RctvThemeSort)

/** A collection (e.g. "Christmas"): the titles the owner picked, or a [theme]. */
data class RctvCollection(
    val id: String,
    val name: String,
    val description: String,
    val items: List<RctvCollectionItem>,
    /** Set for a collection by theme (then [items] is empty). */
    val theme: RctvCollectionTheme?
)

/** Reads banners.json and collections.json, and decides which banners a profile sees. Pure: tested on its own. */
object RctvBannerParser {
    private const val MAX_BANNERS = 50
    private const val MAX_COLLECTIONS = 100
    private const val MAX_ITEMS = 200
    private val ESPN_ID = Regex("^[0-9A-Za-z_\\-]{1,30}$")
    private val LEAGUE_ID = Regex("^[a-z]+:[a-z0-9.\\-]{1,40}$")
    private val EVENT_ID = Regex("^espn:[a-z]+:[a-z0-9.\\-]{1,40}:[0-9A-Za-z_\\-]{1,30}$")
    private val TMDB_PATH = Regex("^/[A-Za-z0-9_\\-]{1,80}\\.(jpg|jpeg|png|webp|svg)$")
    private val COLOR = Regex("^[0-9A-Fa-f]{6}$")
    private val ROW_ID = Regex("^[a-z_]{1,60}$")

    fun banners(body: String?): List<RctvBanner>? {
        val array = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_BANNERS)) {
                val b = array.optJSONObject(i) ?: continue
                bannerOf(b)?.let(::add)
            }
        }
    }

    fun collections(body: String?): List<RctvCollection>? {
        val array = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_COLLECTIONS)) {
                val c = array.optJSONObject(i) ?: continue
                collectionOf(c)?.let(::add)
            }
        }
    }

    /**
     * The banners this profile sees now (the website publishes them in Home's order): in their dates;
     * sports and Live TV only on Full and never on Kids; a collection only when it's published (and on
     * Kids only when allowed).
     */
    fun visible(
        banners: List<RctvBanner>,
        collections: List<RctvCollection>,
        nowMs: Long,
        full: Boolean,
        kids: Boolean
    ): List<RctvBanner> {
        val collectionIds = collections.mapTo(HashSet()) { it.id }
        return banners
            .filter { it.isShownAt(nowMs) }
            .filter { banner ->
                when (val action = banner.action) {
                    is RctvBannerAction.Collection -> action.collectionId in collectionIds && (!kids || banner.kids)
                    else -> full && !kids
                }
            }
    }

    /**
     * Where each banner goes on Home: after Continue Watching, after Streaming Services, or after a
     * Home row ([rowOrder]: every row's id, in Home's order). A row that isn't showing (Top Channels on
     * Light, a row with nothing in it) leaves its banner after the last row before it that shows, or
     * under Streaming Services; a row this app doesn't know, too.
     */
    fun slots(banners: List<RctvBanner>, rowOrder: List<String>, shownRows: Set<String>): Map<String, List<RctvBanner>> =
        banners.groupBy { banner ->
            when (val after = banner.afterRow) {
                RctvBanner.AFTER_CONTINUE_WATCHING, RctvBanner.AFTER_STREAMING_SERVICES -> after
                else -> {
                    val at = rowOrder.indexOf(after)
                    if (at < 0) RctvBanner.AFTER_STREAMING_SERVICES
                    else rowOrder.subList(0, at + 1).lastOrNull { it in shownRows } ?: RctvBanner.AFTER_STREAMING_SERVICES
                }
            }
        }

    private fun bannerOf(b: JSONObject): RctvBanner? {
        val id = b.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = b.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        val action = b.optJSONObject("action")?.let(::actionOf) ?: return null
        return RctvBanner(
            id = id,
            title = title.take(60),
            subtitle = b.textOrEmpty("subtitle").take(160),
            button = b.textOrEmpty("button").take(30),
            note = b.textOrEmpty("note").take(100),
            imageUrl = b.httpsOrNull("image_url"),
            afterRow = b.optString("after").takeIf { ROW_ID.matches(it) } ?: RctvBanner.AFTER_STREAMING_SERVICES,
            kids = b.optBoolean("kids", false),
            startsAtMs = b.timeOrNull("starts_at"),
            endsAtMs = b.timeOrNull("ends_at"),
            action = action
        )
    }

    private fun actionOf(a: JSONObject): RctvBannerAction? = when (a.optString("type")) {
        "collection" -> a.optString("collection_id").takeIf { it.isNotBlank() }?.let { RctvBannerAction.Collection(it) }
        "game" -> {
            val eventId = a.optString("event_id")
            val leagueId = a.optString("league_id")
            val name = a.textOrEmpty("name")
            if (!EVENT_ID.matches(eventId) || !LEAGUE_ID.matches(leagueId) || name.isBlank()) null
            else RctvBannerAction.Game(
                eventId = eventId,
                leagueId = leagueId,
                leagueName = a.textOrEmpty("league_name"),
                sportName = a.textOrEmpty("sport_name"),
                name = name,
                startMs = a.timeOrNull("start"),
                home = a.optJSONObject("home")?.let(::teamOf),
                away = a.optJSONObject("away")?.let(::teamOf)
            )
        }
        "team" -> {
            val leagueId = a.optString("league_id")
            val teamId = a.optString("team_id")
            val name = a.textOrEmpty("name")
            if (!LEAGUE_ID.matches(leagueId) || !ESPN_ID.matches(teamId) || name.isBlank()) null
            else RctvBannerAction.Team(leagueId, teamId, name, a.httpsOrNull("logo"), a.optString("color").takeIf { COLOR.matches(it) })
        }
        "league" -> {
            val leagueId = a.optString("league_id")
            val name = a.textOrEmpty("name")
            if (!LEAGUE_ID.matches(leagueId) || name.isBlank()) null
            else RctvBannerAction.League(leagueId, name, a.textOrEmpty("sport_name"), a.httpsOrNull("logo"))
        }
        "channel" -> a.textOrEmpty("channel").take(80).takeIf { it.isNotBlank() }?.let { RctvBannerAction.Channel(it) }
        else -> null
    }

    private fun teamOf(t: JSONObject): RctvBannerTeam? {
        val id = t.optString("id").takeIf { ESPN_ID.matches(it) } ?: return null
        val name = t.textOrEmpty("name").takeIf { it.isNotBlank() } ?: return null
        return RctvBannerTeam(id, name, t.httpsOrNull("logo"))
    }

    private fun collectionOf(c: JSONObject): RctvCollection? {
        val id = c.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = c.textOrEmpty("name").takeIf { it.isNotBlank() } ?: return null
        val theme = if (c.optString("mode") == "theme") c.optJSONObject("theme")?.let(::themeOf) ?: return null else null
        val items = if (theme != null) emptyList() else buildList {
            val array = c.optJSONArray("items") ?: JSONArray()
            for (i in 0 until minOf(array.length(), MAX_ITEMS)) {
                val it = array.optJSONObject(i) ?: continue
                val type = it.optString("type")
                val tmdbId = it.optInt("tmdb_id", 0)
                val title = it.textOrEmpty("title")
                if ((type != "movie" && type != "series") || tmdbId <= 0 || title.isBlank()) continue
                add(
                    RctvCollectionItem(
                        isSeries = type == "series",
                        tmdbId = tmdbId,
                        title = title.take(200),
                        year = it.optInt("year", 0).takeIf { y -> y in 1871..2199 },
                        posterPath = it.optString("poster_path").takeIf { p -> TMDB_PATH.matches(p) },
                        backdropPath = it.optString("backdrop_path").takeIf { p -> TMDB_PATH.matches(p) }
                    )
                )
            }
        }
        if (theme == null && items.isEmpty()) return null
        return RctvCollection(id = id, name = name.take(80), description = c.textOrEmpty("description").take(300), items = items, theme = theme)
    }

    private fun themeOf(t: JSONObject): RctvCollectionTheme? {
        val keywords = t.optJSONArray("keywords") ?: return null
        val ids = buildList {
            for (i in 0 until minOf(keywords.length(), 10)) {
                keywords.optJSONObject(i)?.optInt("id", 0)?.takeIf { it > 0 }?.let(::add)
            }
        }.distinct()
        if (ids.isEmpty()) return null
        val media = when (t.optString("media")) {
            "movie" -> RctvThemeMedia.MOVIES
            "series" -> RctvThemeMedia.SERIES
            else -> RctvThemeMedia.ALL
        }
        val sort = when (t.optString("sort")) {
            "newest" -> RctvThemeSort.NEWEST
            "rating" -> RctvThemeSort.RATING
            else -> RctvThemeSort.POPULAR
        }
        return RctvCollectionTheme(ids, media, sort)
    }

    private fun JSONObject.textOrEmpty(key: String): String =
        if (!has(key) || isNull(key)) "" else optString(key).trim()

    private fun JSONObject.httpsOrNull(key: String): String? =
        textOrEmpty(key).takeIf { it.startsWith("https://") && it.length <= 500 && it.none(Char::isWhitespace) }

    private fun JSONObject.timeOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else RctvControlParser.epochMsOf(optString(key))
}

/**
 * Finds a banner's Live TV channel in the guide by its name: the same name first ("ESPN" is ESPN,
 * not ESPN2), then a name that starts with it ("ESPN HD", "US: ESPN"), then one that has it.
 */
object RctvChannelMatcher {
    private val QUALITY = Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup|bk)\\b")
    /** A country in front ("US: ESPN", "|US| ESPN"). */
    private val PREFIX = Regex("^\\|?([a-z]{2,3})\\s*[:|]\\s*")

    fun <T> find(wanted: String, channels: List<T>, nameOf: (T) -> String): T? {
        val target = normalize(wanted)
        if (target.isEmpty()) return null
        val named = channels.map { it to normalize(nameOf(it)) }.filter { it.second.isNotEmpty() }
        named.firstOrNull { it.second == target }?.let { return it.first }
        val compactTarget = target.replace(" ", "")
        named.firstOrNull { it.second.replace(" ", "") == compactTarget }?.let { return it.first }
        named.filter { it.second.startsWith("$target ") }.minByOrNull { it.second.length }?.let { return it.first }
        return named.filter { " ${it.second} ".contains(" $target ") }.minByOrNull { it.second.length }?.first
    }

    internal fun normalize(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return plain
            .replace(PREFIX, "")
            .replace(Regex("[^a-z0-9+]+"), " ")
            .replace(QUALITY, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
