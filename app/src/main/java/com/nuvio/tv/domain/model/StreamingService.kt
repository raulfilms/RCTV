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
    /** The logo is made for dark backgrounds (white lettering), so the tile is drawn dark. */
    val darkTile: Boolean = false,
    /** Disney+ opens the app's own Disney+ section instead of the generic service page. */
    val opensDisneyHub: Boolean = false,
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
