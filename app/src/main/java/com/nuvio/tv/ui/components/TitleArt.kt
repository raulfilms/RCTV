package com.nuvio.tv.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.tv.domain.repository.TitleArtResolver

/**
 * When a screen provides this, its horizontal title cards ([AppleLandscapeCard]) look up their title
 * art as they appear: TMDB's backdrop with the title on it, or the title logo over the plain backdrop.
 * Screens without it show the cards as they are.
 */
val LocalTitleArtResolver = staticCompositionLocalOf<TitleArtResolver?> { null }
