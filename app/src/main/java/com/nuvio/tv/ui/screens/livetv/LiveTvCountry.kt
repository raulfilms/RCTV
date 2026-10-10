package com.nuvio.tv.ui.screens.livetv

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

    /** A leading 2–3 letter code followed by a separator, e.g. "US |", "|UK|", "[MX]", "USA -". */
    private val prefixTag = Regex("""^[\s|\[(]*([A-Z]{2,3})[\s\])]*[|:\-–]""")
    private val bracketTag = Regex("""^[\s]*[|\[(]([A-Z]{2,3})[|\])]""")

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

    /** The channel's country: its own, or one guessed from its group or name. */
    fun of(channel: LiveTvChannel): String? {
        normalize(channel.country)?.let { return it }
        for (text in listOfNotNull(channel.groupTitle, channel.name)) {
            val match = bracketTag.find(text) ?: prefixTag.find(text) ?: continue
            val tag = match.groupValues[1]
            if (tag in notCountries) continue
            normalize(tag)?.let { return it }
        }
        return null
    }

    /** "Estados Unidos" / "United States" in the device language. */
    fun displayName(code: String): String =
        Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
}
