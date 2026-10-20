package com.nuvio.tv.core.tmdb

/**
 * RCTV: how a row's title cards show each title's name and where it's from.
 *
 * - [INTERNATIONAL] (International Spotlight): the official English logo; else the logo in the
 *   title's original language when that language is written in Latin letters (a Spanish or French
 *   logo reads fine, a Korean or Japanese one doesn't); else the English title as text. The flag
 *   of the title's country of origin in the card's bottom-right corner.
 * - [SPANISH] (Latin America & Spanish-Language Hits): the Spanish logo; else the original
 *   language's; else the English one; else the Spanish title as text.
 */
enum class TitleSpotlightStyle {
    INTERNATIONAL,
    SPANISH;

    companion object {
        /** The style of a Home row (by its id), or null for rows that show titles the usual way. */
        fun forRow(rowId: String?): TitleSpotlightStyle? = when (rowId) {
            "home_international" -> INTERNATIONAL
            "home_spanish" -> SPANISH
            else -> null
        }
    }
}

/** One of a title's logos on TMDB: its image path, language (ISO 639-1), country and rating. */
data class SpotlightLogo(
    val path: String,
    val language: String?,
    val country: String?,
    val vote: Double
)

/** What TMDB says about a title, kept for its cards ([TitleSpotlightPicker] decides what shows). */
data class TitleSpotlightInfo(
    /** Its English title (TMDB's English name; the original one when there's no English name). */
    val englishTitle: String?,
    val originalTitle: String?,
    /** ISO 639-1, lowercase. */
    val originalLanguage: String?,
    /** Its country of origin (ISO 3166-1 alpha-2), only when the metadata settles it. */
    val country: String?,
    val logos: List<SpotlightLogo>
)

/** The choices behind the spotlight cards; no Android or network, so it can be tested as is. */
object TitleSpotlightPicker {

    /** Languages written in Latin letters: their logos read for everyone the row is for. */
    private val LATIN_SCRIPT = setOf(
        "en", "es", "fr", "it", "pt", "de", "nl", "sv", "da", "no", "nb", "nn", "fi", "is", "pl",
        "cs", "sk", "sl", "hr", "bs", "sq", "ro", "hu", "tr", "id", "ms", "tl", "vi", "ca", "eu",
        "gl", "et", "lv", "lt", "af", "sw", "so", "ga", "cy", "mt", "lb", "eo", "la", "qu", "gn",
        "ha", "yo", "ig", "zu", "xh", "uz", "az", "tk", "mi", "sm", "to", "fj", "ht", "rm", "fo"
    )

    /**
     * Where a language is a main language. Only used to choose among the countries TMDB itself lists
     * for a title (a Korean film made with a US company is Korean), never to guess a country.
     */
    private val LANGUAGE_COUNTRIES: Map<String, Set<String>> = mapOf(
        "ko" to setOf("KR", "KP"),
        "ja" to setOf("JP"),
        "zh" to setOf("CN", "TW", "HK", "MO", "SG"),
        "cn" to setOf("CN", "TW", "HK", "MO", "SG"),
        "hi" to setOf("IN"), "te" to setOf("IN"), "ta" to setOf("IN", "LK"), "ml" to setOf("IN"),
        "kn" to setOf("IN"), "bn" to setOf("IN", "BD"), "mr" to setOf("IN"), "pa" to setOf("IN", "PK"),
        "ur" to setOf("PK", "IN"),
        "th" to setOf("TH"), "vi" to setOf("VN"), "id" to setOf("ID"), "ms" to setOf("MY", "BN", "SG"),
        "tl" to setOf("PH"), "tr" to setOf("TR", "CY"), "fa" to setOf("IR", "AF", "TJ"), "he" to setOf("IL"),
        "ar" to setOf(
            "EG", "SA", "AE", "LB", "MA", "DZ", "TN", "JO", "SY", "IQ", "KW", "QA", "BH", "OM", "YE", "LY",
            "SD", "PS"
        ),
        "es" to setOf(
            "ES", "MX", "AR", "CO", "CL", "PE", "VE", "UY", "PY", "BO", "EC", "GT", "CU", "DO", "HN", "SV",
            "NI", "CR", "PA", "PR", "GQ"
        ),
        "pt" to setOf("PT", "BR", "AO", "MZ", "CV"),
        "fr" to setOf("FR", "BE", "CA", "CH", "LU", "MC", "SN", "CI", "CM", "HT"),
        "de" to setOf("DE", "AT", "CH", "LI", "LU"),
        "it" to setOf("IT", "CH", "SM"),
        "nl" to setOf("NL", "BE"),
        "sv" to setOf("SE", "FI"), "da" to setOf("DK"), "no" to setOf("NO"), "nb" to setOf("NO"),
        "nn" to setOf("NO"), "fi" to setOf("FI"), "is" to setOf("IS"), "pl" to setOf("PL"),
        "cs" to setOf("CZ"), "sk" to setOf("SK"), "hu" to setOf("HU"), "ro" to setOf("RO", "MD"),
        "el" to setOf("GR", "CY"), "ru" to setOf("RU", "BY", "KZ"), "uk" to setOf("UA"),
        "sr" to setOf("RS", "BA", "ME"), "hr" to setOf("HR", "BA"), "bg" to setOf("BG"),
        "et" to setOf("EE"), "lv" to setOf("LV"), "lt" to setOf("LT"), "ka" to setOf("GE"),
        "hy" to setOf("AM"), "en" to setOf("US", "GB", "IE", "CA", "AU", "NZ")
    )

