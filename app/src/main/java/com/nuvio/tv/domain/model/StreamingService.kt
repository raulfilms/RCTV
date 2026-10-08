package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/** A streaming service shown on the home screen (backed by a TMDB watch provider). */
@Immutable
data class StreamingService(
    /** TMDB watch provider id. */
    val id: Int,
    val name: String,
    val logoUrl: String?,
    /** Disney+ opens the app's own Disney+ section instead of the generic service page. */
    val opensDisneyHub: Boolean = false
)
