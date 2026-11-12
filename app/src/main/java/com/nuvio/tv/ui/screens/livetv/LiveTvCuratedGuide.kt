package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.LiveTvChannel
import org.json.JSONObject
import java.util.Locale

/**
 * RCTV (owner's list): the Guide's United States and Puerto Rico channels as the owner chose them
 * (assets/live-guide-curated.json, made from his spreadsheet by tools/live-guide): each one under
 * his name and in his category. Everything else from the provider's US groups stays out. The
 * other countries' channels are the International category, by country. Leagues, game passes and
 * events never show in the Guide: Sports finds games and fights in them.
 *
 * A channel the provider has more than once ("TNT", "TNT FHD backup", "TNT West"; a 24/7 show
 * from two of its lists) shows once; the others are its versions, which the player turns to when
 * the one playing fails ([LiveTvChannelVersions]).
 *
 * Used only when the playlist is the one the list was made from (enough of its names are in it):
 * any other provider's channels are sorted the usual way.
 */
internal object LiveTvCuratedGuide {

    private const val TAG = "LiveTvCuratedGuide"
    private const val ASSET = "live-guide-curated.json"

    /** The categories in the list's order (the Guide lists its channels in this order). */
    private val CATEGORY_ORDER = listOf(
        GuideBrowse.TV_SHOWS, GuideBrowse.NEWS, GuideBrowse.SPORTS, GuideBrowse.MOVIES,
        GuideBrowse.CHANNELS_24_7, GuideBrowse.LIFESTYLE, GuideBrowse.CRIME_REALITY, GuideBrowse.KIDS,
        GuideBrowse.DOCUMENTARIES, GuideBrowse.GAME_SHOWS_CLASSICS, GuideBrowse.MUSIC, GuideBrowse.LATINO,
        GuideBrowse.LOCAL, GuideBrowse.PUERTO_RICO
    )

    /** One of the list's channels: its category, the name it shows under and its country. */
    private class Listed(val category: GuideBrowse, val publicName: String, val country: String, val order: Int)

    private class Rules(
        /** Every listed name's channel, in the list's order. */
        val listed: List<Listed>,
        /** By the provider's name for the channel ([nameKey]). */
        val byOriginal: Map<String, Listed>,
        val internalGroups: Set<String>,
        /** Playlist group ([groupKey]) -> country code or region (International). */
        val countries: Map<String, String>
    )

    /** What a playlist becomes with the list. */
    class Applied(
        /** The Guide's channels: the list's, then the other countries'. */
        val visible: List<LiveTvChannel>,
        /** Not in the Guide: leagues and events (Sports reads them) and the channels' other versions. */
        val hidden: List<LiveTvChannel>
    )

    @Volatile private var rules: Rules? = null
    @Volatile private var loaded = false

