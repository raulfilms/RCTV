package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import java.util.Locale

/**
 * Heuristically detects which of an installed addon's catalogs serve live sports content, the
 * same way ARVIO's `SportsAddonCapabilities.isSportsCatalog` does: a Stremio addon manifest has
 * no dedicated "this is sports" flag, so this matches the catalog's type/id/name/genres against a
 * fixed set of sport terms instead. False positives are relatively low-cost (an unrelated catalog
 * just shows up in the sports row and its events won't play interestingly), so this favors a
 * broad match over a narrow, easily-missed one.
 */
object SportsAddonCatalogClassifier {
    private val sportTerms = setOf(
        "sport", "sports", "football", "soccer", "basketball", "nba", "nfl", "nhl", "mlb",
        "tennis", "motorsport", "formula", "f1", "racing", "rugby", "hockey", "baseball",
        "boxing", "ufc", "mma", "cricket", "golf", "volleyball"
    )

    fun isSportsCatalog(catalog: CatalogDescriptor): Boolean {
        val text = normalizedText(catalog.apiType, catalog.id, catalog.name)
        return containsAny(text, sportTerms)
    }

    /** Every (addon, catalog) pair across [addons] whose catalog looks like live sports. */
    fun findSportsCatalogs(addons: List<Addon>): List<SportsCatalogRef> =
        addons.flatMap { addon -> addon.catalogs.filter(::isSportsCatalog).map { catalog -> SportsCatalogRef(addon, catalog) } }

    private fun normalizedText(vararg parts: String?): String =
        parts.filterNotNull().joinToString(" ").lowercase(Locale.US)

    private fun containsAny(text: String, terms: Set<String>): Boolean =
        terms.any { term -> text.contains(term) }
}

/** One sports-looking catalog belonging to one installed addon. */
data class SportsCatalogRef(val addon: Addon, val catalog: CatalogDescriptor)
