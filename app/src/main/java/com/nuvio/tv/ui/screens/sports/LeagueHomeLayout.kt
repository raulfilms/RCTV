package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.ui.components.tileSurfaceBorder
import com.nuvio.tv.ui.util.longPressable
import kotlinx.coroutines.delay

/*
 * RCTV (owner's design, 13-nov-2026, every league's page): a hero with the game of the week (the
 * live games rotate), a menu bar (Home, Games, Teams, Standings, News, Highlights), and Home's rows
 * with their titles on the left: Live, Schedule (by week), Channels, Game Replays, Highlights. The
 * teams' and channels' real logos are shown where the design has their initials.
 */

internal val LeaguePageBackground = Color(0xFF070B18)
private val LeagueCard = Color(0xFF151C2E)
private val LeagueCardBorder = Color(0x2EFFFFFF)
private val LeagueLiveRed = Color(0xFFE5232B)
private val LeagueBlue = Color(0xFF1F63E0)
private val LeagueText = Color(0xFFE6E9F2)
private val LeagueDim = Color(0xFFB8BAC2)
private val LeaguePagePadding = 40.dp
private val LeagueTitleColumn = 132.dp
private const val HERO_ROTATE_MS = 8_000L

/** The channels' tiles, in turn: deep red, deep blue, deep green, deep purple. */
private val ChannelTileColors = listOf(
    Color(0xFF4A0F18) to Color(0x59FF7882),
    Color(0xFF0E2A5C) to Color(0x598CAFFF),
    Color(0xFF0F3B26) to Color(0x5978E0A8),
    Color(0xFF34155C) to Color(0x59C7A2FF)
)

private fun teamColor(hex: String?, fallback: Color): Color =
    hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback

private fun SportsEvent.awayShort(): String = awayTeamAbbreviation ?: awayTeamName
private fun SportsEvent.homeShort(): String = homeTeamAbbreviation ?: homeTeamName

/** The hero: the league's game of the week (its live games in turn, every few seconds, while it isn't focused). */
@Composable
internal fun LeagueHero(
    events: List<SportsEvent>,
    leagueName: String,
    leagueBadgeUrl: String?,
    libraryEventIds: Set<String>,
    onWatch: (SportsEvent) -> Unit,
    onToggleLibrary: (SportsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return
    var index by remember(events.map { it.id }) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(events.size, focused) {
        while (events.size > 1 && !focused) {
            delay(HERO_ROTATE_MS)
            index = (index + 1) % events.size
        }
    }
    val event = events[index % events.size]
    val scoresHidden = rememberScoresHidden()
    val live = event.status == SportsEventStatus.LIVE
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1A3A), Color(0xFF0A1330), LeaguePageBackground)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x4D7EA0FF), Color.Transparent),
                        center = Offset(size.width * 0.64f, 0f),
                        radius = size.width * 0.28f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x387EA0FF), Color.Transparent),
                        center = Offset(size.width * 0.86f, 0f),
                        radius = size.width * 0.22f
                    )
                )
            }
    ) {
        // The two teams, big, on the right.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 96.dp)
        ) {
            HeroTeamDisc(event.awayTeamBadgeUrl, event.awayShort(), teamColor(event.awayTeamColor, Color(0xFF2B3550)))
            Text(
                text = "VS",
                style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic),
                color = Color.White.copy(alpha = 0.9f)
            )
            HeroTeamDisc(event.homeTeamBadgeUrl, event.homeShort(), teamColor(event.homeTeamColor, Color(0xFF2B3550)))
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.58f)
                .background(
                    Brush.horizontalGradient(
                        listOf(LeaguePageBackground.copy(alpha = 0.96f), LeaguePageBackground.copy(alpha = 0.8f), Color.Transparent)
                    )
                )
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(start = LeaguePagePadding, top = 22.dp)
                .width(470.dp)
        ) {
            LeagueChip(leagueName, leagueBadgeUrl)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                RedBar(13.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.league_game_of_week, leagueName).uppercase(),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
                    color = LeagueText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${event.awayTeamName.uppercase()} vs ${event.homeTeamName.uppercase()}",
                style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, lineHeight = 32.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) LivePill(stringResource(R.string.league_live_now).uppercase(), big = true)
                val score = if (!scoresHidden && event.awayScore != null && event.homeScore != null && event.status != SportsEventStatus.UPCOMING) {
                    "${event.awayScore} – ${event.homeScore}"
                } else null
                val parts = listOfNotNull(score, statusLine(event), event.broadcast, leagueName.takeIf { it.isNotBlank() })
                Text(
                    text = parts.joinToString("  |  "),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = LeagueText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .onFocusChanged { focused = it.hasFocus }
            ) {
                HeroButton(
                    label = stringResource(if (live) R.string.sports_watch_live else R.string.sports_view_details).uppercase(),
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    onClick = { onWatch(event) }
                )
                val saved = event.id in libraryEventIds
                HeroButton(
                    label = stringResource(if (saved) R.string.hero_remove_from_library else R.string.hero_add_to_library).uppercase(),
                    icon = if (saved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                    primary = false,
                    onClick = { onToggleLibrary(event) }
                )
            }
        }
        if (events.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = LeaguePagePadding, bottom = 16.dp)
            ) {
                events.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(width = if (i == index % events.size) 14.dp else 5.dp, height = 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i == index % events.size) Color.White else Color.White.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroTeamDisc(logoUrl: String?, short: String, color: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(Color(0x1FFFFFFF))
            .border(3.dp, color, CircleShape)
    ) {
        if (!logoUrl.isNullOrBlank()) {
            SportsLogoImage(url = logoUrl, background = SportsLogoDarkBackground, contentDescription = short, modifier = Modifier.size(70.dp))
        } else {
            Text(text = short.take(4).uppercase(), style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black), color = Color.White)
        }
    }
}