    /** Reads the list once (off the main thread). */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            rules = runCatching {
                parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
            }.onFailure { Log.w(TAG, "Channel list unavailable: ${it.javaClass.simpleName}") }.getOrNull()
            loaded = true
        }
    }

    /** For tests: the list from its text. */
    internal fun loadForTest(json: String) {
        synchronized(this) {
            rules = parse(json)
            loaded = true
        }
    }

    private fun parse(text: String): Rules {
        val json = JSONObject(text)
        val lists = json.getJSONObject("lists")
        val byOriginal = HashMap<String, Listed>()
        val all = ArrayList<Listed>()
        var order = 0
        for (category in CATEGORY_ORDER) {
            val list = lists.optJSONArray(category.name) ?: continue
            val country = if (category == GuideBrowse.PUERTO_RICO) "PR" else "US"
            for (i in 0 until list.length()) {
                val row = list.optJSONArray(i) ?: continue
                val original = row.optString(0).trim()
                if (original.isEmpty()) continue
                val public = row.optString(1, "").trim().ifEmpty { original }
                val listed = Listed(category, public, country, order++)
                all += listed
                // A name listed twice keeps its first place.
                byOriginal.putIfAbsent(nameKey(original), listed)
            }
        }
        val internal = json.optJSONArray("internalGroups")
        val internalGroups = HashSet<String>()
        if (internal != null) for (i in 0 until internal.length()) internalGroups += groupKey(internal.optString(i))
        val countries = HashMap<String, String>()
        json.optJSONObject("countries")?.let { map ->
            map.keys().forEach { group -> countries[groupKey(group)] = map.optString(group) }
        }
        return Rules(all, byOriginal, internalGroups, countries)
    }

    /**
     * [channels] (the playlist's, as loaded) with the list: or null when the list doesn't fit this
     * playlist (another provider), so the Guide sorts them the usual way.
     */
    fun apply(channels: List<LiveTvChannel>): Applied? {
        val rules = rules ?: return null
        if (rules.byOriginal.isEmpty() || channels.isEmpty()) return null

        // The list's channels found, each with every version of it.
        class Slot(val listed: Listed) {
            val members = ArrayList<LiveTvChannel>()
        }
        // One per channel the list names (two names of one show in a category are one channel).
        val slotsByKey = LinkedHashMap<String, Slot>()
        /** A list channel by its version key alone: the provider's other versions find it so. */
        val slotByVersion = HashMap<String, Slot>()
        fun slotKey(listed: Listed) = "${listed.category.name}|${versionKey(listed.publicName)}"
        rules.listed.forEach { listed ->
            val slot = slotsByKey.getOrPut(slotKey(listed)) { Slot(listed) }
            slotByVersion.putIfAbsent(versionKey(listed.publicName), slot)
        }
        val unlisted = ArrayList<LiveTvChannel>()
        var found = 0
        for (channel in channels) {
            val listed = rules.byOriginal[nameKey(channel.name)]
            if (listed == null) {
                unlisted += channel
                continue
            }
            found++
            slotsByKey.getValue(slotKey(listed)).members += channel
        }
        // Not this provider's playlist: too few of the list's names in it.
        if (found < minimumMatches(rules.byOriginal.size)) return null

        val hidden = ArrayList<LiveTvChannel>()
        val international = LinkedHashMap<String, MutableList<LiveTvChannel>>()
        for (channel in unlisted) {
            val group = channel.groupTitle?.let(::groupKey).orEmpty()
            when {
                group in rules.internalGroups -> hidden += channel
                rules.countries.containsKey(group) -> {
                    val country = rules.countries.getValue(group)
                    international.getOrPut("$country|${versionKey(channel.name)}") { ArrayList() } +=
                        channel.copy(country = country)
                }
                isUsOrPuertoRicoGroup(group) -> {
                    // Another version of one of the list's channels ("USA: TNT West"); anything
                    // else from the US groups isn't in the owner's list.
                    slotByVersion[versionKey(channel.name)]?.members?.add(channel)
                }
                // Streaming services' copies and anything else: not in the Guide.
            }
        }

        val visible = ArrayList<LiveTvChannel>(slotsByKey.size + international.size)
        slotsByKey.values
            .filter { it.members.isNotEmpty() }
            .sortedBy { it.listed.order }
            .forEach { slot ->
                val ordered = orderVersions(slot.members)
                val shown = ordered.first().copy(
                    name = slot.listed.publicName,
                    guideCategory = slot.listed.category.name,
                    country = slot.listed.country
                )
                visible += shown
                ordered.drop(1).forEach { version ->
                    hidden += version.copy(name = shown.name, country = shown.country, versionOf = shown.id)
                }
            }
        international.values.forEach { members ->
            val ordered = orderVersions(members)
            val shown = ordered.first().copy(guideCategory = GuideBrowse.INTERNATIONAL.name)
            visible += shown
            ordered.drop(1).forEach { version -> hidden += version.copy(versionOf = shown.id) }
        }
        return Applied(visible, hidden)
    }

    /** The provider's best version first: its own feed, then a backup, a FAST copy, a West feed. */
    private fun orderVersions(members: List<LiveTvChannel>): List<LiveTvChannel> =
        members.withIndex().sortedWith(compareBy({ versionPenalty(it.value) }, { it.index })).map { it.value }

    private fun versionPenalty(channel: LiveTvChannel): Int {
        val name = channel.name.lowercase(Locale.ROOT)
        val group = channel.groupTitle?.lowercase(Locale.ROOT).orEmpty()
        return when {
            westWord.containsMatchIn(name) -> 3
            name.startsWith("tubi") || "tubi" in group -> 2
            "backup" in name -> 1
            else -> 0
        }
    }

    private val westWord = Regex("\\bwest\\b")

    private fun minimumMatches(listed: Int): Int = maxOf(30, listed / 7)

    private fun isUsOrPuertoRicoGroup(group: String): Boolean =
        group.startsWith("usa") || group.startsWith("us|") || group.startsWith("pri") || group.startsWith("24/7")

    /** A provider's name compared as it is, any case or spacing. */
    internal fun nameKey(name: String): String =
        name.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).joinToString(" ")

    /** A group's name, any case or spacing ("USA |  Flo Sports" is "USA | Flo Sports"). */
    internal fun groupKey(group: String): String =
        group.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

    private val prefix = Regex("^\\s*(usa|us|tubi|pri|pr)\\s*[:|]\\s*")
    private val sevenPrefix = Regex("^\\s*(en\\s+)?24/7\\s*[·:|]?\\s*")
    private val versionWords = setOf("hd", "fhd", "uhd", "4k", "sd", "backup", "bk", "east", "west", "channel", "feed")

    /**
     * What makes two of the provider's channels the same one: "USA: TNT FHD backup", "USA: TNT
     * West" and "TNT" are "tnt"; "24/7 · 13 Reasons Why" and "EN 24/7 | 13 Reasons Why" are
     * "13 reasons why".
     */
    internal fun versionKey(name: String): String {
        var text = name.lowercase(Locale.ROOT).replace("ⓧ", " ")
        var before: String
        do {
            before = text
            text = prefix.replaceFirst(text, "")
            text = sevenPrefix.replaceFirst(text, "")
        } while (text != before)
        val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last() in versionWords) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }
}

/**
 * RCTV: every version of the Guide's channels by their address, so the player can turn to
 * another when the one playing fails. Addresses carry the IPTV login: never logged.
 */
object LiveTvChannelVersions {
    @Volatile private var byUrl: Map<String, List<String>> = emptyMap()

    /** From the Guide's channels and their versions ([LiveTvChannel.versionOf]). */
    fun update(channels: List<LiveTvChannel>, hidden: List<LiveTvChannel>) {
        val versions = hidden.filter { it.versionOf != null }.groupBy { it.versionOf }
        if (versions.isEmpty()) {
            byUrl = emptyMap()
            return
        }
        val map = HashMap<String, List<String>>()
        channels.forEach { channel ->
            val others = versions[channel.id] ?: return@forEach
            val urls = (listOf(channel) + others).map { it.streamUrl }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
            if (urls.size > 1) urls.forEach { map[it] = urls }
        }
        byUrl = map
    }

    /** The channel's versions in order (the first is the one the Guide shows), or just [url]. */
    fun of(url: String): List<String> = byUrl[url] ?: listOf(url)

    /** The version after [url] (around to the first), or null when the channel has no other. */
    fun next(url: String): String? {
        val all = byUrl[url] ?: return null
        val index = all.indexOf(url)
        return all[(index + 1) % all.size].takeIf { it != url }
    }

    /** One key for every version of a channel (its first address). */
    fun channelKey(url: String): String = byUrl[url]?.firstOrNull() ?: url
}
