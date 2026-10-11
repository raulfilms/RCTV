package com.nuvio.tv.data.disneyplus

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbCollectionMediaType

/**
 * Static definition of the Disney+ hub: which pages exist (the main page plus one page per brand
 * tile), which rows each page shows, and where every row pulls its titles from on TMDB.
 *
 * Every TMDB id used here was checked against themoviedb.org (companies, networks, watch providers).
 * Hand-picked titles (classics, princesses, villains, Star Wars saga...) are stored by title + year and
 * resolved through TMDB search at runtime, so there are no hardcoded per-title ids to drift out of date.
 */
object DisneyPlusIds {
    // Watch providers (US region)
    const val PROVIDER_DISNEY_PLUS = "337"
    const val PROVIDER_HULU = "15"

    // Production companies
    const val CO_WALT_DISNEY_PICTURES = "2"
    const val CO_PIXAR = "3"
    const val CO_LUCASFILM = "1"
    const val CO_MARVEL_STUDIOS = "420"
    const val CO_MARVEL_ANIMATION = "13252"
    const val CO_WALT_DISNEY_ANIMATION = "6125"
    const val CO_NATIONAL_GEOGRAPHIC = "7521|189457"

    // TV networks
    const val NET_DISNEY_PLUS = "2739"
    const val NET_HULU = "453"
    const val NET_FX = "88"
    const val NET_ABC = "2"
    const val NET_FREEFORM = "1267"
    const val NET_NAT_GEO = "43"
    const val NET_DISNEY_CHANNEL = "54"
    const val NET_DISNEY_JUNIOR = "281"
    const val NET_DISNEY_XD = "44"
    const val NET_ESPN = "29"

    // TMDB genre ids
    const val G_ACTION = "28"
    const val G_ADVENTURE = "12"
    const val G_ANIMATION = "16"
    const val G_COMEDY = "35"
    const val G_DOCUMENTARY = "99"
    const val G_FAMILY = "10751"
    const val G_TV_ACTION_ADVENTURE = "10759"
    const val G_TV_KIDS = "10762"

    /** Company ids owned by The Walt Disney Company, used to decide what counts as "Disney" content. */
    val DISNEY_COMPANY_IDS: Set<Int> = setOf(2, 3, 1, 420, 13252, 6125, 7521, 189457, 3475)

    /** Network ids owned by The Walt Disney Company. */
    val DISNEY_NETWORK_IDS: Set<Int> = setOf(2739, 453, 88, 2, 1267, 43, 54, 281, 44, 29)

    /**
     * Name fragments for Disney-owned studios/labels that aren't in the id sets above (20th Century,
     * Searchlight, Touchstone, ...). Matched case-insensitively against TMDB production company names.
     */
    val DISNEY_COMPANY_NAME_FRAGMENTS: List<String> = listOf(
        "walt disney", "disney", "pixar", "lucasfilm", "marvel studios", "marvel animation",
        "20th century", "twentieth century", "searchlight pictures", "fox searchlight",
        "touchstone pictures", "national geographic", "fx productions", "abc signature",
        "abc studios", "hulu originals", "blue sky studios"
    )

    /** Network names that are Disney-owned. Matched exactly (case-insensitive) to avoid e.g. "ABC (AU)". */
    val DISNEY_NETWORK_NAMES: Set<String> = setOf(
        "disney+", "disney channel", "disney junior", "disney xd", "hulu", "fx", "fxx", "fx on hulu",
        "abc", "freeform", "abc family", "national geographic", "nat geo wild", "nat geo", "espn"
    )
}

/**
 * Paramount+ ids. Networks were checked on themoviedb.org (CBS 16, BET 24, Comedy Central 47, MTV 33,
 * Nickelodeon 13, Showtime 67, Paramount+ 4330). Smithsonian Channel and Nick Jr. are found at runtime
 * through one of their own shows ([NetworkLookup]), so no unchecked id is hardcoded.
 */
object ParamountPlusIds {
    /** Paramount Plus / Paramount Plus Premium (US), the same ids Home's Paramount+ tile uses. */
    const val PROVIDERS = "531|2303"

    const val CO_PARAMOUNT_PICTURES = "4"

    const val NET_PARAMOUNT_PLUS = "4330"
    const val NET_CBS = "16"
    const val NET_BET = "24"
    const val NET_COMEDY_CENTRAL = "47"
    const val NET_MTV = "33"
    const val NET_NICKELODEON = "13"
    const val NET_SHOWTIME = "67"

    val SMITHSONIAN_CHANNEL = NetworkLookup(
        names = listOf("Smithsonian Channel"),
        probes = listOf(
            CuratedTitle("Aerial America", 2010, TmdbCollectionMediaType.TV),
            CuratedTitle("Air Disasters", 2011, TmdbCollectionMediaType.TV)
        )
    )

    val NICK_JR = NetworkLookup(
        names = listOf("Nick Jr."),
        probes = listOf(
            CuratedTitle("PAW Patrol", 2013, TmdbCollectionMediaType.TV),
            CuratedTitle("Bubble Guppies", 2011, TmdbCollectionMediaType.TV),
            CuratedTitle("Blaze and the Monster Machines", 2014, TmdbCollectionMediaType.TV)
        )
    )

    // TMDB genre ids used on the Paramount+ pages
    const val G_CRIME = "80"
    const val G_DRAMA = "18"
    const val G_MYSTERY = "9648"
    const val G_TV_REALITY = "10764"
    const val G_TV_TALK = "10767"

    /** Paramount Global companies, used to decide what counts as Paramount content. */
    val COMPANY_IDS: Set<Int> = setOf(4)

    val NETWORK_IDS: Set<Int> = setOf(4330, 16, 24, 47, 33, 13, 67)

    val COMPANY_NAME_FRAGMENTS: List<String> = listOf(
        "paramount", "cbs", "nickelodeon", "mtv", "comedy central", "bet films", "bet networks",
        "black entertainment television", "showtime", "smithsonian", "viacom", "republic pictures"
    )

    val NETWORK_NAMES: Set<String> = setOf(
        "paramount+", "paramount+ with showtime", "cbs all access", "cbs", "showtime", "nickelodeon",
        "nick jr.", "nicktoons", "nick at nite", "teennick", "comedy central", "mtv", "mtv2", "vh1",
        "bet", "bet+", "cmt", "tv land", "paramount network", "spike", "pop", "smithsonian channel"
    )
}

/**
 * Discovery+ ids. TLC 84, Investigation Discovery 244 and Animal Planet 91 were checked on themoviedb.org;
 * discovery+, HGTV, Food Network, OWN and Magnolia Network are found at runtime through their own shows.
 */
object DiscoveryPlusIds {
    /** discovery+ (US), the same id Home's discovery+ tile uses. */
    const val PROVIDERS = "520"

    const val NET_DISCOVERY = "64"
    const val NET_TLC = "84"
    const val NET_ID = "244"
    const val NET_ANIMAL_PLANET = "91"

    private fun show(title: String, year: Int) = CuratedTitle(title, year, TmdbCollectionMediaType.TV)

    val DISCOVERY_PLUS = NetworkLookup(
        names = listOf("discovery+"),
        probes = listOf(show("Ghost Adventures: Cecil Hotel", 2021), show("90 Day Bares All", 2021), show("The Lost Kitchen", 2021))
    )
    val HGTV = NetworkLookup(
        names = listOf("HGTV"),
        probes = listOf(show("Fixer Upper", 2013), show("Property Brothers", 2011), show("Love It or List It", 2008))
    )
    val FOOD_NETWORK = NetworkLookup(
        names = listOf("Food Network"),
        probes = listOf(show("Chopped", 2009), show("Diners, Drive-Ins and Dives", 2007), show("Beat Bobby Flay", 2013))
    )
    val OWN = NetworkLookup(
        names = listOf("OWN", "Oprah Winfrey Network"),
        probes = listOf(show("Queen Sugar", 2016), show("Greenleaf", 2016), show("Iyanla: Fix My Life", 2012))
    )
    val MAGNOLIA = NetworkLookup(
        names = listOf("Magnolia Network"),
        probes = listOf(
            show("Fixer Upper: Welcome Home", 2022),
            show("Magnolia Table with Joanna Gaines", 2021),
            show("Restoration Road with Clint Harp", 2021)
        )
    )

    // TMDB genre ids used on the discovery+ pages
    const val G_CRIME = "80"
    const val G_DRAMA = "18"
    const val G_TV_REALITY = "10764"
    const val G_TV_TALK = "10767"

    /** Discovery companies and networks, used to decide what counts as discovery+ content. */
    val COMPANY_IDS: Set<Int> = emptySet()

    val NETWORK_IDS: Set<Int> = setOf(64, 84, 244, 91)

    val COMPANY_NAME_FRAGMENTS: List<String> = listOf(
        "discovery", "scripps networks", "hgtv", "food network", "animal planet", "harpo", "magnolia network",
        "magnolia studio"
    )

    val NETWORK_NAMES: Set<String> = setOf(
        "discovery+", "discovery", "hgtv", "food network", "tlc", "investigation discovery", "id", "own",
        "oprah winfrey network", "animal planet", "magnolia network", "science channel", "discovery science",
        "travel channel", "cooking channel", "destination america", "american heroes channel", "ahc",
        "discovery life", "discovery family", "motor trend", "motortrend", "diy network"
    )
}

/**
 * HBO Max ids. HBO 49, HBO Max 3186 and Cartoon Network 56 were checked on themoviedb.org; Adult Swim is
 * found at runtime through its own shows, Crunchyroll's watch provider by name, and studios (DC, Warner
 * Bros. Animation, Sesame Workshop...) through TMDB company search.
 */
object HboMaxIds {
    /** HBO Max / Max (US), the same ids Home's HBO Max tile uses. */
    const val PROVIDERS = "1899|384"

    const val NET_HBO = "49"
    const val NET_HBO_MAX = "3186"
    const val NET_CARTOON_NETWORK = "56"

    val ADULT_SWIM = NetworkLookup(
        names = listOf("Adult Swim"),
        probes = listOf(
            CuratedTitle("Rick and Morty", 2013, TmdbCollectionMediaType.TV),
            CuratedTitle("Robot Chicken", 2005, TmdbCollectionMediaType.TV),
            CuratedTitle("Aqua Teen Hunger Force", 2000, TmdbCollectionMediaType.TV)
        )
    )

    /** Watch provider looked up by name in TMDB's US provider list. */
    const val CRUNCHYROLL = "Crunchyroll"

    /** The three company names DC titles are credited to on TMDB. */
    val DC_COMPANIES = listOf("DC Comics", "DC Entertainment", "DC Studios")

    const val WB_ANIMATION = "Warner Bros. Animation"
    const val SESAME_WORKSHOP = "Sesame Workshop"

    // TMDB genre ids used on the HBO Max pages
    const val G_DRAMA = "18"

    /** Warner Bros. / HBO companies and networks, used to decide what counts as HBO Max content. */
    val COMPANY_IDS: Set<Int> = emptySet()

    val NETWORK_IDS: Set<Int> = setOf(49, 3186, 56)

    val COMPANY_NAME_FRAGMENTS: List<String> = listOf(
        "warner bros", "hbo", "dc comics", "dc entertainment", "dc studios", "new line cinema", "castle rock",
        "cartoon network", "williams street", "hanna-barbera", "turner", "cinemax", "warner animation"
    )

    val NETWORK_NAMES: Set<String> = setOf(
        "hbo", "hbo max", "max", "cinemax", "cartoon network", "adult swim", "[adult swim]", "tnt", "tbs", "trutv",
        "tcm", "turner classic movies", "boomerang", "cartoonito", "the wb"
    )
}

/**
 * A TV network found through one of its shows: the first probe whose TMDB networks include one of [names]
 * (e.g. "OWN" / "Oprah Winfrey Network").
 */
data class NetworkLookup(val names: List<String>, val probes: List<CuratedTitle>)

/** Which titles count as a service's own (Continue Watching on its page only keeps those). */
data class HubOwnership(
    val companyIds: Set<Int>,
    val networkIds: Set<Int>,
    val companyNameFragments: List<String>,
    val networkNames: Set<String>
)

/** The streaming service a hub page belongs to: its watch providers, main page and what it owns. */
enum class HubService(
    /** TMDB watch provider ids (US), "|"-separated. */
    val providers: String
) {
    DISNEY(DisneyPlusIds.PROVIDER_DISNEY_PLUS),
    PARAMOUNT(ParamountPlusIds.PROVIDERS),
    DISCOVERY(DiscoveryPlusIds.PROVIDERS),
    HBO_MAX(HboMaxIds.PROVIDERS);

    val ownership: HubOwnership
        get() = when (this) {
            DISNEY -> HubOwnership(
                DisneyPlusIds.DISNEY_COMPANY_IDS,
                DisneyPlusIds.DISNEY_NETWORK_IDS,
                DisneyPlusIds.DISNEY_COMPANY_NAME_FRAGMENTS,
                DisneyPlusIds.DISNEY_NETWORK_NAMES
            )
            PARAMOUNT -> HubOwnership(
                ParamountPlusIds.COMPANY_IDS,
                ParamountPlusIds.NETWORK_IDS,
                ParamountPlusIds.COMPANY_NAME_FRAGMENTS,
                ParamountPlusIds.NETWORK_NAMES
            )
            DISCOVERY -> HubOwnership(
                DiscoveryPlusIds.COMPANY_IDS,
                DiscoveryPlusIds.NETWORK_IDS,
                DiscoveryPlusIds.COMPANY_NAME_FRAGMENTS,
                DiscoveryPlusIds.NETWORK_NAMES
            )
            HBO_MAX -> HubOwnership(
                HboMaxIds.COMPANY_IDS,
                HboMaxIds.NETWORK_IDS,
                HboMaxIds.COMPANY_NAME_FRAGMENTS,
                HboMaxIds.NETWORK_NAMES
            )
        }
}

