package com.nuvio.tv.ui.screens.channelprofile

import com.nuvio.tv.domain.model.LiveTvChannel
import java.text.Normalizer
import java.util.Locale

/**
 * A channel's network family ("cadena"), for its profile page: which channels are its main
 * live broadcasts, which are local affiliates (ABC, CBS, NBC, FOX), and how to find its shows
 * and movies on TMDB.
 */
data class ChannelBrand(
    /** Stable id: a known brand's key ("espn", "fox"), or "ch:<base word>" for any other channel. */
    val key: String,
    /** Name shown when there's no logo, and used in messages. */
    val name: String,
    /** Name to look up in the bundled logo pack; null uses the chosen channel's own logo. */
    val logoName: String?,
    /** Its main channels, in display order (each pattern matches a whole normalized name). */
    val family: List<Regex>,
    /** First word of its local stations' names ("fox" -> "FOX 8 New Orleans"); null: no locals. */
    val affiliate: String? = null,
    /** TMDB network ids (its TV shows). Checked against TMDB's name before use. */
    val tmdbNetworks: List<Int> = emptyList(),
    /** TMDB company names whose movies count as the brand's ("HBO Films"). */
    val companyQueries: List<String> = emptyList(),
    /** TMDB network names that count as this brand (compact: lower case, letters and digits). */
    val networkNames: Set<String> = emptySet(),
    /** Production company name fragments that make a movie the brand's ("hbo"). */
    val companyFragments: List<String> = emptyList(),
    /** For a brand made from one channel: the word its channels share ("hallmark"). */
    val baseWord: String? = null
) {
    val hasLocals: Boolean get() = affiliate != null
}

/** A brand's channels on this playlist: its main channels and (for a broadcast network) its locals. */
data class ChannelBrandMembers(
    val live: List<LiveTvChannel>,
    val locals: List<LiveTvChannel>
) {
    val all: List<LiveTvChannel> get() = live + locals
}

object ChannelBrands {

    // ------------------------------------------------------------------ names

