package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.nuvio.tv.data.disneyplus.DisneyPlusHub
import com.nuvio.tv.domain.model.RowLayoutConfig

/** The row layout from the website (vertical or horizontal rows); provided app-wide by MainActivity. */
val LocalRowLayout = compositionLocalOf { RowLayoutConfig.DEFAULT }

/** The page a row is on, as the layout file names it ("home", "paramount", "channel"...). */
val LocalRowLayoutPage = staticCompositionLocalOf { "" }

/** Page names used in the layout file. Streaming hub pages use their own key (see [rowLayoutPage]). */
object RowLayoutPages {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DISCOVER = "discover"
    const val CHANNEL = "channel"
    const val BROWSE = "browse"
    const val COLLECTION = "collection"
    const val SEE_ALL = "see_all"
    const val LIBRARY = "library"
    const val CAST = "cast"
    const val DISNEY_PLUS = "disneyplus"
}

/** The layout file's name for a streaming page: "disneyplus", "pixar", "paramount", "peacock_nbc"... */
fun DisneyPlusHub.rowLayoutPage(): String =
    if (this == DisneyPlusHub.MAIN) RowLayoutPages.DISNEY_PLUS else key

/** True when the website set this row (on the current page) to vertical posters. */
@Composable
fun isRowVertical(vararg rowKeys: String?): Boolean =
    LocalRowLayout.current.isVertical(LocalRowLayoutPage.current, *rowKeys)

/** True when the website set a whole page (a grid such as See All or Library) to vertical posters. */
@Composable
fun isPageVertical(page: String): Boolean = LocalRowLayout.current.isPageVertical(page)

/** Apple TV 2:3 poster for rows the website sets to vertical. */
val AppleVerticalPosterStyle: PosterCardStyle
    get() = PosterCardDefaults.Style.copy(
        width = AppleTvPosterWidth,
        height = AppleTvPosterHeight,
        cornerRadius = AppleTvRadius.Poster,
        focusedBorderWidth = 0.dp,
        focusedScale = AppleTvFocusScale
    )
