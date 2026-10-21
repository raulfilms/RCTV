package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.tileSurfaceBorder
import com.nuvio.tv.ui.screens.detail.AiringCard
import com.nuvio.tv.ui.screens.detail.ShowAiring
import com.nuvio.tv.ui.screens.livetv.GuideLogoWhitening
import com.nuvio.tv.ui.screens.sports.SportsGameCard
import com.nuvio.tv.ui.screens.sports.SportsLogoTile
import com.nuvio.tv.ui.screens.sports.sportsLeagueColor
import com.nuvio.tv.ui.screens.sports.sportsTeamColor
import com.nuvio.tv.ui.screens.sports.uniqueKeys
import com.nuvio.tv.ui.theme.NuvioTheme

/** RCTV: what Search's channel, guide and sports rows open. */
internal class SearchExtraActions(
    /** A channel: its page. */
    val onChannel: (LiveTvChannel) -> Unit,
    /** A program of the guide: on now plays its channel, later opens the channel's page. */
    val onProgram: (SearchProgramResult) -> Unit,
    val onTeam: (SportsTeam) -> Unit,
    val onLeague: (SportsLeague) -> Unit,
    /** A game: on now plays it (its channel), any other opens its page. */
    val onGame: (SportsEvent) -> Unit
)

/** The row's key in Search's focus bookkeeping ("extra_TEAMS"). */
internal fun SearchExtraRowKind.rowKey(): String = "extra_$name"

/**
 * One of Search's rows besides the movies and series: Channels, Teams, Leagues, Games or On the
 * Guide, titled like the other rows. [focusIndex] (0 or more) takes the focus to that card (moving
 * down from the search field, or back from the page a card opened).
 */
@Composable
internal fun SearchExtraRow(
    kind: SearchExtraRowKind,
    extras: SearchExtraResults,
    actions: SearchExtraActions,
    focusIndex: Int,
    upFocusRequester: FocusRequester?,
    onItemFocused: (index: Int) -> Unit,
    onItemOpened: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    when (kind) {
        SearchExtraRowKind.CHANNELS -> ExtraRow(
            title = stringResource(R.string.search_row_channels),
            count = extras.channels.size,
            keyOf = { "ch_${extras.channels[it].channel.id}" },
            focusIndex = focusIndex,
            upFocusRequester = upFocusRequester,
            onItemFocused = onItemFocused,
            modifier = modifier
        ) { index, itemModifier ->
            val result = extras.channels[index]
            SearchChannelTile(
                result = result,
                onClick = {
                    onItemOpened(index)
                    actions.onChannel(result.channel)
                },
                modifier = itemModifier
            )
        }

        SearchExtraRowKind.PROGRAMS -> ExtraRow(
            title = stringResource(R.string.search_row_programs),
            count = extras.programs.size,
            keyOf = { "pg_${extras.programs[it].key}" },
            focusIndex = focusIndex,
            upFocusRequester = upFocusRequester,
            onItemFocused = onItemFocused,
            modifier = modifier
        ) { index, itemModifier ->
            val result = extras.programs[index]
            AiringCard(
                // Its channel under it by the name shown everywhere else ("ESPN", not "US: ESPN FHD").
                airing = ShowAiring(result.channel.copy(name = result.channelName), result.program),
                isOnNow = result.program.isAiringAt(now),
                now = now,
                fallbackImageUrl = null,
                cardText = result.program.title,
                onClick = {
                    onItemOpened(index)
                    actions.onProgram(result)
                },
                modifier = itemModifier
            )
        }

        SearchExtraRowKind.TEAMS -> ExtraRow(
            title = stringResource(R.string.search_row_teams),
            count = extras.teams.size,
            keyOf = { "tm_${extras.teams[it].team.leagueId}_${extras.teams[it].team.id}" },
            focusIndex = focusIndex,
            upFocusRequester = upFocusRequester,
            onItemFocused = onItemFocused,
            modifier = modifier
        ) { index, itemModifier ->
            val result = extras.teams[index]
            val team = result.team
            SportsLogoTile(
                name = team.name,
                logoUrl = team.badgeUrl,
                color = sportsTeamColor(team.color, team.id),
                isFavorite = false,
                onClick = {
                    onItemOpened(index)
                    actions.onTeam(team)
                },
                onLongPress = null,
                modifier = itemModifier,
                prominentName = true,
                subtitle = result.leagueLabel
            )
        }

        SearchExtraRowKind.LEAGUES -> ExtraRow(
            title = stringResource(R.string.search_row_leagues),
            count = extras.leagues.size,
            keyOf = { "lg_${extras.leagues[it].league.id}" },
            focusIndex = focusIndex,
            upFocusRequester = upFocusRequester,
            onItemFocused = onItemFocused,
            modifier = modifier
        ) { index, itemModifier ->
            val league = extras.leagues[index].league
            SportsLogoTile(
                name = league.name,
                logoUrl = league.badgeUrl,
                color = sportsLeagueColor(league.id, league.name),
                isFavorite = false,
                onClick = {
                    onItemOpened(index)
                    actions.onLeague(league)
                },
                onLongPress = null,
                modifier = itemModifier,
                prominentName = true,
                subtitle = league.sportName
            )
        }

        SearchExtraRowKind.GAMES -> ExtraRow(
            title = stringResource(R.string.search_row_games),
            count = extras.games.size,
            keyOf = { "gm_${extras.games[it].id}" },
            focusIndex = focusIndex,
            upFocusRequester = upFocusRequester,
            onItemFocused = onItemFocused,
            modifier = modifier
        ) { index, itemModifier ->
            val event = extras.games[index]
            SportsGameCard(
                event = event,
                onClick = {
                    onItemOpened(index)
                    actions.onGame(event)
                },
                onLongPress = null,
                modifier = itemModifier
            )
        }
    }
}