/**
 * Every page of the app's own streaming sections: the Disney+ main page, its brand pages and
 * collections, and the Paramount+, discovery+ and HBO Max main pages with their brand pages. [key] is
 * used in the navigation route.
 */
enum class DisneyPlusHub(
    val key: String,
    @StringRes val titleRes: Int,
    val service: HubService = HubService.DISNEY
) {
    MAIN("main", R.string.disney_plus_title),
    DISNEY("disney", R.string.disney_brand_disney),
    PIXAR("pixar", R.string.disney_brand_pixar),
    MARVEL("marvel", R.string.disney_brand_marvel),
    STAR_WARS("star_wars", R.string.disney_brand_star_wars),
    NAT_GEO("national_geographic", R.string.disney_brand_nat_geo),
    HULU("hulu", R.string.disney_brand_hulu),
    ESPN("espn", R.string.disney_brand_espn),

    // Collection pages, opened from the "Collections" row on the Disney page.
    COL_WDAS("collection_wdas", R.string.disney_col_wdas),
    COL_DISNEY_JR("collection_disney_jr", R.string.disney_col_disney_jr),
    COL_DISNEY_CHANNEL("collection_disney_channel", R.string.disney_col_disney_channel),
    COL_DISNEY_XD("collection_disney_xd", R.string.disney_col_disney_xd),
    COL_DISNEYNATURE("collection_disneynature", R.string.disney_col_disneynature),
    COL_DCOM("collection_dcom", R.string.disney_col_dcom),

    // Paramount+: its main page, then one page per brand tile.
    PARAMOUNT_MAIN("paramount", R.string.paramount_plus_title, HubService.PARAMOUNT),
    P_CBS("paramount_cbs", R.string.paramount_brand_cbs, HubService.PARAMOUNT),
    P_BET("paramount_bet", R.string.paramount_brand_bet, HubService.PARAMOUNT),
    P_COMEDY_CENTRAL("paramount_comedy_central", R.string.paramount_brand_comedy_central, HubService.PARAMOUNT),
    P_MTV("paramount_mtv", R.string.paramount_brand_mtv, HubService.PARAMOUNT),
    P_NICKELODEON("paramount_nickelodeon", R.string.paramount_brand_nickelodeon, HubService.PARAMOUNT),
    P_SMITHSONIAN("paramount_smithsonian", R.string.paramount_brand_smithsonian, HubService.PARAMOUNT),

    // discovery+: its main page, then one page per brand tile.
    DISCOVERY_MAIN("discovery", R.string.discovery_plus_title, HubService.DISCOVERY),
    D_ORIGINALS("discovery_originals", R.string.discovery_brand_originals, HubService.DISCOVERY),
    D_HGTV("discovery_hgtv", R.string.discovery_brand_hgtv, HubService.DISCOVERY),
    D_FOOD_NETWORK("discovery_food_network", R.string.discovery_brand_food_network, HubService.DISCOVERY),
    D_TLC("discovery_tlc", R.string.discovery_brand_tlc, HubService.DISCOVERY),
    D_ID("discovery_id", R.string.discovery_brand_id, HubService.DISCOVERY),
    D_OWN("discovery_own", R.string.discovery_brand_own, HubService.DISCOVERY),
    D_ANIMAL_PLANET("discovery_animal_planet", R.string.discovery_brand_animal_planet, HubService.DISCOVERY),
    D_MAGNOLIA("discovery_magnolia", R.string.discovery_brand_magnolia, HubService.DISCOVERY),

    // HBO Max: its main page, then one page per brand tile.
    HBOMAX_MAIN("hbomax", R.string.hbomax_title, HubService.HBO_MAX),
    H_HBO("hbomax_hbo", R.string.hbomax_brand_hbo, HubService.HBO_MAX),
    H_MAX_ORIGINALS("hbomax_max_originals", R.string.hbomax_brand_max_originals, HubService.HBO_MAX),
    H_DC("hbomax_dc", R.string.hbomax_brand_dc, HubService.HBO_MAX),
    H_LOONEY_TUNES("hbomax_looney_tunes", R.string.hbomax_brand_looney_tunes, HubService.HBO_MAX),
    H_WB_ANIMATION("hbomax_wb_animation", R.string.hbomax_brand_wb_animation, HubService.HBO_MAX),
    H_SESAME("hbomax_sesame_workshop", R.string.hbomax_brand_sesame_workshop, HubService.HBO_MAX),
    H_CRUNCHYROLL("hbomax_crunchyroll", R.string.hbomax_brand_crunchyroll, HubService.HBO_MAX);

    /** A service's main page (hero, brand tiles, Continue Watching...), as opposed to a brand page. */
    val isMain: Boolean
        get() = this == MAIN || this == PARAMOUNT_MAIN || this == DISCOVERY_MAIN || this == HBOMAX_MAIN

    companion object {
        fun fromKey(key: String?): DisneyPlusHub =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MAIN

        /** The brand tiles shown under the hero on a service's main page, in order. */
        fun brandTiles(service: HubService): List<DisneyPlusHub> = when (service) {
            HubService.DISNEY -> listOf(DISNEY, PIXAR, MARVEL, STAR_WARS, NAT_GEO, HULU, ESPN)
            HubService.PARAMOUNT -> listOf(P_CBS, P_BET, P_COMEDY_CENTRAL, P_MTV, P_NICKELODEON, P_SMITHSONIAN)
            HubService.DISCOVERY -> listOf(
                D_ORIGINALS, D_HGTV, D_FOOD_NETWORK, D_TLC, D_ID, D_OWN, D_ANIMAL_PLANET, D_MAGNOLIA
            )
            HubService.HBO_MAX -> listOf(
                H_HBO, H_MAX_ORIGINALS, H_DC, H_LOONEY_TUNES, H_WB_ANIMATION, H_SESAME, H_CRUNCHYROLL
            )
        }
    }
}

/** A title picked by hand, resolved to a TMDB entry at runtime via search (title + year). */
data class CuratedTitle(
    val title: String,
    val year: Int?,
    val mediaType: TmdbCollectionMediaType = TmdbCollectionMediaType.MOVIE
)

/** Where a row's titles come from. A row can combine several sources; their results get interleaved. */
sealed interface DisneyRowSource {
    data class Discover(
        val mediaType: TmdbCollectionMediaType,
        val sortBy: String = "popularity.desc",
        val companies: String? = null,
        val networks: String? = null,
        val genres: String? = null,
        val withoutGenres: String? = null,
        val providers: String? = null,
        val keywordQuery: String? = null,
        /** TMDB company name, looked up through company search at runtime (combined with [companies] as OR). */
        val companyQuery: String? = null,
        /** A network found at runtime through one of its shows (combined with [networks] as OR). */
        val networkLookup: NetworkLookup? = null,
        /** A watch provider looked up by name at runtime (combined with [providers] as OR). */
        val providerQuery: String? = null,
        val releaseDateGte: String? = null,
        val releaseDateLte: String? = null,
        val voteCountGte: Int? = null,
        val runtimeGte: Int? = null,
        val runtimeLte: Int? = null
    ) : DisneyRowSource

    data class Curated(val titles: List<CuratedTitle>) : DisneyRowSource
}

enum class DisneyRowKind {
    /** Regular poster row filled from [DisneyRowSpec.sources]. */
    CATALOG,

    /** In-progress Disney-owned titles from the person's own watch progress. */
    CONTINUE_WATCHING,

    /** Titles on Disney+ matching the genres the person watches most. */
    RECOMMENDED,

    /** A row of wide collection tiles ([DisneyRowSpec.collections]); each opens its own collection page. */
    COLLECTIONS
}

data class DisneyRowSpec(
    val id: String,
    @StringRes val titleRes: Int,
    val sources: List<DisneyRowSource> = emptyList(),
    val kind: DisneyRowKind = DisneyRowKind.CATALOG,
    val collections: List<DisneyPlusHub> = emptyList()
)

/** A page definition: its rows, and which row feeds the hero banner. */
data class DisneyHubSpec(
    val hub: DisneyPlusHub,
    val heroRowId: String,
    val rows: List<DisneyRowSpec>
)

private val MOVIE = TmdbCollectionMediaType.MOVIE
private val TV = TmdbCollectionMediaType.TV

private fun movie(title: String, year: Int) = CuratedTitle(title, year, MOVIE)
private fun series(title: String, year: Int) = CuratedTitle(title, year, TV)

object DisneyPlusCatalog {
    private const val RECENT = "primary_release_date.desc"
    private const val RECENT_TV = "first_air_date.desc"
    private const val POPULAR = "popularity.desc"
    private const val MOST_VOTED = "vote_count.desc"
    private const val TOP_RATED = "vote_average.desc"
    private const val RELEASE_ORDER = "primary_release_date.asc"

    // ---- Curated lists ----

    private val CLASSICS = listOf(
        movie("The Lion King", 1994),
        movie("Aladdin", 1992),
        movie("Beauty and the Beast", 1991),
        movie("Frozen", 2013),
        movie("Moana", 2016),
        movie("Toy Story", 1995),
        movie("The Little Mermaid", 1989),
        movie("Cinderella", 1950),
        movie("Snow White and the Seven Dwarfs", 1937),
        movie("Pinocchio", 1940),
        movie("Bambi", 1942),
        movie("The Jungle Book", 1967),
        movie("Peter Pan", 1953),
        movie("Mulan", 1998),
        movie("Lady and the Tramp", 1955),
        movie("One Hundred and One Dalmatians", 1961),
        movie("Dumbo", 1941),
        movie("Fantasia", 1940)
    )

    private val PRINCESSES = listOf(
        movie("Frozen", 2013),
        movie("Tangled", 2010),
        movie("Moana", 2016),
        movie("Cinderella", 1950),
        movie("The Little Mermaid", 1989),
        movie("Snow White and the Seven Dwarfs", 1937),
        movie("Beauty and the Beast", 1991),
        movie("Aladdin", 1992),
        movie("Mulan", 1998),
        movie("Pocahontas", 1995),
        movie("The Princess and the Frog", 2009),
        movie("Brave", 2012),
        movie("Sleeping Beauty", 1959),
        movie("Raya and the Last Dragon", 2021),
        movie("Frozen II", 2019)
    )

    private val VILLAINS = listOf(
        movie("The Empire Strikes Back", 1980),   // Darth Vader
        movie("Avengers: Infinity War", 2018),    // Thanos
        movie("Maleficent", 2014),                // Maleficent
        movie("The Lion King", 1994),             // Scar
        movie("Cruella", 2021),                   // Cruella de Vil
        series("Loki", 2021),                     // Loki
        movie("Avengers: Endgame", 2019),
        movie("Sleeping Beauty", 1959),
        movie("One Hundred and One Dalmatians", 1961),
        movie("Hercules", 1997),
        movie("Revenge of the Sith", 2005),
        movie("Maleficent: Mistress of Evil", 2019)
    )

    private val STAR_WARS_MOVIES = listOf(
        movie("Star Wars: Episode I - The Phantom Menace", 1999),
        movie("Star Wars: Episode II - Attack of the Clones", 2002),
        movie("Star Wars: Episode III - Revenge of the Sith", 2005),
        movie("Solo: A Star Wars Story", 2018),
        movie("Rogue One: A Star Wars Story", 2016),
        movie("Star Wars", 1977),
        movie("The Empire Strikes Back", 1980),
        movie("Return of the Jedi", 1983),
        movie("Star Wars: The Force Awakens", 2015),
        movie("Star Wars: The Last Jedi", 2017),
        movie("Star Wars: The Rise of Skywalker", 2019)
    )

    private val STAR_WARS_SERIES = listOf(
        series("The Mandalorian", 2019),
        series("Andor", 2022),
        series("Ahsoka", 2023),
        series("Obi-Wan Kenobi", 2022),
        series("The Book of Boba Fett", 2021),
        series("Skeleton Crew", 2024),
        series("The Acolyte", 2024)
    )

    private val STAR_WARS_ANIMATED = listOf(
        series("Star Wars: The Clone Wars", 2008),
        series("Star Wars Rebels", 2014),
        series("Star Wars: The Bad Batch", 2021),
        series("Star Wars: Tales of the Jedi", 2022),
        series("Star Wars: Visions", 2021),
        series("Star Wars: Tales of the Empire", 2024),
        series("Star Wars Resistance", 2018),
        movie("Star Wars: The Clone Wars", 2008)
    )

    private val STAR_WARS_FEATURED = listOf(
        series("The Mandalorian", 2019),
        series("Andor", 2022),
        series("Ahsoka", 2023),
        movie("Star Wars", 1977),
        movie("Rogue One: A Star Wars Story", 2016),
        series("Star Wars: The Clone Wars", 2008),
        movie("The Empire Strikes Back", 1980),
        series("Obi-Wan Kenobi", 2022)
    )

    private val PIXAR_FEATURED = listOf(
        movie("Toy Story", 1995),
        movie("Cars", 2006),
        movie("Inside Out", 2015)
    )

    private val TOY_STORY = listOf(
        movie("Toy Story", 1995),
        movie("Toy Story 2", 1999),
        movie("Toy Story 3", 2010),
        movie("Toy Story 4", 2019),
        movie("Lightyear", 2022),
        series("Toy Story Toons", 2011)
    )

