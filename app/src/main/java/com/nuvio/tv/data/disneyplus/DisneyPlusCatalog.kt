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

    /** Nat Geo WILD, found through its own shows (no unchecked id hardcoded). */
    val NAT_GEO_WILD = NetworkLookup(
        names = listOf("Nat Geo Wild"),
        probes = listOf(
            CuratedTitle("The Incredible Dr. Pol", 2011, TmdbCollectionMediaType.TV),
            CuratedTitle("Dr. K's Exotic Animal ER", 2012, TmdbCollectionMediaType.TV),
            CuratedTitle("Critter Fixers: Country Vets", 2020, TmdbCollectionMediaType.TV)
        )
    )

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
    const val WB_PICTURES = "Warner Bros. Pictures"
    const val MGM = "Metro-Goldwyn-Mayer"

    // TMDB movie genre ids used on the TCM page
    const val G_MUSIC = "10402"
    const val G_WESTERN = "37"

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
 * Peacock ids. Peacock 3353 and NBC 6 (networks) and Working Title Films 10163 (company) were checked on
 * themoviedb.org; Universal Pictures 33, Illumination 6704, DreamWorks Animation 521, Focus Features 10146
 * and Amblin Entertainment 56 are TMDB's long-standing studio ids. Bravo, Telemundo and WWE Network are
 * found at runtime through their own shows, Amblin Partners through TMDB company search.
 */
object PeacockIds {
    /** Peacock Premium / Premium Plus (US), the same ids Home's Peacock tile uses. */
    const val PROVIDERS = "386|387"

    const val NET_PEACOCK = "3353"
    const val NET_NBC = "6"

    const val CO_UNIVERSAL = "33"
    const val CO_ILLUMINATION = "6704"
    const val CO_DREAMWORKS_ANIMATION = "521"
    const val CO_FOCUS_FEATURES = "10146"
    const val CO_WORKING_TITLE = "10163"
    const val CO_AMBLIN_ENTERTAINMENT = "56"

    const val AMBLIN_PARTNERS = "Amblin Partners"
    const val DREAMWORKS_TV = "DreamWorks Animation Television"
    const val WWE_STUDIOS = "WWE Studios"

    private fun show(title: String, year: Int) = CuratedTitle(title, year, TmdbCollectionMediaType.TV)

    val BRAVO = NetworkLookup(
        names = listOf("Bravo"),
        probes = listOf(show("Top Chef", 2006), show("The Real Housewives of Beverly Hills", 2010), show("Below Deck", 2013))
    )
    val TELEMUNDO = NetworkLookup(
        names = listOf("Telemundo"),
        probes = listOf(show("El Señor de los Cielos", 2013), show("La Reina del Sur", 2011), show("Señora Acero", 2014))
    )
    val WWE_NETWORK = NetworkLookup(
        names = listOf("WWE Network"),
        probes = listOf(show("WWE 24", 2015), show("WWE Untold", 2018), show("Legends' House", 2014))
    )

    // TMDB genre ids used on the Peacock pages
    const val G_CRIME = "80"
    const val G_DRAMA = "18"
    const val G_ROMANCE = "10749"
    const val G_TV_REALITY = "10764"
    const val G_TV_TALK = "10767"

    /** NBCUniversal companies and networks, used to decide what counts as Peacock content. */
    val COMPANY_IDS: Set<Int> = setOf(33, 6704, 521, 10146, 10163, 56)

    val NETWORK_IDS: Set<Int> = setOf(3353, 6)

    val COMPANY_NAME_FRAGMENTS: List<String> = listOf(
        "universal", "nbcuniversal", "nbc studios", "illumination", "dreamworks", "focus features", "working title",
        "amblin", "telemundo", "bravo", "wwe", "usa network", "syfy", "oxygen", "peacock"
    )

    val NETWORK_NAMES: Set<String> = setOf(
        "peacock", "nbc", "bravo", "telemundo", "universo", "usa network", "syfy", "e!", "oxygen", "cnbc", "msnbc",
        "nbc sports", "nbcsn", "golf channel", "wwe network", "universal kids", "sprout"
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
    HBO_MAX(HboMaxIds.PROVIDERS),
    PEACOCK(PeacockIds.PROVIDERS);

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
            PEACOCK -> HubOwnership(
                PeacockIds.COMPANY_IDS,
                PeacockIds.NETWORK_IDS,
                PeacockIds.COMPANY_NAME_FRAGMENTS,
                PeacockIds.NETWORK_NAMES
            )
        }
}

/**
 * Every page of the app's own streaming sections: the Disney+ main page, its brand pages and
 * collections, and the Paramount+, discovery+, HBO Max and Peacock main pages with their brand pages. [key] is
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

    // Collection pages from the Pixar, Marvel, Star Wars and National Geographic pages, and the
    // "Explore Collections" row on the main Disney+ page.
    COL_TOY_STORY("collection_toy_story", R.string.disney_col_toy_story),
    COL_CARS("collection_cars", R.string.disney_col_cars),
    COL_INCREDIBLES("collection_incredibles", R.string.disney_col_incredibles),
    COL_INSIDE_OUT("collection_inside_out", R.string.disney_col_inside_out),
    COL_PIXAR_SHORTS("collection_pixar_shorts", R.string.disney_col_pixar_shorts),
    COL_SPARKSHORTS("collection_sparkshorts", R.string.disney_col_sparkshorts),
    COL_FORKY("collection_forky", R.string.disney_col_forky),
    COL_INFINITY_SAGA("collection_infinity_saga", R.string.disney_col_infinity_saga),
    COL_AVENGERS("collection_avengers", R.string.disney_col_avengers),
    COL_IRON_SUIT("collection_iron_suit", R.string.disney_col_iron_suit),
    COL_SPIDER_MAN("collection_spider_man", R.string.disney_col_spider_man),
    COL_THOR("collection_thor", R.string.disney_col_thor),
    COL_BLACK_PANTHER("collection_black_panther", R.string.disney_col_black_panther),
    COL_BLACK_WIDOW("collection_black_widow", R.string.disney_col_black_widow),
    COL_ANT_MAN("collection_ant_man", R.string.disney_col_ant_man),
    COL_DOCTOR_STRANGE("collection_doctor_strange", R.string.disney_col_doctor_strange),
    COL_LOKI("collection_loki", R.string.disney_col_loki),
    COL_WANDA_VISION("collection_wanda_vision", R.string.disney_col_wanda_vision),
    COL_FALCON_WINTER_SOLDIER("collection_falcon_winter_soldier", R.string.disney_col_falcon_winter_soldier),
    COL_DAREDEVIL("collection_daredevil", R.string.disney_col_daredevil),
    COL_SKYWALKER_SAGA("collection_skywalker_saga", R.string.disney_col_skywalker_saga),
    COL_MANDALORIAN_GROGU("collection_mandalorian_grogu", R.string.disney_col_mandalorian_grogu),
    COL_DARTH_VADER("collection_darth_vader", R.string.disney_col_darth_vader),
    COL_OBI_WAN("collection_obi_wan", R.string.disney_col_obi_wan),
    COL_STAR_WARS_TALES("collection_star_wars_tales", R.string.disney_col_star_wars_tales),
    COL_LUCASFILM_ANIMATION("collection_lucasfilm_animation", R.string.disney_col_lucasfilm_animation),
    COL_STAR_WARS_VINTAGE("collection_star_wars_vintage", R.string.disney_col_star_wars_vintage),
    COL_NG_ANIMALS_NATURE("collection_ng_animals_nature", R.string.disney_col_ng_animals_nature),
    COL_NG_EXPLORING("collection_ng_exploring", R.string.disney_col_ng_exploring),
    COL_NG_SPACE("collection_ng_space", R.string.disney_col_ng_space),
    COL_NG_EARTH_MONTH("collection_ng_earth_month", R.string.disney_col_ng_earth_month),
    COL_NG_OUR_HOME("collection_ng_our_home", R.string.disney_col_ng_our_home),
    COL_NG_VETS_PETS("collection_ng_vets_pets", R.string.disney_col_ng_vets_pets),
    COL_NG_SHARKS("collection_ng_sharks", R.string.disney_col_ng_sharks),
    COL_DISNEY100("collection_disney100", R.string.disney_col_disney100),
    COL_AVATAR("collection_avatar", R.string.disney_col_avatar),
    COL_BLUEY("collection_bluey", R.string.disney_col_bluey),
    COL_SIMPSONS("collection_simpsons", R.string.disney_col_simpsons),
    COL_TREEHOUSE_OF_HORROR("collection_treehouse_of_horror", R.string.disney_col_treehouse_of_horror),
    COL_PHINEAS_FERB("collection_phineas_ferb", R.string.disney_col_phineas_ferb),
    COL_LILO_STITCH("collection_lilo_stitch", R.string.disney_col_lilo_stitch),
    COL_ZOOTOPIA("collection_zootopia", R.string.disney_col_zootopia),
    COL_DESCENDANTS("collection_descendants", R.string.disney_col_descendants),
    COL_CHIP_DALE("collection_chip_dale", R.string.disney_col_chip_dale),
    COL_DISNEY_FAIRIES("collection_disney_fairies", R.string.disney_col_disney_fairies),
    COL_TRON("collection_tron", R.string.disney_col_tron),
    COL_AIR_BUD("collection_air_bud", R.string.disney_col_air_bud),
    COL_PRINCESSES("collection_princesses", R.string.disney_col_princesses),
    COL_THROWBACKS("collection_throwbacks", R.string.disney_col_throwbacks),
    COL_MUSICALS("collection_musicals", R.string.disney_col_musicals),
    COL_PRIDE("collection_pride", R.string.disney_col_pride),
    COL_BLACK_STORIES("collection_black_stories", R.string.disney_col_black_stories),
    COL_HALLOWEEN("collection_halloween", R.string.disney_col_halloween),
    COL_DISNEY_PARKS("collection_disney_parks", R.string.disney_col_disney_parks),
    COL_DISNEY_PLUS_INSIDER("collection_disney_plus_insider", R.string.disney_col_disney_plus_insider),

    // Paramount+: its main page, then one page per brand tile.
    PARAMOUNT_MAIN("paramount", R.string.paramount_plus_title, HubService.PARAMOUNT),
    P_CBS("paramount_cbs", R.string.paramount_brand_cbs, HubService.PARAMOUNT),
    P_BET("paramount_bet", R.string.paramount_brand_bet, HubService.PARAMOUNT),
    P_COMEDY_CENTRAL("paramount_comedy_central", R.string.paramount_brand_comedy_central, HubService.PARAMOUNT),
    P_MTV("paramount_mtv", R.string.paramount_brand_mtv, HubService.PARAMOUNT),
    P_NICKELODEON("paramount_nickelodeon", R.string.paramount_brand_nickelodeon, HubService.PARAMOUNT),
    P_SMITHSONIAN("paramount_smithsonian", R.string.paramount_brand_smithsonian, HubService.PARAMOUNT),

    // Paramount+ "Universes": collection pages opened from the main page's Universes row.
    PU_STAR_TREK("paramount_universe_star_trek", R.string.paramount_col_star_trek, HubService.PARAMOUNT),
    PU_TAYLOR_SHERIDAN("paramount_universe_taylor_sheridan", R.string.paramount_col_taylor_sheridan, HubService.PARAMOUNT),
    PU_MISSION_IMPOSSIBLE("paramount_universe_mission_impossible", R.string.paramount_col_mission_impossible, HubService.PARAMOUNT),
    PU_TYLER_PERRY("paramount_universe_tyler_perry", R.string.paramount_col_tyler_perry, HubService.PARAMOUNT),
    PU_QUIET_PLACE_SCREAM("paramount_universe_quiet_place_scream", R.string.paramount_col_quiet_place_scream, HubService.PARAMOUNT),
    PU_DEXTER("paramount_universe_dexter", R.string.paramount_col_dexter, HubService.PARAMOUNT),
    PU_NCIS("paramount_universe_ncis", R.string.paramount_col_ncis, HubService.PARAMOUNT),
    PU_CSI("paramount_universe_csi", R.string.paramount_col_csi, HubService.PARAMOUNT),
    PU_CRIMINAL_MINDS("paramount_universe_criminal_minds", R.string.paramount_col_criminal_minds, HubService.PARAMOUNT),
    PU_SPONGEBOB("paramount_universe_spongebob", R.string.paramount_col_spongebob, HubService.PARAMOUNT),
    PU_SOUTH_PARK("paramount_universe_south_park", R.string.paramount_col_south_park, HubService.PARAMOUNT),
    PU_CHALLENGE("paramount_universe_challenge", R.string.paramount_col_challenge, HubService.PARAMOUNT),

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
    H_TCM("hbomax_tcm", R.string.hbomax_brand_tcm, HubService.HBO_MAX),
    H_CRUNCHYROLL("hbomax_crunchyroll", R.string.hbomax_brand_crunchyroll, HubService.HBO_MAX),

    // Peacock: its main page, then one page per brand tile.
    PEACOCK_MAIN("peacock", R.string.peacock_title, HubService.PEACOCK),
    PK_ORIGINALS("peacock_originals", R.string.peacock_brand_originals, HubService.PEACOCK),
    PK_NBC("peacock_nbc", R.string.peacock_brand_nbc, HubService.PEACOCK),
    PK_BRAVO("peacock_bravo", R.string.peacock_brand_bravo, HubService.PEACOCK),
    PK_TELEMUNDO("peacock_telemundo", R.string.peacock_brand_telemundo, HubService.PEACOCK),
    PK_WWE("peacock_wwe", R.string.peacock_brand_wwe, HubService.PEACOCK),
    PK_NBC_SPORTS("peacock_nbc_sports", R.string.peacock_brand_nbc_sports, HubService.PEACOCK),
    PK_UNIVERSAL("peacock_universal", R.string.peacock_brand_universal, HubService.PEACOCK),
    PK_ILLUMINATION("peacock_illumination", R.string.peacock_brand_illumination, HubService.PEACOCK),
    PK_DREAMWORKS("peacock_dreamworks", R.string.peacock_brand_dreamworks, HubService.PEACOCK),
    PK_FOCUS("peacock_focus_features", R.string.peacock_brand_focus, HubService.PEACOCK),
    PK_WORKING_TITLE("peacock_working_title", R.string.peacock_brand_working_title, HubService.PEACOCK),
    PK_AMBLIN("peacock_amblin", R.string.peacock_brand_amblin, HubService.PEACOCK);

    /** A service's main page (hero, brand tiles, Continue Watching...), as opposed to a brand page. */
    val isMain: Boolean
        get() = this == MAIN || this == PARAMOUNT_MAIN || this == DISCOVERY_MAIN || this == HBOMAX_MAIN ||
            this == PEACOCK_MAIN

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
                H_HBO, H_MAX_ORIGINALS, H_DC, H_LOONEY_TUNES, H_WB_ANIMATION, H_SESAME, H_TCM, H_CRUNCHYROLL
            )
            HubService.PEACOCK -> listOf(
                PK_ORIGINALS, PK_NBC, PK_BRAVO, PK_TELEMUNDO, PK_WWE, PK_NBC_SPORTS,
                PK_UNIVERSAL, PK_ILLUMINATION, PK_DREAMWORKS, PK_FOCUS, PK_WORKING_TITLE, PK_AMBLIN
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
    val collections: List<DisneyPlusHub> = emptyList(),
    /**
     * The complete TMDB catalog the row stands for, which its See All goes on with after the row's own
     * titles: e.g. Disney Classics shows hand-picked classics, then every Walt Disney Animation Studios
     * film. Rows built from TMDB queries already page through everything they match.
     */
    val seeAll: List<DisneyRowSource.Discover> = emptyList()
)

