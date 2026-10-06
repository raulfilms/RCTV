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
    val DISNEY_NETWORK_IDS: Set<Int> = setOf(2739, 453, 88, 2, 1267, 43, 54, 281, 44)

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
        "abc", "freeform", "abc family", "national geographic", "nat geo wild", "nat geo"
    )
}

/** The main Disney+ page plus one page per brand tile. [key] is used in the navigation route. */
enum class DisneyPlusHub(val key: String, @StringRes val titleRes: Int) {
    MAIN("main", R.string.disney_plus_title),
    DISNEY("disney", R.string.disney_brand_disney),
    PIXAR("pixar", R.string.disney_brand_pixar),
    MARVEL("marvel", R.string.disney_brand_marvel),
    STAR_WARS("star_wars", R.string.disney_brand_star_wars),
    NAT_GEO("national_geographic", R.string.disney_brand_nat_geo),
    HULU("hulu", R.string.disney_brand_hulu);

    companion object {
        fun fromKey(key: String?): DisneyPlusHub =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MAIN

        /** The six brand tiles shown under the hero on the main page, in order. */
        val brandTiles: List<DisneyPlusHub> = listOf(DISNEY, PIXAR, MARVEL, STAR_WARS, NAT_GEO, HULU)
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
    RECOMMENDED
}

data class DisneyRowSpec(
    val id: String,
    @StringRes val titleRes: Int,
    val sources: List<DisneyRowSource> = emptyList(),
    val kind: DisneyRowKind = DisneyRowKind.CATALOG
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

    private val DISNEY_COLLECTIONS = listOf(
        movie("The Lion King", 1994),
        movie("Frozen", 2013),
        movie("Moana", 2016),
        movie("Pirates of the Caribbean: The Curse of the Black Pearl", 2003),
        movie("High School Musical", 2006),
        movie("Descendants", 2015),
        movie("Zootopia", 2016),
        movie("Wreck-It Ralph", 2012),
        movie("Big Hero 6", 2014),
        movie("Mary Poppins", 1964),
        movie("Enchanted", 2007),
        movie("Tron", 1982),
        movie("Honey, I Shrunk the Kids", 1989),
        movie("The Muppets", 2011)
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
            DisneyRowSpec("d_collections", R.string.disney_row_collections, listOf(curated(DISNEY_COLLECTIONS))),
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
    }
}