    private val CARS = listOf(
        movie("Cars", 2006),
        movie("Cars 2", 2011),
        movie("Cars 3", 2017),
        series("Cars on the Road", 2022),
        series("Cars Toons", 2008)
    )

    private val INSIDE_OUT = listOf(
        movie("Inside Out", 2015),
        movie("Inside Out 2", 2024),
        series("Dream Productions", 2024)
    )

    private val KIDS_FEATURED = listOf(
        series("Bluey", 2018),
        series("Mickey Mouse Clubhouse", 2006),
        series("Mickey Mouse Funhouse", 2021),
        series("Mickey Mouse", 2013)
    )

    // ---- Disney brand page (mirrors the row layout of Disney+'s own "Disney" page) ----

    private val DISNEY_FEATURED = listOf(
        movie("Zootopia 2", 2025),
        movie("Lilo & Stitch", 2025),
        movie("Moana 2", 2024),
        movie("Mufasa: The Lion King", 2024),
        movie("Freakier Friday", 2025),
        movie("Snow White", 2025),
        movie("Wish", 2023),
        movie("Encanto", 2021),
        movie("Frozen II", 2019),
        movie("Moana", 2016)
    )

    private val DISNEYNATURE = listOf(
        movie("Earth", 2007),
        movie("Oceans", 2009),
        movie("African Cats", 2011),
        movie("Chimpanzee", 2012),
        movie("Bears", 2014),
        movie("Monkey Kingdom", 2015),
        movie("Born in China", 2016),
        movie("Penguins", 2019),
        movie("Dolphin Reef", 2018),
        movie("Elephant", 2020),
        movie("Polar Bear", 2022),
        movie("Tiger", 2024),
        movie("Wings of Life", 2011),
        movie("The Crimson Wing: Mystery of the Flamingos", 2008)
    )

    private val PLAYLIST_HALLOWEEN = listOf(
        movie("Hocus Pocus", 1993),
        movie("Hocus Pocus 2", 2022),
        movie("Halloweentown", 1998),
        movie("The Nightmare Before Christmas", 1993),
        movie("Twitches", 2005),
        movie("Haunted Mansion", 2023),
        movie("The Haunted Mansion", 2003),
        movie("Frankenweenie", 2012),
        movie("Under Wraps", 2021),
        movie("Return to Halloweentown", 2006),
        movie("The Legend of Sleepy Hollow", 1949)
    )

    private val PLAYLIST_HOLIDAY = listOf(
        movie("The Santa Clause", 1994),
        series("The Santa Clauses", 2022),
        movie("Noelle", 2019),
        movie("Home Alone", 1990),
        movie("The Muppet Christmas Carol", 1992),
        movie("A Christmas Carol", 2009),
        movie("Mickey's Once Upon a Christmas", 1999),
        movie("Olaf's Frozen Adventure", 2017),
        movie("Prep & Landing", 2009),
        movie("Miracle on 34th Street", 1947),
        movie("The Nightmare Before Christmas", 1993)
    )

    private val PLAYLIST_FEEL_GOOD = listOf(
        movie("Encanto", 2021),
        movie("Zootopia", 2016),
        movie("Big Hero 6", 2014),
        movie("Enchanted", 2007),
        movie("Freaky Friday", 2003),
        movie("The Princess Diaries", 2001),
        movie("Cool Runnings", 1993),
        movie("Remember the Titans", 2000),
        movie("The Parent Trap", 1998),
        movie("High School Musical", 2006),
        movie("Mary Poppins", 1964)
    )

    /** The Disney page's "Playlists" row follows the season: Halloween in Sep–Oct, holidays in Nov–Dec. */
    private val seasonalPlaylist: Pair<Int, List<CuratedTitle>> = when (java.time.LocalDate.now().monthValue) {
        9, 10 -> R.string.disney_row_playlist_halloween to PLAYLIST_HALLOWEEN
        11, 12 -> R.string.disney_row_playlist_holiday to PLAYLIST_HOLIDAY
        else -> R.string.disney_row_playlist_feel_good to PLAYLIST_FEEL_GOOD
    }

    private val LILO_AND_STITCH = listOf(
        movie("Lilo & Stitch", 2025),
        movie("Lilo & Stitch", 2002),
        movie("Stitch! The Movie", 2003),
        movie("Lilo & Stitch 2: Stitch Has a Glitch", 2005),
        movie("Leroy & Stitch", 2006),
        series("Lilo & Stitch: The Series", 2003)
    )

    private val DISNEY_VILLAINS = listOf(
        movie("Maleficent", 2014),
        movie("Cruella", 2021),
        movie("The Lion King", 1994),
        movie("Sleeping Beauty", 1959),
        movie("The Little Mermaid", 1989),
        movie("Aladdin", 1992),
        movie("Hercules", 1997),
        movie("One Hundred and One Dalmatians", 1961),
        movie("Snow White and the Seven Dwarfs", 1937),
        movie("Tangled", 2010),
        movie("Descendants", 2015),
        movie("Maleficent: Mistress of Evil", 2019),
        movie("Mufasa: The Lion King", 2024)
    )

    private val SING_ALONGS = listOf(
        movie("Frozen", 2013),
        movie("Moana", 2016),
        movie("Encanto", 2021),
        movie("Frozen II", 2019),
        movie("The Little Mermaid", 1989),
        movie("Beauty and the Beast", 1991),
        movie("Aladdin", 1992),
        movie("The Lion King", 1994),
        movie("Tangled", 2010),
        movie("Wish", 2023),
        movie("Mary Poppins", 1964),
        movie("High School Musical", 2006),
        movie("Descendants", 2015)
    )

    private val FROZEN = listOf(
        movie("Frozen", 2013),
        movie("Frozen II", 2019),
        movie("Frozen Fever", 2015),
        movie("Olaf's Frozen Adventure", 2017),
        movie("Once Upon a Snowman", 2020),
        series("Olaf Presents", 2021),
        series("Into the Unknown: Making Frozen II", 2020)
    )

    private val CHARACTERS = listOf(
        series("Mickey Mouse Clubhouse", 2006),
        movie("The Many Adventures of Winnie the Pooh", 1977),
        movie("Lilo & Stitch", 2002),
        movie("A Goofy Movie", 1995),
        series("DuckTales", 2017),
        movie("Big Hero 6", 2014),
        movie("Wreck-It Ralph", 2012),
        movie("Zootopia", 2016),
        movie("Moana", 2016),
        movie("Frozen", 2013),
        movie("The Lion King", 1994),
        movie("Encanto", 2021)
    )

    private val DISNEY_PLUS_ORIGINAL_MOVIES = listOf(
        movie("Hocus Pocus 2", 2022),
        movie("Pinocchio", 2022),
        movie("Disenchanted", 2022),
        movie("Peter Pan & Wendy", 2023),
        movie("Lady and the Tramp", 2019),
        movie("Noelle", 2019),
        movie("Togo", 2019),
        movie("Descendants: The Rise of Red", 2024),
        movie("Zombies", 2018),
        movie("Once Upon a Studio", 2023)
    )

    private val MUSIC_TO_OUR_EARS = listOf(
        movie("Hamilton", 2020),
        movie("Taylor Swift: The Eras Tour", 2023),
        series("The Beatles: Get Back", 2021),
        movie("Elton John: Never Too Late", 2024),
        movie("Black Is King", 2020),
        movie("Billie Eilish: Happier Than Ever, A Love Letter to Los Angeles", 2021),
        movie("Encanto at the Hollywood Bowl", 2022),
        movie("Fantasia", 1940),
        movie("Fantasia 2000", 1999),
        movie("Hannah Montana: The Movie", 2009)
    )

    private val MICKEY_AND_FRIENDS = listOf(
        series("Mickey Mouse Clubhouse", 2006),
        series("Mickey Mouse Funhouse", 2021),
        series("Mickey Mouse", 2013),
        series("The Wonderful World of Mickey Mouse", 2020),
        series("Mickey and the Roadster Racers", 2017),
        series("DuckTales", 2017),
        movie("A Goofy Movie", 1995),
        movie("Mickey's Christmas Carol", 1983),
        movie("Mickey, Donald, Goofy: The Three Musketeers", 2004),
        movie("Mickey: The Story of a Mouse", 2022),
        movie("Chip 'n Dale: Rescue Rangers", 2022)
    )

    // ---- Collection pages (Disney page -> Collections row) ----

    private val WDAS_FEATURED = listOf(
        movie("Zootopia 2", 2025),
        movie("Moana 2", 2024),
        movie("Wish", 2023),
        movie("Encanto", 2021),
        movie("Raya and the Last Dragon", 2021),
        movie("Frozen II", 2019),
        movie("Ralph Breaks the Internet", 2018),
        movie("Zootopia", 2016),
        movie("Moana", 2016),
        movie("Big Hero 6", 2014)
    )

    private val DISNEY_JR_FEATURED = listOf(
        series("Bluey", 2018),
        series("Spidey and His Amazing Friends", 2021),
        series("Mickey Mouse Clubhouse", 2006),
        series("Sofia the First", 2013),
        series("Doc McStuffins", 2012),
        series("Puppy Dog Pals", 2017),
        series("Vampirina", 2017),
        series("SuperKitties", 2023),
        series("Pupstruction", 2023),
        series("Mickey Mouse Funhouse", 2021),
        series("Firebuds", 2022)
    )

    private val DISNEY_CHANNEL_FEATURED = listOf(
        series("Hannah Montana", 2006),
        series("Wizards of Waverly Place", 2007),
        series("Phineas and Ferb", 2007),
        series("Gravity Falls", 2012),
        series("That's So Raven", 2003),
        series("The Suite Life of Zack & Cody", 2005),
        series("Good Luck Charlie", 2010),
        series("Austin & Ally", 2011),
        series("Lizzie McGuire", 2001),
        series("Amphibia", 2019),
        series("The Owl House", 2020),
        series("Kim Possible", 2002)
    )

    private val DISNEY_XD_FEATURED = listOf(
        series("Star vs. the Forces of Evil", 2015),
        series("DuckTales", 2017),
        series("Kickin' It", 2011),
        series("Lab Rats", 2012),
        series("Kick Buttowski: Suburban Daredevil", 2010),
        series("Pair of Kings", 2010),
        series("Motorcity", 2012),
        series("Tron: Uprising", 2012),
        series("Big City Greens", 2018),
        series("Milo Murphy's Law", 2016)
    )

    private val DCOM_FEATURED = listOf(
        movie("High School Musical", 2006),
        movie("Descendants", 2015),
        movie("Camp Rock", 2008),
        movie("Zombies", 2018),
        movie("Descendants: The Rise of Red", 2024),
        movie("High School Musical 2", 2007),
        movie("Halloweentown", 1998),
        movie("The Cheetah Girls", 2003),
        movie("Teen Beach Movie", 2013),
        movie("Lemonade Mouth", 2011),
        movie("Descendants 2", 2017),
        movie("Descendants 3", 2019)
    )

    private val DCOM_CLASSICS = listOf(
        movie("Smart House", 1999),
        movie("Zenon: Girl of the 21st Century", 1999),
        movie("Halloweentown", 1998),
        movie("Twitches", 2005),
        movie("Cadet Kelly", 2002),
        movie("Jump In!", 2007),
        movie("Read It and Weep", 2006),
        movie("Life-Size", 2000),
        movie("Motocrossed", 2001),
        movie("Johnny Tsunami", 1999),
        movie("Brink!", 1998),
        movie("Princess Protection Program", 2009),
        movie("Starstruck", 2010),
        movie("Den Brother", 2010)
    )

    private val DCOM_MUSICALS = listOf(
        movie("High School Musical", 2006),
        movie("High School Musical 2", 2007),
        movie("Camp Rock", 2008),
        movie("Camp Rock 2: The Final Jam", 2010),
        movie("Descendants", 2015),
        movie("Zombies", 2018),
        movie("Zombies 2", 2020),
        movie("Teen Beach Movie", 2013),
        movie("Teen Beach 2", 2015),
        movie("Lemonade Mouth", 2011),
        movie("The Cheetah Girls 2", 2006),
        movie("Descendants: The Rise of Red", 2024)
    )

    /**
     * Titles whose backdrops make up each collection tile's artwork (a strip of 3-4 images, like the
     * Disney+ collection cards). Only the TMDB backdrops of these titles are used.
     */
    fun collectionArt(hub: DisneyPlusHub): List<CuratedTitle> = when (hub) {
        DisneyPlusHub.COL_WDAS -> listOf(movie("Fantasia", 1940), movie("Moana", 2016), movie("Lilo & Stitch", 2002), movie("The Little Mermaid", 1989))
        DisneyPlusHub.COL_DISNEY_JR -> listOf(series("Spidey and His Amazing Friends", 2021), series("Sofia the First", 2013), series("Mickey Mouse Clubhouse", 2006))
        DisneyPlusHub.COL_DISNEY_CHANNEL -> listOf(series("Hannah Montana", 2006), series("Wizards of Waverly Place", 2007), series("Gravity Falls", 2012))
        DisneyPlusHub.COL_DISNEY_XD -> listOf(series("Star vs. the Forces of Evil", 2015), series("DuckTales", 2017), series("Kickin' It", 2011))
        DisneyPlusHub.COL_DISNEYNATURE -> listOf(movie("Polar Bear", 2022), movie("Tiger", 2024), movie("Dolphin Reef", 2018))
        DisneyPlusHub.COL_DCOM -> listOf(movie("High School Musical", 2006), movie("Descendants", 2015), movie("Zombies", 2018))
        else -> emptyList()
    }

    // ---- Shared row building blocks ----

