package com.nuvio.tv.ui.util

import com.nuvio.tv.domain.model.MetaPreview
import java.util.Locale

private val META_PREVIEW_YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

/** First 4-digit year found in this item's release info, e.g. "2019" from "2019-2021". */
fun MetaPreview.extractReleaseYear(): String? =
    releaseInfo?.let { META_PREVIEW_YEAR_REGEX.find(it)?.value }

/** Normalized (lowercase, trimmed) content type key, e.g. "movie", "series", "tv". */
fun MetaPreview.normalizedApiType(): String = apiType.trim().lowercase(Locale.ROOT)

/** A single choice in a type/genre/year filter dropdown, with how many currently-loaded items match it. */
data class MetaFilterOption(
    val key: String,
    val label: String,
    val count: Int
)

data class MetaGenreYearFilterResult(
    val typeOptions: List<MetaFilterOption>,
    val genreOptions: List<MetaFilterOption>,
    val yearOptions: List<MetaFilterOption>,
    val filteredItems: List<MetaPreview>
)

/**
 * Builds type/genre/year facet options (each counted against the OTHER active filters, so picking
 * a genre narrows the type and year lists too, and so on) plus the resulting filtered items, from
 * whatever items are currently loaded on screen. Mirrors the faceted filter behavior already used
 * by the Library screen (see LibraryViewModel.withVisibleItems).
 */
fun buildMetaGenreYearFilter(
    items: List<MetaPreview>,
    selectedType: String? = null,
    selectedGenre: String?,
    selectedYear: String?
): MetaGenreYearFilterResult {
    fun matchesType(item: MetaPreview) = selectedType == null || item.normalizedApiType() == selectedType
    fun matchesGenre(item: MetaPreview) = selectedGenre == null ||
        item.genres.any { it.equals(selectedGenre, ignoreCase = true) }
    fun matchesYear(item: MetaPreview) = selectedYear == null || item.extractReleaseYear() == selectedYear

    val typeCounts = LinkedHashMap<String, Int>()
    items.asSequence()
        .filter { matchesGenre(it) && matchesYear(it) }
        .forEach { item ->
            val type = item.normalizedApiType()
            if (type.isNotBlank()) {
                typeCounts[type] = (typeCounts[type] ?: 0) + 1
            }
        }
    val typeOptions = typeCounts.entries
        .sortedBy { it.key }
        .map { (type, count) -> MetaFilterOption(key = type, label = type, count = count) }

    val genreCounts = LinkedHashMap<String, Int>()
    items.asSequence()
        .filter { matchesType(it) && matchesYear(it) }
        .forEach { item ->
            item.genres.forEach { genre ->
                val normalized = genre.trim()
                if (normalized.isNotBlank()) {
                    genreCounts[normalized] = (genreCounts[normalized] ?: 0) + 1
                }
            }
        }
    val genreOptions = genreCounts.entries
        .sortedBy { it.key.lowercase(Locale.ROOT) }
        .map { (genre, count) -> MetaFilterOption(key = genre, label = genre, count = count) }

    val yearCounts = LinkedHashMap<String, Int>()
    items.asSequence()
        .filter { matchesType(it) && matchesGenre(it) }
        .forEach { item ->
            val year = item.extractReleaseYear() ?: return@forEach
            yearCounts[year] = (yearCounts[year] ?: 0) + 1
        }
    val yearOptions = yearCounts.entries
        .sortedByDescending { it.key }
        .map { (year, count) -> MetaFilterOption(key = year, label = year, count = count) }

    val filteredItems = items.filter { matchesType(it) && matchesGenre(it) && matchesYear(it) }

    return MetaGenreYearFilterResult(
        typeOptions = typeOptions,
        genreOptions = genreOptions,
        yearOptions = yearOptions,
        filteredItems = filteredItems
    )
}