@Composable
private fun LeagueChip(name: String, badgeUrl: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x1FFFFFFF))
            .border(1.dp, Color(0x47FFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        if (!badgeUrl.isNullOrBlank()) {
            SportsLogoImage(url = badgeUrl, background = SportsLogoDarkBackground, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(
            text = name.uppercase(),
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
private fun RedBar(height: Dp) {
    Box(
        modifier = Modifier
            .size(width = 2.5.dp, height = height)
            .clip(RoundedCornerShape(2.dp))
            .background(LeagueLiveRed)
    )
}

@Composable
private fun LivePill(text: String, big: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LeagueLiveRed)
            .padding(horizontal = if (big) 7.dp else 4.dp, vertical = if (big) 3.dp else 1.5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (big) 4.5.dp else 3.5.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Text(
            text = text,
            style = TextStyle(fontSize = if (big) 10.sp else 7.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
            color = Color.White
        )
    }
}

@Composable
private fun HeroButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) LeagueBlue else Color(0x0FFFFFFF),
            contentColor = Color.White,
            focusedContainerColor = if (primary) LeagueBlue else Color(0x33FFFFFF),
            focusedContentColor = Color.White
        ),
        border = tileSurfaceBorder(shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = if (primary) Modifier else Modifier.border(1.dp, Color(0x73FFFFFF), shape)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text = label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp))
        }
    }
}

/** The league's menu, in a bar: Home, Games, Teams, Standings, News, Highlights. */
@Composable
internal fun LeagueTabBar(
    tabs: List<Pair<LeagueDetailTab, Int>>,
    selected: LeagueDetailTab,
    onSelect: (LeagueDetailTab) -> Unit
) {
    val bar = RoundedCornerShape(9.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = LeaguePagePadding)
            .fillMaxWidth()
            .height(32.dp)
            .clip(bar)
            .background(LeagueCard.copy(alpha = 0.92f))
            .border(1.dp, LeagueCardBorder, bar)
    ) {
        tabs.forEach { (tab, labelRes) ->
            val isSelected = tab == selected
            val shape = RoundedCornerShape(6.dp)
            Surface(
                onClick = { onSelect(tab) },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) Color.White else Color.Transparent,
                    contentColor = if (isSelected) LeaguePageBackground else LeagueText,
                    focusedContainerColor = if (isSelected) Color.White else Color(0x33FFFFFF),
                    focusedContentColor = if (isSelected) LeaguePageBackground else Color.White
                ),
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
            ) {
                Text(
                    text = stringResource(labelRes).uppercase(),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** One of Home's rows: its title (and what goes under it) on the left, its cards to the right. */
@Composable
internal fun LeagueHomeRow(
    title: String,
    height: Dp,
    header: @Composable ColumnScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LeaguePagePadding)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.width(LeagueTitleColumn)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RedBar(13.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp, lineHeight = 15.sp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            header()
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = 4.dp, end = LeaguePagePadding, top = 4.dp, bottom = 4.dp),
            modifier = Modifier
                .weight(1f)
                .height(height + 8.dp),
            content = content
        )
    }
}

