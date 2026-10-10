package com.nuvio.tv.ui.screens.livetv

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import java.util.Locale

/**
 * The Guide's first menu: three ways to list every channel (Live Now, A to Z, Custom) and the
 * channel categories.
 */
enum class GuideBrowse(@StringRes val labelRes: Int, val isCategory: Boolean) {
    LIVE_NOW(R.string.livetv_guide_live_now, false),
    A_TO_Z(R.string.livetv_guide_a_to_z, false),
    CUSTOM(R.string.livetv_guide_custom, false),
    TV_SHOWS(R.string.livetv_guide_cat_tv_shows, true),
    NEWS(R.string.livetv_guide_cat_news, true),
    SPORTS(R.string.livetv_guide_cat_sports, true),
    MOVIES(R.string.livetv_guide_cat_movies, true),
    LIFESTYLE(R.string.livetv_guide_cat_lifestyle, true),
    CRIME_REALITY(R.string.livetv_guide_cat_crime_reality, true),
    KIDS(R.string.livetv_guide_cat_kids, true),
    DOCUMENTARIES(R.string.livetv_guide_cat_documentaries, true),
    GAME_SHOWS_CLASSICS(R.string.livetv_guide_cat_game_shows, true),
    MUSIC(R.string.livetv_guide_cat_music, true),
    INTERNATIONAL(R.string.livetv_guide_cat_international, true),
    LOCAL(R.string.livetv_guide_cat_local, true)
}

/**
 * Sorts channels into the Guide's categories. A channel's programs vote with their running
 * time (XMLTV categories such as "Sitcom", "News", "Football"); its name and playlist group
 * add strong hints ("ESPN", "Disney", "Telemundo"); a broadcast sub-channel number like "7.1"
 * or a call sign marks a local station. A channel can be in more than one category.
 */
internal object LiveTvGuideCategories {

    private val programBuckets: Map<String, GuideBrowse> = buildMap {
        fun put(bucket: GuideBrowse, vararg names: String) = names.forEach { put(it, bucket) }
        put(
            GuideBrowse.TV_SHOWS, "sitcom", "comedy", "drama", "comedy drama", "soap", "entertainment", "talk",
            "action", "adventure", "fantasy", "science fiction", "thriller", "horror", "romance",
            "romantic comedy", "dark comedy", "anthology", "variety", "miniseries", "standup",
            "political satire", "historical drama", "war", "holiday", "special"
        )
        put(
            GuideBrowse.NEWS, "news", "weather", "newsmagazine", "public affairs", "politics",
            "bus./financial", "interview", "debate"
        )
        put(
            GuideBrowse.SPORTS, "sports", "sports talk", "football", "basketball", "baseball", "soccer",
            "hockey", "golf", "tennis", "boxing", "mixed martial arts", "martial arts", "pro wrestling",
            "auto racing", "motorsports", "motorcycle racing", "sports event", "playoff sports",
            "multi-sport event", "action sports", "volleyball", "cricket", "rugby", "rugby union",
            "australian rules football", "horse racing", "rodeo", "bull riding", "poker", "pickleball",
            "curling", "archery", "surfing", "equestrian", "drag racing", "classic sport event"
        )
        put(GuideBrowse.MOVIES, "movie", "film", "feature film")
        put(
            GuideBrowse.LIFESTYLE, "cooking", "consumer", "shopping", "house/garden", "home improvement",
            "travel", "health", "how-to", "fashion", "auto", "collectibles", "self improvement",
            "outdoors", "hunting", "fishing", "exercise", "arts/crafts", "parenting", "pets", "boat",
            "motorcycle", "auction", "agriculture"
        )
        put(
            GuideBrowse.CRIME_REALITY, "reality", "competition reality", "crime", "crime drama", "mystery",
            "law", "paranormal", "docudrama"
        )
        // "Animated" alone is left out: The Simpsons and South Park are animated too.
        put(GuideBrowse.KIDS, "children", "anime", "family", "educational")
        put(
            GuideBrowse.DOCUMENTARIES, "documentary", "science", "nature", "animals", "history",
            "american history", "ancient history", "world history", "biography", "technology",
            "environment", "medical", "military", "aviation", "art"
        )
        put(GuideBrowse.GAME_SHOWS_CLASSICS, "game show", "western", "card games")
        put(
            GuideBrowse.MUSIC, "music", "musical", "musical comedy", "concert", "rock", "pop", "country",
            "hip-hop & rap", "r&b", "gospel", "soul", "funk", "heavy metal", "dance", "latin",
            "performing arts"
        )
    }