/** Gives a row its complete catalog for See All (see [DisneyRowSpec.seeAll]). */
fun DisneyRowSpec.withSeeAll(vararg sources: DisneyRowSource.Discover): DisneyRowSpec = copy(seeAll = sources.toList())

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
     * Titles whose backdrops would make up a collection tile's artwork. Every collection now has its own
     * tile picture (bundled with the app), so none is needed.
     */
    fun collectionArt(hub: DisneyPlusHub): List<CuratedTitle> = emptyList()

    // ================================================================== Disney+ brand pages
    // Row order and names follow each brand's page on Disney+ (en-us).

    // ---- Pixar page ----

    private val PIXAR_PAGE_FEATURED = listOf(
        movie("Elio", 2025),
        movie("Inside Out 2", 2024),
        series("Win or Lose", 2025),
        movie("Elemental", 2023),
        movie("Lightyear", 2022),
        movie("Turning Red", 2022),
        movie("Luca", 2021),
        movie("Soul", 2020),
        movie("Onward", 2020),
        movie("Toy Story 4", 2019),
        movie("Incredibles 2", 2018),
        movie("Coco", 2017)
    )

    private val SPARKSHORTS = listOf(
        movie("Purl", 2019),
        movie("Smash and Grab", 2019),
        movie("Kitbull", 2019),
        movie("Float", 2019),
        movie("Wind", 2019),
        movie("Loop", 2020),
        movie("Out", 2020),
        movie("Burrow", 2020),
        movie("Twenty Something", 2021),
        movie("Nona", 2021)
    )

    private val FORKY_AND_FRIENDS = listOf(
        series("Forky Asks a Question", 2019),
        movie("Lamp Life", 2020),
        movie("Toy Story of Terror!", 2013),
        movie("Toy Story That Time Forgot", 2014),
        movie("Hawaiian Vacation", 2011),
        movie("Small Fry", 2011),
        movie("Partysaurus Rex", 2012)
    )

    private val BEASTS_AND_MONSTERS = listOf(
        movie("Monsters, Inc.", 2001),
        movie("Monsters University", 2013),
        series("Monsters at Work", 2021),
        movie("Brave", 2012),
        movie("The Good Dinosaur", 2015),
        movie("Turning Red", 2022),
        movie("Onward", 2020),
        movie("Luca", 2021),
        movie("Elio", 2025),
        movie("Mike's New Car", 2002)
    )

    private val UNLIKELY_HEROES = listOf(
        movie("WALL·E", 2008),
        movie("Ratatouille", 2007),
        movie("Finding Nemo", 2003),
        movie("Finding Dory", 2016),
        movie("Up", 2009),
        movie("A Bug's Life", 1998),
        movie("Coco", 2017),
        movie("Soul", 2020),
        movie("Inside Out", 2015),
        movie("The Incredibles", 2004),
        movie("Cars", 2006),
        movie("Lightyear", 2022)
    )

    private val PIXAR_OSCAR_WINNERS = listOf(
        movie("Finding Nemo", 2003),
        movie("The Incredibles", 2004),
        movie("Ratatouille", 2007),
        movie("WALL·E", 2008),
        movie("Up", 2009),
        movie("Toy Story 3", 2010),
        movie("Brave", 2012),
        movie("Inside Out", 2015),
        movie("Coco", 2017),
        movie("Toy Story 4", 2019),
        movie("Soul", 2020),
        movie("Bao", 2018),
        movie("Piper", 2016),
        movie("For the Birds", 2000)
    )

    // ---- Marvel page ----

    private val MARVEL_PAGE_FEATURED = listOf(
        movie("The Fantastic 4: First Steps", 2025),
        movie("Thunderbolts*", 2025),
        series("Daredevil: Born Again", 2025),
        movie("Captain America: Brave New World", 2025),
        series("Ironheart", 2025),
        series("Agatha All Along", 2024),
        movie("Deadpool & Wolverine", 2024),
        series("X-Men '97", 2024),
        series("Your Friendly Neighborhood Spider-Man", 2025),
        series("Marvel Zombies", 2025),
        series("Eyes of Wakanda", 2025),
        series("Wonder Man", 2026)
    )

    private val AVENGERS_COUNTDOWN = listOf(
        movie("Avengers: Endgame", 2019),
        movie("Avengers: Infinity War", 2018),
        movie("Thunderbolts*", 2025),
        movie("The Fantastic 4: First Steps", 2025),
        movie("Captain America: Brave New World", 2025),
        movie("Deadpool & Wolverine", 2024),
        series("Loki", 2021),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        movie("Ant-Man and the Wasp: Quantumania", 2023),
        movie("Shang-Chi and the Legend of the Ten Rings", 2021),
        movie("The Avengers", 2012),
        movie("Avengers: Age of Ultron", 2015)
    )

    private val MCU_INFINITY_SAGA = listOf(
        movie("Iron Man", 2008),
        movie("The Incredible Hulk", 2008),
        movie("Iron Man 2", 2010),
        movie("Thor", 2011),
        movie("Captain America: The First Avenger", 2011),
        movie("The Avengers", 2012),
        movie("Iron Man 3", 2013),
        movie("Thor: The Dark World", 2013),
        movie("Captain America: The Winter Soldier", 2014),
        movie("Guardians of the Galaxy", 2014),
        movie("Avengers: Age of Ultron", 2015),
        movie("Ant-Man", 2015),
        movie("Captain America: Civil War", 2016),
        movie("Doctor Strange", 2016),
        movie("Guardians of the Galaxy Vol. 2", 2017),
        movie("Spider-Man: Homecoming", 2017),
        movie("Thor: Ragnarok", 2017),
        movie("Black Panther", 2018),
        movie("Avengers: Infinity War", 2018),
        movie("Ant-Man and the Wasp", 2018),
        movie("Captain Marvel", 2019),
        movie("Avengers: Endgame", 2019),
        movie("Spider-Man: Far From Home", 2019)
    )

    private val MCU_MULTIVERSE_SAGA = listOf(
        series("WandaVision", 2021),
        series("The Falcon and the Winter Soldier", 2021),
        series("Loki", 2021),
        movie("Black Widow", 2021),
        series("What If...?", 2021),
        movie("Shang-Chi and the Legend of the Ten Rings", 2021),
        movie("Eternals", 2021),
        series("Hawkeye", 2021),
        movie("Spider-Man: No Way Home", 2021),
        series("Moon Knight", 2022),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        series("Ms. Marvel", 2022),
        movie("Thor: Love and Thunder", 2022),
        series("She-Hulk: Attorney at Law", 2022),
        movie("Black Panther: Wakanda Forever", 2022),
        movie("Ant-Man and the Wasp: Quantumania", 2023),
        movie("Guardians of the Galaxy Vol. 3", 2023),
        series("Secret Invasion", 2023),
        movie("The Marvels", 2023),
        series("Echo", 2024),
        movie("Deadpool & Wolverine", 2024),
        series("Agatha All Along", 2024),
        movie("Captain America: Brave New World", 2025),
        series("Daredevil: Born Again", 2025),
        movie("Thunderbolts*", 2025),
        series("Ironheart", 2025),
        movie("The Fantastic 4: First Steps", 2025)
    )

    private val SPIDER_VERSE = listOf(
        movie("Spider-Man", 2002),
        movie("Spider-Man 2", 2004),
        movie("Spider-Man 3", 2007),
        movie("The Amazing Spider-Man", 2012),
        movie("The Amazing Spider-Man 2", 2014),
        movie("Spider-Man: Homecoming", 2017),
        movie("Spider-Man: Into the Spider-Verse", 2018),
        movie("Spider-Man: Far From Home", 2019),
        movie("Spider-Man: No Way Home", 2021),
        movie("Spider-Man: Across the Spider-Verse", 2023),
        series("Your Friendly Neighborhood Spider-Man", 2025),
        series("Spidey and His Amazing Friends", 2021),
        movie("Venom", 2018),
        movie("Venom: Let There Be Carnage", 2021),
        movie("Venom: The Last Dance", 2024)
    )

    /** MCU films in story order. */
    private val MCU_MOVIE_TIMELINE = listOf(
        movie("Captain America: The First Avenger", 2011),
        movie("Captain Marvel", 2019),
        movie("Iron Man", 2008),
        movie("Iron Man 2", 2010),
        movie("The Incredible Hulk", 2008),
        movie("Thor", 2011),
        movie("The Avengers", 2012),
        movie("Thor: The Dark World", 2013),
        movie("Iron Man 3", 2013),
        movie("Captain America: The Winter Soldier", 2014),
        movie("Guardians of the Galaxy", 2014),
        movie("Guardians of the Galaxy Vol. 2", 2017),
        movie("Avengers: Age of Ultron", 2015),
        movie("Ant-Man", 2015),
        movie("Captain America: Civil War", 2016),
        movie("Black Widow", 2021),
        movie("Black Panther", 2018),
        movie("Spider-Man: Homecoming", 2017),
        movie("Doctor Strange", 2016),
        movie("Thor: Ragnarok", 2017),
        movie("Ant-Man and the Wasp", 2018),
        movie("Avengers: Infinity War", 2018),
        movie("Avengers: Endgame", 2019),
        movie("Shang-Chi and the Legend of the Ten Rings", 2021),
        movie("Spider-Man: Far From Home", 2019),
        movie("Eternals", 2021),
        movie("Spider-Man: No Way Home", 2021),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        movie("Black Panther: Wakanda Forever", 2022),
        movie("Thor: Love and Thunder", 2022),
        movie("Ant-Man and the Wasp: Quantumania", 2023),
        movie("Guardians of the Galaxy Vol. 3", 2023),
        movie("The Marvels", 2023),
        movie("Deadpool & Wolverine", 2024),
        movie("Captain America: Brave New World", 2025),
        movie("Thunderbolts*", 2025)
    )

    private val MARVEL_MONSTERS = listOf(
        movie("Werewolf by Night", 2022),
        series("Marvel Zombies", 2025),
        movie("Blade", 1998),
        movie("Blade II", 2002),
        movie("Blade: Trinity", 2004),
        movie("Ghost Rider", 2007),
        movie("Ghost Rider: Spirit of Vengeance", 2011),
        series("Moon Knight", 2022),
        series("Hit-Monkey", 2021),
        series("Helstrom", 2020)
    )

    private val PREPARE_FOR_VISION = listOf(
        series("WandaVision", 2021),
        movie("Avengers: Age of Ultron", 2015),
        movie("Captain America: Civil War", 2016),
        movie("Avengers: Infinity War", 2018),
        movie("Avengers: Endgame", 2019),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        series("Agatha All Along", 2024)
    )

    private val MUTANT_LEGACY = listOf(
        series("X-Men '97", 2024),
        series("X-Men: The Animated Series", 1992),
        movie("X-Men", 2000),
        movie("X2", 2003),
        movie("X-Men: The Last Stand", 2006),
        movie("X-Men Origins: Wolverine", 2009),
        movie("X-Men: First Class", 2011),
        movie("The Wolverine", 2013),
        movie("X-Men: Days of Future Past", 2014),
        movie("Deadpool", 2016),
        movie("X-Men: Apocalypse", 2016),
        movie("Logan", 2017),
        movie("Deadpool 2", 2018),
        movie("Dark Phoenix", 2019),
        movie("The New Mutants", 2020),
        movie("Deadpool & Wolverine", 2024)
    )

    private val FANTASTIC_ADVENTURES = listOf(
        movie("The Fantastic 4: First Steps", 2025),
        movie("Fantastic Four", 2005),
        movie("Fantastic Four: Rise of the Silver Surfer", 2007),
        movie("Fantastic Four", 2015)
    )

    private val MARVEL_ORIGIN_STORIES = listOf(
        movie("Iron Man", 2008),
        movie("Captain America: The First Avenger", 2011),
        movie("Thor", 2011),
        movie("Guardians of the Galaxy", 2014),
        movie("Ant-Man", 2015),
        movie("Doctor Strange", 2016),
        movie("Spider-Man: Homecoming", 2017),
        movie("Black Panther", 2018),
        movie("Captain Marvel", 2019),
        movie("Shang-Chi and the Legend of the Ten Rings", 2021),
        movie("Eternals", 2021),
        series("Moon Knight", 2022),
        series("Ms. Marvel", 2022),
        series("Ironheart", 2025)
    )

    private val THE_DEFENDERS = listOf(
        series("Daredevil", 2015),
        series("Jessica Jones", 2015),
        series("Luke Cage", 2016),
        series("Iron Fist", 2017),
        series("The Defenders", 2017),
        series("The Punisher", 2017),
        series("Daredevil: Born Again", 2025)
    )

    /** MCU films and series in story order. */
    private val MCU_COMPLETE_TIMELINE = listOf(
        movie("Captain America: The First Avenger", 2011),
        series("Agent Carter", 2015),
        movie("Captain Marvel", 2019),
        movie("Iron Man", 2008),
        movie("Iron Man 2", 2010),
        movie("The Incredible Hulk", 2008),
        movie("Thor", 2011),
        movie("The Avengers", 2012),
        movie("Thor: The Dark World", 2013),
        movie("Iron Man 3", 2013),
        movie("Captain America: The Winter Soldier", 2014),
        movie("Guardians of the Galaxy", 2014),
        movie("Guardians of the Galaxy Vol. 2", 2017),
        series("I Am Groot", 2022),
        movie("Avengers: Age of Ultron", 2015),
        movie("Ant-Man", 2015),
        movie("Captain America: Civil War", 2016),
        movie("Black Widow", 2021),
        movie("Black Panther", 2018),
        movie("Spider-Man: Homecoming", 2017),
        movie("Doctor Strange", 2016),
        movie("Thor: Ragnarok", 2017),
        movie("Ant-Man and the Wasp", 2018),
        movie("Avengers: Infinity War", 2018),
        movie("Avengers: Endgame", 2019),
        series("Loki", 2021),
        series("What If...?", 2021),
        series("WandaVision", 2021),
        movie("Shang-Chi and the Legend of the Ten Rings", 2021),
        series("The Falcon and the Winter Soldier", 2021),
        movie("Spider-Man: Far From Home", 2019),
        movie("Eternals", 2021),
        movie("Spider-Man: No Way Home", 2021),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        series("Hawkeye", 2021),
        series("Moon Knight", 2022),
        movie("Black Panther: Wakanda Forever", 2022),
        series("Echo", 2024),
        series("She-Hulk: Attorney at Law", 2022),
        series("Ms. Marvel", 2022),
        movie("Thor: Love and Thunder", 2022),
        movie("Werewolf by Night", 2022),
        movie("The Guardians of the Galaxy Holiday Special", 2022),
        movie("Ant-Man and the Wasp: Quantumania", 2023),
        movie("Guardians of the Galaxy Vol. 3", 2023),
        series("Secret Invasion", 2023),
        movie("The Marvels", 2023),
        series("Agatha All Along", 2024),
        movie("Deadpool & Wolverine", 2024),
        movie("Captain America: Brave New World", 2025),
        series("Daredevil: Born Again", 2025),
        movie("Thunderbolts*", 2025),
        series("Ironheart", 2025)
    )

    private val SAM_WILSON = listOf(
        series("The Falcon and the Winter Soldier", 2021),
        movie("Captain America: Brave New World", 2025),
        movie("Captain America: The Winter Soldier", 2014),
        movie("Avengers: Age of Ultron", 2015),
        movie("Ant-Man", 2015),
        movie("Captain America: Civil War", 2016),
        movie("Avengers: Infinity War", 2018),
        movie("Avengers: Endgame", 2019)
    )

    private val MARVEL_RIVALS_CHARACTERS = listOf(
        movie("Guardians of the Galaxy", 2014),
        movie("Black Panther", 2018),
        movie("Doctor Strange", 2016),
        series("Loki", 2021),
        movie("Thor: Ragnarok", 2017),
        movie("Iron Man", 2008),
        movie("The Incredible Hulk", 2008),
        series("WandaVision", 2021),
        series("Moon Knight", 2022),
        movie("Venom", 2018),
        movie("Captain America: The First Avenger", 2011),
        movie("Black Widow", 2021),
        movie("Spider-Man: Homecoming", 2017),
        movie("Logan", 2017),
        movie("The Fantastic 4: First Steps", 2025),
        movie("Deadpool", 2016)
    )

    private val MARVEL_ANIMATION = listOf(
        series("What If...?", 2021),
        series("X-Men '97", 2024),
        series("Your Friendly Neighborhood Spider-Man", 2025),
        series("Marvel Zombies", 2025),
        series("Eyes of Wakanda", 2025),
        series("I Am Groot", 2022),
        series("Moon Girl and Devil Dinosaur", 2023),
        series("Spidey and His Amazing Friends", 2021),
        series("Hit-Monkey", 2021),
        series("X-Men: The Animated Series", 1992)
    )

    private val MARVEL_IMAX_ENHANCED = listOf(
        movie("Avengers: Endgame", 2019),
        movie("Avengers: Infinity War", 2018),
        movie("Black Panther", 2018),
        movie("Captain Marvel", 2019),
        movie("Doctor Strange", 2016),
        movie("Guardians of the Galaxy", 2014),
        movie("Guardians of the Galaxy Vol. 2", 2017),
        movie("Shang-Chi and the Legend of the Ten Rings", 2021),
        movie("Eternals", 2021),
        movie("Black Widow", 2021),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        movie("Thor: Ragnarok", 2017),
        movie("Thor: Love and Thunder", 2022),
        movie("Black Panther: Wakanda Forever", 2022),
        movie("Ant-Man and the Wasp: Quantumania", 2023),
        movie("Guardians of the Galaxy Vol. 3", 2023),
        movie("The Marvels", 2023),
        movie("Captain America: Civil War", 2016),
        movie("Ant-Man and the Wasp", 2018),
        movie("Iron Man", 2008)
    )

    private val MARVEL_UNSCRIPTED = listOf(
        series("Marvel Studios: Assembled", 2021),
        series("Marvel's 616", 2020),
        series("Marvel Studios: Legends", 2021),
        movie("Marvel Studios: Expanding the Universe", 2019)
    )

    // ---- Star Wars page ----

    private val STAR_WARS_PAGE_FEATURED = listOf(
        series("Andor", 2022),
        series("Star Wars: Maul - Shadow Lord", 2026),
        series("Star Wars: Visions", 2021),
        series("The Mandalorian", 2019),
        series("Star Wars: Young Jedi Adventures", 2023),
        series("Ahsoka", 2023),
        series("Star Wars: Skeleton Crew", 2024),
        series("Star Wars: Tales of the Underworld", 2025),
        movie("Star Wars", 1977),
        movie("Rogue One: A Star Wars Story", 2016),
        series("Star Wars: The Clone Wars", 2008),
        series("Obi-Wan Kenobi", 2022)
    )

    /** The films in release order. */
    private val STAR_WARS_MOVIES_RELEASE = listOf(
        movie("Star Wars", 1977),
        movie("The Empire Strikes Back", 1980),
        movie("Return of the Jedi", 1983),
        movie("Star Wars: Episode I - The Phantom Menace", 1999),
        movie("Star Wars: Episode II - Attack of the Clones", 2002),
        movie("Star Wars: Episode III - Revenge of the Sith", 2005),
        movie("Star Wars: The Clone Wars", 2008),
        movie("Star Wars: The Force Awakens", 2015),
        movie("Rogue One: A Star Wars Story", 2016),
        movie("Star Wars: The Last Jedi", 2017),
        movie("Solo: A Star Wars Story", 2018),
        movie("Star Wars: The Rise of Skywalker", 2019)
    )

    /** Films and series in story order. */
    private val STAR_WARS_TIMELINE = listOf(
        series("The Acolyte", 2024),
        movie("Star Wars: Episode I - The Phantom Menace", 1999),
        movie("Star Wars: Episode II - Attack of the Clones", 2002),
        series("Star Wars: Tales of the Jedi", 2022),
        movie("Star Wars: The Clone Wars", 2008),
        series("Star Wars: The Clone Wars", 2008),
        movie("Star Wars: Episode III - Revenge of the Sith", 2005),
        series("Star Wars: Tales of the Empire", 2024),
        series("Star Wars: The Bad Batch", 2021),
        movie("Solo: A Star Wars Story", 2018),
        series("Obi-Wan Kenobi", 2022),
        series("Andor", 2022),
        series("Star Wars Rebels", 2014),
        movie("Rogue One: A Star Wars Story", 2016),
        movie("Star Wars", 1977),
        movie("The Empire Strikes Back", 1980),
        movie("Return of the Jedi", 1983),
        series("The Mandalorian", 2019),
        series("The Book of Boba Fett", 2021),
        series("Ahsoka", 2023),
        series("Star Wars: Skeleton Crew", 2024),
        series("Star Wars Resistance", 2018),
        movie("Star Wars: The Force Awakens", 2015),
        movie("Star Wars: The Last Jedi", 2017),
        movie("Star Wars: The Rise of Skywalker", 2019)
    )

    private val STAR_WARS_SERIES_AND_SPECIALS = listOf(
        series("The Mandalorian", 2019),
        series("Andor", 2022),
        series("Ahsoka", 2023),
        series("Obi-Wan Kenobi", 2022),
        series("The Book of Boba Fett", 2021),
        series("Star Wars: Skeleton Crew", 2024),
        series("The Acolyte", 2024),
        series("Star Wars: The Clone Wars", 2008),
        series("Star Wars Rebels", 2014),
        series("Star Wars: The Bad Batch", 2021),
        series("Star Wars Resistance", 2018),
        series("Star Wars: Visions", 2021),
        series("Star Wars: Tales of the Jedi", 2022),
        series("Star Wars: Tales of the Empire", 2024),
        series("Star Wars: Tales of the Underworld", 2025),
        series("Star Wars: Young Jedi Adventures", 2023),
        series("LEGO Star Wars: Rebuild the Galaxy", 2024),
        movie("The LEGO Star Wars Holiday Special", 2020),
        movie("LEGO Star Wars Terrifying Tales", 2021),
        movie("LEGO Star Wars Summer Vacation", 2022)
    )

    private val STAR_WARS_REFERENCES = listOf(
        series("Family Guy", 1999),
        series("The Muppet Show", 1976),
        series("Phineas and Ferb", 2007),
        series("The Simpsons", 1989),
        series("A.N.T. Farm", 2011),
        series("Glee", 2009)
    )

    private val STAR_WARS_ANIMATION = listOf(
        series("Star Wars: The Clone Wars", 2008),
        series("Star Wars Rebels", 2014),
        series("Star Wars: The Bad Batch", 2021),
        series("Star Wars: Visions", 2021),
        series("Star Wars: Tales of the Jedi", 2022),
        series("Star Wars: Tales of the Empire", 2024),
        series("Star Wars: Tales of the Underworld", 2025),
        series("Star Wars: Young Jedi Adventures", 2023),
        series("Star Wars Resistance", 2018),
        series("LEGO Star Wars: Rebuild the Galaxy", 2024),
        series("Star Wars: Maul - Shadow Lord", 2026),
        movie("Star Wars: The Clone Wars", 2008),
        series("Star Wars: Clone Wars", 2003),
        series("LEGO Star Wars: The Freemaker Adventures", 2016)
    )

    private val STAR_WARS_DOCUMENTARIES = listOf(
        series("Disney Gallery: Star Wars: The Mandalorian", 2020),
        series("Light & Magic", 2022),
        movie("Obi-Wan Kenobi: A Jedi's Return", 2022),
        movie("Under the Helmet: The Legacy of Boba Fett", 2021),
        movie("Empire of Dreams: The Story of the Star Wars Trilogy", 2004),
        movie("Disney Gallery: Star Wars: The Book of Boba Fett", 2022)
    )

    private val STAR_WARS_SHORTS = listOf(
        series("Star Wars: Forces of Destiny", 2017),
        series("Star Wars Blips", 2017),
        series("LEGO Star Wars: The Resistance Rises", 2016),
        series("LEGO Star Wars: All-Stars", 2018),
        series("Star Wars Galaxy of Sounds", 2021),
        series("Star Wars Vehicle Flythroughs", 2021),
        series("Star Wars Biomes", 2021),
        movie("Zen - Grogu and Dust Bunnies", 2022),
        series("LEGO Star Wars: The New Yoda Chronicles", 2014)
    )

    private val STAR_WARS_VINTAGE = listOf(
        series("Star Wars: Droids", 1985),
        series("Star Wars: Ewoks", 1985),
        movie("The Ewok Adventure", 1984),
        movie("Ewoks: The Battle for Endor", 1985),
        series("Star Wars: Clone Wars", 2003)
    )

    private val OBI_WAN_KENOBI = listOf(
        series("Obi-Wan Kenobi", 2022),
        movie("Star Wars: Episode I - The Phantom Menace", 1999),
        movie("Star Wars: Episode II - Attack of the Clones", 2002),
        movie("Star Wars: Episode III - Revenge of the Sith", 2005),
        movie("Star Wars", 1977),
        series("Star Wars: The Clone Wars", 2008),
        movie("Obi-Wan Kenobi: A Jedi's Return", 2022),
        series("Star Wars Rebels", 2014),
        series("Star Wars: Tales of the Jedi", 2022),
        movie("The Empire Strikes Back", 1980)
    )

    private val DARTH_VADER = listOf(
        movie("Star Wars: Episode III - Revenge of the Sith", 2005),
        movie("Star Wars", 1977),
        movie("The Empire Strikes Back", 1980),
        movie("Return of the Jedi", 1983),
        movie("Rogue One: A Star Wars Story", 2016),
        series("Obi-Wan Kenobi", 2022),
        series("Star Wars Rebels", 2014),
        series("Star Wars: Tales of the Jedi", 2022),
        movie("Star Wars: Episode II - Attack of the Clones", 2002),
        movie("Star Wars: Episode I - The Phantom Menace", 1999),
        series("Star Wars: The Clone Wars", 2008)
    )

    // ---- National Geographic page ----

    private val NAT_GEO_ORIGINALS = listOf(
        series("Welcome to Earth", 2021),
        series("Limitless with Chris Hemsworth", 2022),
        series("Secrets of the Whales", 2021),
        series("Secrets of the Elephants", 2023),
        series("Secrets of the Octopus", 2024),
        series("Arctic Ascent with Alex Honnold", 2024),
        series("Pole to Pole with Will Smith", 2025),
        series("America's National Parks", 2022),
        series("The World According to Jeff Goldblum", 2019),
        series("Tucci in Italy", 2025)
    )

    private val NAT_GEO_FOOD_AND_TRAVEL = listOf(
        series("Gordon Ramsay: Uncharted", 2019),
        series("Tucci in Italy", 2025),
        series("Restaurants at the End of the World", 2023),
        series("The World According to Jeff Goldblum", 2019),
        series("Pole to Pole with Will Smith", 2025)
    )

    private val NAT_GEO_EXPLORING = listOf(
        series("Welcome to Earth", 2021),
        series("One Strange Rock", 2018),
        series("Pole to Pole with Will Smith", 2025),
        series("America the Beautiful", 2022),
        series("Arctic Ascent with Alex Honnold", 2024),
        series("Lost Cities with Albert Lin", 2019),
        movie("Explorer: The Last Tepui", 2022),
        movie("Free Solo", 2018),
        movie("The Rescue", 2021)
    )

    private val NAT_GEO_ANIMALS = listOf(
        series("Secrets of the Whales", 2021),
        series("Secrets of the Elephants", 2023),
        series("Secrets of the Octopus", 2024),
        series("Secrets of the Penguins", 2025),
        series("America the Beautiful", 2022),
        series("Incredible Animal Journeys", 2024)
    )

    private val NAT_GEO_VETS_AND_PETS = listOf(
        series("The Incredible Dr. Pol", 2011),
        series("Critter Fixers: Country Vets", 2020),
        series("Dr. K's Exotic Animal ER", 2012),
        series("Heartland Docs, DVM", 2021),
        series("Dr. Oakley, Yukon Vet", 2014)
    )

    private val NAT_GEO_SCIENCE = listOf(
        series("Limitless with Chris Hemsworth", 2022),
        series("Brain Games", 2011),
        series("Cosmos: A Spacetime Odyssey", 2014),
        series("Cosmos: Possible Worlds", 2020),
        series("Mars", 2016),
        series("One Strange Rock", 2018),
        series("Year Million", 2017)
    )

    private val NAT_GEO_ANCIENT_MYSTERIES = listOf(
        series("Lost Treasures of Egypt", 2019),
        series("Kingdom of the Mummies", 2020),
        series("Lost Cities with Albert Lin", 2019),
        series("Lost Treasures of the Maya", 2022),
        series("Lost Treasures of Rome", 2023),
        series("Lost Treasures of the Bible", 2023),
        series("Drain the Oceans", 2018)
    )

    private val NAT_GEO_KIDS = listOf(
        series("Weird But True!", 2016),
        series("Earth to Ned", 2020),
        series("Animals Up Close with Bertie Gregory", 2023),
        series("Brain Games", 2011)
    )

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

    // ------------------------------------------------------------------ complete catalogs (See All)

    /** Walt Disney Pictures + Walt Disney Animation Studios. */
    private const val DISNEY_FILM_STUDIOS = "2|6125"

    /** Every movie and series TMDB tags with [keyword] (optionally only from [companies] / in [genres]). */
    private fun keywordCatalog(keyword: String, companies: String? = null, genres: String? = null) = arrayOf(
        DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = keyword, companies = companies, genres = genres),
        DisneyRowSource.Discover(TV, sortBy = MOST_VOTED, keywordQuery = keyword, companies = companies, genres = genres)
    )

    // ---- Rows reused across pages ----

    private val rowClassics = DisneyRowSpec("classics", R.string.disney_row_classics, listOf(curated(CLASSICS))).withSeeAll(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_ANIMATION, sortBy = MOST_VOTED, runtimeGte = 40))
    private val rowPrincess = DisneyRowSpec("princess", R.string.disney_row_princess, listOf(curated(PRINCESSES))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companies = DISNEY_FILM_STUDIOS, keywordQuery = "princess"))
    private val rowVillains = DisneyRowSpec("villains", R.string.disney_row_villains, listOf(curated(VILLAINS))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companies = "$DISNEY_FILM_STUDIOS|${DisneyPlusIds.CO_PIXAR}", keywordQuery = "villain"))
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
            // Every collection that isn't on a brand page (Disney, Pixar, Marvel, Star Wars, Nat Geo).
            DisneyRowSpec(
                "explore_collections", R.string.disney_row_explore_collections,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(
                    DisneyPlusHub.COL_DISNEY100,
                    DisneyPlusHub.COL_AVATAR,
                    DisneyPlusHub.COL_BLUEY,
                    DisneyPlusHub.COL_SIMPSONS,
                    DisneyPlusHub.COL_TREEHOUSE_OF_HORROR,
                    DisneyPlusHub.COL_PHINEAS_FERB,
                    DisneyPlusHub.COL_LILO_STITCH,
                    DisneyPlusHub.COL_ZOOTOPIA,
                    DisneyPlusHub.COL_DESCENDANTS,
                    DisneyPlusHub.COL_CHIP_DALE,
                    DisneyPlusHub.COL_DISNEY_FAIRIES,
                    DisneyPlusHub.COL_TRON,
                    DisneyPlusHub.COL_AIR_BUD,
                    DisneyPlusHub.COL_PRINCESSES,
                    DisneyPlusHub.COL_THROWBACKS,
                    DisneyPlusHub.COL_MUSICALS,
                    DisneyPlusHub.COL_PRIDE,
                    DisneyPlusHub.COL_BLACK_STORIES,
                    DisneyPlusHub.COL_HALLOWEEN,
                    DisneyPlusHub.COL_DISNEY_PARKS,
                    DisneyPlusHub.COL_DISNEY_PLUS_INSIDER
                )
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
            ).withSeeAll(*keywordCatalog("star wars")),
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
            DisneyRowSpec("d_nature", R.string.disney_row_disneynature, listOf(curated(DISNEYNATURE))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disneynature")),
            DisneyRowSpec("d_princesses", R.string.disney_row_princesses, listOf(curated(PRINCESSES))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companies = DISNEY_FILM_STUDIOS, keywordQuery = "princess")),
            DisneyRowSpec(
                "d_wdas", R.string.disney_row_wdas,
                listOf(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_ANIMATION, sortBy = RECENT, runtimeGte = 60, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "d_live_action", R.string.disney_row_live_action_movies,
                listOf(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_PICTURES, withoutGenres = DisneyPlusIds.G_ANIMATION, voteCountGte = 200))
            ),
            DisneyRowSpec("d_lilo_stitch", R.string.disney_row_lilo_stitch, listOf(curated(LILO_AND_STITCH))),
            DisneyRowSpec("d_villains", R.string.disney_row_villains, listOf(curated(DISNEY_VILLAINS))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companies = "$DISNEY_FILM_STUDIOS|${DisneyPlusIds.CO_PIXAR}", keywordQuery = "villain")),
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
            DisneyRowSpec("d_music", R.string.disney_row_music_to_our_ears, listOf(curated(MUSIC_TO_OUR_EARS))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companies = DISNEY_FILM_STUDIOS, genres = "10402")),
            DisneyRowSpec("d_disney_channel", R.string.disney_row_disney_channel_series, listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_CHANNEL))),
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
            DisneyRowSpec("d_mickey", R.string.disney_row_mickey_and_friends, listOf(curated(MICKEY_AND_FRIENDS))).withSeeAll(*keywordCatalog("mickey mouse")),
            DisneyRowSpec(
                "d_dcom", R.string.disney_row_dcom,
                listOf(curated(DCOM_FEATURED), DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Channel"))
            )
        )
    )

    private val pixar = DisneyHubSpec(
        hub = DisneyPlusHub.PIXAR,
        heroRowId = "px_featured",
        rows = listOf(
            DisneyRowSpec("px_featured", R.string.disney_row_featured, listOf(curated(PIXAR_PAGE_FEATURED))).withSeeAll(company(MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = MOST_VOTED, runtimeGte = 40), company(TV, DisneyPlusIds.CO_PIXAR)),
            DisneyRowSpec("px_toy_story", R.string.disney_row_toy_story, listOf(curated(TOY_STORY))),
            DisneyRowSpec(
                "px_movies", R.string.disney_row_movies,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, runtimeGte = 60, voteCountGte = 50))
            ),
            DisneyRowSpec(
                "px_shorts", R.string.disney_row_shorts,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, runtimeLte = 30, voteCountGte = 10))
            ),
            DisneyRowSpec("px_originals", R.string.disney_row_originals_short, listOf(company(TV, DisneyPlusIds.CO_PIXAR))),
            DisneyRowSpec(
                "px_throwbacks", R.string.disney_row_throwbacks,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = MOST_VOTED, runtimeGte = 60, releaseDateLte = "2010-12-31"))
            ),
            DisneyRowSpec(
                "px_sparkshorts", R.string.pixar_row_sparkshorts,
                listOf(
                    curated(SPARKSHORTS),
                    company(
                        MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = RELEASE_ORDER, runtimeLte = 25,
                        releaseDateGte = "2019-01-01", releaseDateLte = "2021-12-31"
                    )
                )
            ),
            DisneyRowSpec("px_forky", R.string.pixar_row_forky, listOf(curated(FORKY_AND_FRIENDS))),
            DisneyRowSpec("px_cars", R.string.disney_row_cars, listOf(curated(CARS))),
            DisneyRowSpec("px_beasts", R.string.pixar_row_beasts_and_monsters, listOf(curated(BEASTS_AND_MONSTERS))),
            DisneyRowSpec("px_unlikely_heroes", R.string.pixar_row_unlikely_heroes, listOf(curated(UNLIKELY_HEROES))),
            DisneyRowSpec("px_oscar_winners", R.string.pixar_row_oscar_winners, listOf(curated(PIXAR_OSCAR_WINNERS))),
            DisneyRowSpec(
                "px_through_the_years", R.string.pixar_row_through_the_years,
                listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = RELEASE_ORDER, runtimeGte = 60, voteCountGte = 50, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "px_collections", R.string.disney_row_collections,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(DisneyPlusHub.COL_TOY_STORY, DisneyPlusHub.COL_CARS, DisneyPlusHub.COL_INCREDIBLES, DisneyPlusHub.COL_INSIDE_OUT, DisneyPlusHub.COL_PIXAR_SHORTS, DisneyPlusHub.COL_SPARKSHORTS, DisneyPlusHub.COL_FORKY)
            )
        )
    )

    private val marvel = DisneyHubSpec(
        hub = DisneyPlusHub.MARVEL,
        heroRowId = "mv_featured",
        rows = listOf(
            DisneyRowSpec("mv_featured", R.string.disney_row_featured, listOf(curated(MARVEL_PAGE_FEATURED))).withSeeAll(company(MOVIE, DisneyPlusIds.CO_MARVEL_STUDIOS, sortBy = MOST_VOTED), company(TV, DisneyPlusIds.CO_MARVEL_STUDIOS), DisneyRowSource.Discover(TV, companyQuery = "Marvel Television")),
            DisneyRowSpec("mv_countdown", R.string.marvel_row_avengers_countdown, listOf(curated(AVENGERS_COUNTDOWN))),
            DisneyRowSpec("mv_infinity_saga", R.string.marvel_row_infinity_saga, listOf(curated(MCU_INFINITY_SAGA))),
            DisneyRowSpec("mv_multiverse_saga", R.string.marvel_row_multiverse_saga, listOf(curated(MCU_MULTIVERSE_SAGA))),
            DisneyRowSpec("mv_spider_verse", R.string.marvel_row_spider_verse, listOf(curated(SPIDER_VERSE))).withSeeAll(*keywordCatalog("spider-man")),
            DisneyRowSpec("mv_movie_timeline", R.string.marvel_row_movie_timeline, listOf(curated(MCU_MOVIE_TIMELINE))),
            DisneyRowSpec("mv_monsters", R.string.marvel_row_monsters, listOf(curated(MARVEL_MONSTERS))),
            DisneyRowSpec("mv_prepare_for_vision", R.string.marvel_row_prepare_for_vision, listOf(curated(PREPARE_FOR_VISION))),
            DisneyRowSpec("mv_mutant_legacy", R.string.marvel_row_mutant_legacy, listOf(curated(MUTANT_LEGACY))).withSeeAll(*keywordCatalog("x-men")),
            DisneyRowSpec("mv_fantastic", R.string.marvel_row_fantastic_adventures, listOf(curated(FANTASTIC_ADVENTURES))).withSeeAll(*keywordCatalog("fantastic four")),
            DisneyRowSpec("mv_origin_stories", R.string.marvel_row_origin_stories, listOf(curated(MARVEL_ORIGIN_STORIES))),
            DisneyRowSpec("mv_defenders", R.string.marvel_row_defenders, listOf(curated(THE_DEFENDERS))),
            DisneyRowSpec("mv_complete_timeline", R.string.marvel_row_complete_timeline, listOf(curated(MCU_COMPLETE_TIMELINE))),
            DisneyRowSpec("mv_sam_wilson", R.string.marvel_row_sam_wilson, listOf(curated(SAM_WILSON))),
            DisneyRowSpec("mv_rivals", R.string.marvel_row_rivals_characters, listOf(curated(MARVEL_RIVALS_CHARACTERS))),
            DisneyRowSpec(
                "mv_character_collections", R.string.marvel_row_character_collections,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(DisneyPlusHub.COL_INFINITY_SAGA, DisneyPlusHub.COL_AVENGERS, DisneyPlusHub.COL_IRON_SUIT, DisneyPlusHub.COL_SPIDER_MAN, DisneyPlusHub.COL_THOR, DisneyPlusHub.COL_BLACK_PANTHER, DisneyPlusHub.COL_BLACK_WIDOW, DisneyPlusHub.COL_ANT_MAN, DisneyPlusHub.COL_DOCTOR_STRANGE, DisneyPlusHub.COL_LOKI, DisneyPlusHub.COL_WANDA_VISION, DisneyPlusHub.COL_FALCON_WINTER_SOLDIER, DisneyPlusHub.COL_DAREDEVIL)
            ),
            DisneyRowSpec(
                "mv_animation", R.string.marvel_row_animation,
                listOf(curated(MARVEL_ANIMATION), company(TV, DisneyPlusIds.CO_MARVEL_ANIMATION))
            ),
            DisneyRowSpec("mv_imax", R.string.marvel_row_imax_enhanced, listOf(curated(MARVEL_IMAX_ENHANCED))),
            DisneyRowSpec(
                "mv_unscripted", R.string.marvel_row_unscripted,
                listOf(
                    curated(MARVEL_UNSCRIPTED),
                    DisneyRowSource.Discover(TV, companies = DisneyPlusIds.CO_MARVEL_STUDIOS, genres = DisneyPlusIds.G_DOCUMENTARY)
                )
            )
        )
    )

    private val starWars = DisneyHubSpec(
        hub = DisneyPlusHub.STAR_WARS,
        heroRowId = "sw_featured",
        rows = listOf(
            DisneyRowSpec("sw_featured", R.string.disney_row_featured, listOf(curated(STAR_WARS_PAGE_FEATURED))).withSeeAll(*keywordCatalog("star wars")),
            DisneyRowSpec("sw_movies", R.string.disney_row_movies, listOf(curated(STAR_WARS_MOVIES_RELEASE))),
            DisneyRowSpec("sw_timeline", R.string.sw_row_timeline, listOf(curated(STAR_WARS_TIMELINE))),
            DisneyRowSpec("sw_series_specials", R.string.sw_row_series_and_specials, listOf(curated(STAR_WARS_SERIES_AND_SPECIALS))).withSeeAll(*keywordCatalog("star wars")),
            DisneyRowSpec("sw_references", R.string.sw_row_references, listOf(curated(STAR_WARS_REFERENCES))),
            DisneyRowSpec(
                "sw_collections", R.string.disney_row_collections,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(DisneyPlusHub.COL_SKYWALKER_SAGA, DisneyPlusHub.COL_MANDALORIAN_GROGU, DisneyPlusHub.COL_DARTH_VADER, DisneyPlusHub.COL_OBI_WAN, DisneyPlusHub.COL_STAR_WARS_TALES, DisneyPlusHub.COL_LUCASFILM_ANIMATION, DisneyPlusHub.COL_STAR_WARS_VINTAGE)
            ),
            DisneyRowSpec("sw_animation", R.string.sw_row_animation, listOf(curated(STAR_WARS_ANIMATION))).withSeeAll(*keywordCatalog("star wars", genres = DisneyPlusIds.G_ANIMATION)),
            DisneyRowSpec(
                "sw_documentaries", R.string.disney_row_documentaries,
                listOf(
                    curated(STAR_WARS_DOCUMENTARIES),
                    DisneyRowSource.Discover(MOVIE, companies = DisneyPlusIds.CO_LUCASFILM, genres = DisneyPlusIds.G_DOCUMENTARY),
                    DisneyRowSource.Discover(TV, companies = DisneyPlusIds.CO_LUCASFILM, genres = DisneyPlusIds.G_DOCUMENTARY)
                )
            ),
            DisneyRowSpec(
                "sw_shorts", R.string.disney_row_shorts,
                listOf(curated(STAR_WARS_SHORTS), company(MOVIE, DisneyPlusIds.CO_LUCASFILM, runtimeLte = 30))
            ),
            DisneyRowSpec("sw_vintage", R.string.sw_row_vintage, listOf(curated(STAR_WARS_VINTAGE))),
            DisneyRowSpec("sw_obi_wan", R.string.sw_row_obi_wan, listOf(curated(OBI_WAN_KENOBI))),
            DisneyRowSpec("sw_darth_vader", R.string.sw_row_darth_vader, listOf(curated(DARTH_VADER)))
        )
    )

    private val natGeo = DisneyHubSpec(
        hub = DisneyPlusHub.NAT_GEO,
        heroRowId = "ng_featured",
        rows = listOf(
            DisneyRowSpec(
                "ng_featured", R.string.disney_row_featured,
                listOf(
                    tvNetwork(DisneyPlusIds.NET_NAT_GEO),
                    DisneyRowSource.Discover(TV, networks = DisneyPlusIds.NET_DISNEY_PLUS, companies = DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC),
                    company(MOVIE, DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC)
                )
            ),
            DisneyRowSpec(
                "ng_originals", R.string.disney_row_originals_short,
                listOf(
                    curated(NAT_GEO_ORIGINALS),
                    DisneyRowSource.Discover(TV, networks = DisneyPlusIds.NET_DISNEY_PLUS, companies = DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC)
                )
            ),
            DisneyRowSpec("ng_movies", R.string.disney_row_movies, listOf(company(MOVIE, DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC))),
            DisneyRowSpec(
                "ng_food_travel", R.string.natgeo_row_food_and_travel,
                listOf(
                    curated(NAT_GEO_FOOD_AND_TRAVEL),
                    net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "travel"),
                    net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "food")
                )
            ),
            DisneyRowSpec("ng_series", R.string.disney_row_series, listOf(tvNetwork(DisneyPlusIds.NET_NAT_GEO, sortBy = MOST_VOTED))),
            DisneyRowSpec(
                "ng_exploring", R.string.natgeo_row_exploring_our_world,
                listOf(curated(NAT_GEO_EXPLORING), net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "exploration"))
            ),
            DisneyRowSpec(
                "ng_collections", R.string.disney_row_collections,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(DisneyPlusHub.COL_NG_ANIMALS_NATURE, DisneyPlusHub.COL_NG_EXPLORING, DisneyPlusHub.COL_NG_SPACE, DisneyPlusHub.COL_NG_EARTH_MONTH, DisneyPlusHub.COL_NG_OUR_HOME, DisneyPlusHub.COL_NG_VETS_PETS, DisneyPlusHub.COL_NG_SHARKS)
            ),
            DisneyRowSpec(
                "ng_sharks", R.string.natgeo_row_sharks,
                listOf(
                    net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "shark"),
                    net(null, DisneyPlusIds.NAT_GEO_WILD, keywordQuery = "shark"),
                    DisneyRowSource.Discover(MOVIE, companies = DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC, keywordQuery = "shark")
                )
            ),
            DisneyRowSpec(
                "ng_animals", R.string.natgeo_row_animals_and_nature,
                listOf(
                    curated(NAT_GEO_ANIMALS),
                    net(DisneyPlusIds.NET_NAT_GEO, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature"),
                    net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "animal")
                )
            ),
            DisneyRowSpec("ng_wild", R.string.natgeo_row_wild_series, listOf(net(null, DisneyPlusIds.NAT_GEO_WILD))),
            DisneyRowSpec(
                "ng_history", R.string.natgeo_row_world_history,
                listOf(
                    net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "history"),
                    DisneyRowSource.Discover(MOVIE, companies = DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC, keywordQuery = "history")
                )
            ),
            DisneyRowSpec(
                "ng_vets", R.string.natgeo_row_vets_and_pets,
                listOf(curated(NAT_GEO_VETS_AND_PETS), net(null, DisneyPlusIds.NAT_GEO_WILD, keywordQuery = "veterinarian"))
            ),
            DisneyRowSpec(
                "ng_science", R.string.natgeo_row_science_and_innovation,
                listOf(curated(NAT_GEO_SCIENCE), net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "science"))
            ),
            DisneyRowSpec(
                "ng_ancient", R.string.natgeo_row_ancient_mysteries,
                listOf(curated(NAT_GEO_ANCIENT_MYSTERIES), net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "archaeology"))
            ),
            DisneyRowSpec(
                "ng_kids", R.string.natgeo_row_kids,
                listOf(curated(NAT_GEO_KIDS), net(DisneyPlusIds.NET_NAT_GEO, genres = DisneyPlusIds.G_TV_KIDS))
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
            DisneyRowSpec("wdas_featured", R.string.disney_row_featured, listOf(curated(WDAS_FEATURED))).withSeeAll(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_ANIMATION, sortBy = MOST_VOTED)),
            DisneyRowSpec(
                "wdas_all", R.string.disney_row_all_films_release_order,
                listOf(company(MOVIE, wdas, sortBy = RELEASE_ORDER, runtimeGte = 60, voteCountGte = 50, releaseDateLte = TODAY))
            ),
            DisneyRowSpec("wdas_popular", R.string.disney_row_popular, listOf(company(MOVIE, wdas, runtimeGte = 60, voteCountGte = 200))),
            DisneyRowSpec("wdas_classics", R.string.disney_row_classics, listOf(curated(CLASSICS))).withSeeAll(company(MOVIE, DisneyPlusIds.CO_WALT_DISNEY_ANIMATION, sortBy = MOST_VOTED, runtimeGte = 40)),
            DisneyRowSpec("wdas_princesses", R.string.disney_row_princesses, listOf(curated(PRINCESSES))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companies = DISNEY_FILM_STUDIOS, keywordQuery = "princess")),
            DisneyRowSpec("wdas_shorts", R.string.disney_row_shorts, listOf(company(MOVIE, wdas, runtimeLte = 30, voteCountGte = 10)))
        )
    )

    private val colDisneyJr = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEY_JR,
        heroRowId = "jr_featured",
        rows = listOf(
            DisneyRowSpec("jr_featured", R.string.disney_row_featured, listOf(curated(DISNEY_JR_FEATURED))).withSeeAll(net(DisneyPlusIds.NET_DISNEY_JUNIOR, sortBy = MOST_VOTED)),
            DisneyRowSpec("jr_popular", R.string.disney_row_popular_shows, listOf(tvNetwork(DisneyPlusIds.NET_DISNEY_JUNIOR))),
            DisneyRowSpec(
                "jr_new", R.string.disney_row_new_short,
                listOf(DisneyRowSource.Discover(TV, sortBy = RECENT_TV, networks = DisneyPlusIds.NET_DISNEY_JUNIOR, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "jr_movies", R.string.disney_row_movies,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Junior"))
            ),
            DisneyRowSpec("jr_mickey", R.string.disney_row_mickey_and_friends, listOf(curated(MICKEY_AND_FRIENDS))).withSeeAll(*keywordCatalog("mickey mouse"))
        )
    )

    private val colDisneyChannel = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEY_CHANNEL,
        heroRowId = "dc_featured",
        rows = listOf(
            DisneyRowSpec("dc_featured", R.string.disney_row_featured, listOf(curated(DISNEY_CHANNEL_FEATURED))).withSeeAll(net(DisneyPlusIds.NET_DISNEY_CHANNEL, sortBy = MOST_VOTED)),
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
            DisneyRowSpec("dc_dcom", R.string.disney_col_dcom, listOf(curated(DCOM_FEATURED))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Channel"))
        )
    )

    private val colDisneyXd = DisneyHubSpec(
        hub = DisneyPlusHub.COL_DISNEY_XD,
        heroRowId = "xd_featured",
        rows = listOf(
            DisneyRowSpec("xd_featured", R.string.disney_row_featured, listOf(curated(DISNEY_XD_FEATURED))).withSeeAll(net(DisneyPlusIds.NET_DISNEY_XD, sortBy = MOST_VOTED)),
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
            DisneyRowSpec("dn_films", R.string.disney_row_disneynature_films, listOf(curated(DISNEYNATURE))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disneynature")),
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
            DisneyRowSpec("dcom_featured", R.string.disney_row_featured, listOf(curated(DCOM_FEATURED))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Channel")),
            DisneyRowSpec("dcom_musicals", R.string.disney_row_musicals, listOf(curated(DCOM_MUSICALS))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Channel", genres = "10402")),
            DisneyRowSpec("dcom_classics", R.string.disney_row_all_time_favorites, listOf(curated(DCOM_CLASSICS))).withSeeAll(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, companyQuery = "Disney Channel")),
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

    // ------------------------------------------------------------------ Paramount+ Universes

    private val STAR_TREK_UNIVERSE = listOf(
        series("Star Trek", 1966),
        series("Star Trek: The Animated Series", 1973),
        series("Star Trek: The Next Generation", 1987),
        series("Star Trek: Deep Space Nine", 1993),
        series("Star Trek: Voyager", 1995),
        series("Star Trek: Enterprise", 2001),
        series("Star Trek: Discovery", 2017),
        series("Star Trek: Short Treks", 2018),
        series("Star Trek: Picard", 2020),
        series("Star Trek: Lower Decks", 2020),
        series("Star Trek: Prodigy", 2021),
        series("Star Trek: Strange New Worlds", 2022),
        CuratedTitle("Star Trek: Starfleet Academy", null, TV),
        movie("Star Trek: The Motion Picture", 1979),
        movie("Star Trek II: The Wrath of Khan", 1982),
        movie("Star Trek III: The Search for Spock", 1984),
        movie("Star Trek IV: The Voyage Home", 1986),
        movie("Star Trek V: The Final Frontier", 1989),
        movie("Star Trek VI: The Undiscovered Country", 1991),
        movie("Star Trek: Generations", 1994),
        movie("Star Trek: First Contact", 1996),
        movie("Star Trek: Insurrection", 1998),
        movie("Star Trek: Nemesis", 2002),
        movie("Star Trek", 2009),
        movie("Star Trek Into Darkness", 2013),
        movie("Star Trek Beyond", 2016),
        movie("Star Trek: Section 31", 2025)
    )

    private val TAYLOR_SHERIDAN_UNIVERSE = listOf(
        series("Yellowstone", 2018),
        series("1883", 2021),
        series("1923", 2022),
        series("Landman", 2024),
        series("Mayor of Kingstown", 2021),
        series("Tulsa King", 2022),
        series("Lioness", 2023),
        series("Lawmen: Bass Reeves", 2023)
    )

    private val MISSION_IMPOSSIBLE_UNIVERSE = listOf(
        movie("Mission: Impossible", 1996),
        movie("Mission: Impossible II", 2000),
        movie("Mission: Impossible III", 2006),
        movie("Mission: Impossible - Ghost Protocol", 2011),
        movie("Mission: Impossible - Rogue Nation", 2015),
        movie("Mission: Impossible - Fallout", 2018),
        movie("Mission: Impossible - Dead Reckoning Part One", 2023),
        movie("Mission: Impossible - The Final Reckoning", 2025),
        // Tom Cruise at Paramount
        movie("Top Gun", 1986),
        movie("Top Gun: Maverick", 2022),
        movie("Days of Thunder", 1990),
        movie("War of the Worlds", 2005),
        movie("Jack Reacher", 2012),
        movie("Jack Reacher: Never Go Back", 2016),
        series("Mission: Impossible", 1966)
    )

    private val TYLER_PERRY_TELEVISION = listOf(
        series("Tyler Perry's Sistas", 2019),
        series("Tyler Perry's The Oval", 2019),
        series("All the Queen's Men", 2021),
        CuratedTitle("Tyler Perry's Perimeter", null, TV),
        series("Tyler Perry's Bruh", 2020),
        series("Tyler Perry's Ruthless", 2020),
        series("Tyler Perry's Zatima", 2022),
        series("Tyler Perry's Assisted Living", 2020),
        CuratedTitle("Tyler Perry's Divorced Sistas", null, TV),
        series("Tyler Perry's Young Dylan", 2020),
        CuratedTitle("Tyler Perry's House of Payne", null, TV)
    )

    private val QUIET_PLACE_SCREAM = listOf(
        movie("A Quiet Place", 2018),
        CuratedTitle("A Quiet Place Part II", null, MOVIE),
        movie("A Quiet Place: Day One", 2024),
        movie("Scream", 1996),
        movie("Scream 2", 1997),
        movie("Scream 3", 2000),
        movie("Scream 4", 2011),
        movie("Scream", 2022),
        movie("Scream VI", 2023),
        CuratedTitle("Scream 7", null, MOVIE),
        series("Scream", 2015)
    )

    private val DEXTER_UNIVERSE = listOf(
        series("Dexter", 2006),
        series("Dexter: New Blood", 2021),
        series("Dexter: Original Sin", 2024),
        series("Dexter: Resurrection", 2025)
    )

    private val NCIS_UNIVERSE = listOf(
        series("NCIS", 2003),
        series("NCIS: Origins", 2024),
        series("NCIS: Tony & Ziva", 2025),
        series("NCIS: Los Angeles", 2009),
        series("NCIS: New Orleans", 2014),
        series("NCIS: Hawai'i", 2021),
        series("NCIS: Sydney", 2023),
        series("JAG", 1995)
    )

    private val CSI_UNIVERSE = listOf(
        series("CSI: Crime Scene Investigation", 2000),
        series("CSI: Miami", 2002),
        series("CSI: NY", 2004),
        series("CSI: Cyber", 2015),
        series("CSI: Vegas", 2021)
    )

    private val CRIMINAL_MINDS_UNIVERSE = listOf(
        series("Criminal Minds", 2005),
        series("Criminal Minds: Suspect Behavior", 2011),
        series("Criminal Minds: Beyond Borders", 2016)
    )

    private val SPONGEBOB_UNIVERSE = listOf(
        series("SpongeBob SquarePants", 1999),
        series("Kamp Koral: SpongeBob's Under Years", 2021),
        series("The Patrick Star Show", 2021),
        movie("The SpongeBob SquarePants Movie", 2004),
        movie("The SpongeBob Movie: Sponge Out of Water", 2015),
        movie("The SpongeBob Movie: Sponge on the Run", 2020),
        movie("Saving Bikini Bottom: The Sandy Cheeks Movie", 2024),
        movie("Plankton: The Movie", 2025),
        CuratedTitle("The SpongeBob Movie: Search for SquarePants", null, MOVIE)
    )

    private val SOUTH_PARK_UNIVERSE = listOf(
        series("South Park", 1997),
        movie("South Park: Bigger, Longer & Uncut", 1999),
        movie("South Park: Post COVID", 2021),
        movie("South Park: Post COVID: The Return of COVID", 2021),
        movie("South Park the Streaming Wars", 2022),
        movie("South Park the Streaming Wars Part 2", 2022),
        movie("South Park: Joining the Panderverse", 2023),
        movie("South Park (Not Suitable for Children)", 2023),
        movie("South Park: The End of Obesity", 2024)
    )

    private val CHALLENGE_UNIVERSE = listOf(
        series("The Challenge", 1998),
        series("The Challenge: USA", 2022),
        series("The Challenge: UK", 2023),
        series("The Challenge: Australia", 2022),
        CuratedTitle("The Challenge: Argentina", null, TV),
        series("The Challenge: All Stars", 2021),
        series("The Challenge: World Championship", 2023)
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
            // The franchises Paramount+ is built around, as tiles that open each one's page.
            DisneyRowSpec(
                "p_universes", R.string.paramount_row_universes,
                kind = DisneyRowKind.COLLECTIONS,
                collections = listOf(
                    DisneyPlusHub.PU_STAR_TREK,
                    DisneyPlusHub.PU_TAYLOR_SHERIDAN,
                    DisneyPlusHub.PU_MISSION_IMPOSSIBLE,
                    DisneyPlusHub.PU_TYLER_PERRY,
                    DisneyPlusHub.PU_QUIET_PLACE_SCREAM,
                    DisneyPlusHub.PU_DEXTER,
                    DisneyPlusHub.PU_NCIS,
                    DisneyPlusHub.PU_CSI,
                    DisneyPlusHub.PU_CRIMINAL_MINDS,
                    DisneyPlusHub.PU_SPONGEBOB,
                    DisneyPlusHub.PU_SOUTH_PARK,
                    DisneyPlusHub.PU_CHALLENGE
                )
            ),
            DisneyRowSpec("p_originals", R.string.paramount_row_originals, listOf(net(ParamountPlusIds.NET_PARAMOUNT_PLUS))),
            DisneyRowSpec("p_sheridan", R.string.paramount_row_sheridan, listOf(curated(TAYLOR_SHERIDAN))),
            DisneyRowSpec("p_cbs", R.string.paramount_brand_cbs, listOf(net(ParamountPlusIds.NET_CBS))),
            DisneyRowSpec("p_star_trek", R.string.paramount_row_star_trek, listOf(curated(STAR_TREK_SERIES), curated(STAR_TREK_MOVIES))).withSeeAll(*keywordCatalog("star trek")),
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
            DisneyRowSpec("hm_looney_tunes", R.string.hbomax_brand_looney_tunes, listOf(curated(LOONEY_TUNES))).withSeeAll(*keywordCatalog("looney tunes")),
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
            DisneyRowSpec("lt_featured", R.string.disney_row_featured, listOf(curated(LOONEY_TUNES))).withSeeAll(*keywordCatalog("looney tunes")),
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

    private val TCM_ESSENTIALS = listOf(
        movie("Casablanca", 1942),
        movie("Citizen Kane", 1941),
        movie("The Wizard of Oz", 1939),
        movie("Singin' in the Rain", 1952),
        movie("Gone with the Wind", 1939),
        movie("North by Northwest", 1959),
        movie("The Maltese Falcon", 1941),
        movie("Ben-Hur", 1959),
        movie("A Streetcar Named Desire", 1951),
        movie("Rebel Without a Cause", 1955),
        movie("The Treasure of the Sierra Madre", 1948),
        movie("East of Eden", 1955),
        movie("Doctor Zhivago", 1965),
        movie("2001: A Space Odyssey", 1968)
    )

    /** Classic movies (up to [until]), from every studio. */
    private fun classics(
        until: String,
        sortBy: String = MOST_VOTED,
        genres: String? = null,
        keywordQuery: String? = null,
        voteCountGte: Int? = null
    ) = DisneyRowSource.Discover(
        mediaType = MOVIE,
        sortBy = sortBy,
        genres = genres,
        keywordQuery = keywordQuery,
        voteCountGte = voteCountGte,
        releaseDateLte = until
    )

    private val hboMaxTcm = DisneyHubSpec(
        hub = DisneyPlusHub.H_TCM,
        heroRowId = "tcm_essentials",
        rows = listOf(
            DisneyRowSpec("tcm_essentials", R.string.hbomax_row_tcm_essentials, listOf(curated(TCM_ESSENTIALS))).withSeeAll(onHboMax(MOVIE, sortBy = MOST_VOTED, releaseDateLte = "1979-12-31")),
            DisneyRowSpec(
                "tcm_on_hbo_max", R.string.hbomax_row_classics_on_hbo_max,
                listOf(onHboMax(MOVIE, voteCountGte = 100, releaseDateLte = "1979-12-31"))
            ),
            DisneyRowSpec(
                "tcm_golden_age", R.string.hbomax_row_golden_age,
                listOf(
                    studio(MOVIE, HboMaxIds.WB_PICTURES, sortBy = MOST_VOTED, releaseDateLte = "1959-12-31"),
                    studio(MOVIE, HboMaxIds.MGM, sortBy = MOST_VOTED, releaseDateLte = "1959-12-31")
                )
            ),
            DisneyRowSpec("tcm_noir", R.string.hbomax_row_film_noir, listOf(classics("1959-12-31", keywordQuery = "film noir"))),
            DisneyRowSpec("tcm_musicals", R.string.hbomax_row_classic_musicals, listOf(classics("1969-12-31", genres = HboMaxIds.G_MUSIC))),
            DisneyRowSpec("tcm_westerns", R.string.hbomax_row_classic_westerns, listOf(classics("1969-12-31", genres = HboMaxIds.G_WESTERN))),
            DisneyRowSpec("tcm_silent", R.string.hbomax_row_silent_films, listOf(classics("1930-12-31", keywordQuery = "silent film"))),
            DisneyRowSpec(
                "tcm_top_rated", R.string.hbomax_row_top_rated_classics,
                listOf(classics("1969-12-31", sortBy = TOP_RATED, voteCountGte = 1000))
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

    // ================================================================== Peacock

    private val ONE_CHICAGO_LAW_ORDER = listOf(
        series("Chicago Fire", 2012),
        series("Chicago P.D.", 2014),
        series("Chicago Med", 2015),
        series("Law & Order", 1990),
        series("Law & Order: Special Victims Unit", 1999),
        series("Law & Order: Organized Crime", 2021),
        series("Chicago Justice", 2017)
    )

    private val REAL_HOUSEWIVES = listOf(
        series("The Real Housewives of Beverly Hills", 2010),
        series("The Real Housewives of Atlanta", 2008),
        series("The Real Housewives of New Jersey", 2009),
        series("The Real Housewives of Orange County", 2006),
        series("The Real Housewives of New York City", 2008),
        series("The Real Housewives of Potomac", 2016),
        series("The Real Housewives of Salt Lake City", 2020),
        series("The Real Housewives of Miami", 2011),
        series("The Real Housewives of Dubai", 2022)
    )

    private val BELOW_DECK = listOf(
        series("Below Deck", 2013),
        series("Below Deck Mediterranean", 2016),
        series("Below Deck Sailing Yacht", 2020),
        series("Below Deck Down Under", 2022),
        series("Below Deck Adventure", 2022)
    )

    private val JURASSIC = listOf(
        movie("Jurassic Park", 1993),
        movie("The Lost World: Jurassic Park", 1997),
        movie("Jurassic Park III", 2001),
        movie("Jurassic World", 2015),
        movie("Jurassic World: Fallen Kingdom", 2018),
        movie("Jurassic World Dominion", 2022),
        movie("Jurassic World Rebirth", 2025)
    )

    private val FAST_FURIOUS = listOf(
        movie("The Fast and the Furious", 2001),
        movie("2 Fast 2 Furious", 2003),
        movie("The Fast and the Furious: Tokyo Drift", 2006),
        movie("Fast & Furious", 2009),
        movie("Fast Five", 2011),
        movie("Fast & Furious 6", 2013),
        movie("Furious 7", 2015),
        movie("The Fate of the Furious", 2017),
        movie("Hobbs & Shaw", 2019),
        movie("F9", 2021),
        movie("Fast X", 2023)
    )

    private val UNIVERSAL_MONSTERS = listOf(
        movie("Dracula", 1931),
        movie("Frankenstein", 1931),
        movie("The Mummy", 1932),
        movie("The Invisible Man", 1933),
        movie("Bride of Frankenstein", 1935),
        movie("The Wolf Man", 1941),
        movie("Creature from the Black Lagoon", 1954),
        movie("The Invisible Man", 2020),
        movie("Wolf Man", 2025)
    )

    private val DESPICABLE_ME = listOf(
        movie("Despicable Me", 2010),
        movie("Despicable Me 2", 2013),
        movie("Minions", 2015),
        movie("Despicable Me 3", 2017),
        movie("Minions: The Rise of Gru", 2022),
        movie("Despicable Me 4", 2024)
    )

    private val SHREK = listOf(
        movie("Shrek", 2001),
        movie("Shrek 2", 2004),
        movie("Shrek the Third", 2007),
        movie("Shrek Forever After", 2010),
        movie("Puss in Boots", 2011),
        movie("Puss in Boots: The Last Wish", 2022)
    )

    private val HOW_TO_TRAIN_YOUR_DRAGON = listOf(
        movie("How to Train Your Dragon", 2010),
        movie("How to Train Your Dragon 2", 2014),
        movie("How to Train Your Dragon: The Hidden World", 2019),
        movie("How to Train Your Dragon", 2025)
    )

    private val KUNG_FU_PANDA = listOf(
        movie("Kung Fu Panda", 2008),
        movie("Kung Fu Panda 2", 2011),
        movie("Kung Fu Panda 3", 2016),
        movie("Kung Fu Panda 4", 2024)
    )

    private val AMBLIN_CLASSICS = listOf(
        movie("E.T. the Extra-Terrestrial", 1982),
        movie("Back to the Future", 1985),
        movie("The Goonies", 1985),
        movie("Gremlins", 1984),
        movie("Who Framed Roger Rabbit", 1988),
        movie("An American Tail", 1986),
        movie("The Land Before Time", 1988),
        movie("Back to the Future Part II", 1989),
        movie("Jurassic Park", 1993),
        movie("Casper", 1995),
        movie("Twister", 1996),
        movie("Men in Black", 1997)
    )

    private fun onPeacock(
        mediaType: TmdbCollectionMediaType,
        sortBy: String = POPULAR,
        genres: String? = null,
        keywordQuery: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null
    ) = DisneyRowSource.Discover(
        mediaType = mediaType,
        sortBy = sortBy,
        providers = PeacockIds.PROVIDERS,
        genres = genres,
        keywordQuery = keywordQuery,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte
    )

    /** Movies from a studio by its TMDB company id(s). */
    private fun studioMovies(
        companies: String,
        sortBy: String = POPULAR,
        genres: String? = null,
        voteCountGte: Int? = null,
        releaseDateLte: String? = null,
        onPeacock: Boolean = false
    ) = DisneyRowSource.Discover(
        mediaType = MOVIE,
        sortBy = sortBy,
        companies = companies,
        genres = genres,
        voteCountGte = voteCountGte,
        releaseDateLte = releaseDateLte,
        providers = if (onPeacock) PeacockIds.PROVIDERS else null
    )

    /**
     * A movie studio's page: popular, new, on Peacock, its franchises ([middleRows]), classics and top
     * rated. [extraSources] adds more sources to every row (e.g. Amblin Partners next to Amblin Entertainment).
     */
    private fun studioPage(
        hub: DisneyPlusHub,
        prefix: String,
        companies: String,
        middleRows: List<DisneyRowSpec> = emptyList(),
        classicUntil: String? = "1999-12-31",
        topRatedMinVotes: Int = 1000,
        extraSources: (sortBy: String, voteCountGte: Int?, releaseDateLte: String?) -> List<DisneyRowSource> = { _, _, _ -> emptyList() }
    ) = DisneyHubSpec(
        hub = hub,
        heroRowId = "${prefix}_popular",
        rows = buildList {
            add(
                DisneyRowSpec(
                    "${prefix}_popular", R.string.peacock_row_popular_movies,
                    listOf(studioMovies(companies, voteCountGte = 50)) + extraSources(POPULAR, 50, null)
                )
            )
            add(
                DisneyRowSpec(
                    "${prefix}_new", R.string.disney_row_new_short,
                    listOf(studioMovies(companies, sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY)) +
                        extraSources(RECENT, 10, TODAY)
                )
            )
            add(
                DisneyRowSpec(
                    "${prefix}_on_peacock", R.string.peacock_row_on_peacock,
                    listOf(studioMovies(companies, onPeacock = true))
                )
            )
            addAll(middleRows)
            if (classicUntil != null) {
                add(
                    DisneyRowSpec(
                        "${prefix}_classics", R.string.peacock_row_classics,
                        listOf(studioMovies(companies, sortBy = MOST_VOTED, releaseDateLte = classicUntil)) +
                            extraSources(MOST_VOTED, null, classicUntil)
                    )
                )
            }
            add(
                DisneyRowSpec(
                    "${prefix}_top_rated", R.string.disney_row_top_rated,
                    listOf(studioMovies(companies, sortBy = TOP_RATED, voteCountGte = topRatedMinVotes)) +
                        extraSources(TOP_RATED, topRatedMinVotes, null)
                )
            )
        }
    )

    private val peacockMain = DisneyHubSpec(
        hub = DisneyPlusHub.PEACOCK_MAIN,
        heroRowId = "pk_trending",
        rows = listOf(
            DisneyRowSpec("continue_watching", R.string.disney_row_continue_watching, kind = DisneyRowKind.CONTINUE_WATCHING),
            DisneyRowSpec("recommended", R.string.disney_row_recommended, kind = DisneyRowKind.RECOMMENDED),
            DisneyRowSpec(
                "pk_new", R.string.peacock_row_new,
                listOf(
                    onPeacock(MOVIE, sortBy = RECENT, voteCountGte = 10, releaseDateLte = TODAY),
                    onPeacock(TV, sortBy = RECENT_TV, voteCountGte = 5, releaseDateLte = TODAY)
                )
            ),
            DisneyRowSpec("pk_trending", R.string.disney_row_trending, listOf(onPeacock(MOVIE), onPeacock(TV))),
            DisneyRowSpec("pk_originals", R.string.peacock_brand_originals, listOf(net(PeacockIds.NET_PEACOCK))),
            DisneyRowSpec("pk_nbc", R.string.peacock_row_nbc_shows, listOf(net(PeacockIds.NET_NBC))),
            DisneyRowSpec("pk_one_chicago", R.string.peacock_row_one_chicago, listOf(curated(ONE_CHICAGO_LAW_ORDER))),
            DisneyRowSpec("pk_bravo", R.string.peacock_brand_bravo, listOf(net(null, PeacockIds.BRAVO))),
            DisneyRowSpec(
                "pk_universal", R.string.peacock_brand_universal,
                listOf(studioMovies(PeacockIds.CO_UNIVERSAL, voteCountGte = 300, releaseDateLte = TODAY))
            ),
            DisneyRowSpec(
                "pk_animation", R.string.peacock_row_animation,
                listOf(
                    studioMovies(PeacockIds.CO_DREAMWORKS_ANIMATION, voteCountGte = 300),
                    studioMovies(PeacockIds.CO_ILLUMINATION, voteCountGte = 300)
                )
            ),
            DisneyRowSpec("pk_wwe", R.string.peacock_brand_wwe, listOf(DisneyRowSource.Discover(TV, keywordQuery = "wwe"))),
            DisneyRowSpec("pk_telemundo", R.string.peacock_brand_telemundo, listOf(net(null, PeacockIds.TELEMUNDO))),
            DisneyRowSpec("pk_movies", R.string.disney_row_movies, listOf(onPeacock(MOVIE, voteCountGte = 200))),
            DisneyRowSpec("pk_series", R.string.disney_row_series, listOf(onPeacock(TV, voteCountGte = 50))),
            DisneyRowSpec(
                "pk_drama", R.string.hbomax_row_drama,
                listOf(
                    onPeacock(TV, genres = PeacockIds.G_DRAMA, voteCountGte = 100),
                    onPeacock(MOVIE, genres = PeacockIds.G_DRAMA, voteCountGte = 300)
                )
            ),
            DisneyRowSpec(
                "pk_comedy", R.string.disney_row_comedy,
                listOf(
                    onPeacock(MOVIE, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 100),
                    onPeacock(TV, genres = DisneyPlusIds.G_COMEDY, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "pk_action", R.string.disney_row_action,
                listOf(
                    onPeacock(MOVIE, genres = "${DisneyPlusIds.G_ACTION}|${DisneyPlusIds.G_ADVENTURE}", voteCountGte = 100),
                    onPeacock(TV, genres = DisneyPlusIds.G_TV_ACTION_ADVENTURE, voteCountGte = 50)
                )
            ),
            DisneyRowSpec(
                "pk_kids", R.string.paramount_row_kids,
                listOf(
                    onPeacock(TV, genres = DisneyPlusIds.G_TV_KIDS),
                    onPeacock(MOVIE, genres = "${DisneyPlusIds.G_ANIMATION},${DisneyPlusIds.G_FAMILY}", voteCountGte = 100)
                )
            ),
            DisneyRowSpec(
                "pk_documentaries", R.string.disney_row_documentaries,
                listOf(
                    onPeacock(MOVIE, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 10),
                    onPeacock(TV, genres = DisneyPlusIds.G_DOCUMENTARY, voteCountGte = 5)
                )
            )
        )
    )

    private val peacockOriginals = networkPage(
        hub = DisneyPlusHub.PK_ORIGINALS,
        prefix = "pko",
        networks = PeacockIds.NET_PEACOCK,
        middleRows = listOf(
            DisneyRowSpec("pko_dramas", R.string.paramount_row_dramas, listOf(net(PeacockIds.NET_PEACOCK, genres = PeacockIds.G_DRAMA))),
            DisneyRowSpec("pko_comedies", R.string.paramount_row_comedies, listOf(net(PeacockIds.NET_PEACOCK, genres = DisneyPlusIds.G_COMEDY))),
            DisneyRowSpec("pko_crime", R.string.paramount_row_crime, listOf(net(PeacockIds.NET_PEACOCK, genres = PeacockIds.G_CRIME))),
            DisneyRowSpec(
                "pko_docuseries", R.string.discovery_row_docuseries,
                listOf(net(PeacockIds.NET_PEACOCK, genres = DisneyPlusIds.G_DOCUMENTARY))
            ),
            DisneyRowSpec(
                "pko_reality", R.string.paramount_row_reality_competition,
                listOf(net(PeacockIds.NET_PEACOCK, genres = PeacockIds.G_TV_REALITY))
            )
        ),
        classicUntil = null,
        topRatedMinVotes = 50
    )

    private val peacockNbc = networkPage(
        hub = DisneyPlusHub.PK_NBC,
        prefix = "nbc",
        networks = PeacockIds.NET_NBC,
        middleRows = listOf(
            DisneyRowSpec("nbc_one_chicago", R.string.peacock_row_one_chicago, listOf(curated(ONE_CHICAGO_LAW_ORDER))),
            DisneyRowSpec("nbc_dramas", R.string.paramount_row_dramas, listOf(net(PeacockIds.NET_NBC, genres = PeacockIds.G_DRAMA))),
            DisneyRowSpec("nbc_comedies", R.string.paramount_row_comedies, listOf(net(PeacockIds.NET_NBC, genres = DisneyPlusIds.G_COMEDY))),
            DisneyRowSpec(
                "nbc_reality", R.string.paramount_row_reality_competition,
                listOf(net(PeacockIds.NET_NBC, genres = PeacockIds.G_TV_REALITY))
            ),
            DisneyRowSpec("nbc_late_night", R.string.paramount_row_late_night, listOf(net(PeacockIds.NET_NBC, genres = PeacockIds.G_TV_TALK)))
        ),
        topRatedMinVotes = 300
    )

    private val peacockBravo = networkPage(
        hub = DisneyPlusHub.PK_BRAVO,
        prefix = "bravo",
        networks = null,
        lookup = PeacockIds.BRAVO,
        middleRows = listOf(
            DisneyRowSpec("bravo_housewives", R.string.peacock_row_real_housewives, listOf(curated(REAL_HOUSEWIVES))),
            DisneyRowSpec("bravo_below_deck", R.string.peacock_row_below_deck, listOf(curated(BELOW_DECK))),
            DisneyRowSpec(
                "bravo_reality", R.string.paramount_row_reality,
                listOf(net(null, PeacockIds.BRAVO, genres = PeacockIds.G_TV_REALITY))
            ),
            DisneyRowSpec(
                "bravo_talk", R.string.paramount_row_late_night,
                listOf(net(null, PeacockIds.BRAVO, genres = PeacockIds.G_TV_TALK))
            )
        ),
        classicUntil = "2010-12-31",
        topRatedMinVotes = 20
    )

    private val peacockTelemundo = networkPage(
        hub = DisneyPlusHub.PK_TELEMUNDO,
        prefix = "tlmd",
        networks = null,
        lookup = PeacockIds.TELEMUNDO,
        middleRows = listOf(
            DisneyRowSpec(
                "tlmd_telenovelas", R.string.peacock_row_telenovelas,
                listOf(net(null, PeacockIds.TELEMUNDO, sortBy = MOST_VOTED, genres = PeacockIds.G_DRAMA))
            ),
            DisneyRowSpec(
                "tlmd_crime", R.string.paramount_row_crime,
                listOf(net(null, PeacockIds.TELEMUNDO, genres = PeacockIds.G_CRIME))
            ),
            DisneyRowSpec(
                "tlmd_reality", R.string.paramount_row_reality_competition,
                listOf(net(null, PeacockIds.TELEMUNDO, genres = PeacockIds.G_TV_REALITY))
            )
        ),
        classicUntil = "2012-12-31",
        topRatedMinVotes = 10
    )

    private val peacockWwe = DisneyHubSpec(
        hub = DisneyPlusHub.PK_WWE,
        heroRowId = "wwe_popular",
        rows = listOf(
            DisneyRowSpec("wwe_popular", R.string.peacock_row_wwe_shows, listOf(DisneyRowSource.Discover(TV, keywordQuery = "wwe"))),
            DisneyRowSpec(
                "wwe_events", R.string.peacock_row_wwe_events,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = RECENT, keywordQuery = "wwe", releaseDateLte = TODAY))
            ),
            DisneyRowSpec("wwe_network", R.string.peacock_row_wwe_network, listOf(net(null, PeacockIds.WWE_NETWORK))),
            DisneyRowSpec(
                "wwe_classic_events", R.string.peacock_row_wwe_classic_events,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "wwe"))
            ),
            DisneyRowSpec("wwe_studios", R.string.peacock_row_wwe_studios, listOf(studio(MOVIE, PeacockIds.WWE_STUDIOS, sortBy = MOST_VOTED))),
            DisneyRowSpec(
                "wwe_docs", R.string.peacock_row_wrestling_docs,
                listOf(
                    DisneyRowSource.Discover(TV, keywordQuery = "professional wrestling", genres = DisneyPlusIds.G_DOCUMENTARY),
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "professional wrestling", genres = DisneyPlusIds.G_DOCUMENTARY)
                )
            )
        )
    )

    private val peacockNbcSports = DisneyHubSpec(
        hub = DisneyPlusHub.PK_NBC_SPORTS,
        heroRowId = "nbcs_sports",
        rows = listOf(
            DisneyRowSpec(
                "nbcs_sports", R.string.peacock_row_sports_on_peacock,
                listOf(onPeacock(TV, keywordQuery = "sport"), onPeacock(MOVIE, keywordQuery = "sport"))
            ),
            DisneyRowSpec("nbcs_nbc", R.string.peacock_row_sports_on_nbc, listOf(net(PeacockIds.NET_NBC, keywordQuery = "sport"))),
            DisneyRowSpec(
                "nbcs_football", R.string.peacock_row_football,
                listOf(
                    DisneyRowSource.Discover(TV, keywordQuery = "american football"),
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "american football")
                )
            ),
            DisneyRowSpec(
                "nbcs_soccer", R.string.peacock_row_soccer,
                listOf(
                    DisneyRowSource.Discover(TV, keywordQuery = "soccer"),
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "soccer")
                )
            ),
            DisneyRowSpec(
                "nbcs_olympics", R.string.peacock_row_olympics,
                listOf(
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "olympic games"),
                    DisneyRowSource.Discover(TV, keywordQuery = "olympic games")
                )
            ),
            DisneyRowSpec(
                "nbcs_golf", R.string.peacock_row_golf,
                listOf(
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "golf"),
                    DisneyRowSource.Discover(TV, keywordQuery = "golf")
                )
            ),
            DisneyRowSpec(
                "nbcs_docs", R.string.peacock_row_sports_docs,
                listOf(
                    DisneyRowSource.Discover(TV, keywordQuery = "sport", genres = DisneyPlusIds.G_DOCUMENTARY),
                    DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "sport", genres = DisneyPlusIds.G_DOCUMENTARY)
                )
            ),
            DisneyRowSpec(
                "nbcs_movies", R.string.peacock_row_sports_movies,
                listOf(DisneyRowSource.Discover(MOVIE, sortBy = MOST_VOTED, keywordQuery = "sport", withoutGenres = DisneyPlusIds.G_DOCUMENTARY))
            )
        )
    )

    private val peacockUniversal = studioPage(
        hub = DisneyPlusHub.PK_UNIVERSAL,
        prefix = "uni",
        companies = PeacockIds.CO_UNIVERSAL,
        middleRows = listOf(
            DisneyRowSpec("uni_jurassic", R.string.peacock_row_jurassic, listOf(curated(JURASSIC))),
            DisneyRowSpec("uni_fast", R.string.peacock_row_fast_furious, listOf(curated(FAST_FURIOUS))),
            DisneyRowSpec("uni_monsters", R.string.peacock_row_universal_monsters, listOf(curated(UNIVERSAL_MONSTERS))),
            DisneyRowSpec(
                "uni_animation", R.string.peacock_row_animation,
                listOf(
                    studioMovies(PeacockIds.CO_ILLUMINATION, voteCountGte = 300),
                    studioMovies(PeacockIds.CO_DREAMWORKS_ANIMATION, voteCountGte = 300)
                )
            )
        ),
        classicUntil = "1989-12-31",
        topRatedMinVotes = 3000
    )

    private val peacockIllumination = studioPage(
        hub = DisneyPlusHub.PK_ILLUMINATION,
        prefix = "illu",
        companies = PeacockIds.CO_ILLUMINATION,
        middleRows = listOf(
            DisneyRowSpec("illu_despicable", R.string.peacock_row_despicable_me, listOf(curated(DESPICABLE_ME)))
        ),
        classicUntil = null,
        topRatedMinVotes = 500
    )

    private val peacockDreamWorks = studioPage(
        hub = DisneyPlusHub.PK_DREAMWORKS,
        prefix = "dwa",
        companies = PeacockIds.CO_DREAMWORKS_ANIMATION,
        middleRows = listOf(
            DisneyRowSpec("dwa_shrek", R.string.peacock_row_shrek, listOf(curated(SHREK))),
            DisneyRowSpec("dwa_dragon", R.string.peacock_row_how_to_train_your_dragon, listOf(curated(HOW_TO_TRAIN_YOUR_DRAGON))),
            DisneyRowSpec("dwa_kung_fu_panda", R.string.peacock_row_kung_fu_panda, listOf(curated(KUNG_FU_PANDA))),
            DisneyRowSpec("dwa_series", R.string.peacock_row_dreamworks_series, listOf(studio(TV, PeacockIds.DREAMWORKS_TV)))
        ),
        classicUntil = "2005-12-31",
        topRatedMinVotes = 1000
    )

    private val peacockFocus = studioPage(
        hub = DisneyPlusHub.PK_FOCUS,
        prefix = "focus",
        companies = PeacockIds.CO_FOCUS_FEATURES,
        middleRows = listOf(
            DisneyRowSpec(
                "focus_dramas", R.string.paramount_row_dramas,
                listOf(studioMovies(PeacockIds.CO_FOCUS_FEATURES, sortBy = MOST_VOTED, genres = PeacockIds.G_DRAMA))
            ),
            DisneyRowSpec(
                "focus_comedies", R.string.paramount_row_comedies,
                listOf(studioMovies(PeacockIds.CO_FOCUS_FEATURES, sortBy = MOST_VOTED, genres = DisneyPlusIds.G_COMEDY))
            )
        ),
        classicUntil = "2009-12-31",
        topRatedMinVotes = 500
    )

    private val peacockWorkingTitle = studioPage(
        hub = DisneyPlusHub.PK_WORKING_TITLE,
        prefix = "wt",
        companies = PeacockIds.CO_WORKING_TITLE,
        middleRows = listOf(
            DisneyRowSpec(
                "wt_romcoms", R.string.peacock_row_romantic_comedies,
                listOf(
                    studioMovies(
                        PeacockIds.CO_WORKING_TITLE,
                        sortBy = MOST_VOTED,
                        genres = "${PeacockIds.G_ROMANCE},${DisneyPlusIds.G_COMEDY}"
                    )
                )
            ),
            DisneyRowSpec(
                "wt_dramas", R.string.paramount_row_dramas,
                listOf(studioMovies(PeacockIds.CO_WORKING_TITLE, sortBy = MOST_VOTED, genres = PeacockIds.G_DRAMA))
            )
        ),
        classicUntil = "2005-12-31",
        topRatedMinVotes = 500
    )

    private val peacockAmblin = studioPage(
        hub = DisneyPlusHub.PK_AMBLIN,
        prefix = "amb",
        companies = PeacockIds.CO_AMBLIN_ENTERTAINMENT,
        middleRows = listOf(
            DisneyRowSpec("amb_classics_80s", R.string.peacock_row_amblin_classics, listOf(curated(AMBLIN_CLASSICS))).withSeeAll(studioMovies(PeacockIds.CO_AMBLIN_ENTERTAINMENT, sortBy = MOST_VOTED))
        ),
        classicUntil = "1999-12-31",
        topRatedMinVotes = 1000,
        // Amblin Partners (the company's newer films) next to Amblin Entertainment.
        extraSources = { sortBy, voteCountGte, releaseDateLte ->
            listOf(
                DisneyRowSource.Discover(
                    mediaType = MOVIE,
                    sortBy = sortBy,
                    companyQuery = PeacockIds.AMBLIN_PARTNERS,
                    voteCountGte = voteCountGte,
                    releaseDateLte = releaseDateLte
                )
            )
        }
    )

    // ================================================================== Collection pages

    private val COLLECTION_TOY_STORY_EXTRAS = listOf(
        movie("Toy Story of Terror!", 2013),
        movie("Toy Story That Time Forgot", 2014),
        series("Forky Asks a Question", 2019),
        movie("Lamp Life", 2020)
    )

    private val COLLECTION_WINTER_SOLDIER_EXTRAS = listOf(
        movie("Captain America: The First Avenger", 2011),
        movie("Thunderbolts*", 2025)
    )

    private val COLLECTION_DAREDEVIL_EXTRAS = listOf(
        series("Echo", 2024),
        movie("Spider-Man: No Way Home", 2021),
        movie("Daredevil", 2003)
    )

    private val COLLECTION_OUR_HOME = listOf(
        movie("Earth", 2007),
        movie("Oceans", 2009),
        movie("Born in China", 2016),
        movie("Penguins", 2019),
        movie("Elephant", 2020),
        movie("Polar Bear", 2022),
        movie("Tiger", 2024),
        series("Secrets of the Whales", 2021),
        series("Welcome to Earth", 2021),
        series("America the Beautiful", 2022),
        series("One Strange Rock", 2018)
    )

    private val COLLECTION_SIMPSONS = listOf(
        series("The Simpsons", 1989),
        movie("The Simpsons Movie", 2007),
        movie("The Longest Daycare", 2012),
        movie("Playdate with Destiny", 2020),
        movie("The Good, the Bart, and the Loki", 2021),
        movie("The Force Awakens from Its Nap", 2021),
        movie("Plusaversary", 2021),
        movie("Welcome to the Club", 2022),
        movie("The Simpsons Meet the Bocellis in Feliz Navidad", 2022),
        movie("When Billie Met Lisa", 2023),
        movie("Rogue Not Quite One", 2023),
        movie("May the 12th Be with You", 2024)
    )

    private val COLLECTION_MUSICALS_EXTRAS = listOf(
        movie("Hamilton", 2020),
        movie("Into the Woods", 2014),
        movie("Mary Poppins Returns", 2018),
        movie("Newsies", 1992),
        movie("Enchanted", 2007),
        movie("The Greatest Showman", 2017),
        movie("West Side Story", 2021)
    )

    private val COLTITLES_INCREDIBLES = listOf(
        movie("The Incredibles", 2004),
        movie("Incredibles 2", 2018),
        movie("Jack-Jack Attack", 2005),
        movie("Auntie Edna", 2018)
    )

    private val COLTITLES_INSIDE_OUT = listOf(
        movie("Inside Out", 2015),
        movie("Inside Out 2", 2024),
        series("Dream Productions", 2024),
        movie("Riley's First Date?", 2015)
    )

    private val COLTITLES_PIXAR_SHORTS = listOf(
        movie("Bao", 2018),
        movie("Piper", 2016),
        movie("For the Birds", 2000),
        movie("La Luna", 2011),
        movie("Lava", 2014),
        movie("Partly Cloudy", 2009),
        movie("Presto", 2008),
        movie("Geri's Game", 1997),
        movie("Luxo Jr.", 1986)
    )

    private val COLTITLES_AVENGERS = listOf(
        movie("The Avengers", 2012),
        movie("Avengers: Age of Ultron", 2015),
        movie("Captain America: Civil War", 2016),
        movie("Avengers: Infinity War", 2018),
        movie("Avengers: Endgame", 2019),
        movie("Thunderbolts*", 2025)
    )

    private val COLTITLES_IRON_SUIT = listOf(
        movie("Iron Man", 2008),
        movie("Iron Man 2", 2010),
        movie("Iron Man 3", 2013),
        series("Ironheart", 2025),
        movie("The Avengers", 2012),
        movie("Captain America: Civil War", 2016),
        movie("Spider-Man: Homecoming", 2017),
        movie("Avengers: Endgame", 2019)
    )

    private val COLTITLES_THOR = listOf(
        movie("Thor", 2011),
        movie("Thor: The Dark World", 2013),
        movie("Thor: Ragnarok", 2017),
        movie("Thor: Love and Thunder", 2022),
        series("Loki", 2021),
        movie("The Avengers", 2012)
    )

    private val COLTITLES_BLACK_PANTHER = listOf(
        movie("Black Panther", 2018),
        movie("Black Panther: Wakanda Forever", 2022),
        series("Eyes of Wakanda", 2025),
        movie("Captain America: Civil War", 2016),
        movie("Avengers: Infinity War", 2018)
    )

    private val COLTITLES_BLACK_WIDOW = listOf(
        movie("Black Widow", 2021),
        series("Hawkeye", 2021),
        movie("Thunderbolts*", 2025),
        movie("The Avengers", 2012),
        movie("Captain America: The Winter Soldier", 2014),
        movie("Avengers: Endgame", 2019)
    )

    private val COLTITLES_ANT_MAN = listOf(
        movie("Ant-Man", 2015),
        movie("Ant-Man and the Wasp", 2018),
        movie("Ant-Man and the Wasp: Quantumania", 2023),
        movie("Captain America: Civil War", 2016),
        movie("Avengers: Endgame", 2019)
    )

    private val COLTITLES_DOCTOR_STRANGE = listOf(
        movie("Doctor Strange", 2016),
        movie("Doctor Strange in the Multiverse of Madness", 2022),
        series("What If...?", 2021),
        movie("Spider-Man: No Way Home", 2021),
        movie("Thor: Ragnarok", 2017),
        movie("Avengers: Infinity War", 2018)
    )

    private val COLTITLES_LOKI = listOf(
        series("Loki", 2021),
        movie("Thor", 2011),
        movie("The Avengers", 2012),
        movie("Thor: The Dark World", 2013),
        movie("Thor: Ragnarok", 2017),
        series("What If...?", 2021)
    )

    private val COLTITLES_SKYWALKER_SAGA = listOf(
        movie("Star Wars: Episode I - The Phantom Menace", 1999),
        movie("Star Wars: Episode II - Attack of the Clones", 2002),
        movie("Star Wars: Episode III - Revenge of the Sith", 2005),
        movie("Star Wars", 1977),
        movie("The Empire Strikes Back", 1980),
        movie("Return of the Jedi", 1983),
        movie("Star Wars: The Force Awakens", 2015),
        movie("Star Wars: The Last Jedi", 2017),
        movie("Star Wars: The Rise of Skywalker", 2019)
    )

    private val COLTITLES_MANDALORIAN_GROGU = listOf(
        series("The Mandalorian", 2019),
        movie("The Mandalorian and Grogu", 2026),
        series("The Book of Boba Fett", 2021),
        series("Ahsoka", 2023),
        series("Star Wars: Skeleton Crew", 2024),
        series("Disney Gallery: Star Wars: The Mandalorian", 2020)
    )

    private val COLTITLES_STAR_WARS_TALES = listOf(
        series("Star Wars: Tales of the Jedi", 2022),
        series("Star Wars: Tales of the Empire", 2024),
        series("Star Wars: Tales of the Underworld", 2025)
    )

    private val COLTITLES_NG_SPACE = listOf(
        series("Cosmos: A Spacetime Odyssey", 2014),
        series("Cosmos: Possible Worlds", 2020),
        series("Mars", 2016),
        series("One Strange Rock", 2018),
        series("The Right Stuff", 2020),
        movie("Apollo: Missions to the Moon", 2019),
        series("Year Million", 2017),
        movie("Hubble's Cosmic Journey", 2015)
    )

    private val COLTITLES_NG_EARTH_MONTH = listOf(
        series("Welcome to Earth", 2021),
        series("One Strange Rock", 2018),
        series("Secrets of the Whales", 2021),
        series("America the Beautiful", 2022),
        movie("Before the Flood", 2016),
        series("Hostile Planet", 2019),
        series("Incredible Animal Journeys", 2024),
        series("Pole to Pole with Will Smith", 2025)
    )

    private val COLTITLES_NG_SHARKS = listOf(
        movie("Shark Beach with Chris Hemsworth", 2021),
        series("Shark Week", 1988)
    )

    private val COLTITLES_DISNEY100 = listOf(
        movie("Snow White and the Seven Dwarfs", 1937),
        movie("Pinocchio", 1940),
        movie("Fantasia", 1940),
        movie("Cinderella", 1950),
        movie("Mary Poppins", 1964),
        movie("The Little Mermaid", 1989),
        movie("Beauty and the Beast", 1991),
        movie("Aladdin", 1992),
        movie("The Lion King", 1994),
        movie("Toy Story", 1995),
        movie("Frozen", 2013),
        movie("Encanto", 2021),
        movie("Once Upon a Studio", 2023),
        movie("Wish", 2023)
    )

    private val COLTITLES_AVATAR = listOf(
        movie("Avatar", 2009),
        movie("Avatar: The Way of Water", 2022),
        movie("Avatar: Fire and Ash", 2025)
    )

    private val COLTITLES_BLUEY = listOf(
        series("Bluey", 2018),
        series("Bluey Minisodes", 2024)
    )

    private val COLTITLES_TREEHOUSE_OF_HORROR = listOf(
        series("The Simpsons", 1989),
        movie("The Simpsons Movie", 2007),
        movie("The Simpsons: Treehouse of Horror Presents: Simpsons Wicked This Way Comes", 2024)
    )

    private val COLTITLES_PHINEAS_FERB = listOf(
        series("Phineas and Ferb", 2007),
        movie("Phineas and Ferb the Movie: Across the 2nd Dimension", 2011),
        movie("Phineas and Ferb the Movie: Candace Against the Universe", 2020),
        series("Milo Murphy's Law", 2016)
    )

    private val COLTITLES_ZOOTOPIA = listOf(
        movie("Zootopia", 2016),
        movie("Zootopia 2", 2025),
        series("Zootopia+", 2022)
    )

    private val COLTITLES_DESCENDANTS = listOf(
        movie("Descendants", 2015),
        movie("Descendants 2", 2017),
        movie("Descendants 3", 2019),
        movie("Descendants: The Rise of Red", 2024),
        series("Descendants: Wicked World", 2015),
        movie("Descendants: The Royal Wedding", 2021)
    )

    private val COLTITLES_CHIP_DALE = listOf(
        movie("Chip 'n Dale: Rescue Rangers", 2022),
        series("Chip 'n' Dale: Park Life", 2021),
        series("Chip 'n Dale Rescue Rangers", 1989)
    )

    private val COLTITLES_DISNEY_FAIRIES = listOf(
        movie("Tinker Bell", 2008),
        movie("Tinker Bell and the Lost Treasure", 2009),
        movie("Tinker Bell and the Great Fairy Rescue", 2010),
        movie("Secret of the Wings", 2012),
        movie("The Pirate Fairy", 2014),
        movie("Tinker Bell and the Legend of the NeverBeast", 2014),
        movie("Pixie Hollow Games", 2011),
        movie("Pixie Hollow Bake Off", 2013)
    )

    private val COLTITLES_TRON = listOf(
        movie("TRON", 1982),
        movie("TRON: Legacy", 2010),
        movie("TRON: Ares", 2025),
        series("TRON: Uprising", 2012)
    )

    private val COLTITLES_AIR_BUD = listOf(
        movie("Air Bud", 1997),
        movie("Air Bud: Golden Receiver", 1998),
        movie("Air Bud: World Pup", 2000),
        movie("Air Bud: Seventh Inning Fetch", 2002),
        movie("Air Bud: Spikes Back", 2003),
        movie("Air Buddies", 2006),
        movie("Snow Buddies", 2008),
        movie("Space Buddies", 2009),
        movie("Santa Buddies", 2009),
        movie("The Search for Santa Paws", 2010),
        movie("Spooky Buddies", 2011),
        movie("Treasure Buddies", 2012),
        movie("Super Buddies", 2013)
    )

    private val COLTITLES_PRIDE = listOf(
        movie("Love, Simon", 2018),
        series("Love, Victor", 2020),
        movie("Fire Island", 2022),
        series("Pose", 2018),
        movie("Better Nate Than Ever", 2022),
        movie("Out", 2020),
        movie("Strange World", 2022)
    )

    private val COLTITLES_BLACK_STORIES = listOf(
        movie("Black Panther", 2018),
        movie("Black Panther: Wakanda Forever", 2022),
        movie("Soul", 2020),
        movie("Black Is King", 2020),
        movie("Summer of Soul (...Or, When the Revolution Could Not Be Televised)", 2021),
        movie("Hidden Figures", 2016),
        movie("Queen of Katwe", 2016),
        movie("Remember the Titans", 2000),
        movie("The Princess and the Frog", 2009),
        series("The Proud Family: Louder and Prouder", 2022),
        series("Ironheart", 2025),
        series("Kizazi Moto: Generation Fire", 2023)
    )

    private val COLTITLES_HALLOWEEN = listOf(
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
        movie("Coco", 2017),
        movie("Monsters, Inc.", 2001)
    )

    private val COLTITLES_DISNEY_PARKS = listOf(
        series("Behind the Attraction", 2021),
        series("The Imagineering Story", 2019),
        series("Magic of Disney's Animal Kingdom", 2020),
        movie("Jungle Cruise", 2021),
        movie("Pirates of the Caribbean: The Curse of the Black Pearl", 2003),
        movie("Haunted Mansion", 2023),
        movie("Tomorrowland", 2015)
    )

    private val COLTITLES_DISNEY_PLUS_INSIDER = listOf(
        series("One Day at Disney", 2019),
        series("Disney Gallery: Star Wars: The Mandalorian", 2020),
        series("Marvel Studios: Assembled", 2021),
        series("The Imagineering Story", 2019),
        series("Behind the Attraction", 2021)
    )

    /**
     * A collection page: its titles as "Featured" (the hero comes from them), then split into Movies
     * and Series when it has both.
     */
    private fun collectionPage(
        hub: DisneyPlusHub,
        titles: List<CuratedTitle>,
        extraSources: List<DisneyRowSource> = emptyList(),
        /** The franchise's complete TMDB catalog, for the Featured row's See All. */
        seeAll: List<DisneyRowSource.Discover> = emptyList()
    ): DisneyHubSpec {
        val distinct = titles.distinct()
        val movies = distinct.filter { it.mediaType == MOVIE }
        val shows = distinct.filter { it.mediaType == TV }
        return DisneyHubSpec(
            hub = hub,
            heroRowId = "${hub.key}_featured",
            rows = buildList {
                add(
                    DisneyRowSpec("${hub.key}_featured", R.string.disney_row_featured, listOf(curated(distinct)) + extraSources)
                        .copy(seeAll = seeAll)
                )
                if (movies.isNotEmpty() && shows.isNotEmpty()) {
                    add(DisneyRowSpec("${hub.key}_movies", R.string.disney_row_movies, listOf(curated(movies))))
                    add(DisneyRowSpec("${hub.key}_series", R.string.disney_row_series, listOf(curated(shows))))
                }
            }
        )
    }

    private val collectionPages: Map<DisneyPlusHub, DisneyHubSpec> = listOf(
        collectionPage(DisneyPlusHub.COL_TOY_STORY, TOY_STORY + COLLECTION_TOY_STORY_EXTRAS),
        collectionPage(DisneyPlusHub.COL_CARS, CARS),
        collectionPage(DisneyPlusHub.COL_INCREDIBLES, COLTITLES_INCREDIBLES),
        collectionPage(DisneyPlusHub.COL_INSIDE_OUT, COLTITLES_INSIDE_OUT),
        collectionPage(DisneyPlusHub.COL_PIXAR_SHORTS, COLTITLES_PIXAR_SHORTS, extraSources = listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, runtimeLte = 30, voteCountGte = 10))),
        collectionPage(DisneyPlusHub.COL_SPARKSHORTS, SPARKSHORTS, extraSources = listOf(company(MOVIE, DisneyPlusIds.CO_PIXAR, sortBy = RELEASE_ORDER, runtimeLte = 25, releaseDateGte = "2019-01-01", releaseDateLte = "2021-12-31"))),
        collectionPage(DisneyPlusHub.COL_FORKY, FORKY_AND_FRIENDS),
        collectionPage(DisneyPlusHub.COL_INFINITY_SAGA, MCU_INFINITY_SAGA),
        collectionPage(DisneyPlusHub.COL_AVENGERS, COLTITLES_AVENGERS),
        collectionPage(DisneyPlusHub.COL_IRON_SUIT, COLTITLES_IRON_SUIT),
        collectionPage(DisneyPlusHub.COL_SPIDER_MAN, SPIDER_VERSE),
        collectionPage(DisneyPlusHub.COL_THOR, COLTITLES_THOR),
        collectionPage(DisneyPlusHub.COL_BLACK_PANTHER, COLTITLES_BLACK_PANTHER),
        collectionPage(DisneyPlusHub.COL_BLACK_WIDOW, COLTITLES_BLACK_WIDOW),
        collectionPage(DisneyPlusHub.COL_ANT_MAN, COLTITLES_ANT_MAN),
        collectionPage(DisneyPlusHub.COL_DOCTOR_STRANGE, COLTITLES_DOCTOR_STRANGE),
        collectionPage(DisneyPlusHub.COL_LOKI, COLTITLES_LOKI),
        collectionPage(DisneyPlusHub.COL_WANDA_VISION, PREPARE_FOR_VISION),
        collectionPage(DisneyPlusHub.COL_FALCON_WINTER_SOLDIER, SAM_WILSON + COLLECTION_WINTER_SOLDIER_EXTRAS),
        collectionPage(DisneyPlusHub.COL_DAREDEVIL, THE_DEFENDERS + COLLECTION_DAREDEVIL_EXTRAS),
        collectionPage(DisneyPlusHub.COL_SKYWALKER_SAGA, COLTITLES_SKYWALKER_SAGA),
        collectionPage(DisneyPlusHub.COL_MANDALORIAN_GROGU, COLTITLES_MANDALORIAN_GROGU),
        collectionPage(DisneyPlusHub.COL_DARTH_VADER, DARTH_VADER),
        collectionPage(DisneyPlusHub.COL_OBI_WAN, OBI_WAN_KENOBI),
        collectionPage(DisneyPlusHub.COL_STAR_WARS_TALES, COLTITLES_STAR_WARS_TALES),
        collectionPage(DisneyPlusHub.COL_LUCASFILM_ANIMATION, STAR_WARS_ANIMATION),
        collectionPage(DisneyPlusHub.COL_STAR_WARS_VINTAGE, STAR_WARS_VINTAGE),
        collectionPage(DisneyPlusHub.COL_NG_ANIMALS_NATURE, NAT_GEO_ANIMALS, extraSources = listOf(net(DisneyPlusIds.NET_NAT_GEO, genres = DisneyPlusIds.G_DOCUMENTARY, keywordQuery = "nature"), net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "animal"))),
        collectionPage(DisneyPlusHub.COL_NG_EXPLORING, NAT_GEO_EXPLORING, extraSources = listOf(net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "exploration"))),
        collectionPage(DisneyPlusHub.COL_NG_SPACE, COLTITLES_NG_SPACE, extraSources = listOf(net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "space"))),
        collectionPage(DisneyPlusHub.COL_NG_EARTH_MONTH, COLTITLES_NG_EARTH_MONTH),
        collectionPage(DisneyPlusHub.COL_NG_OUR_HOME, COLLECTION_OUR_HOME),
        collectionPage(DisneyPlusHub.COL_NG_VETS_PETS, NAT_GEO_VETS_AND_PETS, extraSources = listOf(net(null, DisneyPlusIds.NAT_GEO_WILD, keywordQuery = "veterinarian"))),
        collectionPage(DisneyPlusHub.COL_NG_SHARKS, COLTITLES_NG_SHARKS, extraSources = listOf(net(DisneyPlusIds.NET_NAT_GEO, keywordQuery = "shark"), net(null, DisneyPlusIds.NAT_GEO_WILD, keywordQuery = "shark"), DisneyRowSource.Discover(MOVIE, companies = DisneyPlusIds.CO_NATIONAL_GEOGRAPHIC, keywordQuery = "shark"))),
        collectionPage(DisneyPlusHub.COL_DISNEY100, COLTITLES_DISNEY100),
        collectionPage(DisneyPlusHub.COL_AVATAR, COLTITLES_AVATAR),
        collectionPage(DisneyPlusHub.COL_BLUEY, COLTITLES_BLUEY),
        collectionPage(DisneyPlusHub.COL_SIMPSONS, COLLECTION_SIMPSONS),
        collectionPage(DisneyPlusHub.COL_TREEHOUSE_OF_HORROR, COLTITLES_TREEHOUSE_OF_HORROR),
        collectionPage(DisneyPlusHub.COL_PHINEAS_FERB, COLTITLES_PHINEAS_FERB),
        collectionPage(DisneyPlusHub.COL_LILO_STITCH, LILO_AND_STITCH),
        collectionPage(DisneyPlusHub.COL_ZOOTOPIA, COLTITLES_ZOOTOPIA),
        collectionPage(DisneyPlusHub.COL_DESCENDANTS, COLTITLES_DESCENDANTS),
        collectionPage(DisneyPlusHub.COL_CHIP_DALE, COLTITLES_CHIP_DALE),
        collectionPage(DisneyPlusHub.COL_DISNEY_FAIRIES, COLTITLES_DISNEY_FAIRIES),
        collectionPage(DisneyPlusHub.COL_TRON, COLTITLES_TRON),
        collectionPage(DisneyPlusHub.COL_AIR_BUD, COLTITLES_AIR_BUD),
        collectionPage(DisneyPlusHub.COL_PRINCESSES, PRINCESSES),
        collectionPage(DisneyPlusHub.COL_THROWBACKS, DISNEY_CHANNEL_FEATURED + DCOM_CLASSICS),
        collectionPage(DisneyPlusHub.COL_MUSICALS, SING_ALONGS + COLLECTION_MUSICALS_EXTRAS),
        collectionPage(DisneyPlusHub.COL_PRIDE, COLTITLES_PRIDE),
        collectionPage(DisneyPlusHub.COL_BLACK_STORIES, COLTITLES_BLACK_STORIES),
        collectionPage(DisneyPlusHub.COL_HALLOWEEN, COLTITLES_HALLOWEEN),
        collectionPage(DisneyPlusHub.COL_DISNEY_PARKS, COLTITLES_DISNEY_PARKS),
        collectionPage(DisneyPlusHub.COL_DISNEY_PLUS_INSIDER, COLTITLES_DISNEY_PLUS_INSIDER),
        collectionPage(DisneyPlusHub.PU_STAR_TREK, STAR_TREK_UNIVERSE, seeAll = keywordCatalog("star trek").toList()),
        collectionPage(DisneyPlusHub.PU_TAYLOR_SHERIDAN, TAYLOR_SHERIDAN_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_MISSION_IMPOSSIBLE, MISSION_IMPOSSIBLE_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_TYLER_PERRY, TYLER_PERRY_TELEVISION),
        collectionPage(DisneyPlusHub.PU_QUIET_PLACE_SCREAM, QUIET_PLACE_SCREAM),
        collectionPage(DisneyPlusHub.PU_DEXTER, DEXTER_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_NCIS, NCIS_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_CSI, CSI_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_CRIMINAL_MINDS, CRIMINAL_MINDS_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_SPONGEBOB, SPONGEBOB_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_SOUTH_PARK, SOUTH_PARK_UNIVERSE),
        collectionPage(DisneyPlusHub.PU_CHALLENGE, CHALLENGE_UNIVERSE)
    ).associateBy { it.hub }

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
        DisneyPlusHub.H_TCM -> hboMaxTcm
        DisneyPlusHub.H_CRUNCHYROLL -> hboMaxCrunchyroll
        DisneyPlusHub.PEACOCK_MAIN -> peacockMain
        DisneyPlusHub.PK_ORIGINALS -> peacockOriginals
        DisneyPlusHub.PK_NBC -> peacockNbc
        DisneyPlusHub.PK_BRAVO -> peacockBravo
        DisneyPlusHub.PK_TELEMUNDO -> peacockTelemundo
        DisneyPlusHub.PK_WWE -> peacockWwe
        DisneyPlusHub.PK_NBC_SPORTS -> peacockNbcSports
        DisneyPlusHub.PK_UNIVERSAL -> peacockUniversal
        DisneyPlusHub.PK_ILLUMINATION -> peacockIllumination
        DisneyPlusHub.PK_DREAMWORKS -> peacockDreamWorks
        DisneyPlusHub.PK_FOCUS -> peacockFocus
        DisneyPlusHub.PK_WORKING_TITLE -> peacockWorkingTitle
        DisneyPlusHub.PK_AMBLIN -> peacockAmblin
        else -> collectionPages[hub] ?: main
    }
}