    /** Countries that no longer exist (TMDB uses them for old films): no flag for them. */
    private val FORMER_COUNTRIES = setOf("SU", "XC", "YU", "DD", "CS", "AN", "XG", "BU", "ZR", "TP", "XI")

    private val COUNTRY_CODE = Regex("^[A-Z]{2}$")

    private fun codes(list: List<String>?): List<String> =
        list.orEmpty().map { it.trim().uppercase() }.filter { COUNTRY_CODE.matches(it) }.distinct()

    /**
     * The title's country of origin: TMDB's own "country of origin" when it names one, else its only
     * production country. When several are named, the one both lists agree on, else the one where
     * its original language is spoken (when exactly one is). Null when none of that settles it, so
     * no flag shows rather than a wrong one.
     */
    fun primaryCountry(
        originCountries: List<String>?,
        productionCountries: List<String>?,
        originalLanguage: String?
    ): String? {
        val origin = codes(originCountries)
        val production = codes(productionCountries)
        val language = originalLanguage?.trim()?.lowercase()
        fun settle(candidates: List<String>, otherList: List<String>): String? {
            if (candidates.size == 1) return candidates[0]
            if (candidates.isEmpty()) return null
            // Several: the one the other list names too...
            candidates.filter { it in otherList }.singleOrNull()?.let { return it }
            // ...else the one where the title's language is spoken.
            val spoken = LANGUAGE_COUNTRIES[language].orEmpty()
            return candidates.filter { it in spoken }.singleOrNull()
        }
        val found = if (origin.isNotEmpty()) settle(origin, production) else settle(production, emptyList())
        return found?.takeUnless { it in FORMER_COUNTRIES }
    }

    private fun List<SpotlightLogo>.best(language: String, preferCountry: String? = null): SpotlightLogo? =
        filter { it.language.equals(language, ignoreCase = true) && it.path.isNotBlank() }
            .sortedWith(
                compareByDescending<SpotlightLogo> { preferCountry != null && it.country.equals(preferCountry, true) }
                    .thenByDescending { it.vote }
            )
            .firstOrNull()

    private fun normalizedLanguage(language: String?): String? =
        language?.trim()?.lowercase()?.let { if (it == "cn") "zh" else it }?.takeIf { it.isNotBlank() }

    /** The logo the card shows (its TMDB path), or null to show the title as text. */
    fun pickLogo(style: TitleSpotlightStyle, info: TitleSpotlightInfo): String? {
        val original = normalizedLanguage(info.originalLanguage)
        val originalReadable = original?.takeIf { it in LATIN_SCRIPT }
        val logo = when (style) {
            TitleSpotlightStyle.INTERNATIONAL ->
                info.logos.best("en")
                    ?: originalReadable?.takeIf { it != "en" }?.let { info.logos.best(it, info.country) }
            TitleSpotlightStyle.SPANISH ->
                info.logos.best("es", info.country)
                    ?: originalReadable?.takeIf { it != "es" }?.let { info.logos.best(it, info.country) }
                    ?: info.logos.best("en")
        }
        return logo?.path
    }

    /** The title written on the card when it has no logo. */
    fun displayTitle(style: TitleSpotlightStyle, info: TitleSpotlightInfo, fallback: String): String {
        val english = info.englishTitle?.trim()?.takeIf { it.isNotEmpty() }
        val originalTitle = info.originalTitle?.trim()?.takeIf { it.isNotEmpty() }
        return when (style) {
            TitleSpotlightStyle.INTERNATIONAL -> english ?: fallback
            // Its Spanish title: the original one for a Spanish-language title.
            TitleSpotlightStyle.SPANISH ->
                (if (normalizedLanguage(info.originalLanguage) == "es") originalTitle else null) ?: fallback
        }
    }

    /** The flag the card shows (International Spotlight only), or null. */
    fun flagCountry(style: TitleSpotlightStyle, info: TitleSpotlightInfo): String? =
        if (style == TitleSpotlightStyle.INTERNATIONAL) info.country else null
}