    /** Feed and quality words that don't change which channel it is ("ESPN HD", "AMC East"). */
    private val noise = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hdtv", "hevc", "east", "west", "pacific", "us", "feed", "alternate", "alt"
    )
    private val parentheses = Regex("""\([^)]*\)|\[[^]]*]""")
    private val separators = Regex("""[^a-z0-9]+""")
    private val accents = Regex("""\p{Mn}+""")

    /** "Fox Sports 1 US HD" -> "fox sports 1", "ESPN2 (Alternate)" -> "espn2", "A&E East" -> "a and e". */
    fun normalize(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(accents, "")
        val text = plain.lowercase(Locale.ROOT)
            .replace(parentheses, " ")
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace("'", "")
            .replace("’", "")
            .replace("!", "")
        val words = text.split(separators).filter { it.isNotEmpty() }
        val kept = words.filter { it !in noise }
        return (kept.ifEmpty { words }).joinToString(" ")
    }

    /** Letters and digits only: "Fox News Channel" -> "foxnewschannel" (for TMDB network names). */
    fun compact(name: String): String = normalize(name).replace(" ", "")

    /** The channel's name without feed and quality tags, for display: "ESPN2 HD (Alternate)" -> "ESPN2". */
    fun displayName(name: String): String {
        val words = name.replace(parentheses, " ").split(Regex("""\s+""")).filter { it.isNotBlank() }
        val kept = words.filter { it.lowercase(Locale.ROOT).trim('-', '|', ':') !in noise }
        return (kept.ifEmpty { words }).joinToString(" ").trim(' ', '-', '|', ':')
    }

    private val genericWords = setOf(
        "the", "tv", "channel", "network", "news", "sports", "sport", "my", "me", "world", "plus", "one", "de", "la", "el"
    )

    /** The word a channel's family shares: "Hallmark Movies & Mysteries" -> "hallmark". */
    private fun baseWord(normalized: String): String =
        normalized.split(' ').firstOrNull { it.length >= 3 && it !in genericWords } ?: normalized

    // ------------------------------------------------------------------ brands

    private fun r(pattern: String) = Regex(pattern)

    private fun brand(
        key: String,
        name: String,
        family: List<String>,
        networks: List<Int> = emptyList(),
        names: Set<String> = emptySet(),
        logo: String? = name,
        affiliate: String? = null,
        companies: List<String> = emptyList(),
        fragments: List<String> = emptyList()
    ) = ChannelBrand(
        key = key,
        name = name,
        logoName = logo,
        family = family.map(::r),
        affiliate = affiliate,
        tmdbNetworks = networks,
        companyQueries = companies,
        networkNames = names + compact(name),
        companyFragments = fragments
    )

    /**
     * Known families, checked in this order (so "SHO x BET" is Showtime, "Accn Espn" is ESPN).
     * Family patterns match a whole normalized name; a broadcast network's other channels that
     * start with its name ("FOX 8 New Orleans", "CBS News Detroit", "ABC 7 Detroit") are its locals.
     */
    val known: List<ChannelBrand> = listOf(
        brand(
            "espn", "ESPN",
            family = listOf(
                "espn", "espn ?2", "espn ?u", "espnews", "espn deportes", "espn.*",
                "sec network.*", "acc ?network.*|accn.*", "longhorn network.*"
            ),
            networks = listOf(29),
            names = setOf("espn", "espn2", "espnu", "espnews", "espndeportes", "secnetwork", "accnetwork", "espnplus"),
            companies = listOf("ESPN Films"),
            fragments = listOf("espn")
        ),
        brand(
            "fox", "FOX",
            family = listOf(
                "fox( national| stream| network)*",
                "fox news( channel)?", "fox business( network)?",
                "fox sports (1|one)|fs ?1", "fox sports (2|two)|fs ?2",
                "fox deportes", "fox weather", "fox soccer plus", "fox sports racing", "fox sports.*",
                "fox soul", "fox nation", "fox live ?now", "big ten network.*|btn.*"
            ),
            networks = listOf(19),
            names = setOf(
                "fox", "foxnewschannel", "foxnews", "foxbusinessnetwork", "foxbusiness", "fs1", "foxsports1",
                "fs2", "foxsports", "foxdeportes", "foxnation", "foxweather", "foxsoul", "bigtennetwork", "btn"
            ),
            affiliate = "fox"
        ),
        brand(
            "abc", "ABC",
            family = listOf("abc( national| stream| network)*", "abc news( live)?", "abc localish"),
            networks = listOf(2),
            names = setOf("abc", "abcnews", "abcnewslive"),
            affiliate = "abc"
        ),
        brand(
            "cbs", "CBS",
            family = listOf(
                "cbs( national| stream| network)*", "cbs news( 24 ?7| 247| streaming| national)?", "cbs sports.*"
            ),
            networks = listOf(16),
            names = setOf("cbs", "cbsnews", "cbssportsnetwork", "cbssports"),
            affiliate = "cbs"
        ),
        brand(
            "nbc", "NBC",
            family = listOf(
                "nbc( national| stream| network)*", "nbc news( now)?", "nbc sports?( network)?", "nbc sports? .*",
                "nbc universo", "nbc lx( home)?"
            ),
            networks = listOf(6),
            names = setOf("nbc", "nbcnews", "nbcsports", "nbcuniverso"),
            affiliate = "nbc"
        ),
        brand(
            "disney", "Disney Channel",
            family = listOf("disney( channel)?", "disney (jr|junior).*", "disney xd.*", "disney channel.*"),
            networks = listOf(54, 281, 44),
            names = setOf("disneychannel", "disneyjunior", "disneyjr", "disneyxd"),
            companies = listOf("Disney Channel")
        ),
        brand(
            "nickelodeon", "Nickelodeon",
            family = listOf("nickelodeon.*", "nick (jr|junior).*", "nick.*", "teen ?nick.*"),
            networks = listOf(13),
            names = setOf("nickelodeon", "nickjr", "nicktoons", "teennick", "nickatnite"),
            companies = listOf("Nickelodeon Movies"),
            fragments = listOf("nickelodeon")
        ),
        brand(
            "cartoon_network", "Cartoon Network",
            family = listOf("cartoon network.*", "boomerang.*", "adult swim.*", "cartoonito.*"),
            networks = listOf(56, 80),
            names = setOf("cartoonnetwork", "adultswim", "boomerang", "cartoonito")
        ),
        brand(
            "hbo", "HBO",
            family = listOf("hbo", "hbo ?2", "hbo.*"),
            networks = listOf(49),
            names = setOf("hbo", "hbomax", "max"),
            companies = listOf("HBO Films"),
            fragments = listOf("hbo")
        ),
        brand(
            "cinemax", "Cinemax",
            family = listOf("cinemax.*", "(more|action|outer|thriller|movie)max.*", "5 ?star ?max.*"),
            networks = listOf(359),
            names = setOf("cinemax")
        ),
        brand(
            "showtime", "Showtime",
            family = listOf(
                "showtime", "showtime.*", "sho( [0-9]+| 2| x bet)?", "paramount plus with showtime.*",
                "the movie channel.*", "tmc.*"
            ),
            networks = listOf(67),
            names = setOf("showtime", "paramountpluswithshowtime")
        ),
        brand(
            "starz", "Starz",
            family = listOf("starz", "starz.*", "encore.*"),
            networks = listOf(318),
            names = setOf("starz")
        ),
        brand(
            "mgm_plus", "MGM+",
            family = listOf("mgm plus.*", "mgm.*", "epix.*"),
            names = setOf("mgmplus", "epix")
        ),
        brand("amc", "AMC", family = listOf("amc", "amc.*"), networks = listOf(174), names = setOf("amc", "amcplus")),
        brand(
            "fx", "FX",
            family = listOf("fx", "fxx", "fxm", "fx .*", "fxx .*"),
            networks = listOf(88, 1035),
            names = setOf("fx", "fxx", "fxonhulu")
        ),
        brand("comedy_central", "Comedy Central", family = listOf("comedy central.*"), networks = listOf(47)),
        brand(
            "mtv", "MTV",
            family = listOf("mtv", "mtv ?2.*", "mtv.*"),
            networks = listOf(33),
            names = setOf("mtv", "mtv2")
        ),
        brand("bet", "BET", family = listOf("bet", "bet( .*)?"), networks = listOf(24), names = setOf("bet", "betplus")),
        brand(
            "discovery", "Discovery",
            family = listOf(
                "discovery( channel)?", "discovery.*", "science( channel)?", "investigation discovery.*", "destination america"
            ),
            networks = listOf(64),
            names = setOf("discovery", "discoverychannel", "sciencechannel", "investigationdiscovery", "discoveryplus")
        ),
        brand(
            "history", "History",
            family = listOf("history( channel)?", "history.*", "h2( history)?"),
            networks = listOf(65),
            names = setOf("history", "historychannel")
        ),
        brand("ae", "A&E", family = listOf("a and e.*"), networks = listOf(129), names = setOf("aande", "ae")),
        brand(
            "lifetime", "Lifetime",
            family = listOf("lifetime.*", "lmn"),
            networks = listOf(34),
            names = setOf("lifetime", "lifetimemovies", "lmn")
        ),
        brand("tlc", "TLC", family = listOf("tlc.*"), networks = listOf(84)),
        brand("bravo", "Bravo", family = listOf("bravo.*"), networks = listOf(74)),
        brand(
            "e", "E!",
            family = listOf("e", "e entertainment.*"),
            networks = listOf(76),
            names = setOf("e", "eentertainment"),
            logo = "E! Entertainment"
        ),
        brand("syfy", "Syfy", family = listOf("syfy.*"), networks = listOf(77)),
        brand("usa", "USA Network", family = listOf("usa( network)?.*"), networks = listOf(30), names = setOf("usanetwork")),
        brand("tnt", "TNT", family = listOf("tnt.*"), networks = listOf(41)),
        brand("tbs", "TBS", family = listOf("tbs.*"), networks = listOf(68)),
        brand("food", "Food Network", family = listOf("food network.*"), networks = listOf(53)),
        brand("hgtv", "HGTV", family = listOf("hgtv.*"), networks = listOf(210)),
        brand("animal_planet", "Animal Planet", family = listOf("animal planet.*"), networks = listOf(91)),
        brand(
            "nat_geo", "National Geographic",
            family = listOf("national geographic.*", "nat geo.*"),
            networks = listOf(43),
            names = setOf("nationalgeographic", "natgeo", "natgeowild")
        ),
        brand("freeform", "Freeform", family = listOf("freeform.*"), networks = listOf(1267)),
        brand(
            "hallmark", "Hallmark Channel",
            family = listOf("hallmark( channel)?", "hallmark.*"),
            networks = listOf(384),
            names = setOf("hallmarkchannel", "hallmarkmoviesandmysteries", "hallmarkfamily", "hallmark")
        ),
        brand("vh1", "VH1", family = listOf("vh1.*"), networks = listOf(158)),
        brand("pbs", "PBS", family = listOf("pbs.*"), networks = listOf(14)),
        brand("cw", "The CW", family = listOf("(the )?cw.*"), networks = listOf(71), names = setOf("thecw", "cw")),
        brand(
            "cnn", "CNN",
            family = listOf("cnn", "cnn.*", "cable news network", "hln", "headline news"),
            names = setOf("cnn", "hln", "cnninternational")
        )
    )

    /** The family of [channel]: a known brand, or one made from the channel itself. */
    fun brandOf(channel: LiveTvChannel): ChannelBrand {
        val normalized = normalize(channel.name)
        val firstWord = normalized.substringBefore(' ')
        known.firstOrNull { brand -> brand.family.any { it.matches(normalized) } }?.let { return it }
        // A local station of a broadcast network ("FOX 8 New Orleans" -> FOX).
        known.firstOrNull { it.affiliate != null && it.affiliate == firstWord }?.let { return it }
        // Any other channel: its family is the channels that share its first real word.
        val base = baseWord(normalized)
        val display = displayName(channel.name)
        return ChannelBrand(
            key = "ch:$base",
            name = display,
            logoName = null,
            family = emptyList(),
            networkNames = setOf(compact(display), base, base + "channel", base + "network", base + "tv"),
            companyFragments = emptyList(),
            baseWord = base
        )
    }

    /** Position in the family's order, or -1 when [normalized] isn't one of its main channels. */
    private fun familyIndex(brand: ChannelBrand, normalized: String): Int {
        if (brand.baseWord != null) {
            return if (baseWord(normalized) == brand.baseWord) 0 else -1
        }
        return brand.family.indexOfFirst { it.matches(normalized) }
    }

    /** True when [channel] lands on [brand] (each channel belongs to one family only). */
    private fun belongsTo(brand: ChannelBrand, channel: LiveTvChannel): Boolean = brandOf(channel).key == brand.key

    /**
     * The brand's channels in [channels]: main channels in family order and locals (by name),
     * each listed once ("ESPN" and "ESPN HD" are the same channel: the one with a guide wins).
     * [selected] comes first among the locals when it is one.
     */
    fun members(
        brand: ChannelBrand,
        channels: List<LiveTvChannel>,
        selected: LiveTvChannel?,
        hasGuide: (LiveTvChannel) -> Boolean
    ): ChannelBrandMembers {
        val live = ArrayList<Pair<Int, LiveTvChannel>>()
        val locals = ArrayList<LiveTvChannel>()
        for (channel in channels) {
            if (!belongsTo(brand, channel)) continue
            val normalized = normalize(channel.name)
            val index = familyIndex(brand, normalized)
            when {
                index >= 0 -> live += index to channel
                brand.hasLocals && (channel.country == null || channel.country == "US") -> locals += channel
            }
        }
        val liveSorted = live
            .sortedWith(compareBy({ it.first }, { normalize(it.second.name).length }, { normalize(it.second.name) }))
            .map { it.second }
        val localsSorted = locals.sortedWith(
            compareBy<LiveTvChannel>({ if (selected != null && it.id == selected.id) 0 else 1 }, { normalize(it.name) })
        )
        return ChannelBrandMembers(
            live = dedupe(liveSorted, selected, hasGuide),
            locals = dedupe(localsSorted, selected, hasGuide)
        )
    }

    private val hdTag = Regex("""\bHD\b""", RegexOption.IGNORE_CASE)

    /**
     * One card per channel, keeping the list's order. Copies are found by name ("ESPN" / "ESPN HD")
     * or guide id, then by channel number ("ACC Network" / "Accn Espn", both 612) — only between
     * copies that each have one number, so an alternate feed sharing a number can't join two channels.
     * Of the copies it keeps the chosen one, else one with a guide, else HD.
     */
    private fun dedupe(
        channels: List<LiveTvChannel>,
        selected: LiveTvChannel?,
        hasGuide: (LiveTvChannel) -> Boolean
    ): List<LiveTvChannel> {
        // 1. Same name or same guide id.
        val byName = ArrayList<MutableList<LiveTvChannel>>()
        val byKey = HashMap<String, Int>()
        for (channel in channels) {
            val keys = listOfNotNull(
                "n:" + normalize(channel.name),
                channel.epgChannelId?.takeIf { it.isNotBlank() }?.let { "g:$it" }
            )
            val index = keys.firstNotNullOfOrNull { byKey[it] } ?: byName.size.also { byName += ArrayList<LiveTvChannel>() }
            byName[index] += channel
            keys.forEach { byKey.putIfAbsent(it, index) }
        }
        // 2. Same single channel number.
        val groups = ArrayList<MutableList<LiveTvChannel>>()
        val byNumber = HashMap<String, Int>()
        for (group in byName) {
            val numbers = group.mapNotNull { it.number?.trim()?.takeIf { n -> n.isNotEmpty() && n != "0" } }.distinct()
            val number = numbers.singleOrNull()
            val existing = number?.let { byNumber[it] }
            if (existing != null) {
                groups[existing] += group
            } else {
                if (number != null) byNumber[number] = groups.size
                groups += group.toMutableList()
            }
        }
        return groups.map { group ->
            group.firstOrNull { selected != null && it.id == selected.id }
                ?: group.maxWithOrNull(
                    compareBy<LiveTvChannel>(
                        { hasGuide(it) },
                        { hdTag.containsMatchIn(it.name) },
                        { -it.name.length }
                    )
                )
                ?: group.first()
        }
    }

    /** True when a TMDB network name is this brand's ("FOX", "ESPN2", "Hallmark Channel"). */
    fun isBrandNetwork(brand: ChannelBrand, networkName: String?): Boolean {
        val name = networkName?.let(::compact)?.takeIf { it.isNotEmpty() } ?: return false
        return name in brand.networkNames
    }
}
