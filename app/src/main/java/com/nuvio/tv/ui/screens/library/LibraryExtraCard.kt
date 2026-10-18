package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvFocusGlow
import com.nuvio.tv.ui.components.AppleTvFocusScale
import com.nuvio.tv.ui.screens.sports.isLiveAt
import com.nuvio.tv.ui.screens.sports.startTimeTba
import com.nuvio.tv.ui.screens.sports.isGolf
import com.nuvio.tv.ui.screens.sports.sportsStartTimeLabel

/**
 * A team, league, game or custom channel in the Library: its logo (a game: both teams' badges)
 * on the Guide's logo-tile color, its name under it. Logos are made for dark screens, so they
 * aren't cropped like posters.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LibraryExtraCard(
    entry: LibraryEntry,
    logos: List<String>,
    /** A saved game: when it starts shows under its name (LIVE when it's on). */
    event: SportsEvent? = null,
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Card(
        onClick = onClick,
        onLongClick = onLongPress,
        modifier = Modifier
            .size(width = width, height = height)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = AppleTvColors.Contrast,
            focusedContainerColor = AppleTvColors.ContrastHigh
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    logos.size >= 2 -> Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExtraLogo(url = logos[0], name = entry.name, modifier = Modifier.weight(1f).fillMaxSize())
                        Text(
                            text = "VS",
                            color = AppleTvColors.LabelOnContrast,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        ExtraLogo(url = logos[1], name = entry.name, modifier = Modifier.weight(1f).fillMaxSize())
                    }
                    logos.size == 1 -> ExtraLogo(url = logos[0], name = entry.name, modifier = Modifier.fillMaxSize())
                    else -> Text(
                        text = entry.name,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (logos.isNotEmpty()) {
                Text(
                    text = entry.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            // A game: its day and start time ("Tomorrow, 7:30 PM"), so it's clear when it's on.
            val start = event?.startTimeMs
            if (event != null && start != null) {
                val live = event.isLiveAt(System.currentTimeMillis())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (live) {
                        Text(
                            text = stringResource(R.string.sports_live).uppercase(),
                            color = LiveRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(text = "  •  ", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                    }
                    Text(
                        text = when {
                            // A golf tournament: its days.
                            event.isGolf() -> com.nuvio.tv.ui.screens.sports.golfDatesLabel(event)
                            event.startTimeTba() -> com.nuvio.tv.ui.screens.sports.tbaStartLabel(start)
                            else -> sportsStartTimeLabel(start)
                        },
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private val LiveRed = Color(0xFFFF453A)

@Composable
private fun ExtraLogo(url: String, name: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!failed) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
