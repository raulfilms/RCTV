package com.nuvio.tv.data.disneyplus

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbCollectionMediaType

/**
 * TMDB ids of the services whose page is made of TMDB rows only, every one checked on themoviedb.org:
 * US watch providers, studios, networks, keywords and people.
 */
object ServiceHubIds {
    // Watch providers (US): the service and its other plans or channels.
    /** Netflix, Netflix Standard with Ads. */
    const val NETFLIX_PROVIDERS = "8|1796"
    /** Amazon Prime Video, Amazon Prime Video with Ads. */
    const val PRIME_PROVIDERS = "9|2100"
    /** Apple TV (formerly Apple TV+) and its Amazon channel. */
    const val APPLE_PROVIDERS = "350|2243"
    const val ANGEL_PROVIDERS = "1956"
    /** Shudder and its Amazon and Apple TV channels. */
    const val SHUDDER_PROVIDERS = "99|204|2049"

    // Networks (their original series)
    const val NET_NETFLIX = "213"
    const val NET_PRIME_VIDEO = "1024"
    const val NET_APPLE_TV = "2552"
    const val NET_SHUDDER = "2949"

    // Studios (their movies)
    /** Netflix (US), Netflix International Pictures and Netflix Animation Studios. */
    const val CO_NETFLIX = "178464|145174|171251"
    /** Amazon Studios (US) and Amazon MGM Studios. */
    const val CO_AMAZON = "20580|210099"
    /** Apple Studios and Apple (Apple Original Films: F1, Napoleon, Killers of the Flower Moon...). */
    const val CO_APPLE = "194232|14801"
    const val CO_A24 = "41077"
    /** Angel Studios (US): Sound of Freedom, The King of Kings, Cabrini... */
    const val CO_ANGEL = "165435"
    /** Shudder's own films. */
    const val CO_SHUDDER = "142877"
    /** Blumhouse Productions, Blumhouse Atomic Monster, Blumhouse International and BH Tilt. */
    const val CO_BLUMHOUSE_MOVIES = "3172|314016|314048|73625"
    const val CO_BLUMHOUSE_TV = "68884"
    /** FOX Nation: its specials and documentaries (TMDB lists them as films) and its series. */
    const val CO_FOX_NATION = "222451"
}

/**
 * The pages of Netflix, Prime Video, Apple TV, A24, Angel Studios, Shudder, Blumhouse and FOX Nation:
 * rows of TMDB titles, rows of cards (genres, collections, filmmakers, "Discover More") that each open
 * a full list, all with no limit on See All.
 */
internal object ServiceHubs {
    private val MOVIE = TmdbCollectionMediaType.MOVIE
    private val TV = TmdbCollectionMediaType.TV

    private const val POPULAR = "popularity.desc"
    private const val NEWEST = "primary_release_date.desc"
    private const val SOONEST = "primary_release_date.asc"
    private const val TOP_RATED = "vote_average.desc"
    private const val MOST_VOTED = "vote_count.desc"
    private const val A_TO_Z_MOVIES = "title.asc"
    private const val A_TO_Z_SERIES = "name.asc"

    /** News, talk shows and daily soaps stay out of the series rows. */
    private const val NO_NEWS_TALK = "10763,10767,10766"

    /** Titles first made in another language than English. */
    private const val INTERNATIONAL =
        "ko|es|ja|hi|fr|de|it|pt|tr|da|sv|no|pl|nl|zh|cn|th|id|tl|ar|te|ta|ml|he|fa|ru|uk|is|fi"

    // TMDB keywords (checked on themoviedb.org)
    private const val K_TRUE_STORY = "9672"
    private const val K_ANIME = "210024"
    private const val K_STAND_UP = "9716"
    private const val K_TRUE_CRIME = "33722"
    private const val K_SPORTS = "6075"
    private const val K_COMING_OF_AGE = "10683"
    private const val K_DARK_COMEDY = "10123"
    /** supernatural, ghost, possession, demonic possession */
    private const val K_SUPERNATURAL = "6152|162846|9712|161261"
    private const val K_PARANORMAL = "9853"
    /** slasher, serial killer */
    private const val K_KILLERS = "12339|10714"
    /** creature feature, creature, monster */
    private const val K_CREATURES = "158126|13031|1299"
    private const val K_PSYCH_THRILLER = "12565"
    private const val K_REVENGE = "9748"
    /** cult film, b movie */
    private const val K_CULT = "374649|11034"
    private const val K_FOUND_FOOTAGE = "163053"
    private const val K_HOME_INVASION = "14903"
    private const val K_SURVIVAL_HORROR = "50009"
    /** artificial intelligence (a.i.), technology */
    private const val K_AI_TECH = "310|1576"
    /** anthology, horror anthology */
    private const val K_ANTHOLOGY = "9706|250593"
    /** inspirational, inspirational story, faith based */
    private const val K_INSPIRATIONAL = "281585|352366|348144"
    /** christianity, christian, christian film, faith, faith based, bible, jesus christ */
    private const val K_FAITH = "186|258703|253695|6150|348144|3036|230097"
    /** nature, nature documentary, wildlife */
    private const val K_NATURE = "18330|221355|9902"

    private fun days(n: Int): String = DisneyPlusCatalog.daysFromToday(n)
    private val TODAY: String get() = days(0)

    private fun movie(title: String, year: Int?) = CuratedTitle(title, year, MOVIE)
    private fun series(title: String, year: Int?) = CuratedTitle(title, year, TV)
    private fun curated(vararg titles: CuratedTitle) = DisneyRowSource.Curated(titles.toList())
    private fun collection(query: String) = DisneyRowSource.TmdbCollection(query)

    /** A service's (or studio's) whole catalog on TMDB: its movies and, when it has any, its series. */
    private class Catalog(val movies: DisneyRowSource.Discover, val series: DisneyRowSource.Discover?) {
        fun movies(sort: String = POPULAR, change: DisneyRowSource.Discover.() -> DisneyRowSource.Discover = { this }) =
            movies.copy(sortBy = sort).change()

        fun series(sort: String = POPULAR, change: DisneyRowSource.Discover.() -> DisneyRowSource.Discover = { this }) =
            series?.copy(sortBy = sort)?.change()

        /** Movies and series, with the same change made to both. */
        fun both(sort: String = POPULAR, change: DisneyRowSource.Discover.() -> DisneyRowSource.Discover = { this }) =
            listOfNotNull(movies(sort, change), series(sort, change))

        /** Popular titles of the last year, and series with episodes these past two months. */
        fun trending() = listOfNotNull(
            movies { copy(releaseDateGte = days(-365), releaseDateLte = TODAY, voteCountGte = 20) },
            series { copy(airDateGte = days(-60), airDateLte = TODAY) }
        )

        /** Movies out these last three months, and series with new episodes this past month. */
        fun recent() = listOfNotNull(
            movies { copy(releaseDateGte = days(-90), releaseDateLte = TODAY) },
            series { copy(airDateGte = days(-30), airDateLte = TODAY) }
        )

        /** Newest first, up to today. */
        fun newest() = both(NEWEST) { copy(releaseDateLte = TODAY) }

        /** Titles well rated but not yet widely seen. */
        fun hiddenGems() = listOfNotNull(
            movies(TOP_RATED) { copy(voteCountGte = 100, voteCountLte = 1500, voteAverageGte = 7.2) },
            series(TOP_RATED) { copy(voteCountGte = 50, voteCountLte = 800, voteAverageGte = 7.5) }
        )

        /** The best rated, with enough votes not to mislead. */
        fun topRated(movieVotes: Int, seriesVotes: Int) = listOfNotNull(
            movies(TOP_RATED) { copy(voteCountGte = movieVotes) },
            series(TOP_RATED) { copy(voteCountGte = seriesVotes) }
        )
    }

    private fun providers(ids: String) = Catalog(
        movies = DisneyRowSource.Discover(MOVIE, providers = ids),
        series = DisneyRowSource.Discover(TV, providers = ids, withoutGenres = NO_NEWS_TALK)
    )

