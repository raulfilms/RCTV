package com.nuvio.tv.data.streaming

import com.nuvio.tv.R
import com.nuvio.tv.data.remote.api.TmdbWatchProviderEntry
import com.nuvio.tv.domain.model.MetaCompany
import java.util.Locale

/**
 * RCTV (owner's rule): where a title comes from originally, for its logo on the pause screen and
 * its Continue Watching card. That's not wherever it streams today.
 *
 * - **A show:** its network, as the streaming service that network's shows live on. FX, FXX, ABC and
 *   Freeform go to Hulu (American Horror Story is Hulu's, not Prime Video's), NBC to Peacock, CBS
 *   to Paramount+, HBO to HBO Max, and so on. A network with no service of its own (AMC) shows
 *   its own logo.
 * - **A movie:** its studio, as the service the studio belongs to (Warner Bros. → HBO Max,
 *   Universal → Peacock, Pixar → Disney+...), or its own logo when the app has one (A24,
 *   Blumhouse, NEON...).
 * - **Else:** the streaming service it's on (a service's own original whose studio doesn't say so),
 *   and last the company's logo.
 */
object OriginalHome {

    /**
     * Where it comes from: [logoRes] (the app's own logo of that service or studio), else [logoUrl] (a
     * network's or studio's logo from TMDB, shown white). [iconUrl]: the service's square app icon
     * on TMDB, when the title is on it (for a small round badge).
     */
    data class Home(
        val name: String,
        val logoRes: Int? = null,
        val logoUrl: String? = null,
        val iconUrl: String? = null
    )

    private class Service(val name: String, val logoRes: Int, val providerIds: List<Int> = emptyList())

    private val NETFLIX = Service("Netflix", R.drawable.streaming_logo_netflix, listOf(8))
    private val HULU = Service("Hulu", R.drawable.disney_brand_logo_hulu, listOf(15))
    private val PRIME = Service("Prime Video", R.drawable.streaming_logo_prime, listOf(9, 119))
    private val DISNEY = Service("Disney+", R.drawable.streaming_logo_disney, listOf(337))
    private val APPLE = Service("Apple TV+", R.drawable.streaming_logo_appletv, listOf(350))
    private val HBO_MAX = Service("HBO Max", R.drawable.streaming_logo_hbomax, listOf(1899, 384))
    private val PEACOCK = Service("Peacock", R.drawable.streaming_logo_peacock, listOf(386, 387))
    private val PARAMOUNT = Service("Paramount+", R.drawable.streaming_logo_paramount, listOf(531, 2303))
    private val DISCOVERY = Service("Discovery+", R.drawable.streaming_logo_discovery, listOf(520))
    private val SHUDDER = Service("Shudder", R.drawable.streaming_logo_shudder, listOf(99))
    private val FOX_NATION = Service("Fox Nation", R.drawable.streaming_logo_foxnation)
    private val A24 = Service("A24", R.drawable.streaming_logo_a24)
    private val BLUMHOUSE = Service("Blumhouse", R.drawable.streaming_logo_blumhouse)
    private val ANGEL = Service("Angel Studios", R.drawable.streaming_logo_angel)

    /** The services a title may be on (for the last resort: the one it streams on). */
    private val SERVICES = listOf(NETFLIX, HULU, PRIME, DISNEY, APPLE, HBO_MAX, PEACOCK, PARAMOUNT, DISCOVERY, SHUDDER)

    /** A show's network → where its shows live. */
    private val NETWORKS: Map<String, Service> = homes(
        NETFLIX to listOf("Netflix"),
        HULU to listOf("Hulu", "FX", "FXX", "FX on Hulu", "FX Productions", "ABC", "Freeform", "ABC Family", "FOX", "Fox Broadcasting Company"),
        PRIME to listOf("Prime Video", "Amazon", "Amazon Prime Video", "Amazon Studios", "Amazon MGM Studios", "Amazon Freevee", "Freevee", "IMDb TV"),
        DISNEY to listOf("Disney+", "Disney Plus", "Disney Channel", "Disney Junior", "Disney XD", "National Geographic", "Nat Geo Wild"),
        APPLE to listOf("Apple TV+", "Apple TV", "Apple TV Plus"),
        HBO_MAX to listOf("HBO", "HBO Max", "Max", "Cinemax", "TNT", "TBS", "Adult Swim", "Cartoon Network", "CNN", "TCM", "truTV", "Turner Classic Movies"),
        PEACOCK to listOf("Peacock", "NBC", "Bravo", "USA Network", "Syfy", "E!", "Oxygen", "Telemundo", "NBCSN", "CNBC"),
        PARAMOUNT to listOf("Paramount+", "Paramount Plus", "CBS", "CBS All Access", "Showtime", "SHOWTIME", "Paramount Network", "Comedy Central", "MTV", "Nickelodeon", "Nick Jr.", "BET", "BET+", "Smithsonian Channel", "CMT", "TV Land", "Spike"),
        DISCOVERY to listOf("Discovery+", "Discovery", "Discovery Channel", "TLC", "HGTV", "Food Network", "Animal Planet", "Investigation Discovery", "ID", "OWN", "Magnolia Network", "Travel Channel", "Science Channel", "Cooking Channel"),
        SHUDDER to listOf("Shudder"),
        FOX_NATION to listOf("Fox Nation")
    )

