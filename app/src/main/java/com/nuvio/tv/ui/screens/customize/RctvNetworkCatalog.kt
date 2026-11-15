package com.nuvio.tv.ui.screens.customize

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.screens.channelprofile.ChannelBrands
import com.nuvio.tv.ui.screens.livetv.GuideBrowse
import com.nuvio.tv.ui.screens.livetv.LiveTvViewModel

/**
 * RCTV (owner), "Customize your experience", the Networks page: the networks offered, each with its
 * normal logo (the app's bundled logos, as on the channel pages), not the IPTV's channels. They're
 * only logos: once the profile finishes, "Creating Your Experience" finds each picked network's
 * channel in the Guide ([channelIn]) and puts it in the Favorite Networks (the Guide's Custom list,
 * Home's Favorite Networks row).
 */
object RctvNetworkCatalog {

    data class Network(
        /** Stable id (what a pick is saved as). */
        val key: String,
        /** Its name, shown when it has no logo (and on the chips). */
        val name: String,
        val category: GuideBrowse,
        /** The bundled logo's name (its light or wide version is used when there is one); null: none yet. */
        val logo: String?,
        /** Other names its channel goes by in the Guide ("FS1" for "FOX Sports 1"). */
        val aliases: List<String> = emptyList()
    ) {
        /** Its names as the Guide's are compared ([ChannelBrands.compact]: "ESPN HD" -> "espn"). */
        val matchNames: Set<String> by lazy {
            (listOf(name) + aliases).map(ChannelBrands::compact).filter { it.isNotEmpty() }.toSet()
        }
    }

    /** The page's categories, in this order. */
    val CATEGORIES: List<GuideBrowse> = listOf(
        GuideBrowse.ENTERTAINMENT, GuideBrowse.NEWS_INFO, GuideBrowse.SPORTS, GuideBrowse.FAMILY_KIDS,
        GuideBrowse.LIFESTYLE, GuideBrowse.LATINO, GuideBrowse.PREMIUM
    )

    private fun n(key: String, name: String, category: GuideBrowse, logo: String?, vararg aliases: String) =
        Network(key, name, category, logo, aliases.toList())

    private val E = GuideBrowse.ENTERTAINMENT
    private val N = GuideBrowse.NEWS_INFO
    private val S = GuideBrowse.SPORTS
    private val K = GuideBrowse.FAMILY_KIDS
    private val L = GuideBrowse.LIFESTYLE
    private val SP = GuideBrowse.LATINO
    private val P = GuideBrowse.PREMIUM

