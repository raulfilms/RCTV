@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.disneyplus

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.disneyplus.HubCard
import com.nuvio.tv.data.disneyplus.HubCardArt
import com.nuvio.tv.ui.components.AppleLandscapeCardHeight
import com.nuvio.tv.ui.components.AppleLandscapeCardWidth
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType
import com.nuvio.tv.ui.components.GenreThumbnails
import com.nuvio.tv.ui.screens.home.HomeCard
import com.nuvio.tv.ui.screens.home.HomeTileRow
import com.nuvio.tv.ui.theme.NuvioTheme

/** Deep card colors (the white name reads on them), picked by the card's list. */
private val TileColors = listOf(
    Color(0xFF8E2DE2), Color(0xFFC0392B), Color(0xFF16A085), Color(0xFFD35400), Color(0xFF2C3E91),
    Color(0xFF7D3C98), Color(0xFF117A65), Color(0xFFB03A2E), Color(0xFF1F618D), Color(0xFF6E2C00),
    Color(0xFF0E6655), Color(0xFF884EA0), Color(0xFF943126), Color(0xFF1A5276)
)

/** Filmmakers' cards: a portrait photo. */
private val PersonCardWidth: Dp = AppleLandscapeCardHeight * 0.8f
private val PersonCardHeight: Dp = AppleLandscapeCardHeight * 1.15f

/**
 * A row of cards on a service's page (genres, collections, filmmakers, channels, "Discover More"):
 * each opens the whole list it stands for, with filters, on the page's See All.
 */
@Composable
internal fun HubCardsRow(
    title: String,
    cards: List<HubCard>,
    /** Each card's picture by the list it opens, once found. */
    art: Map<String, String>,
    onClick: (HubCard) -> Unit,
    rowFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    HomeTileRow(
        title = title,
        count = cards.size,
        keyOf = { cards[it].rowId },
        rowFocusRequester = rowFocusRequester,
        initialFocusIndex = -1,
        onItemFocused = {},
        modifier = modifier,
        contentStart = NuvioTheme.spacing.xxxl,
        titleBottomGap = 18.dp,
        boldTitle = true
    ) { index, itemModifier ->
        val card = cards[index]
        HubCardView(
            card = card,
            image = art[card.rowId],
            logo = art[hubCardLogoKey(card.rowId)],
            onClick = { onClick(card) },
            modifier = itemModifier
        )
    }
}

/** Where a card's title artwork (a collection's film logo) is kept in its row's or page's art map. */
internal fun hubCardLogoKey(rowId: String): String = "$rowId#logo"

/** One card of a cards row or of a page of cards: its picture, thumbnail or color, with its name. */
@Composable
private fun HubCardView(card: HubCard, image: String?, logo: String?, onClick: () -> Unit, modifier: Modifier) {
    val label = card.label ?: stringResource(card.labelRes)
    when (card.art) {
        HubCardArt.Tile -> {
            // A genre with a thumbnail (its name is on it); any other card is colored, with its name.
            val thumbnail = card.genre?.let(GenreThumbnails::forKey)
            if (thumbnail != null) {
                ThumbnailCard(label, thumbnail, onClick = onClick, modifier = modifier)
            } else {
                TileCard(label, card.rowId, onClick = onClick, modifier = modifier)
            }
        }
        is HubCardArt.Collection -> PictureCard(
            label = label,
            caption = stringResource(R.string.disney_collection_label),
            image = image,
            logo = logo,
            onClick = onClick,
            modifier = modifier
        )
        is HubCardArt.Person -> PersonCard(label, image, onClick = onClick, modifier = modifier)
        is HubCardArt.Provider -> LogoCard(label, image, onClick = onClick, modifier = modifier)
    }
}

/**
 * A page of cards ("All Collections"): every collection's card, in a grid; choosing one opens its
 * movies. Focus starts on [focusCardId] (the card last opened) or the first card.
 */