/** A row of cards under a title like Search's other rows; Up from any card goes to [upFocusRequester] when set. */
@Composable
private fun ExtraRow(
    title: String,
    count: Int,
    keyOf: (Int) -> String,
    focusIndex: Int,
    upFocusRequester: FocusRequester?,
    onItemFocused: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit
) {
    if (count == 0) return
    val requesters = remember(count) { List(count) { FocusRequester() } }
    var lastFocused by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val currentOnItemFocused by rememberUpdatedState(onItemFocused)

    LaunchedEffect(focusIndex, count) {
        val target = focusIndex
        if (target !in 0 until count) return@LaunchedEffect
        runCatching { listState.scrollToItem(target) }
        repeat(8) {
            withFrameNanos { }
            if (runCatching { requesters[target].requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            modifier = Modifier.padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                bottom = NuvioTheme.spacing.md
            )
        )
        val keys = uniqueKeys(count, keyOf)
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer { requesters.getOrNull(lastFocused) ?: FocusRequester.Default }
                .focusGroup(),
            contentPadding = PaddingValues(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            itemsIndexed(items = List(count) { it }, key = { _, index -> keys[index] }) { index, _ ->
                tile(
                    index,
                    Modifier
                        .focusRequester(requesters[index])
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                lastFocused = index
                                currentOnItemFocused(index)
                            }
                        }
                        .focusProperties { if (upFocusRequester != null) up = upFocusRequester }
                )
            }
        }
    }
}

private val ChannelTileWidth = 220.dp
private val ChannelTileHeight = 124.dp

/** A channel: its logo on a tile (its name when it has none), its name and what's on now under it. */
@Composable
private fun SearchChannelTile(
    result: SearchChannelResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val url = result.channel.logoUrl?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.width(ChannelTileWidth)) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(ChannelTileHeight),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = AppleTvColors.Contrast,
                focusedContainerColor = AppleTvColors.ContrastFocus
            ),
            // RCTV: the white outline every tile has while focused (see TileFocus.kt).
            border = tileSurfaceBorder(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (url != null) {
                    val context = LocalContext.current
                    // Dark one-color logos made white, as in the guide.
                    val request = remember(url) {
                        ImageRequest.Builder(context)
                            .data(url)
                            .size(360, 200)
                            .transformations(GuideLogoWhitening)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = result.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .fillMaxHeight(0.58f)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.LiveTv,
                            contentDescription = null,
                            tint = AppleTvColors.LabelOnContrast.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = result.displayName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = result.displayName,
            color = NuvioTheme.colors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        result.onNow?.let { title ->
            Text(
                text = stringResource(R.string.search_channel_on_now, title),
                color = NuvioTheme.colors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