    /**
     * Every network offered, in "All Networks" order: the broadcast networks first (as the Guide
     * starts), then the best-known. RCTV: those without a logo yet (WAPA, WIPR, Teleonce, CNN en
     * Español) show their name until the owner's logos are added.
     */
    val all: List<Network> = listOf(
        n("abc", "ABC", E, "abc"),
        n("nbc", "NBC", E, "nbc"),
        n("fox", "FOX", E, "fox"),
        n("cbs", "CBS", E, "cbs-logo"),
        n("cw", "The CW", E, "the-cw", "CW"),
        n("espn", "ESPN", S, "espn"),
        n("espn2", "ESPN2", S, "espn-2", "ESPN 2"),
        n("fs1", "FOX Sports 1", S, "fox-sports-1", "FS1"),
        n("tnt", "TNT", E, "tnt"),
        n("tbs", "TBS", E, "tbs"),
        n("fx", "FX", E, "fx"),
        n("cnn", "CNN", N, "cnn"),
        n("cnbc", "CNBC", N, "cnbc"),
        n("foxnews", "FOX News", N, "fox-news", "FOX News Channel"),
        n("msnbc", "MSNBC", N, "ms-now", "MS NOW"),
        n("hgtv", "HGTV", L, "hgtv"),
        n("food", "Food Network", L, "food-network"),
        n("discovery", "Discovery", N, "discovery-channel", "Discovery Channel"),
        n("history", "History", N, "history-channel", "History Channel"),
        n("tlc", "TLC", L, "tlc"),
        n("bravo", "Bravo", E, "bravo"),
        n("ae", "A&E", E, "a-and-e"),
        n("univision", "Univision", SP, "univision"),
        n("telemundo", "Telemundo", SP, "telemundo", "Telemundo Television Network"),
        n("pbs", "PBS", E, "pbs"),
        n("amc", "AMC", E, "amc"),
        n("usa", "USA Network", E, "usa", "USA"),
        n("syfy", "Syfy", E, "syfy"),
        n("e", "E!", E, "e-entertainment", "E! Entertainment", "E Entertainment"),
        n("lifetime", "Lifetime", E, "lifetime"),
        n("hallmark", "Hallmark Channel", E, "hallmark-channel", "Hallmark"),
        n("comedycentral", "Comedy Central", E, "comedy-central"),
        n("paramount", "Paramount Network", E, "paramount-network"),
        n("mtv", "MTV", E, "mtv"),
        n("vh1", "VH1", E, "vh1"),
        n("bet", "BET", E, "bet"),
        n("tvland", "TV Land", E, "tv-land"),
        n("oxygen", "Oxygen", E, "oxygen", "Oxygen True Crime"),
        n("wetv", "WE tv", E, "we-tv"),
        n("trutv", "truTV", E, "tru-tv", "Tru TV"),
        n("ion", "ION", E, "ion-television", "ION Television"),
        n("own", "OWN", E, "oprah-winfrey-network", "Oprah Winfrey Network"),
        n("reelz", "Reelz", E, "reelz"),
        n("bbcamerica", "BBC America", E, "bbc-america"),
        n("cmt", "CMT", E, "cmt"),
        n("foxbusiness", "FOX Business", N, "fox-business", "FOX Business Network"),
        n("hln", "HLN", N, "hln"),
        n("bloomberg", "Bloomberg TV", N, "bloomberg-television", "Bloomberg", "Bloomberg Television"),
        n("newsmax", "Newsmax", N, "newsmax-tv", "Newsmax TV"),
        n("weather", "The Weather Channel", N, "weather-channel", "Weather Channel"),
        n("natgeo", "National Geographic", N, "national-geographic", "Nat Geo", "National Geographic Channel"),
        n("natgeowild", "Nat Geo Wild", N, "nat-geo-wild"),
        n("animalplanet", "Animal Planet", N, "animal-planet"),
        n("id", "Investigation Discovery", N, "investigation-discovery", "ID"),
        n("science", "Science", N, "discovery-science", "Science Channel"),
        n("smithsonian", "Smithsonian Channel", N, "smithsonian-channel"),
        n("travel", "Travel Channel", L, "travel-channel"),
        n("cooking", "Cooking Channel", L, "cooking-channel"),
        n("magnolia", "Magnolia Network", L, "magnolia-network", "Magnolia"),
        n("motortrend", "MotorTrend", L, "motor-trend", "Motor Trend"),
        n("nflnetwork", "NFL Network", S, "nfl-network"),
        n("redzone", "NFL RedZone", S, "nfl-red-zone", "NFL Red Zone", "RedZone"),
        n("nbatv", "NBA TV", S, "nba-tv"),
        n("mlbnetwork", "MLB Network", S, "mlb-network"),
        n("nhlnetwork", "NHL Network", S, "nhl-network"),
        n("cbssports", "CBS Sports Network", S, "cbs-sports-network", "CBS Sports"),
        n("fs2", "FOX Sports 2", S, "fox-sports-2", "FS2"),
        n("espnu", "ESPNU", S, "espn-u", "ESPN U"),
        n("espnews", "ESPNews", S, "espnews"),
        n("sec", "SEC Network", S, "sec-network", "ESPN SEC Network"),
        n("acc", "ACC Network", S, "acc-network"),
        n("btn", "Big Ten Network", S, "big-ten-network", "BTN"),
        n("golf", "Golf Channel", S, "nbc-golf-channel", "Golf"),
        n("tennis", "Tennis Channel", S, "tennis-channel"),
        n("disney", "Disney Channel", K, "disney-channel"),
        n("disneyjr", "Disney Junior", K, "disney-jr", "Disney Jr"),
        n("disneyxd", "Disney XD", K, "disney-xd"),
        n("nick", "Nickelodeon", K, "nickelodeon", "Nick"),
        n("nickjr", "Nick Jr.", K, "nick-jr", "Nick Jr"),
        n("cartoon", "Cartoon Network", K, "cartoon-network"),
        n("boomerang", "Boomerang", K, "boomerang"),
        n("freeform", "Freeform", K, "freeform"),
        n("unimas", "UniMás", SP, "unimas"),
        n("galavision", "Galavisión", SP, "galavision"),
        n("tudn", "TUDN", SP, "tudn"),
        n("espndeportes", "ESPN Deportes", SP, "espn-deportes"),
        n("foxdeportes", "FOX Deportes", SP, "fox-sports-deportes", "FOX Sports Deportes"),
        n("cnnespanol", "CNN en Español", SP, null, "CNN Espanol"),
        n("discoveryespanol", "Discovery en Español", SP, "discovery-en-espanol"),
        n("historyespanol", "History en Español", SP, "history-en-espanol"),
        n("telemundopr", "Telemundo Puerto Rico", SP, "telemundo-puerto-rico-wkaq-tv-hz-us-pr", "Telemundo PR"),
        n("wapa", "WAPA", SP, null, "WAPA PR", "WAPA TV"),
        n("wipr", "WIPR", SP, null, "WIPR Canal 6"),
        n("teleonce", "Teleonce", SP, null, "Teleonce PR", "Canal 11"),
        n("hbo", "HBO", P, "hbo"),
        n("showtime", "Showtime", P, "showtime", "SHO"),
        n("starz", "Starz", P, "starz"),
        n("cinemax", "Cinemax", P, "cinemax"),
        n("mgmplus", "MGM+", P, "mgm-plus", "MGM Plus", "EPIX"),
        n("tcm", "TCM", P, "tcm", "Turner Classic Movies"),
        n("ifc", "IFC", P, "ifc"),
        n("sundance", "Sundance TV", P, "sundance-tv", "SundanceTV")
    )

    fun byKey(key: String): Network? = all.firstOrNull { it.key == key }

    /**
     * The Guide's channel for [network]: the first of the Guide's own channels (not another
     * version of one) whose name, without its country tag and feed words ("USA: HBO East" ->
     * "hbo"), is one of the network's names; the United States' or Puerto Rico's before another
     * country's ("UK: CNN"). Null when the Guide has none.
     */
    fun channelIn(network: Network, channels: List<LiveTvChannel>): LiveTvChannel? {
        val names = network.matchNames
        return channels.asSequence()
            .filter { channel ->
                channel.versionOf == null &&
                    ChannelBrands.compact(LiveTvViewModel.stripCountryTag(channel.name)) in names
            }
            .minByOrNull { channel ->
                val country = channel.country?.uppercase()
                if (country == null || country == "US" || country == "PR") 0 else 1
            }
    }
}