@Composable
internal fun HubCardsPage(
    title: String,
    @DrawableRes logoRes: Int?,
    logoDescription: String,
    cards: List<HubCard>,
    art: Map<String, String>,
    focusCardId: String?,
    onClick: (HubCard) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val focusIndex = remember(cards, focusCardId) {
        cards.indexOfFirst { it.rowId == focusCardId }.coerceAtLeast(0)
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusIndex) {
        runCatching { gridState.scrollToItem(focusIndex) }
        repeat(12) {
            withFrameNanos { }
            if (runCatching { focusRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppleTvSpacing.SafeX, end = AppleTvSpacing.SafeX, top = AppleTvSpacing.SafeY),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (logoRes != null) {
                Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = logoDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(30.dp)
                        .widthIn(max = 120.dp)
                )
                Spacer(modifier = Modifier.width(AppleTvSpacing.Space3 + 2.dp))
            }
            Text(
                text = title,
                style = AppleTvType.Title2,
                color = AppleTvColors.Label,
                maxLines = 1
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            contentPadding = PaddingValues(
                start = AppleTvSpacing.SafeX,
                end = AppleTvSpacing.SafeX,
                top = 20.dp,
                bottom = 48.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(cards, key = { _, card -> card.rowId }) { index, card ->
                HubCardView(
                    card = card,
                    image = art[card.rowId],
                    logo = art[hubCardLogoKey(card.rowId)],
                    onClick = { onClick(card) },
                    modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier
                )
            }
        }
    }
}

/** A genre's thumbnail, its name on the picture. */
@Composable
private fun ThumbnailCard(label: String, @DrawableRes thumbnail: Int, onClick: () -> Unit, modifier: Modifier) {
    HomeCard(
        onClick = onClick,
        width = AppleLandscapeCardWidth,
        height = AppleLandscapeCardHeight,
        containerColor = AppleTvColors.Surface2,
        modifier = modifier
    ) {
        Image(
            painter = painterResource(thumbnail),
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TileCard(label: String, key: String, onClick: () -> Unit, modifier: Modifier) {
    val color = TileColors[Math.floorMod(key.hashCode(), TileColors.size)]
    HomeCard(
        onClick = onClick,
        width = AppleLandscapeCardWidth,
        height = AppleLandscapeCardHeight,
        containerColor = color,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.25f))))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 18.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * A TMDB collection: its backdrop with its name at the bottom: the title as artwork (its film's
 * logo, like the posters' own lettering) when TMDB has it, else written.
 */
@Composable
private fun PictureCard(label: String, caption: String, image: String?, logo: String?, onClick: () -> Unit, modifier: Modifier) {
    var logoFailed by remember(logo) { mutableStateOf(false) }
    val showLogo = !logo.isNullOrBlank() && !logoFailed
    HomeCard(
        onClick = onClick,
        width = AppleLandscapeCardWidth,
        height = AppleLandscapeCardHeight,
        containerColor = AppleTvColors.Surface2,
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            ) {
                if (showLogo) {
                    AsyncImage(
                        model = logo,
                        contentDescription = label,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(44.dp)
                            .padding(bottom = 3.dp)
                    )
                } else {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = caption,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/** A filmmaker: their photo, their name at the bottom. */
@Composable
private fun PersonCard(label: String, image: String?, onClick: () -> Unit, modifier: Modifier) {
    HomeCard(
        onClick = onClick,
        width = PersonCardWidth,
        height = PersonCardHeight,
        containerColor = AppleTvColors.Surface2,
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp)
            )
        }
    }
}

/** A channel: its logo on white (its name until the logo is in). */
@Composable
private fun LogoCard(label: String, image: String?, onClick: () -> Unit, modifier: Modifier) {
    var failed by remember(image) { mutableStateOf(false) }
    HomeCard(
        onClick = onClick,
        width = AppleLandscapeCardWidth,
        height = AppleLandscapeCardHeight,
        containerColor = Color.White,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            if (image != null && !failed) {
                AsyncImage(
                    model = image,
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = label,
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