    /**
     * When a program fits several categories it counts for the most specific one: a cooking
     * competition is Lifestyle (not Reality), a sports talk show Sports (not Talk).
     */
    private val bucketPriority = listOf(
        GuideBrowse.MOVIES, GuideBrowse.SPORTS, GuideBrowse.NEWS, GuideBrowse.KIDS, GuideBrowse.MUSIC,
        GuideBrowse.LIFESTYLE, GuideBrowse.CRIME_REALITY, GuideBrowse.DOCUMENTARIES,
        GuideBrowse.GAME_SHOWS_CLASSICS, GuideBrowse.TV_SHOWS
    )

    private fun words(vararg names: String) =
        Regex("""(?<![\p{L}\p{N}])(${names.joinToString("|") { Regex.escape(it) }})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

    // Channel name / playlist group hints.
    private val nameHints: List<Pair<GuideBrowse, Regex>> = listOf(
        GuideBrowse.SPORTS to words(
            "espn", "espn2", "espnu", "fs1", "fs2", "fox sports", "nbc sports", "cbs sports", "sportsnet",
            "sports", "sport", "fanduel", "bally", "nfl", "nba", "mlb", "nhl", "golf", "tennis", "big ten",
            "sec network", "acc network", "pac-12", "bein", "dazn", "msg", "nesn", "yes network", "marquee",
            "root sports", "ufc", "wwe", "deportes", "tudn", "willow", "speed", "racing"
        ),
        GuideBrowse.NEWS to words(
            "news", "cnn", "msnbc", "cnbc", "bloomberg", "weather", "weathernation", "newsmax", "c-span",
            "hln", "newsnation", "livenow", "bbc world", "sky news", "euronews", "al jazeera", "france 24",
            "noticias", "telemundo noticias"
        ),
        GuideBrowse.MOVIES to words(
            "hbo", "cinemax", "starz", "showtime", "encore", "epix", "mgm", "tcm", "turner classic", "flix",
            "movies", "movie", "cine", "cinema", "fxm", "sundance", "ifc", "reelz", "hallmark movies",
            "lifetime movie", "paramount network", "amc"
        ),
        GuideBrowse.KIDS to words(
            "disney", "disney jr", "disney xd", "nick", "nickelodeon", "nick jr", "nicktoons", "teennick",
            "cartoon", "boomerang", "pbs kids", "baby", "kids", "universal kids", "discovery family",
            "babyfirst", "duck tv"
        ),
        GuideBrowse.DOCUMENTARIES to words(
            "discovery", "nat geo", "national geographic", "history", "smithsonian", "science",
            "animal planet", "nature", "bbc earth", "curiosity", "documentary", "military", "ahc",
            "american heroes", "magellan"
        ),
        GuideBrowse.LIFESTYLE to words(
            "food", "food network", "cooking", "hgtv", "travel", "tlc", "magnolia", "fyi", "diy",
            "home shopping", "garden", "qvc", "hsn", "shop", "outdoor", "tastemade", "great american living"
        ),
        GuideBrowse.CRIME_REALITY to words(
            "investigation discovery", "id", "oxygen", "court tv", "a&e", "crime", "true crime",
            "law & crime", "reality", "bravo", "e!", "justice"
        ),
        GuideBrowse.GAME_SHOWS_CLASSICS to words(
            "gsn", "game show", "game show network", "buzzr", "metv", "antenna tv", "cozi", "tv land",
            "decades", "catchy comedy", "grit", "gettv", "start tv", "laff", "h&i", "heroes & icons",
            "rewind tv", "story television", "classic", "classics", "retro", "insp"
        ),
        GuideBrowse.MUSIC to words(
            "mtv", "mtv2", "mtv live", "vh1", "cmt", "axs", "revolt", "music", "music choice", "stingray",
            "vevo", "fuse", "bet jams", "bet soul", "trace", "musica", "música"
        ),
        GuideBrowse.INTERNATIONAL to words(
            "univision", "telemundo", "unimas", "unimás", "galavision", "galavisión", "estrella", "azteca",
            "telefe", "caracol", "rcn", "venevision", "venevisión", "wapa", "telemicro", "en español",
            "español", "espanol", "latino", "latina", "spanish", "tudn", "deportes", "cine latino",
            "rai", "tv5", "tv5monde", "zee", "star plus", "arirang", "nhk", "kbs", "dw", "tve",
            "canal", "mega", "hola", "pasiones", "sorpresa", "vme", "estrella tv", "multimedios"
        ),
        GuideBrowse.LOCAL to words("local", "locals", "locales")
    )

    /** US broadcast call signs: "WXYZ", "KABC-TV", "WJBK-DT". */
    private val callSign = Regex("""(?<![\p{L}\p{N}])([KW][A-Z]{2,3})(-(TV|DT|HD|LD|CD))?(?![\p{L}\p{N}])""")
    /** Look like call signs but are cable networks or brands. */
    private val notCallSigns = setOf("WWE", "WNBA", "WPT", "KBS", "WGN", "WEtv", "KTV")

    private val adultCategories = setOf("adults only", "erotic")

    /** True when most of what a channel airs is adult-only programming. */
    fun isAdultChannel(programs: List<EpgProgram>): Boolean {
        if (programs.isEmpty()) return false
        var adultMinutes = 0L
        var totalMinutes = 0L
        programs.forEach { program ->
            val minutes = (program.endMs - program.startMs) / 60_000L
            totalMinutes += minutes
            if (program.categories.any { it.lowercase(Locale.ROOT) in adultCategories }) adultMinutes += minutes
        }
        return totalMinutes > 0 && adultMinutes * 2 > totalMinutes
    }

    /** Categories for each channel id. */
    fun classify(
        channels: List<LiveTvChannel>,
        programsOf: (LiveTvChannel) -> List<EpgProgram>
    ): Map<String, Set<GuideBrowse>> = channels.associate { channel ->
        channel.id to classify(channel, programsOf(channel))
    }

    private fun classify(channel: LiveTvChannel, programs: List<EpgProgram>): Set<GuideBrowse> {
        val result = mutableSetOf<GuideBrowse>()

        // Programs vote with their running time, each for its most specific category. The
        // leading category counts from 20 % of the airtime, any other from 35 %; "TV Shows" only
        // when it leads (most channels air some series).
        val minutes = mutableMapOf<GuideBrowse, Long>()
        var total = 0L
        programs.forEach { program ->
            val length = ((program.endMs - program.startMs) / 60_000L).coerceAtLeast(1)
            total += length
            val buckets = program.categories
                .mapNotNull { programBuckets[it.lowercase(Locale.ROOT).replace("&amp;", "&")] }
                .toSet()
            bucketPriority.firstOrNull { it in buckets }?.let { minutes[it] = (minutes[it] ?: 0L) + length }
        }
        if (total > 0) {
            minutes.maxByOrNull { it.value }?.let { (bucket, value) ->
                if (value * 5 >= total) result += bucket
            }
            minutes.forEach { (bucket, value) ->
                if (bucket != GuideBrowse.TV_SHOWS && value * 20 >= total * 7) result += bucket
            }
        }

        // Name and playlist group.
        val texts = listOfNotNull(channel.name, channel.groupTitle)
        nameHints.forEach { (bucket, pattern) ->
            if (texts.any { pattern.containsMatchIn(it) }) result += bucket
        }

        // Local stations: a broadcast sub-channel number ("7.1") or a call sign.
        val number = channel.number
        val hasCallSign = callSign.findAll(channel.name).any { it.groupValues[1] !in notCallSigns }
        if ((number != null && number.contains('.')) || hasCallSign) {
            result += GuideBrowse.LOCAL
        }

        // Another country's channel is international.
        if (channel.country != null && channel.country != "US") result += GuideBrowse.INTERNATIONAL

        return result
    }
}
