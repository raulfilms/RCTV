package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.core.util.GuideText
import com.nuvio.tv.domain.model.LiveTvChannel
import java.util.Locale

/**
 * Channel countries for the Guide's Country filter. A playlist's own `tvg-country` wins;
 * otherwise a country tag at the start of the group or channel name is used
 * ("US | News", "|UK| Sports", "MX: Canal 5", "[ES] La 1").
 */
internal object LiveTvCountry {

    private val iso2: Set<String> by lazy { Locale.getISOCountries().toSet() }

    private val iso3ToIso2: Map<String, String> by lazy {
        iso2.mapNotNull { code ->
            runCatching { Locale("", code).isO3Country }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { it.uppercase(Locale.ROOT) to code }
        }.toMap()
    }

    // A leading 2–3 letter code followed by a separator, e.g. "US |", "|UK|", "[MX]", "USA -":
    // RCTV (MEM-07) read without regular expressions (GuideText), for every channel.

    /** Tags that look like country codes but aren't ("TV" is Tuvalu's code, "HD"…). */
    private val notCountries = setOf("TV", "HD", "SD", "UHD", "FHD", "VIP", "PPV", "XXX", "ALL", "NEW", "VOD")

    /** Two-letter ISO code for "US", "usa", "UK", "United States"…, or null. */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // tvg-country may list several ("US;CA"): the first is enough here.
        val first = value.split(';', ',', '|').first().trim()
        val upper = first.uppercase(Locale.ROOT)
        return when {
            upper == "UK" -> "GB"
            upper.length == 2 && upper in iso2 -> upper
            upper.length == 3 && upper in iso3ToIso2 -> iso3ToIso2[upper]
            else -> iso2.firstOrNull { code ->
                Locale("", code).getDisplayCountry(Locale.ENGLISH).equals(first, ignoreCase = true)
            }
        }
    }

    /**
     * RCTV: groups that are a region or a language, not one country ("AR | Arabic", "CAR |
     * Caribbean", "Afrique TV", "| Ex-Yu |"): listed with the countries under their own name.
     */
    private val regions = mapOf(
        "arabic" to REGION_ARABIC, "arab" to REGION_ARABIC, "caribbean" to REGION_CARIBBEAN,
        "afrique" to REGION_AFRICA, "afrique tv" to REGION_AFRICA, "africa" to REGION_AFRICA,
        "ex-yu" to REGION_EX_YU, "ex yu" to REGION_EX_YU, "exyu" to REGION_EX_YU, "balkan" to REGION_EX_YU
    )

    /**
     * RCTV: providers' own names and codes that aren't the ISO ones ("SV | Sweden" and "SW |
     * Switzerland", while ISO's SV is El Salvador; "DMA | Republic Dominican", "IRE | IRISH",
     * "BU | Bulgaria", "PANA | Panama", "España", "Mexico VIX+").
     */
    private val providerNames = mapOf(
        "republic dominican" to "DO", "dominican republic" to "DO", "republica dominicana" to "DO", "rd" to "DO",
        "irish" to "IE", "ireland" to "IE", "ire" to "IE", "bulgaria" to "BG", "bu" to "BG",
        "pana" to "PA", "panama" to "PA", "españa" to "ES", "espana" to "ES", "mexico" to "MX", "méxico" to "MX",
        "bosnia" to "BA", "czech republic" to "CZ", "turkish" to "TR", "turkey" to "TR", "iran" to "IR",
        "russia" to "RU", "usa" to "US", "uk" to "GB", "puerto rico" to "PR", "new zealand" to "NZ",
        "el salvador" to "SV", "korea" to "KR", "south korea" to "KR", "vietnam" to "VN"
    )

    /** "Sweden" → "SE", from the English name; null when it isn't one. */
    private fun byName(text: String): String? {
        val name = text.trim().trimEnd('+').trim().lowercase(Locale.ROOT)
        if (name.length < 4) return null
        providerNames[name]?.let { return it }
        return iso2.firstOrNull { code ->
            Locale("", code).getDisplayCountry(Locale.ENGLISH).equals(name, ignoreCase = true)
        }
    }

    /**
     * RCTV: a provider's group "XX | Name": the name says it best ("SV | Sweden" is Sweden), then
     * the code; a region's name is the region's.
     */
    private fun ofGroup(group: String): String? =
        // A provider has a few hundred groups for tens of thousands of channels: each worked out once.
        groupCountries.getOrPut(group) { ofGroupUncached(group).orEmpty() }.ifEmpty { null }

    private val groupCountries = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun ofGroupUncached(group: String): String? {
        val parts = group.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val first = parts.first()
        val rest = parts.drop(1)
        rest.firstOrNull()?.lowercase(Locale.ROOT)?.let { regions[it] }?.let { return it }
        rest.firstOrNull()?.let(::byName)?.let { return it }
        regions[first.lowercase(Locale.ROOT)]?.let { return it }
        if (rest.isEmpty()) {
            // No separator: "Afrique TV", "Mexico VIX+": its first words.
            regions[first.substringBefore(' ').lowercase(Locale.ROOT)]?.let { return it }
            byName(first.substringBefore(' '))?.let { return it }
            return null
        }
        providerNames[first.lowercase(Locale.ROOT)]?.let { return it }
        return null
    }

    /** The channel's country: its own, or one guessed from its group or name. */
    fun of(channel: LiveTvChannel): String? {
        normalize(channel.country)?.let { return it }
        channel.groupTitle?.let(::ofGroup)?.let { return it }
        for (text in listOfNotNull(channel.groupTitle, channel.name)) {
            val tag = GuideText.bracketCountryTag(text) ?: GuideText.leadingCountryTag(text) ?: continue
            if (tag in notCountries) continue
            normalize(tag)?.let { return it }
        }
        return null
    }

    /** "Estados Unidos" / "United States" in the device language; a region's own name. */
    fun displayName(code: String): String = when (code) {
        REGION_ARABIC -> "Arabic"
        REGION_CARIBBEAN -> if (Locale.getDefault().language == "es") "Caribe" else "Caribbean"
        REGION_AFRICA -> if (Locale.getDefault().language == "es") "África" else "Africa"
        REGION_EX_YU -> "Ex-Yu"
        else -> Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
    }

    // Region keys: longer than any ISO code, so never taken for one.
    const val REGION_ARABIC = "ARABIC"
    const val REGION_CARIBBEAN = "CARIBBEAN"
    const val REGION_AFRICA = "AFRICA"
    const val REGION_EX_YU = "EXYU"
}
