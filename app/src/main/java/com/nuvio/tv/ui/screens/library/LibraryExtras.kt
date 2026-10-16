package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.ui.screens.sports.SPORTS_EVENT_LIBRARY_TYPE
import java.util.Locale

/**
 * The Library's types beyond movies and shows. They're kept by the Sports page and the Guide (not
 * by Trakt or Simkl), so they show whatever the Library's source is.
 */
const val LIBRARY_TYPE_SPORTS_TEAM = "sports_team"
const val LIBRARY_TYPE_SPORTS_LEAGUE = "sports_league"
const val LIBRARY_TYPE_EVENT = SPORTS_EVENT_LIBRARY_TYPE
/** A channel from the Guide's "Custom" list. */
const val LIBRARY_TYPE_CUSTOM_CHANNEL = "live_channel"

internal val LIBRARY_EXTRA_TYPES = setOf(
    LIBRARY_TYPE_SPORTS_TEAM,
    LIBRARY_TYPE_SPORTS_LEAGUE,
    LIBRARY_TYPE_EVENT,
    LIBRARY_TYPE_CUSTOM_CHANNEL
)

/** The Library's Type menu, always in this order: Movies, Shows, then the extra types. */
internal const val LIBRARY_TYPE_MOVIE = "movie"
internal const val LIBRARY_TYPE_SHOW = "series"
internal val LIBRARY_EXTRA_TYPE_ORDER = listOf(
    LIBRARY_TYPE_SPORTS_TEAM,
    LIBRARY_TYPE_SPORTS_LEAGUE,
    LIBRARY_TYPE_EVENT,
    LIBRARY_TYPE_CUSTOM_CHANNEL
)

/** A type key as the Type menu knows it: "tv" and "show" are Shows. */
internal fun normalizeLibraryTypeKey(raw: String): String {
    val key = raw.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
    return when (key) {
        "tv", "show", "shows" -> LIBRARY_TYPE_SHOW
        else -> key
    }
}

/** The Type menu's key for a Library item ("anime" from a tracking provider stays its own type). */
internal fun LibraryEntry.libraryTypeKey(): String = normalizeLibraryTypeKey(mediaCategory ?: type)

internal fun LibraryEntry.isLibraryExtra(): Boolean = type.lowercase(Locale.ROOT) in LIBRARY_EXTRA_TYPES