/** The schedule's week, to move with ‹ and ›. */
@Composable
internal fun LeagueWeekSelector(label: String, onChangeWeek: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(start = 8.5.dp)
    ) {
        WeekArrow(Icons.Default.ChevronLeft) { onChangeWeek(-1) }
        Text(
            text = label.uppercase(),
            style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x1FFFFFFF))
                .padding(horizontal = 6.dp, vertical = 2.5.dp)
        )
        WeekArrow(Icons.Default.ChevronRight) { onChangeWeek(1) }
    }
}

@Composable
private fun WeekArrow(icon: ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0FFFFFFF),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = LeaguePageBackground
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        modifier = Modifier
            .size(16.dp)
            .border(0.5.dp, Color(0x40FFFFFF), shape)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(13.dp))
        }
    }
}

/** A tile's shared frame: the card color, its thin border, and the app's white ring when focused. */
@Composable
private fun LeagueTile(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    container: Color = LeagueCard,
    borderColor: Color = LeagueCardBorder,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(containerColor = container, focusedContainerColor = container),
        border = tileSurfaceBorder(shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .size(width = width, height = height)
            .border(0.5.dp, borderColor, shape)
            .longPressable(onLongPress)
    ) {
        content()
    }
}

@Composable
private fun TeamLogo(url: String?, short: String, size: Dp) {
    if (!url.isNullOrBlank()) {
        SportsLogoImage(url = url, background = LeagueCard, contentDescription = short, modifier = Modifier.size(size))
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF))
        ) {
            Text(text = short.take(3).uppercase(), style = TextStyle(fontSize = 7.sp, fontWeight = FontWeight.Black), color = Color.White)
        }
    }
}

@Composable
private fun WatchChip(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x24FFFFFF))
            .border(0.5.dp, Color(0x4DFFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(text = text, style = TextStyle(fontSize = 7.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp), color = Color.White)
        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(8.dp))
    }
}

/** A live game: LIVE and its clock, both teams and the score, its network, Watch. */
@Composable
internal fun LeagueLiveGameTile(event: SportsEvent, onClick: () -> Unit, onLongPress: () -> Unit) {
    val scoresHidden = rememberScoresHidden()
    LeagueTile(width = 175.dp, height = 56.dp, onClick = onClick, onLongPress = onLongPress) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(teamColor(event.awayTeamColor, LeagueBlue)))
                Box(Modifier.weight(1f).fillMaxHeight().background(teamColor(event.homeTeamColor, LeagueLiveRed)))
            }
            Row(modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 6.dp)) {
                Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        LivePill(stringResource(R.string.sports_live).uppercase())
                        Text(
                            text = statusLine(event),
                            style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Medium),
                            color = LeagueText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TeamLogo(event.awayTeamBadgeUrl, event.awayShort(), 22.dp)
                        ScoreText(if (scoresHidden) null else event.awayScore, event.awayShort())
                        Text(text = if (scoresHidden) "vs" else "–", style = TextStyle(fontSize = 9.sp), color = Color.White.copy(alpha = 0.6f))
                        ScoreText(if (scoresHidden) null else event.homeScore, event.homeShort())
                        TeamLogo(event.homeTeamBadgeUrl, event.homeShort(), 22.dp)
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Text(
                        text = event.broadcast.orEmpty(),
                        style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                        color = LeagueDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                    WatchChip(stringResource(R.string.league_watch).uppercase())
                }
            }
        }
    }
}

@Composable
private fun ScoreText(score: Int?, short: String) {
    Text(
        text = score?.toString() ?: short.take(4).uppercase(),
        style = TextStyle(fontSize = if (score != null) 15.sp else 9.sp, fontWeight = FontWeight.ExtraBold),
        color = Color.White,
        maxLines = 1
    )
}

