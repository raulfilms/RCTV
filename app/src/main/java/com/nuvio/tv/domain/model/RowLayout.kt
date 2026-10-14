package com.nuvio.tv.domain.model

import org.json.JSONObject

/** How a row shows its titles: horizontal 16:9 cards (the default) or vertical 2:3 posters. */
enum class RowOrientation {
    HORIZONTAL,
    VERTICAL;

    companion object {
        fun parse(value: Any?): RowOrientation? = when ((value as? String)?.trim()?.lowercase()) {
            "vertical", "v", "poster", "posters", "portrait" -> VERTICAL
            "horizontal", "h", "landscape", "wide", "16:9" -> HORIZONTAL
            else -> null
        }
    }
}

/** One page's settings: an orientation for the whole page and/or for single rows. */
data class PageRowLayout(
    val default: RowOrientation? = null,
    val rows: Map<String, RowOrientation> = emptyMap()
)

/**
 * Which rows are vertical and which are horizontal, set from outside the app (the layout file the
 * website edits) so a row can change without a new build.
 *
 * A row is found by its id or by the name shown on the TV, on its page first and then in the
 * rows that apply to every page; then the page's own default; then the app default (horizontal).
 */
data class RowLayoutConfig(
    val default: RowOrientation = RowOrientation.HORIZONTAL,
    val rows: Map<String, RowOrientation> = emptyMap(),
    val pages: Map<String, PageRowLayout> = emptyMap()
) {
    fun orientation(page: String, rowKeys: List<String?>): RowOrientation {
        val pageKey = normalizeRowLayoutKey(page).orEmpty()
        val keys = rowKeys.mapNotNull { normalizeRowLayoutKey(it) }.filter { it.isNotEmpty() }.distinct()
        val pageLayout = pages[pageKey]
        if (pageLayout != null) {
            keys.firstNotNullOfOrNull { pageLayout.rows[it] }?.let { return it }
        }
        if (pageKey.isNotEmpty()) {
            keys.firstNotNullOfOrNull { rows["$pageKey/$it"] }?.let { return it }
        }
        keys.firstNotNullOfOrNull { rows[it] }?.let { return it }
        return pageLayout?.default ?: default
    }

    fun isVertical(page: String, vararg rowKeys: String?): Boolean =
        orientation(page, rowKeys.toList()) == RowOrientation.VERTICAL

    /** A whole page's grid (See All, Library...): the page's default, or the app default. */
    fun isPageVertical(page: String): Boolean =
        (pages[normalizeRowLayoutKey(page).orEmpty()]?.default ?: default) == RowOrientation.VERTICAL

    companion object {
        val DEFAULT = RowLayoutConfig()

        /**
         * Reads the layout file. Keys starting with "_" are notes and are skipped; unknown values
         * are ignored, so a typo never breaks the app (the row just stays horizontal).
         */
        fun parse(json: String): RowLayoutConfig? = runCatching {
            val root = JSONObject(json)
            RowLayoutConfig(
                default = RowOrientation.parse(root.opt("default")) ?: RowOrientation.HORIZONTAL,
                rows = root.optJSONObject("rows").toOrientationMap(),
                pages = buildMap {
                    val pagesJson = root.optJSONObject("pages") ?: return@buildMap
                    pagesJson.keys().forEach { rawKey ->
                        if (rawKey.startsWith("_")) return@forEach
                        val key = normalizeRowLayoutKey(rawKey) ?: return@forEach
                        when (val value = pagesJson.opt(rawKey)) {
                            // "paramount": "vertical" — the whole page.
                            is String -> RowOrientation.parse(value)?.let { put(key, PageRowLayout(default = it)) }
                            is JSONObject -> put(
                                key,
                                PageRowLayout(
                                    default = RowOrientation.parse(value.opt("default")),
                                    rows = value.optJSONObject("rows").toOrientationMap()
                                )
                            )
                        }
                    }
                }
            )
        }.getOrNull()

        private fun JSONObject?.toOrientationMap(): Map<String, RowOrientation> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, RowOrientation>()
            keys().forEach { rawKey ->
                if (rawKey.startsWith("_")) return@forEach
                val key = normalizeRowLayoutKey(rawKey) ?: return@forEach
                RowOrientation.parse(opt(rawKey))?.let { out[key] = it }
            }
            return out
        }
    }
}

/** Ids and names match without caring about capitals or extra spaces. */
fun normalizeRowLayoutKey(value: String?): String? =
    value?.trim()?.lowercase()?.replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")