    private fun onDisneyPlus(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        genres: String? = null,
        voteCountGte: Int? = null,
        keywordQuery: String? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        providers = DisneyPlusIds.PROVIDER_DISNEY_PLUS,
        genres = genres,
        voteCountGte = voteCountGte,
        keywordQuery = keywordQuery,
        releaseDateLte = releaseDateLte
    )

    private fun tvNetwork(networks: String, sortBy: String = POPULAR, genres: String? = null, voteCountGte: Int? = null) =
        DisneyRowSource.Discover(mediaType = TV, sortBy = sortBy, networks = networks, genres = genres, voteCountGte = voteCountGte)

    private fun company(
        mediaType: TmdbCollectionMediaType,
        companies: String,
        sortBy: String = POPULAR,
        voteCountGte: Int? = null,
        runtimeGte: Int? = null,
        runtimeLte: Int? = null,
        genres: String? = null,
        withoutGenres: String? = null,
        releaseDateLte: String? = null,
        releaseDateGte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        companies = companies,
        voteCountGte = voteCountGte,
        runtimeGte = runtimeGte,
        runtimeLte = runtimeLte,
        genres = genres,
        withoutGenres = withoutGenres,
        releaseDateLte = releaseDateLte,
        releaseDateGte = releaseDateGte
    )

    private fun curated(titles: List<CuratedTitle>) = DisneyRowSource.Curated(titles)

    // ---- Rows reused across pages ----