/** A game of the week's schedule: its day and time (or result), its network, both teams. */
@Composable
internal fun LeagueScheduleTile(event: SportsEvent, onClick: () -> Unit, onLongPress: () -> Unit) {
    val scoresHidden = rememberScoresHidden()
    LeagueTile(width = 140.dp, height = 50.dp, onClick = onClick, onLongPress = onLongPress) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val finished = event.status == SportsEventStatus.FINISHED
                val time = when {
                    event.status == SportsEventStatus.LIVE -> statusLine(event)
                    finished && !scoresHidden && event.awayScore != null && event.homeScore != null ->
                        "${stringResource(R.string.sports_final)} · ${event.awayScore}–${event.homeScore}"
                    finished -> stringResource(R.string.sports_final)
                    else -> statusLine(event).uppercase()
                }
                Text(
                    text = time,
                    style = TextStyle(fontSize = 7.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
                    color = if (event.status == SportsEventStatus.LIVE) Color(0xFFFF6B70) else LeagueDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                event.broadcast?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontSize = 7.sp, fontWeight = FontWeight.SemiBold),
                        color = LeagueDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp).width(40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamWithShort(event.awayTeamBadgeUrl, event.awayShort())
                Text(
                    text = "VS",
                    style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                TeamWithShort(event.homeTeamBadgeUrl, event.homeShort())
            }
        }
    }
}

@Composable
private fun RowScope.TeamWithShort(logoUrl: String?, short: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TeamLogo(logoUrl, short, 20.dp)
        Text(
            text = short.take(4).uppercase(),
            style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            maxLines = 1
        )
    }
}

/** One of the league's channels: its logo, its name and what's on, LIVE, Watch. */
@Composable
internal fun LeagueChannelTile(item: LeagueChannelItem, program: String?, index: Int, onClick: () -> Unit) {
    val (container, border) = ChannelTileColors[index % ChannelTileColors.size]
    LeagueTile(width = 365.dp, height = 38.dp, onClick = onClick, container = container, borderColor = border) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp)
        ) {
            ChannelLogo(item.channel.logoUrl, Modifier.size(width = 34.dp, height = 22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!program.isNullOrBlank()) {
                    Text(
                        text = program,
                        style = TextStyle(fontSize = 7.5.sp),
                        color = LeagueText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LivePill(stringResource(R.string.sports_live).uppercase())
            WatchChip(stringResource(R.string.league_watch).uppercase())
        }
    }
}

/** A game replay channel: what it's showing (the game), its logo and name. */
@Composable
internal fun LeagueReplayTile(item: LeagueChannelItem, program: String?, onClick: () -> Unit) {
    LeagueTile(width = 175.dp, height = 50.dp, onClick = onClick) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(56.dp)
                    .background(Color(0xFF0D1A3A))
            ) {
                ChannelLogo(item.channel.logoUrl, Modifier.size(width = 40.dp, height = 26.dp))
            }
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp)
            ) {
                Text(
                    text = program?.takeIf { it.isNotBlank() } ?: item.title,
                    style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.title,
                    style = TextStyle(fontSize = 7.sp),
                    color = LeagueDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ChannelLogo(url: String?, modifier: Modifier) {
    if (!url.isNullOrBlank()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        Box(contentAlignment = Alignment.Center, modifier = modifier) {
            Icon(imageVector = Icons.Default.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
    }
}

/** A highlights video: its picture and length, and its title. */
@Composable
internal fun LeagueHighlightTile(video: com.nuvio.tv.data.trailer.YouTubeSearchResult, onClick: () -> Unit) {
    LeagueTile(width = 175.dp, height = 46.dp, onClick = onClick) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(82.dp)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = "https://i.ytimg.com/vi/${video.videoId}/mqdefault.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                video.durationSeconds?.let { seconds ->
                    Text(
                        text = highlightLength(seconds),
                        style = TextStyle(fontSize = 6.5.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = video.title,
                style = TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp),
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 7.dp)
            )
        }
    }
}

private fun highlightLength(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A row's note when it has nothing to show. */
@Composable
internal fun LeagueRowNote(text: String) {
    Text(text = text, style = TextStyle(fontSize = 10.sp), color = LeagueDim, modifier = Modifier.padding(start = 4.dp))
}