    private fun studio(movieCompanies: String, seriesCompanies: String?) = Catalog(
        movies = DisneyRowSource.Discover(MOVIE, companies = movieCompanies),
        series = seriesCompanies?.let { DisneyRowSource.Discover(TV, companies = it) }
    )

    /** A service's own series still to come (first episode within [untilDays]). */
    private fun upcomingSeries(networks: String? = null, companies: String? = null, untilDays: Int = 180) =
        DisneyRowSource.Discover(
            TV, sortBy = POPULAR, networks = networks, companies = companies,
            releaseDateGte = days(1), releaseDateLte = days(untilDays)
        )

    /** A studio's movies still to come (release within [untilDays]). */
    private fun upcomingMovies(companies: String, untilDays: Int = 180, sort: String = POPULAR) =
        DisneyRowSource.Discover(MOVIE, sortBy = sort, companies = companies, releaseDateGte = days(1), releaseDateLte = days(untilDays))

    // ------------------------------------------------------------------ rows of cards

    /** A genre card: the catalog's movies and series in that genre. */
    private data class Pick(
        val key: String,
        @StringRes val label: Int,
        /** Movie genres ("|": any, ",": all). */
        val movieGenres: String? = null,
        val seriesGenres: String? = null,
        /** Keywords, for the movies and the series. */
        val keywords: String? = null,
        val languages: String? = null,
        val withMovies: Boolean = movieGenres != null || keywords != null || languages != null,
        val withSeries: Boolean = seriesGenres != null || keywords != null || languages != null
    )

    private class Cards(private val rowId: String, @StringRes private val rowTitle: Int) {
        val cards = mutableListOf<HubCard>()
        val lists = mutableListOf<DisneyRowSpec>()

        /** A card ([label] a string, or [name] for a proper name) opening the list of [sources]. */
        fun card(
            key: String,
            @StringRes label: Int = 0,
            name: String? = null,
            art: HubCardArt = HubCardArt.Tile,
            sources: List<DisneyRowSource>
        ) {
            val listId = "${rowId}_$key"
            cards += HubCard(listId, label, name, art)
            lists += DisneyRowSpec(
                listId,
                if (label != 0) label else rowTitle,
                sources = sources,
                kind = DisneyRowKind.HIDDEN,
                title = name
            )
        }

        fun genre(catalog: Catalog, pick: Pick, sort: String = POPULAR) = card(
            pick.key, pick.label,
            sources = listOfNotNull(
                if (pick.withMovies) catalog.movies(sort) {
                    copy(genres = pick.movieGenres, keywords = pick.keywords, originalLanguage = pick.languages)
                } else null,
                if (pick.withSeries) catalog.series(sort) {
                    copy(genres = pick.seriesGenres, keywords = pick.keywords, originalLanguage = pick.languages)
                } else null
            )
        )

        /** A TMDB franchise collection ([query] its name on TMDB), shown as [name]. */
        fun franchise(name: String, query: String = name) = card(
            key = slug(name), name = name, art = HubCardArt.Collection(query), sources = listOf(collection(query))
        )
    }

    /** A row of cards, followed by the lists its cards open. */
    private fun cardRow(id: String, @StringRes title: Int, build: Cards.() -> Unit): List<DisneyRowSpec> {
        val cards = Cards(id, title).apply(build)
        return listOf(DisneyRowSpec(id, title, kind = DisneyRowKind.CARDS, cards = cards.cards)) + cards.lists
    }

    private fun slug(name: String): String =
        name.lowercase(java.util.Locale.US).replace("&", "and").replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** The genres of a "Browse by Genre" row. */
    private val ACTION = Pick("action", R.string.svc_card_action, "28", "10759")
    private val ADVENTURE = Pick("adventure", R.string.svc_card_adventure, "12", "10759")
    private val COMEDY = Pick("comedy", R.string.svc_card_comedy, "35", "35")
    private val DRAMA = Pick("drama", R.string.svc_card_drama, "18", "18")
    private val HORROR = Pick("horror", R.string.svc_card_horror, "27")
    private val ROMANCE = Pick("romance", R.string.svc_card_romance, "10749")
    private val THRILLER = Pick("thriller", R.string.svc_card_thriller, "53")
    private val SCI_FI = Pick("scifi", R.string.svc_card_scifi, "878", "10765")
    private val FANTASY = Pick("fantasy", R.string.svc_card_fantasy, "14", "10765")
    private val ANIME = Pick("anime", R.string.svc_card_anime, keywords = K_ANIME)
    private val DOCUMENTARIES = Pick("documentaries", R.string.svc_card_documentaries, "99", "99")
    private val DOCUMENTARY = Pick("documentary", R.string.svc_card_documentary, "99", "99")
    private val CRIME = Pick("crime", R.string.svc_card_crime, "80", "80")
    private val MYSTERY = Pick("mystery", R.string.svc_card_mystery, "9648", "9648")
    private val FAMILY = Pick("family", R.string.svc_card_family, "10751", "10751")
    private val KIDS = Pick("kids", R.string.svc_card_kids, seriesGenres = "10762")
    private val ANIMATION = Pick("animation", R.string.svc_card_animation, "16", "16")
    private val REALITY = Pick("reality", R.string.svc_card_reality, seriesGenres = "10764")
    private val WAR = Pick("war", R.string.svc_card_war, "10752", "10768")
    private val WESTERN = Pick("western", R.string.svc_card_western, "37", "37")
    private val HISTORY = Pick("history", R.string.svc_card_history, "36")
    private val MUSIC = Pick("music", R.string.svc_card_music, "10402")

    private val STREAMING_GENRES = listOf(
        ACTION, COMEDY, DRAMA, HORROR, ROMANCE, THRILLER, SCI_FI, ANIME, DOCUMENTARIES, CRIME, FANTASY,
        MYSTERY, FAMILY, KIDS, ANIMATION, REALITY, WAR, WESTERN, HISTORY, MUSIC
    )

    // ------------------------------------------------------------------ hand-picked titles

    /** Oscar and Emmy winners Netflix released (the awards are on each title's page). */
    private val NETFLIX_AWARDS = listOf(
        movie("All Quiet on the Western Front", 2022), series("Adolescence", 2025), movie("Roma", 2018),
        series("The Crown", 2016), movie("The Power of the Dog", 2021), series("The Queen's Gambit", 2020),
        movie("Guillermo del Toro's Pinocchio", 2022), series("Baby Reindeer", 2024), movie("Marriage Story", 2019),
        series("BEEF", 2023), movie("Emilia Pérez", 2024), series("Squid Game", 2021), movie("Mank", 2020),
        series("Ozark", 2017), movie("Ma Rainey's Black Bottom", 2020), series("When They See Us", 2019),
        movie("My Octopus Teacher", 2020), series("Godless", 2017), movie("American Factory", 2019),
        series("Black Mirror", 2011), movie("Icarus", 2017), movie("The Wonderful Story of Henry Sugar", 2023)
    )

    /** Netflix's best-known films (TMDB rarely credits Netflix as their studio). */
    private val NETFLIX_FILMS = listOf(
        movie("KPop Demon Hunters", 2025), movie("Red Notice", 2021), movie("Extraction", 2020),
        movie("Glass Onion: A Knives Out Mystery", 2022), movie("The Gray Man", 2022), movie("Don't Look Up", 2021),
        movie("Bird Box", 2018), movie("The Adam Project", 2022), movie("Damsel", 2024),
        movie("Leave the World Behind", 2023), movie("Carry-On", 2024), movie("Back in Action", 2025),
        movie("The Irishman", 2019), movie("Society of the Snow", 2023), movie("Enola Holmes", 2020),
        movie("The Old Guard", 2020), movie("Army of the Dead", 2021), movie("Hustle", 2022)
    )

    private val PRIME_AWARDS = listOf(
        movie("Manchester by the Sea", 2016), series("The Marvelous Mrs. Maisel", 2017), movie("American Fiction", 2023),
        series("Fleabag", 2016), movie("Sound of Metal", 2020), series("Transparent", 2014), movie("The Salesman", 2016)
    )