    /** A movie's studio → the service it belongs to, or the studio itself when the app has its logo. */
    private val STUDIOS: Map<String, Service> = homes(
        NETFLIX to listOf("Netflix", "Netflix Animation"),
        PRIME to listOf("Amazon Studios", "Amazon MGM Studios", "Amazon Prime Video", "Prime Video", "Amazon Content Services"),
        APPLE to listOf("Apple Studios", "Apple Original Films", "Apple TV+"),
        DISNEY to listOf(
            "Walt Disney Pictures", "Walt Disney Animation Studios", "Disney", "Pixar", "Pixar Animation Studios",
            "Marvel Studios", "Lucasfilm", "Lucasfilm Ltd.", "20th Century Studios", "20th Century Fox",
            "Twentieth Century Fox", "Searchlight Pictures", "Fox Searchlight Pictures", "Disney+", "Disneynature"
        ),
        HULU to listOf("Hulu", "Hulu Originals", "FX Productions"),
        HBO_MAX to listOf(
            "Warner Bros. Pictures", "Warner Bros.", "New Line Cinema", "DC Studios", "DC Films", "DC Entertainment",
            "HBO Films", "HBO", "HBO Max", "Warner Bros. Animation", "Warner Bros. Pictures Animation", "Castle Rock Entertainment"
        ),
        PEACOCK to listOf(
            "Universal Pictures", "Focus Features", "Illumination", "Illumination Entertainment", "DreamWorks Animation",
            "Working Title Films", "Amblin Entertainment", "Peacock", "Universal Studios"
        ),
        PARAMOUNT to listOf("Paramount Pictures", "Paramount", "Paramount Animation", "Nickelodeon Movies", "Paramount+", "MTV Films", "CBS Films", "Showtime"),
        A24 to listOf("A24"),
        BLUMHOUSE to listOf("Blumhouse Productions", "Blumhouse"),
        ANGEL to listOf("Angel Studios"),
        SHUDDER to listOf("Shudder"),
        Service("NEON", R.drawable.indie_brand_logo_neon) to listOf("NEON", "Neon"),
        Service("Bleecker Street", R.drawable.indie_brand_logo_bleecker) to listOf("Bleecker Street"),
        Service("IFC Films", R.drawable.indie_brand_logo_ifcfilms) to listOf("IFC Films"),
        Service("Magnolia Pictures", R.drawable.indie_brand_logo_magnolia) to listOf("Magnolia Pictures"),
        Service("Annapurna Pictures", R.drawable.indie_brand_logo_annapurna) to listOf("Annapurna Pictures"),
        Service("Millennium Media", R.drawable.indie_brand_logo_millennium) to listOf("Millennium Media", "Millennium Films"),
        Service("Plan B", R.drawable.indie_brand_logo_planb) to listOf("Plan B Entertainment"),
        Service("Roadside Attractions", R.drawable.indie_brand_logo_roadside) to listOf("Roadside Attractions")
    )

    /** Where [networks]' show or [companies]' movie comes from (see [OriginalHome]); null when nothing says. */
    fun of(
        isSeries: Boolean,
        networks: List<MetaCompany>,
        companies: List<MetaCompany>,
        providers: List<TmdbWatchProviderEntry>
    ): Home? {
        if (isSeries) {
            networks.firstNotNullOfOrNull { NETWORKS[key(it.name)] }?.let { return home(it, providers) }
            // A show made by a service's own studio (its network not one of the above).
            companies.firstNotNullOfOrNull { STUDIOS[key(it.name)] }?.let { return home(it, providers) }
            networks.firstOrNull { !it.logo.isNullOrBlank() }?.let { return Home(it.name, logoUrl = it.logo) }
        } else {
            companies.firstNotNullOfOrNull { STUDIOS[key(it.name)] }?.let { return home(it, providers) }
        }
        providers.firstNotNullOfOrNull { provider -> SERVICES.firstOrNull { provider.providerId in it.providerIds } }
            ?.let { return home(it, providers) }
        val company = (if (isSeries) networks + companies else companies).firstOrNull { !it.logo.isNullOrBlank() }
        return company?.let { Home(it.name, logoUrl = it.logo) }
    }

    private fun home(service: Service, providers: List<TmdbWatchProviderEntry>): Home {
        val icon = providers.firstOrNull { it.providerId in service.providerIds && !it.logoPath.isNullOrBlank() }
            ?.logoPath?.let { TMDB_ICON_BASE + it }
        return Home(service.name, logoRes = service.logoRes, iconUrl = icon)
    }

    private fun homes(vararg entries: Pair<Service, List<String>>): Map<String, Service> = buildMap {
        entries.forEach { (service, names) -> names.forEach { put(key(it), service) } }
    }

    /** "Lucasfilm Ltd." → "lucasfilmltd", "Disney+" → "disney+", "E!" → "e". */
    private fun key(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' }

    private const val TMDB_ICON_BASE = "https://image.tmdb.org/t/p/w92"
}
