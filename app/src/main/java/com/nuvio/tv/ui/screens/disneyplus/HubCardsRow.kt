@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.disneyplus

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
        val label = card.label ?: stringResource(card.labelRes)
        val image = art[card.rowId]
        when (card.art) {
            HubCardArt.Tile -> {
                // A genre with a thumbnail (its name is on it); any other card is colored, with its name.
                val thumbnail = card.genre?.let(GenreThumbnails::forKey)
                if (thumbnail != null) {
                    ThumbnailCard(label, thumbnail, onClick = { onClick(card) }, modifier = itemModifier)
                } else {
                    TileCard(label, card.rowId, onClick = { onClick(card) }, modifier = itemModifier)
                }
            }
            is HubCardArt.Collection -> PictureCard(
                label = label,
                caption = stringResource(R.string.disney_collection_label),
                image = image,
                onClick = { onClick(card) },
                modifier = itemModifier
            )
            is HubCardArt.Person -> PersonCard(label, image, onClick = { onClick(card) }, modifier = itemModifier)
            is HubCardArt.Provider -> LogoCard(label, image, onClick = { onClick(card) }, modifier = itemModifier)
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

/** A backdrop with the name at the bottom (a TMDB collection). */
@Composable
private fun PictureCard(label: String, caption: String, image: String?, onClick: () -> Unit, modifier: Modifier) {
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
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