    private val PRIME_HITS = listOf(
        series("The Boys", 2019), series("Reacher", 2022), series("Fallout", 2024), series("Invincible", 2021),
        series("The Lord of the Rings: The Rings of Power", 2022), series("The Summer I Turned Pretty", 2022)
    )

    private val APPLE_AWARDS = listOf(
        movie("CODA", 2021), series("Ted Lasso", 2020), series("The Studio", 2025), series("Severance", 2022),
        series("Black Bird", 2022), series("The Morning Show", 2019)
    )

    private val APPLE_SERIES = listOf(
        series("Severance", 2022), series("Ted Lasso", 2020), series("The Morning Show", 2019),
        series("Slow Horses", 2022), series("Shrinking", 2023), series("Silo", 2023), series("Foundation", 2021),
        series("For All Mankind", 2019), series("Bad Sisters", 2022), series("Pachinko", 2022), series("The Studio", 2025)
    )

    private val APPLE_SPORTS = listOf(
        movie("F1", 2025), series("The Dynasty: New England Patriots", 2024), series("Messi Meets America", 2024),
        series("Make or Break", 2022), series("Greatness Code", 2020)
    )

    private val A24_POPULAR = listOf(
        movie("Everything Everywhere All at Once", 2022), movie("Midsommar", 2019), movie("Hereditary", 2018),
        movie("Uncut Gems", 2019), movie("The Lighthouse", 2019), movie("Moonlight", 2016), movie("Past Lives", 2023)
    )

    private val A24_SERIES = listOf(series("BEEF", 2023), series("Euphoria", 2019), series("The Curse", 2023), series("Ramy", 2019))

    private val A24_HORROR = listOf(
        movie("Hereditary", 2018), movie("The Witch", 2015), movie("Midsommar", 2019), movie("Talk to Me", 2023),
        movie("X", 2022), movie("Pearl", 2022)
    )

    /** A24's Oscar winners, then its nominees. */
    private val A24_AWARDS = listOf(
        movie("Moonlight", 2016), movie("Everything Everywhere All at Once", 2022), movie("The Zone of Interest", 2023),
        movie("The Brutalist", 2024), movie("The Whale", 2022), movie("Room", 2015), movie("Minari", 2020),
        movie("Ex Machina", 2015), movie("Amy", 2015), movie("Lady Bird", 2017), movie("Past Lives", 2023),
        movie("Aftersun", 2022), movie("The Florida Project", 2017), movie("The Lighthouse", 2019),
        movie("First Reformed", 2017)
    )

    /** First features A24 released. */
    private val A24_DEBUTS = listOf(
        movie("Hereditary", 2018), movie("The Witch", 2015), movie("Lady Bird", 2017), movie("Eighth Grade", 2018),
        movie("Aftersun", 2022), movie("Past Lives", 2023), movie("Talk to Me", 2023), movie("Mid90s", 2018),
        movie("Ex Machina", 2015), movie("Saint Maud", 2019), movie("Krisha", 2015), movie("Swiss Army Man", 2016),
        movie("The Last Black Man in San Francisco", 2019)
    )

    private val ANGEL_ORIGINALS = listOf(
        movie("Sound of Freedom", 2023), movie("The King of Kings", 2025), movie("Cabrini", 2024),
        movie("Bonhoeffer: Pastor. Spy. Assassin.", 2024), movie("Sound of Hope: The Story of Possum Trot", 2024),
        movie("His Only Son", 2023), movie("The Shift", 2023), movie("Homestead", 2024),
        series("The Wingfeather Saga", 2022), series("Tuttle Twins", 2021)
    )

    private val BLUMHOUSE_POPULAR = listOf(
        movie("Get Out", 2017), movie("M3GAN", 2022), movie("Five Nights at Freddy's", 2023),
        movie("The Black Phone", 2022), movie("The Invisible Man", 2020)
    )

    private val BLUMHOUSE_AWARDS = listOf(movie("Get Out", 2017), movie("Whiplash", 2014), movie("BlacKkKlansman", 2018))

    // FOX Nation: TMDB barely tags its specials, so its themed rows are hand-picked from its own catalog.
    private val FOX_CRIME = listOf(
        series("Menendez Brothers: Victims or Villains", 2024), series("Hunting Bundy: Chase for the Devil", 2025),
        movie("The Murder Investigation of JFK", 2023), series("The Godfather of Fentanyl", 2024),
        movie("The Twisted World of Jeffrey Epstein", 2019), series("Skim City: The Real-Life Mafia Behind Casino", 2023),
        series("The Great American Heist", 2022), movie("The Life of O.J. Simpson", null),
        series("Outlaws & Lawmen", 2024), series("Scandalous: The Death of Marilyn Monroe (Director's Cut)", 2019)
    )

    private val FOX_POLITICS = listOf(
        movie("The Ingraham Angle Town Hall: Donald Trump", 2024), movie("Live Free or Die with Sean Hannity", 2020),
        movie("DOGE vs D.C.", 2025), movie("Trump in Focus", 2024), movie("The Autopen President", 2025),
        movie("The Cost of Socialism", 2025), movie("Mamdani: The Socialist Surge", 2025),
        movie("Donald Trump: Iowa Town Hall", 2024), movie("Biden's Final Act", null), movie("China Showdown", 2021),
        movie("Red Alert: China vs. Taiwan", null), movie("The New Axis of Evil", null),
        movie("Russia The Cyber Threat", 2022), movie("Ukraine Origins", 2022), movie("Putins First War", 2022)
    )

    private val FOX_FAITH = listOf(
        movie("The Nativity: Facts, Fictions and Faith", 2014), movie("The True Saint Nicholas", 2020),
        movie("One Nation Under God", 2006), movie("General Patton and the Prayer that Changed History", 2025),
        movie("Prayers of a Liberal President", 2019), movie("Christmas at the Cathedral", 2021),
        movie("Feast of the Seven Fishes: An Italian Tradition", 2021), movie("Antisemitism in America", 2024)
    )

    private val FOX_OUTDOORS = listOf(
        movie("Evel: America's Daredevil", 2026), series("Lost Ships of WW2", 2022), movie("Stonehenge: Built by Giants", null),
        movie("Battle for the Arctic", null), movie("A Yellowstone Christmas", 2022), movie("American Moonshot", 2019),
        movie("Mercury Rising", 2023), movie("Abby Goes To Space Camp", null), movie("Christmas on the Moon", 2018)
    )

    private val FOX_PERSONALITIES = listOf(
        movie("Maintaining With Tyrus: Donald Trump Interview", 2024), movie("Charlie Kirk: The Last Interview", 2025),
        movie("Live Free or Die with Sean Hannity", 2020), movie("A Pete Hegseth and Melania Trump Interview", null),
        movie("Inside Guantanamo Bay with Laura Ingraham", null), movie("No Pardon Needed with Tyler Fischer", 2025),
        movie("Punchlines and Patriots", 2025), movie("Twas The Night Before Christmas with Stuart Varney", null),
        movie("Steve's Simply Happy Christmas Cook-A-Long", null), movie("The Faulkner Focus: Trump Town Hall", 2024),
        series("Kelsey Grammer's Historic Battles for America", 2022)
    )

    private val FOX_BIOGRAPHIES = listOf(
        movie("Charlie Kirk: An American Original", 2025), movie("The Life of Hulk Hogan", 2025),
        movie("Evel: America's Daredevil", 2026), movie("Ron & Nancy: A Love Story", 2021),
        movie("American Outlaw: Clint Eastwoods Life in Film", 2021), movie("Andrew Jackson: Hero Under Fire", 2017),
        movie("Franklin and Washington", 2020), movie("Dick Cheney: No Retreat", 2007), movie("Dick Cheney Revealed", 2011),
        movie("Remembering Dick Cheney", 2025), movie("The Mysterious Death of Edgar Allan Poe", 2020),
        movie("Elvis Alive?", 2023), movie("The Life of O.J. Simpson", null)
    )

    private val FOX_HISTORY_SERIES = listOf(
        series("Kelsey Grammer's Historic Battles for America", 2022), series("Secret History of WWII", 2022),
        series("Lost Ships of WW2", 2022)
    )

