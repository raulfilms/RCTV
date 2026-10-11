package com.nuvio.tv.domain.model

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable

/** A tile in the home screen's "Streaming Services" row. */
@Immutable
data class StreamingService(
    val key: String,
    val name: String,
    /** Logo bundled with the app. */
    @DrawableRes val logoRes: Int,
    /** The logo was made for dark backgrounds (white lettering). Every tile is dark now, so unused. */
    val darkTile: Boolean = false,
    /** [logoRes] is a full 16:9 picture that fills the whole tile (no gray tile, no padding around it). */
    val fullBleed: Boolean = false,
    /**
     * Key of the app's own section this tile opens instead of the generic service page
     * ("main" = Disney+, "paramount" = Paramount+, "discovery" = discovery+); null for the generic page.
     */
    val appHub: String? = null,
    /** Where the tile leads; filled in once TMDB has been asked, null until then. */
    val target: StreamingServiceTarget? = null
)

/** A TMDB page to open: a watch provider (what the subscription includes) or a studio. */
@Immutable
data class StreamingServiceTarget(
    /** "provider" or "company", the TMDB browse page kinds. */
    val entityKind: String,
    val entityId: Int
)