    private val rowClassics = DisneyRowSpec("classics", R.string.disney_row_classics, listOf(curated(CLASSICS)))
    private val rowPrincess = DisneyRowSpec("princess", R.string.disney_row_princess, listOf(curated(PRINCESSES)))
    private val rowVillains = DisneyRowSpec("villains", R.string.disney_row_villains, listOf(curated(VILLAINS)))
    private val rowDisneyAnimation = DisneyRowSpec(
        "disney_animation", R.string.disney_row_disney_animation,
        listOf(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_ANIMATION, sortBy = MOST_VOTED, runtimeGte = 60))
    )
    private val rowOriginals = DisneyRowSpec(
        "originals", R.string.disney_row_originals,
        listOf(
            tvNetwork(DisneyPlusIds.NET_DISNEY_PLUS),
            tvNetwork(DisneyPlusIds.NET_DISNEY_PLUS, genres = DisneyPlusIds.G_DOCUMENTARY)
        )
    )
    private val rowKids = DisneyRowSpec(
        "kids", R.string.disney_row_kids,
        listOf(
            curated(KIDS_FEATURED),
            tvNetwork("${DisneyPlusIds.NET_DISNEY_JUNIOR}|${DisneyPlusIds.NET_DISNEY_CHANNEL}"),
            onDisneyPlus(MOVIE, genres = "${DisneyPlusIds.G_ANIMATION},${DisneyPlusIds.G_FAMILY}", voteCountGte = 100)
        )
    )
    private val rowShorts = DisneyRowSpec(
        "shorts", R.string.disney_row_shorts,
        listOf(
            company(MOVIE, "${DisneyPlusIds.CO_PIXAR}|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}", runtimeLte = 30, voteCountGte = 20),
            company(MOVIE, "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|${DisneyPlusIds.CO_MARVEL_STUDIOS}|${DisneyPlusIds.CO_LUCASFILM}", runtimeLte = 30, voteCountGte = 10)
        )
    )

    // ---- Pages ----

    private val main = DisneyHubSpec(
        hub = DisneyPlusHub.MAIN,
        heroRowId = "trending",
        rows = listOf(
            DisneyRowSpec("continue_watching", R.string.disney_row_continue_watching, kind = DisneyRowKind.CONTINUE_WATCHING),
            DisneyRowSpec("recommended", R.string.disney_row_recommended, kind = DisneyRowKind.RECOMMENDED),
            DisneyRowSpec(
                "new", R.string.disney_row_new,
                listOf(
                    onDisneyPlus(MOVIE, sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY),
                    onDisneyPlus(TV, sortBy = RECENT_TV, voteCountGte = 5, releaseDateLte = TODAY)
                )
            ),
            DisneyRowSpec(
                "trending", R.string.disney_row_trending,
                listOf(onDisneyPlus(MOVIE), onDisneyPlus(TV))
            ),
            rowOriginals,
            rowClassics,
            DisneyRowSpec(
                "marvel", R.string.disney_brand_marvel,
                listOf(
                    company(MOVIE, DisneyPlusIds.CO_MARVEL_STUDIOS, voteCountGte = 100),
                    company(TV, DisneyPlusIds.CO_MARVEL_STUDIOS),
                    company(TV, DisneyPlusIds.CO_MARVEL_ANIMATION)
                )
            ),
            DisneyRowSpec(
                "star_wars", R.string.disney_brand_star_wars,
                listOf(curated(STAR_WARS_FEATURED), curated(STAR_WARS_MOVIES), curated(STAR_WARS_ANIMATED))
            ),
            DisneyRowSpec(
                "pixar", R.string.disney_brand_pixar,
                listOf(curated(PIXAR_FEATURED), company(MOVIE, DisneyPlusIds.CO_PIXAR, runtimeGte = 60, voteCountGte = 50))
            ),
            rowDisneyAnimation,
            DisneyRowSpec(
                "hulu", R.string.disney_brand_hulu,
                listOf(
                    tvNetwork(DisneyPlusIds.NET_HULU),
                    tvNetwork(DisneyPlusIds.NET_FX),
                    DisneyRowSource.Discover(MOVIE, providers = DisneyPlusIds.PROVIDER_HULU, voteCountGte = 100),
                    tvNetwork(DisneyPlusIds.NET_ABC),
                    tvNetwork(DisneyPlusIds.NET_FREEFORM)
                )
            ),
            DisneyRowSpec(
                "nat_geo", R.string.disney_brand_nat_geo,
                listOf(
                    tvNetwork(DisneyPlusIds.NET_NAT_GEO),
                    company(MOVIE, DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC)
                )
            ),
            rowKids,
            DisneyRowSpec(
                "family", R.string.disney_row_family,
                listOf(
                    onDisneyPlus(MOVIE, sortBy = MOST_VOTED, genres = DisneyPlusIds.G_FAMILY),
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_FAMILY, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "action", R.string.disney_row_action,
                listOf(
                    onDisneyPlus(MOVIE, genres = "${DisneyPlusIds.G_ACTION}|${DisneyPlusIds.G_ADVENTURE}", voteCountGte = 100),
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_TV_ACTION_ADVENTURE, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "comedy", R.string.disney_row_comedy,
                listOf(
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 100),
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 50)
                )
            ),
            DisneyRowSpec("movies", R.string.disney_row_movies, listOf(onDisneyPlus(MOVIE, voteCountGte = 200))),
            DisneyRowSpec("series", R.string.disney_row_series, listOf(onDisneyPlus(TV, voteCountGte = 50))),
            DisneyRowSpec(
                "documentaries", R.string.disney_row_documentaries,
                listOf(
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 10),
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 5),
                    company(MOVIE, DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC)
                )
            ),
            rowPrincess,
            rowVillains,
            rowShorts
        )
    )

    private val disney = DisneyHubSpec(
        hub = DisneyPlusHub.DISNEY,
        heroRowId = "d_featured",
        rows = listOf(
            DisneyRowSpec(
                "d_featured", R.string.disney_row_featured,
                listOf(
                    curated(DISNEY_FEATURED),
                    company(MOVIE, "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}", voteCountGte = 300)
                )
            ),
            DisneyRowSpec(
                "d_new_movies", R.string.disney_row_new_short,
                listOf(
                    company(
                        MOVIE, "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}",
                        sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY
                    )
                )
            ),
            DisneyRowSpec(
                "d_collections", R.string.disney_row_collections,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(
                    DisneyPlusHub.COL_WDAS,
                    DisneyPlusHub.COL_DISNEY_JR,
                    DisneyPlusHub.COL_DISNEY_CHANNEL,
                    DisneyPlusHub.COL_DISNEY_XD,
                    DisneyPlusHub.COL_DISNEYNATURE,
                    DisneyPlusHub.COL_DCOM
                )
            ),
            DisneyRowSpec(
                "d_throwbacks", R.string.disney_row_throwbacks,
                listOf(
                    company(
                        MOVIE, "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}",
                        sortBy = MOST_VOTED, releaseDateGte = "1985-01-01", releaseDateLte = "2009-12-31"
                    ),
                    DisneyRowSource.Discover(
                        TV, sortBy = MOST_VOTED, networks = DisneyPlusIds.NET_DISNEY_CHANNEL,
                        releaseDateGte = "1997-01-01", releaseDateLte = "2012-12-31"
                    )
                )
            ),
            DisneyRowSpec("d_nature", R.string.disney_row_disneynature, listOf(curated(DISNEYNATURE))),
            DisneyRowSpec("d_playlists", seasonalPlaylist.first, listOf(curated(seasonalPlaylist.second))),
            DisneyRowSpec("d_princesses", R.string.disney_row_princesses, listOf(curated(PRINCESSES))),
            DisneyRowSpec(
                "d_wdas", R.string.disney_row_wdas,
                listOf(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_ANIMATION, sortBy = RECENT, runtimeGte = 60, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "d_live_action", R.string.disney_row_live_action_movies,
                listOf(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_PICTURES, withoutGenres = DisneyPlusIds.G_ANIMATION, voteCountGte = 200))
            ),
            DisneyRowSpec("d_lilo_stitch", R.string.disney_row_lilo_stitch, listOf(curated(LILO_AND_STITCH))),
            DisneyRowSpec("d_villains", R.string.disney_row_villains, listOf(curated(DISNEY_VILLAINS))),
            DisneyRowSpec("d_sing_alongs", R.string.disney_row_sing_alongs, listOf(curated(SING_ALONGS))),
            DisneyRowSpec("d_frozen", R.string.disney_row_frozen, listOf(curated(FROZEN))),
            DisneyRowSpec("d_characters", R.string.disney_row_characters, listOf(curated(CHARACTERS))),
            DisneyRowSpec(
                "d_vintage", R.string.disney_row_vintage,
                listOf(
                    company(
                        MOVIE, "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}",
                        sortBy = MOST_VOTED, releaseDateLte = "1979-12-31"
                    )
                )
            ),
            DisneyRowSpec(
                "d_originals", R.string.disney_row_originals_short,
                listOf(
                    curated(DISNEY_PLUS_ORIGINAL_MOVIES),
                    DisneyRowSource.Discover(
                        TV, networks = DisneyPlusIds.NET_DISNEY_PLUS,
                        companies = "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|3475|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}"
                    )
                )
            ),
            DisneyRowSpec("d_music", R.string.disney_row_music_to_our_ears, listOf(curated(MUSIC_TO_OUR_EARS))),
            DisneyRowSpec("d_disney_channel", R.string.disney_row_disney_channel_series, listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_CHANNEL))),
            DisneyRowSpec(
                "d_new_series", R.string.disney_row_new_short,
                listOf(
                    DisneyRowSource.Discover(
                        TV, sortBy = RECENT_TV,
                        networks = "${DisneyPlusIds.NET_DISNEY_CHANNEL}|${DisneyPlusIds.NET_DISNEY_JUNIOR}",
                        releaseDateLte = TODAY, voteCountGte = 1
                    ),
                    DisneyRowSource.Discover(
                        TV, sortBy = RECENT_TV, networks = DisneyPlusIds.NET_DISNEY_PLUS,
                        companies = "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|3475",
                        releaseDateLte = TODAY
                    )
                )
            ),
            DisneyRowSpec(
                "d_90s", R.string.disney_row_made_in_90s,
                listOf(
                    company(
                        MOVIE, "${DisneyPlusIds.CO_WALT_DISNEY_PICTURES}|${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}",
                        sortBy = MOST_VOTED, releaseDateGte = "1990-01-01", releaseDateLte = "1999-12-31"
                    ),
                    DisneyRowSource.Discover(
                        TV, sortBy = MOST_VOTED, companies = "3475",
                        releaseDateGte = "1990-01-01", releaseDateLte = "1999-12-31"
                    )
                )
            ),
            DisneyRowSpec("d_mickey", R.string.disney_row_mickey_and_friends, listOf(curated(MICKEY_AND_FRIENDS)))
        )
    )

    private val pixar = DisneyHubSpec(
        hub = DisneyPlusHub.PIXAR,
        heroRowId = "pixar_movies",
        rows = listOf(
            DisneyRowSpec(
                "pixar_movies", R.string.disney_row_pixar_movies,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, runtimeGte = 60, voteCountGte = 50))
            ),
            DisneyRowSpec(
                "pixar_originals", R.string.disney_row_pixar_originals,
                listOf(company(TV, DisneyPlusIds.CO_PIXAR))
            ),
            DisneyRowSpec(
                "pixar_shorts", R.string.disney_row_pixar_shorts,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, runtimeLte = 30, voteCountGte = 10))
            ),
            DisneyRowSpec("toy_story", R.string.disney_row_toy_story, listOf(curated(TOY_STORY))),
            DisneyRowSpec("cars", R.string.disney_row_cars, listOf(curated(CARS))),
            DisneyRowSpec("inside_out", R.string.disney_row_inside_out, listOf(curated(INSIDE_OUT))),
            DisneyRowSpec(
                "pixar_top_rated", R.string.disney_row_top_rated,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = TOP_RATED, runtimeGte = 60, voteCountGte = 1000))
            ),
            DisneyRowSpec(
                "pixar_newest", R.string.disney_row_newest,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = RECENT, runtimeGte = 60, releaseDateLte = TODAY))
            )
        )
    )

    private val marvel = DisneyHubSpec(
        hub = DisneyPlusHub.MARVEL,
        heroRowId = "marvel_popular",
        rows = listOf(
            DisneyRowSpec(
                "marvel_popular", R.string.disney_row_marvel_studios,
                listOf(company(MOVIE, DisneyPlusIds.CO_MARVEL_STUDIOS, voteCountGte = 200), company(TV, DisneyPlusIds.CO_MARVEL_STUDIOS))
            ),
            DisneyRowSpec(
                "mcu_movies", R.string.disney_row_mcu_movies,
                listOf(company(MOVIE, DisneyPlusIds.CO_MARVEL_STUDIOS, sortBy = RELEASE_ORDER, voteCountGte = 200, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("marvel_series", R.string.disney_row_marvel_series, listOf(company(TV, DisneyPlusIds.CO_MARVEL_STUDIOS))),
            DisneyRowSpec(
                "marvel_animated", R.string.disney_row_marvel_animated,
                listOf(company(TV, DisneyPlusIds.CO_MARVEL_ANIMATION), company(MOVIE, DisneyPlusIds.CO_MARVEL_ANIMATION))
            ),
            DisneyRowSpec(
                "marvel_top_rated", R.string.disney_row_top_rated,
                listOf(company(MOVIE, DisneyPlusIds.CO_MARVEL_STUDIOS, sortBy = TOP_RATED, voteCountGte = 2000))
            )
        )
    )

    private val starWars = DisneyHubSpec(
        hub = DisneyPlusHub.STAR_WARS,
        heroRowId = "sw_featured",
        rows = listOf(
            DisneyRowSpec("sw_featured", R.string.disney_row_featured, listOf(curated(STAR_WARS_FEATURED))),
            DisneyRowSpec("sw_movies", R.string.disney_row_sw_movies, listOf(curated(STAR_WARS_MOVIES))),
            DisneyRowSpec("sw_series", R.string.disney_row_sw_series, listOf(curated(STAR_WARS_SERIES))),
            DisneyRowSpec("sw_animated", R.string.disney_row_sw_animated, listOf(curated(STAR_WARS_ANIMATED))),
            DisneyRowSpec(
                "lucasfilm", R.string.disney_row_more_lucasfilm,
                listOf(company(TV, DisneyPlusIds.CO_LUCASFILM), company(MOVIE, DisneyPlusIds.CO_LUCASFILM, voteCountGte = 100))
            )
        )
    )

    private val natGeo = DisneyHubSpec(
        hub = DisneyPlusHub.NAT_GEO,
        heroRowId = "natgeo_series",
        rows = listOf(
            DisneyRowSpec("natgeo_series", R.string.disney_row_natgeo_series, listOf(tvNetwork(DisneyPlusIds.NET_NAT_GEO))),
            DisneyRowSpec(
                "natgeo_docs", R.string.disney_row_documentaries,
                listOf(company(MOVIE, DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC), onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 10))
            ),
            DisneyRowSpec(
                "natgeo_nature", R.string.disney_row_nature,
                listOf(
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature"),
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature")
                )
            ),
            DisneyRowSpec(
                "natgeo_science", R.string.disney_row_science,
                listOf(
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "science"),
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "science")
                )
            ),
            DisneyRowSpec(
                "natgeo_history", R.string.disney_row_history,
                listOf(
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "history"),
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "history")
                )
            ),
            DisneyRowSpec(
                "natgeo_travel", R.string.disney_row_travel,
                listOf(
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "travel"),
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "travel")
                )
            ),
            DisneyRowSpec(
                "natgeo_top_rated", R.string.disney_row_top_rated,
                listOf(tvNetwork(DisneyPlusIds.NET_NAT_GEO, sortBy = TOP_RATED, voteCountGte = 20))
            )
        )
    )

    private val hulu = DisneyHubSpec(
        hub = DisneyPlusHub.HULU,
        heroRowId = "hulu_originals",
        rows = listOf(
            DisneyRowSpec("hulu_originals", R.string.disney_row_hulu_originals, listOf(tvNetwork(DisneyPlusIds.NET_HULU))),
            DisneyRowSpec(
                "hulu_series", R.string.disney_row_hulu_series,
                listOf(DisneyRowSource.Discover(TV, providers = DisneyPlusIds.PROVIDER_HULU, voteCountGte = 50))
            ),
            DisneyRowSpec(
                "hulu_movies", R.string.disney_row_hulu_movies,
                listOf(DisneyRowSource.Discover(MOVIE, providers = DisneyPlusIds.PROVIDER_HULU, voteCountGte = 100))
            ),
            DisneyRowSpec("fx", R.string.disney_row_fx, listOf(tvNetwork(DisneyPlusIds.NET_FX))),
            DisneyRowSpec("abc", R.string.disney_row_abc, listOf(tvNetwork(DisneyPlusIds.NET_ABC))),
            DisneyRowSpec("freeform", R.string.disney_row_freeform, listOf(tvNetwork(DisneyPlusIds.NET_FREEFORM)))
        )
    )

    private val espn = DisneyHubSpec(
        hub = DisneyPlusHub.ESPN,
        heroRowId = "espn_originals",
        rows = listOf(
            DisneyRowSpec("espn_originals", R.string.disney_row_espn_originals, listOf(tvNetwork(DisneyPlusIds.NET_ESPN))),
            DisneyRowSpec(
                "espn_films", R.string.disney_row_espn_films,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "ESPN Films"))
            ),
            DisneyRowSpec(
                "espn_docuseries", R.string.disney_row_sports_docuseries,
                listOf(onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "sports"))
            ),
            DisneyRowSpec(
                "espn_sports_movies", R.string.disney_row_sports_movies,
                listOf(onDisneyPlus(MOVIE, keywordQuery = "sports"))
            ),
            DisneyRowSpec(
                "espn_top_rated", R.string.disney_row_top_rated,
                listOf(tvNetwork(DisneyPlusIds.NET_ESPN, sortBy = TOP_RATED, voteCountGte = 20))
            )
        )
    )

    private val wdas = "${DisneyPlusIds.CO_WALT_DISNEY_ANIMATION}"

    private val colWdas = DisneyHubSpec(
        hub = DisneyPlusHub.COL_WDAS,
        heroRowId = "wdas_featured",
        rows = listOf(
            DisneyRowSpec("wdas_featured", R.string.disney_row_featured, listOf(curated(WDAS_FEATURED))),
            DisneyRowSpec(
                "wdas_all", R.string.disney_row_all_films_release_order,
                listOf(company(MOVIE, wdas, sortBy = RELEASE_ORDER, runtimeGte = 60, voteCountGte = 50, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("wdas_popular", R.string.disney_row_popular, listOf(company(MOVIE, wdas, runtimeGte = 60, voteCountGte = 200))),
            DisneyRowSpec("wdas_classics", R.string.disney_row_classics, listOf(curated(CLASSICS))),
            DisneyRowSpec("wdas_princesses", R.string.disney_row_princesses, listOf(curated(PRINCESSES))),
            DisneyRowSpec("wdas_shorts", R.string.disney_row_shorts, listOf(company(MOVIE, wdas, runtimeLte = 30, voteCountGte = 10)))
        )
    )

    private val colDisneyJr = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEY_JR,
        heroRowId = "jr_featured",
        rows = listOf(
            DisneyRowSpec("jr_featured", R.string.disney_row_featured, listOf(curated(DISNEY_JR_FEATURED))),
            DisneyRowSpec("jr_popular", R.string.disney_row_popular_shows, listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_JUNIOR))),
            DisneyRowSpec(
                "jr_new", R.string.disney_row_new_short,
                listOf(DisneyRowSource.Discover(TV, sortBy = RECENT_TV, networks = DisneyPlusIds.NET_DISNEY_JUNIOR, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "jr_movies", R.string.disney_row_movies,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Junior"))
            ),
            DisneyRowSpec("jr_mickey", R.string.disney_row_mickey_and_friends, listOf(curated(MICKEY_AND_FRIENDS)))
        )
    )

    private val colDisneyChannel = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEY_CHANNEL,
        heroRowId = "dc_featured",
        rows = listOf(
            DisneyRowSpec("dc_featured", R.string.disney_row_featured, listOf(curated(DISNEY_CHANNEL_FEATURED))),
            DisneyRowSpec("dc_popular", R.string.disney_row_popular_shows, listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_CHANNEL))),
            DisneyRowSpec(
                "dc_new", R.string.disney_row_new_short,
                listOf(DisneyRowSource.Discover(TV, sortBy = RECENT_TV, networks = DisneyPlusIds.NET_DISNEY_CHANNEL, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "dc_classic", R.string.disney_row_classic_series,
                listOf(
                    DisneyRowSource.Discover(
                        TV, sortBy = MOST_VOTED, networks = DisneyPlusIds.NET_DISNEY_CHANNEL, releaseDateLte = "2012-12-31"
                    )
                )
            ),
            DisneyRowSpec("dc_dcom", R.string.disney_col_dcom, listOf(curated(DCOM_FEATURED)))
        )
    )

    private val colDisneyXd = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEY_XD,
        heroRowId = "xd_featured",
        rows = listOf(
            DisneyRowSpec("xd_featured", R.string.disney_row_featured, listOf(curated(DISNEY_XD_FEATURED))),
            DisneyRowSpec("xd_popular", R.string.disney_row_popular_shows, listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_XD))),
            DisneyRowSpec(
                "xd_top_rated", R.string.disney_row_top_rated,
                listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_XD, sortBy = TOP_RATED, voteCountGte = 30))
            ),
            DisneyRowSpec(
                "xd_animated", R.string.disney_row_animated_series,
                listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_XD, genres = DisneyPlusIds.G_ANIMATION))
            )
        )
    )

    private val colDisneynature = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEYNATURE,
        heroRowId = "dn_films",
        rows = listOf(
            DisneyRowSpec("dn_films", R.string.disney_row_disneynature_films, listOf(curated(DISNEYNATURE))),
            DisneyRowSpec(
                "dn_more", R.string.disney_row_more_nature,
                listOf(
                    onDisneyPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature"),
                    onDisneyPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature")
                )
            )
        )
    )

    private val colDcom = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DCOM,
        heroRowId = "dcom_featured",
        rows = listOf(
            DisneyRowSpec("dcom_featured", R.string.disney_row_featured, listOf(curated(DCOM_FEATURED))),
            DisneyRowSpec("dcom_musicals", R.string.disney_row_musicals, listOf(curated(DCOM_MUSICALS))),
            DisneyRowSpec("dcom_classics", R.string.disney_row_all_time_favorites, listOf(curated(DCOM_CLASSICS))),
            DisneyRowSpec(
                "dcom_all", R.string.disney_row_more_dcoms,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Channel"))
            )
        )
    )

    // ================================================================== Paramount+

    private val STAR_TREK_SERIES = listOf(
        series("Star Trek: Strange New Worlds", 2022),
        series("Star Trek: Discovery", 2017),
        series("Star Trek: Picard", 2020),
        series("Star Trek: Lower Decks", 2020),
        series("Star Trek: Prodigy", 2021),
        series("Star Trek: The Next Generation", 1987),
        series("Star Trek: Deep Space Nine", 1993),
        series("Star Trek: Voyager", 1995),
        series("Star Trek", 1966),
        series("Star Trek: Enterprise", 2001)
    )

    private val STAR_TREK_MOVIES = listOf(
        movie("Star Trek", 2009),
        movie("Star Trek Into Darkness", 2013),
        movie("Star Trek Beyond", 2016),
        movie("Star Trek II: The Wrath of Khan", 1982),
        movie("Star Trek: First Contact", 1996),
        movie("Star Trek: Section 31", 2025)
    )

    private val TAYLOR_SHERIDAN = listOf(
        series("1883", 2021),
        series("1923", 2022),
        series("Tulsa King", 2022),
        series("Mayor of Kingstown", 2021),
        series("Landman", 2024),
        series("Lioness", 2023)
    )

    private fun onParamountPlus(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        genres: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        providers = ParamountPlusIds.PROVIDERS,
        genres = genres,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte
    )

    /** Series from a TV network, by id ([networks]) and/or found at runtime ([lookup]). */
    private fun net(
        networks: String?,
        lookup: NetworkLookup? = null,
        sortBy: String = POPULAR,
        genres: String? = null,
        withoutGenres: String? = null,
        keywordQuery: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = TV,
        sortBy = sortBy,
        networks = networks,
        networkLookup = lookup,
        genres = genres,
        withoutGenres = withoutGenres,
        keywordQuery = keywordQuery,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte
    )

    private fun moviesFrom(companyName: String) =
        DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = companyName)

    /** The rows every network page starts and ends with: popular, newest... top rated. */
    private fun networkPage(
        hub: DisneyPlusHub,
        prefix: String,
        networks: String?,
        lookup: NetworkLookup? = null,
        middleRows: List<DisneyRowSpec>,
        classicUntil: String? = "2005-12-31",
        topRatedMinVotes: Int = 20
    ) = DisneyHubSpec(
        hub = hub,
        heroRowId = "${prefix}_popular",
        rows = buildList {
            add(DisneyRowSpec("${prefix}_popular", R.string.disney_row_popular_shows, listOf(net(networks, lookup))))
            add(
                DisneyRowSpec(
                    "${prefix}_new", R.string.disney_row_new_short,
                    listOf(net(networks, lookup, sortBy = RECENT_TV, voteCountGte = 1, releaseDateLte = TODAY))
                )
            )
            addAll(middleRows)
            if (classicUntil != null) {
                add(
                    DisneyRowSpec(
                        "${prefix}_classic", R.string.disney_row_classic_series,
                        listOf(net(networks, lookup, sortBy = MOST_VOTED, releaseDateLte = classicUntil))
                    )
                )
            }
            add(
                DisneyRowSpec(
                    "${prefix}_top_rated", R.string.disney_row_top_rated,
                    listOf(net(networks, lookup, sortBy = TOP_RATED, voteCountGte = topRatedMinVotes))
                )
            )
        }
    )

    private val paramountMain = DisneyHubSpec(
        hub = DisneyPlusHub.PARAMOUNT_MAIN,
        heroRowId = "p_trending",
        rows = listOf(
            DisneyRowSpec("continue_watching", R.string.disney_row_continue_watching, kind = DisneyRowKind.CONTINUE_WATCHING),
            DisneyRowSpec("recommended", R.string.disney_row_recommended, kind = DisneyRowKind.RECOMMENDED),
            DisneyRowSpec(
                "p_new", R.string.paramount_row_new,
                listOf(
                    onParamountPlus(MOVIE, sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY),
                    onParamountPlus(TV, sortBy = RECENT_TV, voteCountGte = 5, releaseDateLte = TODAY)
                )
            ),
            DisneyRowSpec("p_trending", R.string.disney_row_trending, listOf(onParamountPlus(MOVIE), onParamountPlus(TV))),
            DisneyRowSpec("p_originals", R.string.paramount_row_originals, listOf(net(ParamountPlusIds.NET_PARAMOUNT_PLUS))),
            DisneyRowSpec("p_sheridan", R.string.paramount_row_sheridan, listOf(curated(TAYLOR_SHERIDAN))),
            DisneyRowSpec("p_cbs", R.string.paramount_brand_cbs, listOf(net(ParamountPlusIds.NET_CBS))),
            DisneyRowSpec("p_star_trek", R.string.paramount_row_star_trek, listOf(curated(STAR_TREK_SERIES), curated(STAR_TREK_MOVIES))),
            DisneyRowSpec(
                "p_paramount_pictures", R.string.paramount_row_paramount_pictures,
                listOf(company(MOVIE, ParamountPlusIds.CO_PARAMOUNT_PICTURES, voteCountGte = 300, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("p_showtime", R.string.paramount_row_showtime, listOf(net(ParamountPlusIds.NET_SHOWTIME))),
            DisneyRowSpec(
                "p_nickelodeon", R.string.paramount_brand_nickelodeon,
                listOf(net(ParamountPlusIds.NET_NICKELODEON), moviesFrom("Nickelodeon Movies"))
            ),
            DisneyRowSpec("p_comedy_central", R.string.paramount_brand_comedy_central, listOf(net(ParamountPlusIds.NET_COMEDY_CENTRAL))),
            DisneyRowSpec("p_mtv", R.string.paramount_brand_mtv, listOf(net(ParamountPlusIds.NET_MTV))),
            DisneyRowSpec("p_bet", R.string.paramount_brand_bet, listOf(net(ParamountPlusIds.NET_BET))),
            DisneyRowSpec("p_smithsonian", R.string.paramount_brand_smithsonian, listOf(net(null, ParamountPlusIds.SMITHSONIAN_CHANNEL))),
            DisneyRowSpec("p_movies", R.string.disney_row_movies, listOf(onParamountPlus(MOVIE, voteCountGte = 200))),
            DisneyRowSpec("p_series", R.string.disney_row_series, listOf(onParamountPlus(TV, voteCountGte = 50))),
            DisneyRowSpec(
                "p_reality", R.string.paramount_row_reality,
                listOf(onParamountPlus(TV, genres = ParamountPlusIds.G_TV_REALITY, voteCountGte = 5))
            ),
            DisneyRowSpec(
                "p_crime", R.string.paramount_row_crime,
                listOf(
                    onParamountPlus(TV, genres = "${ParamountPlusIds.G_CRIME}|${ParamountPlusIds.G_MYSTERY}", voteCountGte = 50),
                    onParamountPlus(MOVIE, genres = "${ParamountPlusIds.G_CRIME}|${ParamountPlusIds.G_MYSTERY}", voteCountGte = 200)
                )
            ),
            DisneyRowSpec(
                "p_comedy", R.string.disney_row_comedy,
                listOf(
                    onParamountPlus(MOVIE, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 100),
                    onParamountPlus(TV, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "p_action", R.string.disney_row_action,
                listOf(
                    onParamountPlus(MOVIE, genres = "${DisneyPlusIds.G_ACTION}|${DisneyPlusIds.G_ADVENTURE}", voteCountGte = 100),
                    onParamountPlus(TV, genres = DisneyPlusIds.G_TV_ACTION_ADVENTURE, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "p_kids", R.string.paramount_row_kids,
                listOf(
                    onParamountPlus(TV, genres = DisneyPlusIds.G_TV_KIDS),
                    onParamountPlus(MOVIE, genres = "${DisneyPlusIds.G_ANIMATION},${DisneyPlusIds.G_FAMILY}", voteCountGte = 100)
                )
            ),
            DisneyRowSpec(
                "p_documentaries", R.string.disney_row_documentaries,
                listOf(
                    onParamountPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 10),
                    onParamountPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 5)
                )
            )
        )
    )

    private val paramountCbs = networkPage(
        hub = DisneyPlusHub.P_CBS,
        prefix = "cbs",
        networks = ParamountPlusIds.NET_CBS,
        middleRows = listOf(
            DisneyRowSpec("cbs_crime", R.string.paramount_row_procedurals, listOf(net(ParamountPlusIds.NET_CBS, genres = ParamountPlusIds.G_CRIME))),
            DisneyRowSpec("cbs_dramas", R.string.paramount_row_dramas, listOf(net(ParamountPlusIds.NET_CBS, genres = ParamountPlusIds.G_DRAMA))),
            DisneyRowSpec("cbs_comedies", R.string.paramount_row_comedies, listOf(net(ParamountPlusIds.NET_CBS, genres = DisneyPlusIds.G_COMEDY))),
            DisneyRowSpec(
                "cbs_reality", R.string.paramount_row_reality_competition,
                listOf(net(ParamountPlusIds.NET_CBS, genres = ParamountPlusIds.G_TV_REALITY))
            )
        ),
        topRatedMinVotes = 50
    )

    private val paramountBet = networkPage(
        hub = DisneyPlusHub.P_BET,
        prefix = "bet",
        networks = ParamountPlusIds.NET_BET,
        middleRows = listOf(
            DisneyRowSpec("bet_dramas", R.string.paramount_row_dramas, listOf(net(ParamountPlusIds.NET_BET, genres = ParamountPlusIds.G_DRAMA))),
            DisneyRowSpec("bet_comedies", R.string.paramount_row_comedies, listOf(net(ParamountPlusIds.NET_BET, genres = DisneyPlusIds.G_COMEDY))),
            DisneyRowSpec("bet_reality", R.string.paramount_row_reality, listOf(net(ParamountPlusIds.NET_BET, genres = ParamountPlusIds.G_TV_REALITY))),
            DisneyRowSpec("bet_movies", R.string.paramount_row_bet_movies, listOf(moviesFrom("BET Films")))
        ),
        classicUntil = null,
        topRatedMinVotes = 10
    )

    private val paramountComedyCentral = networkPage(
        hub = DisneyPlusHub.P_COMEDY_CENTRAL,
        prefix = "cc",
        networks = ParamountPlusIds.NET_COMEDY_CENTRAL,
        middleRows = listOf(
            DisneyRowSpec(
                "cc_animated", R.string.disney_row_animated_series,
                listOf(net(ParamountPlusIds.NET_COMEDY_CENTRAL, genres = DisneyPlusIds.G_ANIMATION))
            ),
            DisneyRowSpec(
                "cc_late_night", R.string.paramount_row_late_night,
                listOf(net(ParamountPlusIds.NET_COMEDY_CENTRAL, genres = ParamountPlusIds.G_TV_TALK))
            ),
            DisneyRowSpec("cc_specials", R.string.paramount_row_stand_up, listOf(moviesFrom("Comedy Central")))
        ),
        classicUntil = "2009-12-31"
    )

    private val paramountMtv = networkPage(
        hub = DisneyPlusHub.P_MTV,
        prefix = "mtv",
        networks = ParamountPlusIds.NET_MTV,
        middleRows = listOf(
            DisneyRowSpec("mtv_reality", R.string.paramount_row_reality, listOf(net(ParamountPlusIds.NET_MTV, genres = ParamountPlusIds.G_TV_REALITY))),
            DisneyRowSpec("mtv_animated", R.string.disney_row_animated_series, listOf(net(ParamountPlusIds.NET_MTV, genres = DisneyPlusIds.G_ANIMATION))),
            DisneyRowSpec("mtv_movies", R.string.paramount_row_mtv_films, listOf(moviesFrom("MTV Films")))
        )
    )

    private val paramountNickelodeon = networkPage(
        hub = DisneyPlusHub.P_NICKELODEON,
        prefix = "nick",
        networks = ParamountPlusIds.NET_NICKELODEON,
        middleRows = listOf(
            DisneyRowSpec("nick_toons", R.string.paramount_row_nicktoons, listOf(net(ParamountPlusIds.NET_NICKELODEON, genres = DisneyPlusIds.G_ANIMATION))),
            DisneyRowSpec(
                "nick_live_action", R.string.paramount_row_live_action,
                listOf(net(ParamountPlusIds.NET_NICKELODEON, withoutGenres = DisneyPlusIds.G_ANIMATION))
            ),
            DisneyRowSpec("nick_jr", R.string.paramount_row_nick_jr, listOf(net(null, ParamountPlusIds.NICK_JR))),
            DisneyRowSpec("nick_movies", R.string.paramount_row_nick_movies, listOf(moviesFrom("Nickelodeon Movies")))
        ),
        topRatedMinVotes = 50
    )

    private val paramountSmithsonian = networkPage(
        hub = DisneyPlusHub.P_SMITHSONIAN,
        prefix = "si",
        networks = null,
        lookup = ParamountPlusIds.SMITHSONIAN_CHANNEL,
        middleRows = listOf(
            DisneyRowSpec(
                "si_history", R.string.disney_row_history,
                listOf(net(null, ParamountPlusIds.SMITHSONIAN_CHANNEL, keywordQuery = "history"))
            ),
            DisneyRowSpec(
                "si_ww2", R.string.paramount_row_world_war_ii,
                listOf(net(null, ParamountPlusIds.SMITHSONIAN_CHANNEL, keywordQuery = "world war ii"))
            ),
            DisneyRowSpec(
                "si_aviation", R.string.paramount_row_aviation,
                listOf(net(null, ParamountPlusIds.SMITHSONIAN_CHANNEL, keywordQuery = "aviation"))
            ),
            DisneyRowSpec(
                "si_nature", R.string.disney_row_nature,
                listOf(net(null, ParamountPlusIds.SMITHSONIAN_CHANNEL, keywordQuery = "nature"))
            ),
            DisneyRowSpec("si_films", R.string.paramount_row_films_specials, listOf(moviesFrom("Smithsonian Channel")))
        ),
        classicUntil = null,
        topRatedMinVotes = 5
    )

    // ================================================================== discovery+

    private val NINETY_DAY = listOf(
        series("90 Day Fiancé", 2014),
        series("90 Day Fiancé: Before the 90 Days", 2017),
        series("90 Day Fiancé: Happily Ever After?", 2016),
        series("90 Day Fiancé: The Other Way", 2019),
        series("90 Day: The Single Life", 2021),
        series("90 Day Fiancé: Love in Paradise", 2020),
        series("90 Day Fiancé: Pillow Talk", 2019),
        series("90 Day: The Last Resort", 2023)
    )

    private fun onDiscoveryPlus(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        genres: String? = null,
        keywordQuery: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        providers = DiscoveryPlusIds.PROVIDERS,
        genres = genres,
        keywordQuery = keywordQuery,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte
    )

    private val discoveryMain = DisneyHubSpec(
        hub = DisneyPlusHub.DISCOVERY_MAIN,
        heroRowId = "dp_trending",
        rows = listOf(
            DisneyRowSpec("continue_watching", R.string.disney_row_continue_watching, kind = DisneyRowKind.CONTINUE_WATCHING),
            DisneyRowSpec("recommended", R.string.disney_row_recommended, kind = DisneyRowKind.RECOMMENDED),
            DisneyRowSpec(
                "dp_new", R.string.discovery_row_new,
                listOf(
                    onDiscoveryPlus(TV, sortBy = RECENT_TV, voteCountGte = 1, releaseDateLte = TODAY),
                    onDiscoveryPlus(MOVIE, sortBy = RECENT, voteCountGte = 5, releaseDateLte = TODAY)
                )
            ),
            DisneyRowSpec("dp_trending", R.string.disney_row_trending, listOf(onDiscoveryPlus(TV), onDiscoveryPlus(MOVIE))),
            DisneyRowSpec("dp_originals", R.string.discovery_brand_originals, listOf(net(null, DiscoveryPlusIds.DISCOVERY_PLUS))),
            DisneyRowSpec("dp_hgtv", R.string.discovery_brand_hgtv, listOf(net(null, DiscoveryPlusIds.HGTV))),
            DisneyRowSpec("dp_food_network", R.string.discovery_brand_food_network, listOf(net(null, DiscoveryPlusIds.FOOD_NETWORK))),
            DisneyRowSpec("dp_tlc", R.string.discovery_brand_tlc, listOf(net(DiscoveryPlusIds.NET_TLC))),
            DisneyRowSpec("dp_id", R.string.discovery_brand_id, listOf(net(DiscoveryPlusIds.NET_ID))),
            DisneyRowSpec("dp_discovery", R.string.discovery_row_discovery, listOf(net(DiscoveryPlusIds.NET_DISCOVERY))),
            DisneyRowSpec("dp_own", R.string.discovery_brand_own, listOf(net(null, DiscoveryPlusIds.OWN))),
            DisneyRowSpec("dp_animal_planet", R.string.discovery_brand_animal_planet, listOf(net(DiscoveryPlusIds.NET_ANIMAL_PLANET))),
            DisneyRowSpec("dp_magnolia", R.string.discovery_brand_magnolia, listOf(net(null, DiscoveryPlusIds.MAGNOLIA))),
            DisneyRowSpec("dp_true_crime", R.string.discovery_row_true_crime, listOf(onDiscoveryPlus(TV, genres = DiscoveryPlusIds.G_CRIME))),
            DisneyRowSpec("dp_reality", R.string.paramount_row_reality, listOf(onDiscoveryPlus(TV, genres = DiscoveryPlusIds.G_TV_REALITY))),
            DisneyRowSpec("dp_home", R.string.discovery_row_home, listOf(onDiscoveryPlus(TV, keywordQuery = "home renovation"))),
            DisneyRowSpec("dp_cooking", R.string.discovery_row_food, listOf(onDiscoveryPlus(TV, keywordQuery = "cooking"))),
            DisneyRowSpec("dp_paranormal", R.string.discovery_row_paranormal, listOf(onDiscoveryPlus(TV, keywordQuery = "paranormal"))),
            DisneyRowSpec(
                "dp_nature", R.string.discovery_row_nature_animals,
                listOf(
                    onDiscoveryPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature"),
                    onDiscoveryPlus(TV, keywordQuery = "animal")
                )
            ),
            DisneyRowSpec(
                "dp_documentaries", R.string.disney_row_documentaries,
                listOf(
                    onDiscoveryPlus(TV, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 5),
                    onDiscoveryPlus(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 5)
                )
            )
        )
    )

    private val discoveryOriginals = networkPage(
        hub = DisneyPlusHub.D_ORIGINALS,
        prefix = "dpo",
        networks = null,
        lookup = DiscoveryPlusIds.DISCOVERY_PLUS,
        middleRows = listOf(
            DisneyRowSpec("dpo_true_crime", R.string.discovery_row_true_crime, listOf(net(null, DiscoveryPlusIds.DISCOVERY_PLUS, genres = DiscoveryPlusIds.G_CRIME))),
            DisneyRowSpec("dpo_paranormal", R.string.discovery_row_paranormal, listOf(net(null, DiscoveryPlusIds.DISCOVERY_PLUS, keywordQuery = "paranormal"))),
            DisneyRowSpec("dpo_reality", R.string.paramount_row_reality, listOf(net(null, DiscoveryPlusIds.DISCOVERY_PLUS, genres = DiscoveryPlusIds.G_TV_REALITY))),
            DisneyRowSpec("dpo_docuseries", R.string.discovery_row_docuseries, listOf(net(null, DiscoveryPlusIds.DISCOVERY_PLUS, genres = DisneyPlusIds.G_DOCUMENTARY)))
        ),
        classicUntil = null,
        topRatedMinVotes = 5
    )

    private val discoveryHgtv = networkPage(
        hub = DisneyPlusHub.D_HGTV,
        prefix = "hgtv",
        networks = null,
        lookup = DiscoveryPlusIds.HGTV,
        middleRows = listOf(
            DisneyRowSpec("hgtv_renovation", R.string.discovery_row_renovation, listOf(net(null, DiscoveryPlusIds.HGTV, keywordQuery = "home renovation"))),
            DisneyRowSpec("hgtv_real_estate", R.string.discovery_row_house_hunting, listOf(net(null, DiscoveryPlusIds.HGTV, keywordQuery = "real estate"))),
            DisneyRowSpec("hgtv_competition", R.string.discovery_row_competitions, listOf(net(null, DiscoveryPlusIds.HGTV, keywordQuery = "competition")))
        ),
        classicUntil = "2012-12-31",
        topRatedMinVotes = 10
    )

    private val discoveryFood = networkPage(
        hub = DisneyPlusHub.D_FOOD_NETWORK,
        prefix = "food",
        networks = null,
        lookup = DiscoveryPlusIds.FOOD_NETWORK,
        middleRows = listOf(
            DisneyRowSpec(
                "food_competitions", R.string.discovery_row_cooking_competitions,
                listOf(net(null, DiscoveryPlusIds.FOOD_NETWORK, keywordQuery = "cooking competition"))
            ),
            DisneyRowSpec("food_baking", R.string.discovery_row_baking, listOf(net(null, DiscoveryPlusIds.FOOD_NETWORK, keywordQuery = "baking"))),
            DisneyRowSpec("food_cooking", R.string.discovery_row_cooking_shows, listOf(net(null, DiscoveryPlusIds.FOOD_NETWORK, keywordQuery = "cooking")))
        ),
        classicUntil = "2012-12-31",
        topRatedMinVotes = 10
    )

    private val discoveryTlc = networkPage(
        hub = DisneyPlusHub.D_TLC,
        prefix = "tlc",
        networks = DiscoveryPlusIds.NET_TLC,
        middleRows = listOf(
            DisneyRowSpec("tlc_90_day", R.string.discovery_row_90_day, listOf(curated(NINETY_DAY))),
            DisneyRowSpec("tlc_family", R.string.discovery_row_family, listOf(net(DiscoveryPlusIds.NET_TLC, keywordQuery = "family"))),
            DisneyRowSpec("tlc_weddings", R.string.discovery_row_weddings, listOf(net(DiscoveryPlusIds.NET_TLC, keywordQuery = "wedding")))
        ),
        classicUntil = "2012-12-31",
        topRatedMinVotes = 10
    )

    private val discoveryId = networkPage(
        hub = DisneyPlusHub.D_ID,
        prefix = "id",
        networks = DiscoveryPlusIds.NET_ID,
        middleRows = listOf(
            DisneyRowSpec("id_murder", R.string.discovery_row_murder, listOf(net(DiscoveryPlusIds.NET_ID, keywordQuery = "murder"))),
            DisneyRowSpec("id_cold_case", R.string.discovery_row_cold_cases, listOf(net(DiscoveryPlusIds.NET_ID, keywordQuery = "cold case"))),
            DisneyRowSpec("id_docuseries", R.string.discovery_row_docuseries, listOf(net(DiscoveryPlusIds.NET_ID, genres = DisneyPlusIds.G_DOCUMENTARY)))
        ),
        classicUntil = null,
        topRatedMinVotes = 10
    )

    private val discoveryOwn = networkPage(
        hub = DisneyPlusHub.D_OWN,
        prefix = "own",
        networks = null,
        lookup = DiscoveryPlusIds.OWN,
        middleRows = listOf(
            DisneyRowSpec("own_dramas", R.string.paramount_row_dramas, listOf(net(null, DiscoveryPlusIds.OWN, genres = DiscoveryPlusIds.G_DRAMA))),
            DisneyRowSpec("own_reality", R.string.paramount_row_reality, listOf(net(null, DiscoveryPlusIds.OWN, genres = DiscoveryPlusIds.G_TV_REALITY))),
            DisneyRowSpec("own_talk", R.string.discovery_row_talk, listOf(net(null, DiscoveryPlusIds.OWN, genres = DiscoveryPlusIds.G_TV_TALK)))
        ),
        classicUntil = null,
        topRatedMinVotes = 10
    )

    private val discoveryAnimalPlanet = networkPage(
        hub = DisneyPlusHub.D_ANIMAL_PLANET,
        prefix = "ap",
        networks = DiscoveryPlusIds.NET_ANIMAL_PLANET,
        middleRows = listOf(
            DisneyRowSpec("ap_wildlife", R.string.discovery_row_wildlife, listOf(net(DiscoveryPlusIds.NET_ANIMAL_PLANET, keywordQuery = "wildlife"))),
            DisneyRowSpec("ap_pets", R.string.discovery_row_pets, listOf(net(DiscoveryPlusIds.NET_ANIMAL_PLANET, keywordQuery = "dog"))),
            DisneyRowSpec("ap_vets", R.string.discovery_row_vets, listOf(net(DiscoveryPlusIds.NET_ANIMAL_PLANET, keywordQuery = "veterinarian")))
        ),
        classicUntil = "2009-12-31",
        topRatedMinVotes = 10
    )

    private val discoveryMagnolia = networkPage(
        hub = DisneyPlusHub.D_MAGNOLIA,
        prefix = "mag",
        networks = null,
        lookup = DiscoveryPlusIds.MAGNOLIA,
        middleRows = listOf(
            DisneyRowSpec("mag_home", R.string.discovery_row_home, listOf(net(null, DiscoveryPlusIds.MAGNOLIA, keywordQuery = "home renovation"))),
            DisneyRowSpec("mag_food", R.string.discovery_row_food, listOf(net(null, DiscoveryPlusIds.MAGNOLIA, keywordQuery = "cooking")))
        ),
        classicUntil = null,
        topRatedMinVotes = 5
    )

    // ================================================================== HBO Max

    private val GAME_OF_THRONES = listOf(
        series("Game of Thrones", 2011),
        series("House of the Dragon", 2022),
        series("A Knight of the Seven Kingdoms", 2026)
    )

    private val WIZARDING_WORLD = listOf(
        movie("Harry Potter and the Philosopher's Stone", 2001),
        movie("Harry Potter and the Chamber of Secrets", 2002),
        movie("Harry Potter and the Prisoner of Azkaban", 2004),
        movie("Harry Potter and the Goblet of Fire", 2005),
        movie("Harry Potter and the Order of the Phoenix", 2007),
        movie("Harry Potter and the Half-Blood Prince", 2009),
        movie("Harry Potter and the Deathly Hallows: Part 1", 2010),
        movie("Harry Potter and the Deathly Hallows: Part 2", 2011),
        movie("Fantastic Beasts and Where to Find Them", 2016),
        movie("Fantastic Beasts: The Crimes of Grindelwald", 2018),
        movie("Fantastic Beasts: The Secrets of Dumbledore", 2022)
    )

    private val LOONEY_TUNES = listOf(
        series("Looney Tunes Cartoons", 2020),
        movie("Space Jam", 1996),
        movie("Space Jam: A New Legacy", 2021),
        series("The Looney Tunes Show", 2011),
        movie("Looney Tunes: Back in Action", 2003),
        movie("The Day the Earth Blew Up: A Looney Tunes Movie", 2024),
        series("Tiny Toons Looniversity", 2023),
        series("Bugs Bunny Builders", 2022),
        series("New Looney Tunes", 2015),
        series("Baby Looney Tunes", 2001)
    )

    private fun onHboMax(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        genres: String? = null,
        keywordQuery: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        providers = HboMaxIds.PROVIDERS,
        genres = genres,
        keywordQuery = keywordQuery,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte
    )

    /** One source per DC company name (DC Comics, DC Entertainment, DC Studios); their results get interleaved. */
    private fun dc(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        genres: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ): List<DisneyRowSource> = HboMaxIds.DC_COMPANIES.map { name ->
        DisneyRowSource.Discover(
            mediaType = mediaType,
            sortBy = sortBy,
            companyQuery = name,
            genres = genres,
            voteCountGte = voteCountGte,
            releaseDateLte = releaseDateLte
        )
    }

    private fun studio(
        mediaType: TmdbCollectionMediaType,
        companyName: String,
        sortBy: String = POPULAR,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null,
        runtimeGte: Int? = null,
        runtimeLte: Int? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        companyQuery = companyName,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte,
        runtimeGte = runtimeGte,
        runtimeLte = runtimeLte
    )

    private fun crunchyroll(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        providerQuery = HboMaxIds.CRUNCHYROLL,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte
    )

    private val hboMaxMain = DisneyHubSpec(
        hub = DisneyPlusHub.HBOMAX_MAIN,
        heroRowId = "hm_trending",
        rows = listOf(
            DisneyRowSpec("continue_watching", R.string.disney_row_continue_watching, kind = DisneyRowKind.CONTINUE_WATCHING),
            DisneyRowSpec("recommended", R.string.disney_row_recommended, kind = DisneyRowKind.RECOMMENDED),
            DisneyRowSpec(
                "hm_new", R.string.hbomax_row_new,
                listOf(
                    onHboMax(MOVIE, sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY),
                    onHboMax(TV, sortBy = RECENT_TV, voteCountGte = 5, releaseDateLte = TODAY)
                )
            ),
            DisneyRowSpec("hm_trending", R.string.disney_row_trending, listOf(onHboMax(MOVIE), onHboMax(TV))),
            DisneyRowSpec("hm_hbo", R.string.hbomax_row_hbo_originals, listOf(net(HboMaxIds.NET_HBO))),
            DisneyRowSpec("hm_max_originals", R.string.hbomax_brand_max_originals, listOf(net(HboMaxIds.NET_HBO_MAX))),
            DisneyRowSpec("hm_got", R.string.hbomax_row_game_of_thrones, listOf(curated(GAME_OF_THRONES))),
            DisneyRowSpec("hm_dc", R.string.hbomax_brand_dc, dc(MOVIE, voteCountGte = 200) + dc(TV)),
            DisneyRowSpec("hm_wizarding", R.string.hbomax_row_wizarding_world, listOf(curated(WIZARDING_WORLD))),
            DisneyRowSpec(
                "hm_wb_pictures", R.string.hbomax_row_wb_pictures,
                listOf(studio(MOVIE, "Warner Bros. Pictures", voteCountGte = 300, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("hm_looney_tunes", R.string.hbomax_brand_looney_tunes, listOf(curated(LOONEY_TUNES))),
            DisneyRowSpec("hm_cartoon_network", R.string.hbomax_row_cartoon_network, listOf(net(HboMaxIds.NET_CARTOON_NETWORK))),
            DisneyRowSpec("hm_adult_swim", R.string.hbomax_row_adult_swim, listOf(net(null, HboMaxIds.ADULT_SWIM))),
            DisneyRowSpec("hm_sesame", R.string.hbomax_brand_sesame_workshop, listOf(studio(TV, HboMaxIds.SESAME_WORKSHOP))),
            DisneyRowSpec(
                "hm_anime", R.string.hbomax_row_anime,
                listOf(onHboMax(TV, genres = DisneyPlusIds.G_ANIMATION, keywordQuery = "anime"))
            ),
            DisneyRowSpec("hm_movies", R.string.disney_row_movies, listOf(onHboMax(MOVIE, voteCountGte = 200))),
            DisneyRowSpec("hm_series", R.string.disney_row_series, listOf(onHboMax(TV, voteCountGte = 50))),
            DisneyRowSpec(
                "hm_drama", R.string.hbomax_row_drama,
                listOf(
                    onHboMax(TV, genres = HboMaxIds.G_DRAMA, voteCountGte = 100),
                    onHboMax(MOVIE, genres = HboMaxIds.G_DRAMA, voteCountGte = 300)
                )
            ),
            DisneyRowSpec(
                "hm_comedy", R.string.disney_row_comedy,
                listOf(
                    onHboMax(MOVIE, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 100),
                    onHboMax(TV, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "hm_action", R.string.disney_row_action,
                listOf(
                    onHboMax(MOVIE, genres = "${DisneyPlusIds.G_ACTION}|${DisneyPlusIds.G_ADVENTURE}", voteCountGte = 100),
                    onHboMax(TV, genres = DisneyPlusIds.G_TV_ACTION_ADVENTURE, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "hm_kids", R.string.paramount_row_kids,
                listOf(
                    onHboMax(TV, genres = DisneyPlusIds.G_TV_KIDS),
                    onHboMax(MOVIE, genres = "${DisneyPlusIds.G_ANIMATION},${DisneyPlusIds.G_FAMILY}", voteCountGte = 100)
                )
            ),
            DisneyRowSpec(
                "hm_documentaries", R.string.disney_row_documentaries,
                listOf(
                    onHboMax(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 10),
                    onHboMax(TV, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 5)
                )
            )
        )
    )

    private val hboMaxHbo = networkPage(
        hub = DisneyPlusHub.H_HBO,
        prefix = "hbo",
        networks = HboMaxIds.NET_HBO,
        middleRows = listOf(
            DisneyRowSpec("hbo_dramas", R.string.paramount_row_dramas, listOf(net(HboMaxIds.NET_HBO, genres = HboMaxIds.G_DRAMA))),
            DisneyRowSpec("hbo_comedies", R.string.paramount_row_comedies, listOf(net(HboMaxIds.NET_HBO, genres = DisneyPlusIds.G_COMEDY))),
            DisneyRowSpec("hbo_docuseries", R.string.discovery_row_docuseries, listOf(net(HboMaxIds.NET_HBO, genres = DisneyPlusIds.G_DOCUMENTARY))),
            DisneyRowSpec("hbo_films", R.string.hbomax_row_hbo_films, listOf(moviesFrom("HBO Films")))
        ),
        classicUntil = "2007-12-31",
        topRatedMinVotes = 200
    )

    private val hboMaxOriginals = networkPage(
        hub = DisneyPlusHub.H_MAX_ORIGINALS,
        prefix = "maxo",
        networks = HboMaxIds.NET_HBO_MAX,
        middleRows = listOf(
            DisneyRowSpec("maxo_dramas", R.string.paramount_row_dramas, listOf(net(HboMaxIds.NET_HBO_MAX, genres = HboMaxIds.G_DRAMA))),
            DisneyRowSpec("maxo_comedies", R.string.paramount_row_comedies, listOf(net(HboMaxIds.NET_HBO_MAX, genres = DisneyPlusIds.G_COMEDY))),
            DisneyRowSpec(
                "maxo_animated", R.string.disney_row_animated_series,
                listOf(net(HboMaxIds.NET_HBO_MAX, genres = DisneyPlusIds.G_ANIMATION))
            ),
            DisneyRowSpec(
                "maxo_docuseries", R.string.discovery_row_docuseries,
                listOf(net(HboMaxIds.NET_HBO_MAX, genres = DisneyPlusIds.G_DOCUMENTARY))
            )
        ),
        classicUntil = null,
        topRatedMinVotes = 50
    )

    private val hboMaxDc = DisneyHubSpec(
        hub = DisneyPlusHub.H_DC,
        heroRowId = "dc_movies",
        rows = listOf(
            DisneyRowSpec("dc_movies", R.string.hbomax_row_dc_movies, dc(MOVIE, voteCountGte = 200)),
            DisneyRowSpec("dc_newest", R.string.disney_row_newest, dc(MOVIE, sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY)),
            DisneyRowSpec("dc_series", R.string.hbomax_row_dc_series, dc(TV)),
            DisneyRowSpec(
                "dc_animated", R.string.hbomax_row_dc_animated,
                dc(MOVIE, sortBy = MOST_VOTED, genres = DisneyPlusIds.G_ANIMATION)
            ),
            DisneyRowSpec(
                "dc_batman", R.string.hbomax_row_batman,
                listOf(
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "batman"),
                    DisneyRowSource.Discover(TV, keywordQuery = "batman")
                )
            ),
            DisneyRowSpec(
                "dc_superman", R.string.hbomax_row_superman,
                listOf(
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "superman"),
                    DisneyRowSource.Discover(TV, keywordQuery = "superman")
                )
            ),
            DisneyRowSpec("dc_top_rated", R.string.disney_row_top_rated, dc(MOVIE, sortBy = TOP_RATED, voteCountGte = 1000))
        )
    )

    private val hboMaxLooneyTunes = DisneyHubSpec(
        hub = DisneyPlusHub.H_LOONEY_TUNES,
        heroRowId = "lt_featured",
        rows = listOf(
            DisneyRowSpec("lt_featured", R.string.disney_row_featured, listOf(curated(LOONEY_TUNES))),
            DisneyRowSpec("lt_series", R.string.disney_row_series, listOf(DisneyRowSource.Discover(TV, keywordQuery = "looney tunes"))),
            DisneyRowSpec(
                "lt_movies", R.string.disney_row_movies,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "looney tunes", runtimeGte = 60))
            ),
            DisneyRowSpec(
                "lt_shorts", R.string.hbomax_row_classic_shorts,
                listOf(studio(MOVIE, "Warner Bros. Cartoons", sortBy = MOST_VOTED, runtimeLte = 20))
            )
        )
    )

    private val hboMaxWbAnimation = DisneyHubSpec(
        hub = DisneyPlusHub.H_WB_ANIMATION,
        heroRowId = "wba_series",
        rows = listOf(
            DisneyRowSpec("wba_series", R.string.disney_row_popular_shows, listOf(studio(TV, HboMaxIds.WB_ANIMATION))),
            DisneyRowSpec(
                "wba_new", R.string.disney_row_new_short,
                listOf(studio(TV, HboMaxIds.WB_ANIMATION, sortBy = RECENT_TV, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "wba_movies", R.string.disney_row_movies,
                listOf(studio(MOVIE, HboMaxIds.WB_ANIMATION, sortBy = MOST_VOTED, runtimeGte = 60))
            ),
            DisneyRowSpec("wba_cartoon_network", R.string.hbomax_row_cartoon_network, listOf(net(HboMaxIds.NET_CARTOON_NETWORK))),
            DisneyRowSpec("wba_adult_swim", R.string.hbomax_row_adult_swim, listOf(net(null, HboMaxIds.ADULT_SWIM))),
            DisneyRowSpec(
                "wba_hanna_barbera", R.string.hbomax_row_hanna_barbera,
                listOf(studio(TV, "Hanna-Barbera Productions", sortBy = MOST_VOTED))
            ),
            DisneyRowSpec(
                "wba_top_rated", R.string.disney_row_top_rated,
                listOf(studio(TV, HboMaxIds.WB_ANIMATION, sortBy = TOP_RATED, voteCountGte = 50))
            )
        )
    )

    private val hboMaxSesame = DisneyHubSpec(
        hub = DisneyPlusHub.H_SESAME,
        heroRowId = "ses_series",
        rows = listOf(
            DisneyRowSpec("ses_series", R.string.disney_row_popular_shows, listOf(studio(TV, HboMaxIds.SESAME_WORKSHOP))),
            DisneyRowSpec(
                "ses_new", R.string.disney_row_new_short,
                listOf(studio(TV, HboMaxIds.SESAME_WORKSHOP, sortBy = RECENT_TV, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("ses_movies", R.string.disney_row_movies, listOf(studio(MOVIE, HboMaxIds.SESAME_WORKSHOP, sortBy = MOST_VOTED))),
            DisneyRowSpec(
                "ses_classic", R.string.disney_row_classic_series,
                listOf(studio(TV, HboMaxIds.SESAME_WORKSHOP, sortBy = MOST_VOTED, releaseDateLte = "2005-12-31"))
            )
        )
    )

    private val hboMaxCrunchyroll = DisneyHubSpec(
        hub = DisneyPlusHub.H_CRUNCHYROLL,
        heroRowId = "cr_popular",
        rows = listOf(
            DisneyRowSpec("cr_popular", R.string.hbomax_row_popular_anime, listOf(crunchyroll(TV))),
            DisneyRowSpec(
                "cr_new", R.string.disney_row_new_short,
                listOf(crunchyroll(TV, sortBy = RECENT_TV, voteCountGte = 1, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("cr_originals", R.string.hbomax_row_crunchyroll_originals, listOf(studio(TV, "Crunchyroll"))),
            DisneyRowSpec("cr_movies", R.string.hbomax_row_anime_movies, listOf(crunchyroll(MOVIE))),
            DisneyRowSpec(
                "cr_on_hbo_max", R.string.hbomax_row_anime_on_hbo_max,
                listOf(onHboMax(TV, genres = DisneyPlusIds.G_ANIMATION, keywordQuery = "anime"))
            ),
            DisneyRowSpec("cr_top_rated", R.string.disney_row_top_rated, listOf(crunchyroll(TV, sortBy = TOP_RATED, voteCountGte = 300)))
        )
    )

    /** Sentinel replaced with today's date (yyyy-MM-dd) when the row is resolved. */
    const val TODAY = "__today__"

    fun hub(hub: DisneyPlusHub): DisneyHubSpec = when (hub) {
        DisneyPlusHub.MAIN -> main
        DisneyPlusHub.DISNEY -> disney
        DisneyPlusHub.PIXAR -> pixar
        DisneyPlusHub.MARVEL -> marvel
        DisneyPlusHub.STAR_WARS -> starWars
        DisneyPlusHub.NAT_GEO -> natGeo
        DisneyPlusHub.HULU -> hulu
        DisneyPlusHub.ESPN -> espn
        DisneyPlusHub.COL_WDAS -> colWdas
        DisneyPlusHub.COL_DISNEY_JR -> colDisneyJr
        DisneyPlusHub.COL_DISNEY_CHANNEL -> colDisneyChannel
        DisneyPlusHub.COL_DISNEY_XD -> colDisneyXd
        DisneyPlusHub.COL_DISNEYNATURE -> colDisneynature
        DisneyPlusHub.COL_DCOM -> colDcom
        DisneyPlusHub.PARAMOUNT_MAIN -> paramountMain
        DisneyPlusHub.P_CBS -> paramountCbs
        DisneyPlusHub.P_BET -> paramountBet
        DisneyPlusHub.P_COMEDY_CENTRAL -> paramountComedyCentral
        DisneyPlusHub.P_MTV -> paramountMtv
        DisneyPlusHub.P_NICKELODEON -> paramountNickelodeon
        DisneyPlusHub.P_SMITHSONIAN -> paramountSmithsonian
        DisneyPlusHub.DISCOVERY_MAIN -> discoveryMain
        DisneyPlusHub.D_ORIGINALS -> discoveryOriginals
        DisneyPlusHub.D_HGTV -> discoveryHgtv
        DisneyPlusHub.D_FOOD_NETWORK -> discoveryFood
        DisneyPlusHub.D_TLC -> discoveryTlc
        DisneyPlusHub.D_ID -> discoveryId
        DisneyPlusHub.D_OWN -> discoveryOwn
        DisneyPlusHub.D_ANIMAL_PLANET -> discoveryAnimalPlanet
        DisneyPlusHub.D_MAGNOLIA -> discoveryMagnolia
        DisneyPlusHub.HBOMAX_MAIN -> hboMaxMain
        DisneyPlusHub.H_HBO -> hboMaxHbo
        DisneyPlusHub.H_MAX_ORIGINALS -> hboMaxOriginals
        DisneyPlusHub.H_DC -> hboMaxDc
        DisneyPlusHub.H_LOONEY_TUNES -> hboMaxLooneyTunes
        DisneyPlusHub.H_WB_ANIMATION -> hboMaxWbAnimation
        DisneyPlusHub.H_SESAME -> hboMaxSesame
        DisneyPlusHub.H_CRUNCHYROLL -> hboMaxCrunchyroll
    }
}