    /** Netflix's own film series, as TMDB collections. */
    private val NETFLIX_FRANCHISES = listOf(
        "Extraction", "Enola Holmes", "Knives Out", "The Kissing Booth", "To All the Boys", "Fear Street",
        "The Old Guard", "Murder Mystery", "Rebel Moon", "Bird Box", "Army of the Dead", "Through My Window",
        "The Princess Switch", "A Christmas Prince", "The Christmas Chronicles"
    )

    /** Film series of the studios Amazon owns (MGM: Bond, Rocky, Creed...) and of its own films; name to TMDB query. */
    private val PRIME_FRANCHISES = listOf(
        "James Bond" to "James Bond", "Rocky" to "Rocky", "Creed" to "Creed", "Legally Blonde" to "Legally Blonde",
        "The Pink Panther" to "The Pink Panther (Original)", "RoboCop" to "RoboCop", "Stargate" to "Stargate",
        "Poltergeist" to "Poltergeist", "Borat" to "Borat", "Coming to America" to "Coming to America",
        "Hotel Transylvania" to "Hotel Transylvania", "Bill & Ted" to "Bill & Ted", "Barbershop" to "Barbershop",
        "Road House" to "Road House (Remake)"
    )

    private val BLUMHOUSE_FRANCHISES = listOf(
        "The Purge", "Insidious", "Paranormal Activity", "Halloween", "M3GAN", "Five Nights at Freddy's",
        "The Black Phone", "Happy Death Day", "Ouija", "Sinister"
    )

    /** A24 filmmakers: name, TMDB person ids ("|" for a duo), and whose photo the card shows. */
    private data class Filmmaker(val name: String, val ids: String, val photo: Int)

    private val A24_FILMMAKERS = listOf(
        Filmmaker("Ari Aster", "1145520", 1145520),
        Filmmaker("Robert Eggers", "138781", 138781),
        Filmmaker("Sean Baker", "118415", 118415),
        Filmmaker("Daniels", "1383612|1317730", 1383612),
        Filmmaker("Greta Gerwig", "45400", 45400),
        Filmmaker("Barry Jenkins", "92784", 92784),
        Filmmaker("Alex Garland", "2036", 2036),
        Filmmaker("Safdie Brothers", "129561|227564", 129561),
        Filmmaker("Ti West", "101542", 101542),
        Filmmaker("Yorgos Lanthimos", "122423", 122423),
        Filmmaker("Jonathan Glazer", "66728", 66728),
        Filmmaker("Kelly Reichardt", "56383", 56383),
        Filmmaker("Trey Edward Shults", "1423660", 1423660),
        Filmmaker("Celine Song", "2518227", 2518227),
        Filmmaker("Bo Burnham", "115128", 115128),
        Filmmaker("Lee Isaac Chung", "931952", 931952),
        Filmmaker("Charlotte Wells", "1904110", 1904110),
        Filmmaker("Darren Aronofsky", "6431", 6431)
    )

    /** Blumhouse's directors: Jordan Peele, James Wan, Leigh Whannell, Scott Derrickson, Mike Flanagan, Christopher Landon, David Gordon Green. */
    private const val BLUMHOUSE_DIRECTORS = "291263|2127|2128|55499|551463|54248|64141"

    /** Prime Video Channels (Amazon channels on TMDB): name and watch provider id. */
    private val PRIME_CHANNELS = listOf(
        "HBO Max" to 1825, "Paramount+" to 582, "MGM+" to 583, "STARZ" to 1794, "AMC+" to 528,
        "Crunchyroll" to 1968, "discovery+" to 584, "BritBox" to 197, "Shudder" to 204
    )

    // ------------------------------------------------------------------ Netflix

    private val netflixCatalog = providers(ServiceHubIds.NETFLIX_PROVIDERS)
    private val netflixOriginalSeries = DisneyRowSource.Discover(TV, networks = ServiceHubIds.NET_NETFLIX)
    private val netflixOriginalMovies = DisneyRowSource.Discover(MOVIE, companies = ServiceHubIds.CO_NETFLIX)

