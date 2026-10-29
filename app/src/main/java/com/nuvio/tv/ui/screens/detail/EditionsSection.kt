package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.smartscraper.AutoStreamEdition
import com.nuvio.tv.core.smartscraper.AutoStreamEngine
import com.nuvio.tv.core.smartscraper.ReleaseEdition
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme

private data class EditionListItem(
    val edition: ReleaseEdition,
    val preview: MetaPreview
)

/**
 * RCTV: a movie's other versions (Extended Edition, Director's Cut), first in its Extras, as the
 * trailers are: the movie's picture, the version's name and what its best file is ("4K · 7.4 GB ·
 * 5.1"), never where it comes from. Choosing one plays that version through Auto Stream.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditionsSection(
    editions: List<AutoStreamEdition>,
    image: String?,
    posterCardCornerRadius: Dp = NuvioTheme.spacing.md,
    upFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    onEditionClick: (ReleaseEdition) -> Unit
) {
    if (editions.isEmpty()) return

    val extendedLabel = stringResource(R.string.detail_edition_extended)
    val directorsCutLabel = stringResource(R.string.detail_edition_directors_cut)
    val items = remember(editions, image, extendedLabel, directorsCutLabel) {
        editions.mapNotNull { option ->
            val name = when (option.edition) {
                ReleaseEdition.EXTENDED -> extendedLabel
                ReleaseEdition.DIRECTORS_CUT -> directorsCutLabel
                ReleaseEdition.NONE -> return@mapNotNull null
            }
            EditionListItem(
                edition = option.edition,
                preview = MetaPreview(
                    id = "edition:${option.edition.key}",
                    type = ContentType.MOVIE,
                    name = name,
                    poster = image,
                    posterShape = PosterShape.LANDSCAPE,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = AutoStreamEngine.summary(option.best),
                    imdbRating = null,
                    genres = emptyList()
                )
            )
        }
    }
    if (items.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val landscapeStyle = remember(posterCardCornerRadius) {
        PosterCardStyle(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = posterCardCornerRadius,
            focusedBorderWidth = NuvioTheme.spacing.xxs,
            focusedScale = 1.02f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer { firstItemFocusRequester }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            itemsIndexed(items = items, key = { _, item -> item.preview.id }) { index, item ->
                Column {
                    GridContentCard(
                        item = item.preview,
                        onClick = { onEditionClick(item.edition) },
                        posterCardStyle = landscapeStyle,
                        showLabel = true,
                        imageCrossfade = true,
                        focusRequester = if (index == 0) firstItemFocusRequester else null,
                        upFocusRequester = upFocusRequester,
                        depthSurface = CardDepthSurface.TRAILERS
                    )
                    val subtitle = item.preview.releaseInfo
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(landscapeStyle.width)
                                .padding(start = NuvioTheme.spacing.xxs, end = NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.xxs)
                        )
                    }
                }
            }
        }
    }
}