    val netflix: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.NETFLIX_MAIN,
        heroRowId = "nf_top10",
        rows = buildList {
            add(DisneyRowSpec("nf_top10", R.string.svc_row_top10_country, netflixCatalog.both(), ranked = true))
            add(DisneyRowSpec("nf_trending", R.string.svc_row_trending, netflixCatalog.trending()))
            add(
                DisneyRowSpec(
                    "nf_new", R.string.svc_row_new_popular,
                    netflixCatalog.recent() + listOf(
                        upcomingSeries(networks = ServiceHubIds.NET_NETFLIX),
                        upcomingMovies(ServiceHubIds.CO_NETFLIX)
                    ),
                    premiereBadges = true
                )
            )
            add(
                DisneyRowSpec(
                    "nf_only", R.string.svc_row_only_netflix,
                    listOf(netflixOriginalSeries, DisneyRowSource.Curated(NETFLIX_FILMS), netflixOriginalMovies)
                )
            )
            add(DisneyRowSpec("nf_movies", R.string.svc_row_popular_movies, listOf(netflixCatalog.movies())))
            add(DisneyRowSpec("nf_series", R.string.svc_row_popular_tv, listOfNotNull(netflixCatalog.series())))
            addAll(cardRow("nf_genres", R.string.svc_row_browse_genre) {
                STREAMING_GENRES.forEach { genre(netflixCatalog, it) }
            })
            addAll(cardRow("nf_collections", R.string.svc_row_franchises) { NETFLIX_FRANCHISES.forEach { franchise(it) } })
            add(
                DisneyRowSpec(
                    "nf_international", R.string.svc_row_international_favorites,
                    netflixCatalog.both { copy(originalLanguage = INTERNATIONAL) }
                )
            )
            add(
                DisneyRowSpec(
                    "nf_docs", R.string.svc_row_docs_specials,
                    netflixCatalog.both { copy(genres = "99") } + listOfNotNull(
                        netflixCatalog.movies { copy(keywords = K_STAND_UP) },
                        netflixCatalog.series { copy(keywords = K_TRUE_CRIME) }
                    )
                )
            )
            add(
                DisneyRowSpec(
                    "nf_kids_anime", R.string.svc_row_kids_anime,
                    netflixCatalog.both { copy(genres = "16") } + listOfNotNull(
                        netflixCatalog.series { copy(genres = "10762") },
                        netflixCatalog.movies { copy(keywords = K_ANIME) }
                    )
                )
            )
            addAll(cardRow("nf_more", R.string.svc_row_discover_more) {
                card("movies", R.string.svc_card_all_movies, sources = listOf(netflixCatalog.movies()))
                card("series", R.string.svc_card_all_tv, sources = listOfNotNull(netflixCatalog.series()))
                card("awards", R.string.svc_card_award_winning, sources = listOf(DisneyRowSource.Curated(NETFLIX_AWARDS)))
                card("gems", R.string.svc_card_hidden_gems, sources = netflixCatalog.hiddenGems())
                card("recent", R.string.svc_card_recently_added, sources = netflixCatalog.newest())
                card(
                    "soon", R.string.svc_card_coming_soon,
                    sources = listOf(
                        upcomingSeries(networks = ServiceHubIds.NET_NETFLIX, untilDays = 365).copy(sortBy = SOONEST),
                        upcomingMovies(ServiceHubIds.CO_NETFLIX, untilDays = 365, sort = SOONEST)
                    )
                )
                card(
                    "anime", R.string.svc_card_anime_animation,
                    sources = netflixCatalog.both { copy(keywords = K_ANIME) } + netflixCatalog.both { copy(genres = "16") }
                )
                card(
                    "kids", R.string.svc_card_kids_family,
                    sources = listOfNotNull(netflixCatalog.movies { copy(genres = "10751") }, netflixCatalog.series { copy(genres = "10762|10751") })
                )
                card("reality", R.string.svc_card_reality_competition, sources = listOfNotNull(netflixCatalog.series { copy(genres = "10764") }))
                card("collections", R.string.svc_card_all_collections, sources = NETFLIX_FRANCHISES.map(::collection))
            })
        }
    )


    // ------------------------------------------------------------------ Prime Video

    private val primeCatalog = providers(ServiceHubIds.PRIME_PROVIDERS)

    val primeVideo: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.PRIME_MAIN,
        heroRowId = "pv_top10",
        rows = buildList {
            add(DisneyRowSpec("pv_top10", R.string.svc_row_top10_popular, primeCatalog.both(), ranked = true))
            add(DisneyRowSpec("pv_trending", R.string.svc_row_trending, primeCatalog.trending()))
            add(
                DisneyRowSpec(
                    "pv_new", R.string.svc_row_new_releases,
                    primeCatalog.recent() + listOf(
                        upcomingSeries(networks = ServiceHubIds.NET_PRIME_VIDEO),
                        upcomingMovies(ServiceHubIds.CO_AMAZON)
                    ),
                    premiereBadges = true
                )
            )
            add(
                DisneyRowSpec(
                    "pv_originals", R.string.svc_row_amazon_originals,
                    listOf(
                        DisneyRowSource.Curated(PRIME_HITS),
                        DisneyRowSource.Discover(TV, networks = ServiceHubIds.NET_PRIME_VIDEO),
                        DisneyRowSource.Discover(MOVIE, companies = ServiceHubIds.CO_AMAZON)
                    )
                )
            )
            add(DisneyRowSpec("pv_movies", R.string.svc_row_popular_movies, listOf(primeCatalog.movies())))
            add(DisneyRowSpec("pv_series", R.string.svc_row_popular_tv, listOfNotNull(primeCatalog.series())))
            addAll(cardRow("pv_genres", R.string.svc_row_browse_genre) {
                STREAMING_GENRES.forEach { genre(primeCatalog, it) }
            })
            addAll(cardRow("pv_collections", R.string.svc_row_franchises) {
                PRIME_FRANCHISES.forEach { (name, query) -> franchise(name, query) }
            })
            add(
                DisneyRowSpec(
                    "pv_sports", R.string.svc_row_sports_live,
                    primeCatalog.both { copy(keywords = K_SPORTS) } +
                        DisneyRowSource.Discover(TV, networks = ServiceHubIds.NET_PRIME_VIDEO, keywords = K_SPORTS)
                )
            )
            add(
                DisneyRowSpec(
                    "pv_intl_docs", R.string.svc_row_intl_docs,
                    primeCatalog.both { copy(originalLanguage = INTERNATIONAL) } +
                        primeCatalog.both { copy(genres = "99") } +
                        listOfNotNull(primeCatalog.series { copy(keywords = K_TRUE_CRIME) })
                )
            )
            addAll(cardRow("pv_more", R.string.svc_row_discover_more) {
                card("movies", R.string.svc_card_all_movies, sources = listOf(primeCatalog.movies()))
                card("series", R.string.svc_card_all_tv, sources = listOfNotNull(primeCatalog.series()))
                card("new", R.string.svc_card_new_releases, sources = primeCatalog.newest())
                card(
                    "soon", R.string.svc_card_coming_soon,
                    sources = listOf(
                        upcomingSeries(networks = ServiceHubIds.NET_PRIME_VIDEO, untilDays = 365).copy(sortBy = SOONEST),
                        upcomingMovies(ServiceHubIds.CO_AMAZON, untilDays = 365, sort = SOONEST)
                    )
                )
                card(
                    "awards", R.string.svc_card_award_winning,
                    sources = listOf(DisneyRowSource.Curated(PRIME_AWARDS)) + primeCatalog.topRated(2000, 800)
                )
                card(
                    "kids", R.string.svc_card_kids_family,
                    sources = listOfNotNull(primeCatalog.movies { copy(genres = "10751") }, primeCatalog.series { copy(genres = "10762|10751") })
                )
                card("reality", R.string.svc_card_reality_competition, sources = listOfNotNull(primeCatalog.series { copy(genres = "10764") }))
                PRIME_CHANNELS.forEach { (name, providerId) ->
                    card(
                        "channel_$providerId", name = name, art = HubCardArt.Provider(providerId),
                        sources = providers(providerId.toString()).both()
                    )
                }
                card("collections", R.string.svc_card_all_collections, sources = PRIME_FRANCHISES.map { collection(it.second) })
            })
        }
    )

    // ------------------------------------------------------------------ Apple TV

    private val appleCatalog = providers(ServiceHubIds.APPLE_PROVIDERS)
    private val appleOriginalSeries = DisneyRowSource.Discover(TV, networks = ServiceHubIds.NET_APPLE_TV)
    private val appleOriginalFilms = DisneyRowSource.Discover(MOVIE, companies = ServiceHubIds.CO_APPLE)

    val appleTv: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.APPLETV_MAIN,
        heroRowId = "atv_top10",
        rows = buildList {
            add(DisneyRowSpec("atv_top10", R.string.svc_row_top10_popular, appleCatalog.both(), ranked = true))
            add(
                DisneyRowSpec(
                    "atv_new", R.string.svc_row_new_premieres,
                    appleCatalog.recent() + listOf(
                        upcomingSeries(networks = ServiceHubIds.NET_APPLE_TV),
                        upcomingMovies(ServiceHubIds.CO_APPLE)
                    ),
                    premiereBadges = true
                )
            )
            add(DisneyRowSpec("atv_originals", R.string.svc_row_apple_originals, listOf(appleOriginalSeries, appleOriginalFilms)))
            add(
                DisneyRowSpec(
                    "atv_series", R.string.svc_row_popular_series,
                    listOf(DisneyRowSource.Curated(APPLE_SERIES), appleOriginalSeries)
                )
            )
            add(
                DisneyRowSpec("atv_films", R.string.svc_row_apple_films, listOf(appleOriginalFilms))
                    .withSeeAll(appleOriginalFilms.copy(sortBy = MOST_VOTED))
            )
            add(
                DisneyRowSpec(
                    "atv_scifi", R.string.svc_row_scifi_fantasy,
                    listOfNotNull(appleCatalog.series { copy(genres = "10765") }, appleCatalog.movies { copy(genres = "878|14") })
                )
            )
            addAll(cardRow("atv_genres", R.string.svc_row_browse_genre) {
                listOf(DRAMA, COMEDY, THRILLER, CRIME, ACTION, ROMANCE, MYSTERY, DOCUMENTARY, SCI_FI, FANTASY, FAMILY, ANIMATION, KIDS)
                    .forEach { genre(appleCatalog, it) }
            })
            add(
                DisneyRowSpec(
                    "atv_docs", R.string.svc_row_docs_specials,
                    appleCatalog.both { copy(genres = "99") } + appleCatalog.both { copy(keywords = K_NATURE) } +
                        listOf(appleCatalog.movies { copy(genres = "99,10402") })
                )
            )
            add(
                DisneyRowSpec(
                    "atv_sports", R.string.svc_row_sports,
                    listOf(DisneyRowSource.Curated(APPLE_SPORTS)) + appleCatalog.both { copy(keywords = K_SPORTS) }
                )
            )
            add(
                DisneyRowSpec(
                    "atv_awards", R.string.svc_row_awards_acclaimed,
                    listOf(DisneyRowSource.Curated(APPLE_AWARDS)) + appleCatalog.topRated(500, 300)
                )
            )
            addAll(cardRow("atv_more", R.string.svc_row_discover_more) {
                card("movies", R.string.svc_card_all_movies, sources = listOf(appleCatalog.movies()))
                card("series", R.string.svc_card_all_series, sources = listOfNotNull(appleCatalog.series()))
                card("originals", R.string.svc_card_originals, sources = listOf(appleOriginalSeries, appleOriginalFilms))
                card("international", R.string.svc_card_international, sources = appleCatalog.both { copy(originalLanguage = INTERNATIONAL) })
                card("new", R.string.svc_card_new_releases, sources = appleCatalog.newest())
                card(
                    "soon", R.string.svc_card_coming_soon,
                    sources = listOf(
                        upcomingSeries(networks = ServiceHubIds.NET_APPLE_TV, untilDays = 365).copy(sortBy = SOONEST),
                        upcomingMovies(ServiceHubIds.CO_APPLE, untilDays = 365, sort = SOONEST)
                    )
                )
                card(
                    "kids", R.string.svc_card_kids_family,
                    sources = listOfNotNull(appleCatalog.movies { copy(genres = "10751") }, appleCatalog.series { copy(genres = "10762|10751") })
                )
                card("docs", R.string.svc_card_documentaries, sources = appleCatalog.both { copy(genres = "99") })
            })
        }
    )

    // ------------------------------------------------------------------ A24

    private val a24Catalog = studio(ServiceHubIds.CO_A24, ServiceHubIds.CO_A24)

    val a24: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.A24_MAIN,
        heroRowId = "a24_popular",
        rows = buildList {
            add(
                DisneyRowSpec("a24_popular", R.string.svc_row_a24_popular, listOf(DisneyRowSource.Curated(A24_POPULAR), a24Catalog.movies()))
                    .withSeeAll(a24Catalog.movies(MOST_VOTED))
            )
            add(
                DisneyRowSpec(
                    "a24_new", R.string.svc_row_new_upcoming_films,
                    listOf(
                        a24Catalog.movies { copy(releaseDateGte = days(-120), releaseDateLte = TODAY) },
                        upcomingMovies(ServiceHubIds.CO_A24, untilDays = 730)
                    ),
                    premiereBadges = true
                )
            )
            add(DisneyRowSpec("a24_tv", R.string.svc_row_a24_tv, listOfNotNull(DisneyRowSource.Curated(A24_SERIES), a24Catalog.series())))
            add(
                DisneyRowSpec(
                    "a24_horror", R.string.svc_row_a24_horror,
                    listOf(DisneyRowSource.Curated(A24_HORROR), a24Catalog.movies { copy(genres = "27") })
                )
            )
            add(
                DisneyRowSpec(
                    "a24_awards", R.string.svc_row_awards_nominees,
                    listOf(DisneyRowSource.Curated(A24_AWARDS), a24Catalog.movies(TOP_RATED) { copy(voteCountGte = 300) })
                )
            )
            add(
                DisneyRowSpec(
                    "a24_scifi", R.string.svc_row_scifi_fantasy,
                    listOfNotNull(a24Catalog.movies { copy(genres = "878|14") }, a24Catalog.series { copy(genres = "10765") })
                )
            )
            add(
                DisneyRowSpec(
                    "a24_drama", R.string.svc_row_drama_coming_of_age,
                    listOfNotNull(
                        a24Catalog.movies { copy(keywords = K_COMING_OF_AGE) },
                        a24Catalog.movies { copy(genres = "18") },
                        a24Catalog.series { copy(genres = "18") }
                    )
                )
            )
            add(
                DisneyRowSpec(
                    "a24_comedy", R.string.svc_row_comedy_dark,
                    listOfNotNull(
                        a24Catalog.movies { copy(keywords = K_DARK_COMEDY) },
                        a24Catalog.movies { copy(genres = "35") },
                        a24Catalog.series { copy(genres = "35") }
                    )
                )
            )
            add(
                DisneyRowSpec(
                    "a24_docs", R.string.svc_row_docs_real,
                    listOfNotNull(
                        a24Catalog.movies { copy(genres = "99") },
                        a24Catalog.movies { copy(keywords = K_TRUE_STORY) },
                        a24Catalog.series { copy(genres = "99") }
                    )
                )
            )
            addAll(cardRow("a24_collections", R.string.svc_row_a24_collections) {
                card(
                    "awards_season", R.string.svc_card_awards_season,
                    sources = listOf(DisneyRowSource.Curated(A24_AWARDS), a24Catalog.movies(TOP_RATED) { copy(voteCountGte = 300) })
                )
                card("debuts", R.string.svc_card_debuts, sources = listOf(DisneyRowSource.Curated(A24_DEBUTS)))
                card("lovebirds", R.string.svc_card_lovebirds, sources = listOf(a24Catalog.movies { copy(genres = "10749") }))
                card("creature_comforts", R.string.svc_card_creature_comforts, sources = listOf(a24Catalog.movies { copy(keywords = K_CREATURES) }))
            })
            addAll(cardRow("a24_filmmakers", R.string.svc_row_a24_filmmakers) {
                A24_FILMMAKERS.forEach { filmmaker ->
                    card(
                        slug(filmmaker.name), name = filmmaker.name, art = HubCardArt.Person(filmmaker.photo),
                        sources = listOf(a24Catalog.movies(NEWEST) { copy(crew = filmmaker.ids) })
                    )
                }
            })
            addAll(cardRow("a24_more", R.string.svc_row_discover_more) {
                card("films", R.string.svc_card_all_a24_films, sources = listOf(a24Catalog.movies()))
                card("tv", R.string.svc_card_all_a24_tv, sources = listOfNotNull(a24Catalog.series()))
                // Movies and series together: its See All page has the Genre menu.
                card("genres", R.string.svc_card_browse_genre, sources = a24Catalog.both())
                card("romance", R.string.svc_card_romance, sources = listOf(a24Catalog.movies { copy(genres = "10749") }))
                card(
                    "mystery_thriller", R.string.svc_card_mystery_thriller,
                    sources = listOfNotNull(a24Catalog.movies { copy(genres = "9648|53") }, a24Catalog.series { copy(genres = "9648") })
                )
                card("crime", R.string.svc_card_crime, sources = a24Catalog.both { copy(genres = "80") })
                card("action", R.string.svc_card_action, sources = listOfNotNull(a24Catalog.movies { copy(genres = "28") }, a24Catalog.series { copy(genres = "10759") }))
                card("international", R.string.svc_card_international_films, sources = listOf(a24Catalog.movies { copy(originalLanguage = INTERNATIONAL) }))
                card("soon", R.string.svc_card_coming_soon, sources = listOf(upcomingMovies(ServiceHubIds.CO_A24, untilDays = 730, sort = SOONEST)))
            })
        }
    )

    // ------------------------------------------------------------------ Angel Studios

    private val angelCatalog = providers(ServiceHubIds.ANGEL_PROVIDERS)
    private val angelStudio = studio(ServiceHubIds.CO_ANGEL, ServiceHubIds.CO_ANGEL)

    val angel: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.ANGEL_MAIN,
        heroRowId = "ang_popular",
        rows = buildList {
            add(DisneyRowSpec("ang_popular", R.string.svc_row_popular_trending, angelCatalog.both() + angelStudio.movies()))
            add(
                DisneyRowSpec(
                    "ang_new", R.string.svc_row_new_premieres,
                    listOfNotNull(
                        angelStudio.movies { copy(releaseDateGte = days(-120), releaseDateLte = TODAY) },
                        angelCatalog.series { copy(airDateGte = days(-60), airDateLte = TODAY) },
                        upcomingMovies(ServiceHubIds.CO_ANGEL, untilDays = 365)
                    ),
                    premiereBadges = true
                )
            )
            add(DisneyRowSpec("ang_originals", R.string.svc_row_angel_originals, listOf(DisneyRowSource.Curated(ANGEL_ORIGINALS)) + angelStudio.both()))
            add(
                DisneyRowSpec(
                    "ang_inspirational", R.string.svc_row_inspirational,
                    listOf(
                        angelCatalog.movies { copy(keywords = K_INSPIRATIONAL) },
                        angelCatalog.movies { copy(keywords = K_TRUE_STORY, genres = "18") }
                    )
                )
            )
            add(DisneyRowSpec("ang_series", R.string.svc_row_popular_series, listOfNotNull(angelCatalog.series())))
            add(
                DisneyRowSpec(
                    "ang_family", R.string.svc_row_family_kids,
                    listOfNotNull(angelCatalog.movies { copy(genres = "10751|16") }, angelCatalog.series { copy(genres = "10762|10751|16") })
                )
            )
            addAll(cardRow("ang_genres", R.string.svc_row_browse_genre) {
                listOf(
                    DRAMA, FAMILY, ADVENTURE, COMEDY,
                    Pick("history", R.string.svc_card_history, "36", "10768"),
                    DOCUMENTARY, ANIMATION, Pick("thriller", R.string.svc_card_thriller, "53", "9648")
                ).forEach { genre(angelCatalog, it) }
            })
            add(
                DisneyRowSpec(
                    "ang_faith", R.string.svc_row_faith_history,
                    angelCatalog.both { copy(keywords = K_FAITH) } + angelCatalog.movies { copy(genres = "36") }
                )
            )
            add(DisneyRowSpec("ang_true", R.string.svc_row_true_stories, angelCatalog.both { copy(keywords = K_TRUE_STORY) }))
            add(
                DisneyRowSpec(
                    "ang_coming", R.string.svc_row_guild_coming,
                    listOf(
                        upcomingMovies(ServiceHubIds.CO_ANGEL, untilDays = 730, sort = SOONEST),
                        upcomingSeries(companies = ServiceHubIds.CO_ANGEL, untilDays = 730)
                    ),
                    premiereBadges = true
                )
            )
            addAll(cardRow("ang_more", R.string.svc_row_discover_more) {
                card("movies", R.string.svc_card_all_movies, sources = listOf(angelCatalog.movies(), angelStudio.movies()))
                card("series", R.string.svc_card_all_series, sources = listOfNotNull(angelCatalog.series(), angelStudio.series()))
                card("originals", R.string.svc_card_originals, sources = angelStudio.both())
                card("top_rated", R.string.svc_card_top_rated, sources = angelCatalog.topRated(100, 30))
                card("comedy", R.string.svc_card_comedy, sources = angelCatalog.both { copy(genres = "35") })
                card("docs", R.string.svc_card_documentaries, sources = angelCatalog.both { copy(genres = "99") })
                card("international", R.string.svc_card_international, sources = angelCatalog.both { copy(originalLanguage = INTERNATIONAL) })
                card("soon", R.string.svc_card_coming_soon, sources = listOf(upcomingMovies(ServiceHubIds.CO_ANGEL, untilDays = 730, sort = SOONEST)))
            })
        }
    )

    // ------------------------------------------------------------------ Shudder

    private val shudderCatalog = providers(ServiceHubIds.SHUDDER_PROVIDERS)
    private val shudderOriginalSeries = DisneyRowSource.Discover(TV, networks = ServiceHubIds.NET_SHUDDER)
    private val shudderOriginalFilms = DisneyRowSource.Discover(MOVIE, companies = ServiceHubIds.CO_SHUDDER)

    val shudder: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.SHUDDER_MAIN,
        heroRowId = "sh_best",
        rows = buildList {
            add(DisneyRowSpec("sh_best", R.string.svc_row_best_of_shudder, shudderCatalog.topRated(300, 100)))
            add(
                DisneyRowSpec(
                    "sh_new", R.string.svc_row_new_premieres,
                    listOfNotNull(
                        shudderCatalog.movies(NEWEST) { copy(releaseDateGte = days(-365), releaseDateLte = TODAY) },
                        shudderOriginalSeries.copy(airDateGte = days(-60), airDateLte = TODAY),
                        upcomingMovies(ServiceHubIds.CO_SHUDDER, untilDays = 365),
                        upcomingSeries(networks = ServiceHubIds.NET_SHUDDER, untilDays = 365)
                    ),
                    premiereBadges = true
                )
            )
            add(DisneyRowSpec("sh_originals", R.string.svc_row_shudder_originals, listOf(shudderOriginalSeries, shudderOriginalFilms)))
            add(
                DisneyRowSpec(
                    "sh_timeless", R.string.svc_row_timeless_terrors,
                    listOf(shudderCatalog.movies(MOST_VOTED) { copy(releaseDateLte = "1989-12-31") })
                )
            )
            add(
                DisneyRowSpec(
                    "sh_masterpieces", R.string.svc_row_horror_masterpieces,
                    listOf(shudderCatalog.movies(TOP_RATED) { copy(genres = "27", voteCountGte = 1000) })
                )
            )
            add(DisneyRowSpec("sh_series", R.string.svc_row_serial_nightmares, listOfNotNull(shudderCatalog.series(), shudderOriginalSeries)))
            add(DisneyRowSpec("sh_docs", R.string.svc_row_behind_screams, shudderCatalog.both { copy(genres = "99") }))
            addAll(cardRow("sh_genres", R.string.svc_row_browse_genre) {
                listOf(
                    Pick("supernatural", R.string.svc_card_supernatural, keywords = K_SUPERNATURAL),
                    Pick("killers", R.string.svc_card_killers, keywords = K_KILLERS),
                    Pick("creatures", R.string.svc_card_creature_features, keywords = K_CREATURES),
                    Pick("psychological", R.string.svc_card_psych_thriller, keywords = K_PSYCH_THRILLER),
                    Pick("paranormal", R.string.svc_card_paranormal, keywords = K_PARANORMAL),
                    Pick("international", R.string.svc_card_international, languages = INTERNATIONAL),
                    DOCUMENTARY,
                    COMEDY,
                    Pick("crime_mystery", R.string.svc_card_crime_mystery, "80|9648", "80|9648"),
                    Pick("revenge", R.string.svc_card_revenge, keywords = K_REVENGE),
                    SCI_FI,
                    Pick("cult", R.string.svc_card_cult, keywords = K_CULT)
                ).forEach { genre(shudderCatalog, it) }
            })
            add(
                DisneyRowSpec(
                    "sh_international", R.string.svc_row_intl_horror,
                    shudderCatalog.both { copy(originalLanguage = INTERNATIONAL) }
                )
            )
            addAll(cardRow("sh_more", R.string.svc_row_discover_more) {
                card("movies", R.string.svc_card_all_movies, sources = listOf(shudderCatalog.movies()))
                card("series", R.string.svc_card_all_series, sources = listOfNotNull(shudderCatalog.series(), shudderOriginalSeries))
                card("new", R.string.svc_card_new_releases, sources = shudderCatalog.newest())
                card("classics", R.string.svc_card_classics, sources = listOf(shudderCatalog.movies(MOST_VOTED) { copy(releaseDateLte = "1989-12-31") }))
                card("acclaimed", R.string.svc_card_critically_acclaimed, sources = shudderCatalog.topRated(300, 100))
                card("horror_comedy", R.string.svc_card_horror_comedy, sources = listOf(shudderCatalog.movies { copy(genres = "27,35") }))
                card("originals", R.string.svc_card_originals, sources = listOf(shudderOriginalSeries, shudderOriginalFilms))
                card("catalog", R.string.svc_card_full_catalog, sources = shudderCatalog.both())
            })
        }
    )

    // ------------------------------------------------------------------ Blumhouse

    private val blumhouseCatalog = studio(ServiceHubIds.CO_BLUMHOUSE_MOVIES, ServiceHubIds.CO_BLUMHOUSE_TV)

    val blumhouse: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.BLUMHOUSE_MAIN,
        heroRowId = "bh_popular",
        rows = buildList {
            add(
                DisneyRowSpec("bh_popular", R.string.svc_row_blumhouse_popular, listOf(DisneyRowSource.Curated(BLUMHOUSE_POPULAR), blumhouseCatalog.movies()))
                    .withSeeAll(blumhouseCatalog.movies(MOST_VOTED))
            )
            add(
                DisneyRowSpec(
                    "bh_new", R.string.svc_row_new_upcoming,
                    listOf(
                        blumhouseCatalog.movies { copy(releaseDateGte = days(-120), releaseDateLte = TODAY) },
                        upcomingMovies(ServiceHubIds.CO_BLUMHOUSE_MOVIES, untilDays = 1095)
                    ),
                    premiereBadges = true
                )
            )
            addAll(cardRow("bh_franchises", R.string.svc_row_franchises_sequels) { BLUMHOUSE_FRANCHISES.forEach { franchise(it) } })
            add(
                DisneyRowSpec(
                    "bh_psych", R.string.svc_row_psych_horror,
                    listOf(blumhouseCatalog.movies { copy(keywords = K_PSYCH_THRILLER) }, blumhouseCatalog.movies { copy(genres = "53") })
                )
            )
            add(DisneyRowSpec("bh_supernatural", R.string.svc_row_supernatural, listOf(blumhouseCatalog.movies { copy(keywords = "$K_SUPERNATURAL|$K_PARANORMAL") })))
            add(DisneyRowSpec("bh_slashers", R.string.svc_row_slashers, listOf(blumhouseCatalog.movies { copy(keywords = K_KILLERS) })))
            add(
                DisneyRowSpec(
                    "bh_scifi", R.string.svc_row_scifi_ai,
                    listOf(blumhouseCatalog.movies { copy(keywords = K_AI_TECH) }, blumhouseCatalog.movies { copy(genres = "878") })
                )
            )
            add(DisneyRowSpec("bh_comedy", R.string.svc_row_horror_comedy, listOf(blumhouseCatalog.movies { copy(genres = "27,35") })))
            add(DisneyRowSpec("bh_tv", R.string.svc_row_blumhouse_tv, listOfNotNull(blumhouseCatalog.series())))
            add(DisneyRowSpec("bh_beyond", R.string.svc_row_beyond_horror, listOf(blumhouseCatalog.movies { copy(withoutGenres = "27") })))
            addAll(cardRow("bh_more", R.string.svc_row_discover_more) {
                card("movies", R.string.svc_card_all_blumhouse_movies, sources = listOf(blumhouseCatalog.movies()))
                card("tv", R.string.svc_card_all_blumhouse_tv, sources = listOfNotNull(blumhouseCatalog.series()))
                card("horror", R.string.svc_card_all_horror, sources = listOf(blumhouseCatalog.movies { copy(genres = "27") }))
                card("creatures", R.string.svc_card_creature_features, sources = listOf(blumhouseCatalog.movies { copy(keywords = K_CREATURES) }))
                card("found_footage", R.string.svc_card_found_footage, sources = listOf(blumhouseCatalog.movies { copy(keywords = K_FOUND_FOOTAGE) }))
                card("home_invasion", R.string.svc_card_home_invasion, sources = listOf(blumhouseCatalog.movies { copy(keywords = K_HOME_INVASION) }))
                card("survival", R.string.svc_card_survival_horror, sources = listOf(blumhouseCatalog.movies { copy(keywords = K_SURVIVAL_HORROR) }))
                card(
                    "mystery", R.string.svc_card_mystery_suspense,
                    sources = listOfNotNull(blumhouseCatalog.movies { copy(genres = "9648|53") }, blumhouseCatalog.series { copy(genres = "9648") })
                )
                card("anthologies", R.string.svc_card_anthologies, sources = blumhouseCatalog.both { copy(keywords = K_ANTHOLOGY) })
                card(
                    "awards", R.string.svc_card_award_winning_films,
                    sources = listOf(DisneyRowSource.Curated(BLUMHOUSE_AWARDS), blumhouseCatalog.movies(TOP_RATED) { copy(voteCountGte = 500) })
                )
                card("directors", R.string.svc_card_directors, sources = listOf(blumhouseCatalog.movies(NEWEST) { copy(crew = BLUMHOUSE_DIRECTORS) }))
                card("catalog", R.string.svc_card_full_catalog, sources = blumhouseCatalog.both())
            })
        }
    )

    // ------------------------------------------------------------------ FOX Nation

    private val foxCatalog = studio(ServiceHubIds.CO_FOX_NATION, ServiceHubIds.CO_FOX_NATION)

    val foxNation: DisneyHubSpec = DisneyHubSpec(
        hub = DisneyPlusHub.FOXNATION_MAIN,
        heroRowId = "fn_popular",
        rows = buildList {
            add(DisneyRowSpec("fn_popular", R.string.svc_row_popular_fox, foxCatalog.both()))
            add(
                DisneyRowSpec(
                    "fn_new", R.string.svc_row_new_premieres,
                    listOfNotNull(
                        foxCatalog.movies(NEWEST) { copy(releaseDateGte = days(-120), releaseDateLte = TODAY) },
                        foxCatalog.series(NEWEST) { copy(releaseDateGte = days(-365), releaseDateLte = TODAY) },
                        upcomingMovies(ServiceHubIds.CO_FOX_NATION, untilDays = 365),
                        upcomingSeries(companies = ServiceHubIds.CO_FOX_NATION, untilDays = 365)
                    ),
                    premiereBadges = true
                )
            )
            add(DisneyRowSpec("fn_originals", R.string.svc_row_fox_originals, foxCatalog.both(MOST_VOTED)))
            add(
                DisneyRowSpec(
                    "fn_history", R.string.svc_row_american_history,
                    listOfNotNull(
                        foxCatalog.movies { copy(genres = "36") },
                        DisneyRowSource.Curated(FOX_HISTORY_SERIES),
                        foxCatalog.series { copy(genres = "10768") }
                    )
                )
            )
            add(
                DisneyRowSpec(
                    "fn_crime", R.string.svc_row_true_crime,
                    listOf(DisneyRowSource.Curated(FOX_CRIME)) + foxCatalog.both { copy(genres = "80") }
                )
            )
            add(
                DisneyRowSpec(
                    "fn_politics", R.string.svc_row_politics,
                    listOfNotNull(DisneyRowSource.Curated(FOX_POLITICS), foxCatalog.series { copy(genres = "10768") })
                )
            )
            add(DisneyRowSpec("fn_faith", R.string.svc_row_faith_religious, listOf(DisneyRowSource.Curated(FOX_FAITH))))
            add(
                DisneyRowSpec(
                    "fn_outdoors", R.string.svc_row_outdoors,
                    listOfNotNull(DisneyRowSource.Curated(FOX_OUTDOORS), foxCatalog.series { copy(genres = "10759") })
                )
            )
            add(
                DisneyRowSpec(
                    "fn_personalities", R.string.svc_row_personalities,
                    listOf(DisneyRowSource.Curated(FOX_PERSONALITIES), foxCatalog.movies { copy(genres = "35") })
                )
            )
            add(
                DisneyRowSpec(
                    "fn_entertainment", R.string.svc_row_movies_reality,
                    listOfNotNull(
                        foxCatalog.movies { copy(genres = "10751|35|10770|10402") },
                        foxCatalog.series { copy(genres = "18|35|10764") }
                    )
                )
            )
            addAll(cardRow("fn_more", R.string.svc_row_discover_more) {
                card("shows", R.string.svc_card_all_shows, sources = listOfNotNull(foxCatalog.series()))
                card("movies", R.string.svc_card_all_movies, sources = listOf(foxCatalog.movies()))
                card("docs", R.string.svc_card_all_documentaries, sources = foxCatalog.both { copy(genres = "99") })
                card("biography", R.string.svc_card_biography, sources = listOf(DisneyRowSource.Curated(FOX_BIOGRAPHIES)))
                card("crime", R.string.svc_card_crime, sources = listOf(DisneyRowSource.Curated(FOX_CRIME)) + foxCatalog.both { copy(genres = "80") })
                card(
                    "history", R.string.svc_card_history,
                    sources = listOfNotNull(foxCatalog.movies { copy(genres = "36") }, DisneyRowSource.Curated(FOX_HISTORY_SERIES))
                )
                card("faith", R.string.svc_card_faith, sources = listOf(DisneyRowSource.Curated(FOX_FAITH)))
                card(
                    "politics", R.string.svc_card_politics,
                    sources = listOfNotNull(DisneyRowSource.Curated(FOX_POLITICS), foxCatalog.series { copy(genres = "10768") })
                )
                card(
                    "war", R.string.svc_card_war,
                    sources = listOfNotNull(foxCatalog.movies { copy(genres = "10752") }, foxCatalog.series { copy(genres = "10768") })
                )
                card("comedy", R.string.svc_card_comedy, sources = listOf(foxCatalog.movies { copy(genres = "35") }))
                card(
                    "a_z", R.string.svc_card_browse_az,
                    sources = listOfNotNull(foxCatalog.movies(A_TO_Z_MOVIES), foxCatalog.series(A_TO_Z_SERIES))
                )
            })
        }
    )
}
