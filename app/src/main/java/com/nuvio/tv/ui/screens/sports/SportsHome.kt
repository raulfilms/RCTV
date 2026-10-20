@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.sports

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsContinueWatchingItem
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.ui.components.AppleLandscapeCardHeight
import com.nuvio.tv.ui.components.AppleLandscapeCardWidth
import com.nuvio.tv.ui.components.AppleSeeAllCard
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvRadius
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType
import com.nuvio.tv.ui.screens.home.HomeCard
import com.nuvio.tv.ui.screens.home.HomeTileRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.longPressable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Each Sports row shows this many cards, then "See All". */
internal const val SPORTS_ROW_MAX = 10

/** The games' cards: 16:9, a little bigger than the leagues' and teams' tiles. */
internal val SportsGameCardWidth: Dp = 240.dp
internal val SportsGameCardHeight: Dp = 135.dp

/** The leagues' and teams' tiles: the streaming hubs' 16:9 tiles. */
internal val SportsTileWidth: Dp = AppleLandscapeCardWidth
internal val SportsTileHeight: Dp = AppleLandscapeCardHeight

private val LiveRed = Color(0xFFE5202E)
private val FavoriteGold = Color(0xFFFFC53D)

/** Colors for teams and leagues the source gives none for, picked by their id. */
private val TilePalette = listOf(
    Color(0xFF1D428A), Color(0xFFC8102E), Color(0xFF00573F), Color(0xFF552583), Color(0xFFE35205),
    Color(0xFF00788C), Color(0xFF6F263D), Color(0xFF0C2340), Color(0xFF8A6A00), Color(0xFF3D195B)
)

/** The big leagues' own colors (the rest get one from [TilePalette]). */
private fun leagueColor(name: String): Color? {
    val n = name.lowercase(Locale.US)
    return when {
        "wnba" in n -> Color(0xFFFA4D00)
        "nba" in n -> Color(0xFF1D428A)
        "nfl" in n || "national football" in n -> Color(0xFF013369)
        "mlb" in n || "baseball" in n -> Color(0xFF002D72)
        "nhl" in n || "hockey" in n -> Color(0xFF1B1B1F)
        "mls" in n || "major league soccer" in n -> Color(0xFF1F2A44)
        "premier" in n -> Color(0xFF3D195B)
        "champions" in n -> Color(0xFF0E1E5B)
        "laliga" in n || "la liga" in n || "spanish" in n -> Color(0xFFE2231A)
        "serie a" in n || "italian" in n -> Color(0xFF0A3D91)
        "bundesliga" in n || "german" in n -> Color(0xFFD20515)
        "ligue 1" in n || "french" in n -> Color(0xFF091C3E)
        "college" in n || "ncaa" in n -> Color(0xFF0B3B60)
        "ufc" in n || "mma" in n -> Color(0xFFD20A0A)
        "f1" in n || "formula" in n -> Color(0xFFE10600)
        "nascar" in n || "indycar" in n || "motogp" in n -> RaceNavy
        "ppv" in n -> PpvCrimson
        n == "wbc" -> Color(0xFF00843D)
        n == "wba" -> Color(0xFF1B365D)
        n == "ibf" -> Color(0xFF8A6A00)
        n == "wbo" -> Color(0xFF4B1F7A)
        n == "top rank" || n == "matchroom boxing" || n == "pbc" -> Color(0xFF1C1C1E)
        "wta" in n -> Color(0xFF5B2C83)
        "lidom" in n || "dominican" in n -> Color(0xFF002D62)
        "caribe" in n -> Color(0xFF0B6E4F)
        n == "triple-a" || n == "double-a" || n == "high-a" || n == "single-a" -> Color(0xFF002D72)
        "cfl" in n || "canadian football" in n -> Color(0xFFB0001E)
        "ufl" in n || "united football" in n -> Color(0xFF14284B)
        "australian open" in n -> Color(0xFF0072CE)
        "roland" in n -> Color(0xFFB4532A)
        "wimbledon" in n -> Color(0xFF006633)
        n == "us open" -> Color(0xFF003D7C)
        "masters" in n -> Color(0xFF006747)
        "pga championship" in n -> Color(0xFF00205B)
        "u.s. open" in n || "open championship" in n -> Color(0xFF1A2A50)
        "libertadores" in n || "sudamericana" in n || "copa am" in n -> Color(0xFF0B2A5B)
        "world cup" in n || "club world cup" in n -> Color(0xFF326295)
        "europa" in n || "nations league" in n -> Color(0xFF14284B)
        "pga" in n || "golf" in n -> Color(0xFF0B5A2B)
        "liga mx" in n || "mexican" in n || n == "mex.1" -> Color(0xFF00573F)
        "tennis" in n || n == "atp" || n == "wta" -> Color(0xFF0F3B73)
        else -> null
    }
}

/** A league's color: by its name ("National Basketball Association"), else by its id ("basketball:nba"), else one picked by its id. */
internal fun sportsLeagueColor(id: String, name: String): Color =
    leagueColor(name) ?: leagueColor(id.substringAfter(':')) ?: TilePalette[Math.floorMod(id.hashCode(), TilePalette.size)]

/** The leagues' short names by id ("football:nfl" to "NFL", "soccer:esp.1" to "La Liga"). */
private val LeagueShortNames: Map<String, String> by lazy {
    EspnSportsClient.TEAMS_FILTERS.associate { chip -> chip.id to chip.label }
}

/** A league's short name under its tile ("NFL" rather than "National Football League"). */
internal fun sportsLeagueShortName(league: SportsLeague): String = LeagueShortNames[league.id] ?: league.name

/** A team's color ("002244" from the source), else one picked by its id. */
internal fun sportsTeamColor(hex: String?, seed: String?): Color {
    hex?.let { value ->
        runCatching { return Color(android.graphics.Color.parseColor("#$value")) }
    }
    return TilePalette[Math.floorMod((seed ?: "").hashCode(), TilePalette.size)]
}

/** A team's short name: the source's ("BUF"), else from the game's short name ("MIL @ BOS"), else its first letters. */
private fun shortTeamName(event: SportsEvent, home: Boolean): String {
    (if (home) event.homeTeamAbbreviation else event.awayTeamAbbreviation)?.let { return it }
    val match = Regex("""^\s*([A-Za-z0-9]{2,5})\s*(@|vs\.?|VS\.?|v)\s*([A-Za-z0-9]{2,5})\s*$""").find(event.name)
    if (match != null) {
        val (first, separator, second) = match.destructured
        // "AWAY @ HOME"; "HOME vs AWAY".
        return if (separator == "@") (if (home) second else first) else (if (home) first else second)
    }
    val name = if (home) event.homeTeamName else event.awayTeamName
    return name.filter { it.isLetterOrDigit() }.take(3).uppercase(Locale.getDefault())
}

/** Where a game is at: "4th Quarter", "Final", or when it starts ("Today, 7:00 PM"). */
@Composable
internal fun sportsGameStatus(event: SportsEvent): String {
    if (event.isGolf()) return golfStatusText(event)
    return when (event.status) {
        SportsEventStatus.LIVE -> event.statusDetail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sports_live)
        SportsEventStatus.FINISHED -> event.statusDetail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sports_final)
        SportsEventStatus.UPCOMING -> {
            val start = event.startTimeMs ?: return event.statusDetail.orEmpty()
            if (event.startTimeTba()) tbaStartLabel(start) else sportsStartTimeLabel(start)
        }
    }
}

/** When a game starts: "Today, 7:00 PM", "Tomorrow, 7:00 PM", else "Sat, Oct 18 • 7:00 PM". */
@Composable
internal fun sportsStartTimeLabel(startMs: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(startMs))
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.livetv_details_today, time)
        today.plusDays(1) -> stringResource(R.string.livetv_details_tomorrow, time)
        else -> SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(startMs))
    }
}

// ------------------------------------------------------------------ Hero

/**
 * The page's banner: a game (live first) in its teams' colors, their logos big at each side, the
 * score or when it starts in the middle, then Watch Live and Add to Library. It moves on to the next
 * game every few seconds; Left on Watch / Right on the library button go back / on by hand.
 */
@Composable
internal fun SportsHeroCarousel(
    events: List<SportsEvent>,
    libraryEventIds: Set<String>,
    primaryFocusRequester: FocusRequester,
    onWatch: (SportsEvent) -> Unit,
    onToggleLibrary: (SportsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return
    // RCTV: the game shown is kept by its id, so the scores and games updating (every few seconds
    // while the page loads, then every few minutes) don't send the banner back to the first game
    // and start its wait over: it kept showing the first one.
    var shownId by remember { mutableStateOf<String?>(null) }
    val index = events.indexOfFirst { it.id == shownId }.takeIf { it >= 0 } ?: 0
    val event = events[index]
    val latestEvents by rememberUpdatedState(events)
    // A slideshow: on to the next game every few seconds, also while focus is on its buttons; each
    // press of the remote on it (or a change of game) starts the wait over.
    var lastInteraction by remember { mutableIntStateOf(0) }
    LaunchedEffect(event.id, lastInteraction, events.size) {
        if (events.size < 2) return@LaunchedEffect
        delay(HERO_ROTATE_MS)
        val current = latestEvents
        val at = current.indexOfFirst { it.id == event.id }.coerceAtLeast(0)
        shownId = current[(at + 1) % current.size].id
    }
    val step: (Int) -> Unit = { delta -> shownId = events[Math.floorMod(index + delta, events.size)].id }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(330.dp)
            .onPreviewKeyEvent { key ->
                if (key.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) lastInteraction++
                false
            }
    ) {
        AnimatedContent(
            targetState = event,
            // A new game fades in; the same game with a new score doesn't.
            contentKey = { it.id },
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(400)) },
            label = "sportsHeroBackdrop",
            modifier = Modifier.fillMaxSize()
        ) { shown -> SportsHeroBackdrop(shown) }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val isLive = event.status == SportsEventStatus.LIVE
            Text(
                text = stringResource(if (isLive) R.string.sports_hero_live_now else R.string.sports_hero_up_next).uppercase(),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = (event.leagueName ?: event.sportName).orEmpty(),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(url = event.homeTeamBadgeUrl, size = 62.dp)
                Spacer(modifier = Modifier.width(22.dp))
                if (isLive) SportsLiveBadge(large = true) else Text(
                    text = stringResource(R.string.sports_vs),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(22.dp))
                TeamLogo(url = event.awayTeamBadgeUrl, size = 62.dp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            val home = shortTeamName(event, home = true)
            val away = shortTeamName(event, home = false)
            Text(
                text = if (event.homeScore != null && event.awayScore != null && event.status != SportsEventStatus.UPCOMING) {
                    "$home ${event.homeScore}  |  $away ${event.awayScore}"
                } else {
                    "$home  |  $away"
                },
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = sportsGameStatus(event),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val inLibrary = event.id in libraryEventIds
                HeroPillButton(
                    text = stringResource(if (isLive) R.string.sports_watch_live else R.string.sports_view_details),
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    onClick = { onWatch(event) },
                    modifier = Modifier
                        .focusRequester(primaryFocusRequester)
                        .onPreviewKeyEvent { key ->
                            if (key.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && events.size > 1
                            ) {
                                step(-1)
                                true
                            } else {
                                false
                            }
                        }
                )
                HeroPillButton(
                    text = stringResource(if (inLibrary) R.string.livetv_details_in_library else R.string.hero_add_to_library),
                    icon = if (inLibrary) Icons.Default.Check else Icons.Default.Add,
                    primary = false,
                    onClick = { onToggleLibrary(event) },
                    modifier = Modifier.onPreviewKeyEvent { key ->
                        if (key.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && events.size > 1
                        ) {
                            step(1)
                            true
                        } else {
                            false
                        }
                    }
                )
            }
        }

        // Which game of the banner this is.
        if (events.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                events.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(if (i == index) 26.dp else 8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

private const val HERO_ROTATE_MS = 9_000L

/** The banner's background: the home team's color on the left, the away team's on the right, their logos large. */
@Composable
private fun SportsHeroBackdrop(event: SportsEvent) {
    val homeColor = sportsTeamColor(event.homeTeamColor, event.homeTeamId ?: event.homeTeamName)
    val awayColor = sportsTeamColor(event.awayTeamColor, event.awayTeamId ?: event.awayTeamName)
    val base = AppleTvColors.Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to lerp(homeColor, Color.Black, 0.15f),
                        0.42f to lerp(homeColor, base, 0.82f),
                        0.58f to lerp(awayColor, base, 0.82f),
                        1f to lerp(awayColor, Color.Black, 0.15f)
                    )
                )
            )
    ) {
        // The teams' logos, large at each side.
        TeamLogo(
            url = event.homeTeamBadgeUrl,
            size = 250.dp,
            background = lerp(homeColor, Color.Black, 0.15f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 40.dp)
                .alpha(0.9f)
        )
        TeamLogo(
            url = event.awayTeamBadgeUrl,
            size = 250.dp,
            background = lerp(awayColor, Color.Black, 0.15f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-40).dp)
                .alpha(0.9f)
        )
        // Fades into the page at the bottom, darker behind the text in the middle.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.3f to Color.Black.copy(alpha = 0.15f),
                            0.5f to Color.Black.copy(alpha = 0.45f),
                            0.7f to Color.Black.copy(alpha = 0.15f),
                            1f to Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.7f to Color.Transparent,
                            1f to base
                        )
                    )
                )
        )
    }
}

@Composable
internal fun TeamLogo(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = SportsLogoDarkBackground
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) {
            SportsLogoImage(
                url = url,
                background = background,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.SportsBasketball,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

/** A rounded banner button: white (Watch Live) or outlined (Add to Library); white when focused. */
@Composable
internal fun HeroPillButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White else Color.Black.copy(alpha = 0.25f),
            contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (primary) Border.None else Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)), shape = shape),
            focusedBorder = Border.None
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
        }
    }
}

/** The red "● LIVE" badge. */
@Composable
internal fun SportsLiveBadge(large: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LiveRed)
            .padding(horizontal = if (large) 10.dp else 6.dp, vertical = if (large) 3.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (large) 7.dp else 5.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Spacer(modifier = Modifier.width(if (large) 6.dp else 4.dp))
        Text(
            text = stringResource(R.string.sports_live).uppercase(),
            color = Color.White,
            fontSize = if (large) 15.sp else 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ------------------------------------------------------------------ Cards

/**
 * A game, 16:9: the two teams' logos (team vs team) in their colors with their names under them,
 * LIVE or when it starts in the corner, the score in the middle once it's on; the league and where
 * the game is at under the card.
 */
@Composable
internal fun SportsGameCard(
    event: SportsEvent,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val homeColor = sportsTeamColor(event.homeTeamColor, event.homeTeamId ?: event.homeTeamName)
    val awayColor = sportsTeamColor(event.awayTeamColor, event.awayTeamId ?: event.awayTeamName)
    val status = sportsGameStatus(event)
    Column(modifier = Modifier.width(SportsGameCardWidth)) {
        HomeCard(
            onClick = onClick,
            width = SportsGameCardWidth,
            height = SportsGameCardHeight,
            containerColor = AppleTvColors.Surface2,
            modifier = modifier.longPressable(onLongPress)
        ) {
            // A golf tournament, a race, a PPV show: its name (and leader, or picture) on its color.
            if (event.isSoloEvent()) {
                SoloEventCardBody(event = event, status = status, liveBadge = { SportsLiveBadge() })
                return@HomeCard
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to lerp(homeColor, Color.Black, 0.25f),
                                0.5f to lerp(homeColor, awayColor, 0.5f).let { lerp(it, AppleTvColors.Background, 0.75f) },
                                1f to lerp(awayColor, Color.Black, 0.25f)
                            )
                        )
                    )
            ) {
                // Darker under the names, so they read on any team's colors (yellow, silver...).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.5f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.45f)
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GameTeam(
                        name = event.homeTeamName,
                        logo = event.homeTeamBadgeUrl,
                        background = lerp(homeColor, Color.Black, 0.25f),
                        modifier = Modifier.weight(1f)
                    )
                    val showScore = event.status != SportsEventStatus.UPCOMING &&
                        event.homeScore != null && event.awayScore != null
                    Text(
                        text = if (showScore) "${event.homeScore} - ${event.awayScore}" else stringResource(R.string.sports_vs),
                        color = Color.White,
                        fontSize = if (showScore) 19.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(shadow = CardTextShadow),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    GameTeam(
                        name = event.awayTeamName,
                        logo = event.awayTeamBadgeUrl,
                        background = lerp(awayColor, Color.Black, 0.25f),
                        modifier = Modifier.weight(1f)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    when (event.status) {
                        SportsEventStatus.LIVE -> SportsLiveBadge()
                        else -> Text(
                            text = status,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = listOfNotNull(event.leagueName ?: event.sportName, status.takeIf { event.status == SportsEventStatus.LIVE })
                .joinToString(" • "),
            color = AppleTvColors.LabelSecondary,
            style = AppleTvType.Caption1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A dark shadow under white text on a game card: it reads on light team colors too. */
private val CardTextShadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 6f)

/** One team on a game card: its logo, its name under it (bold, big enough to read, shadowed). */
@Composable
private fun GameTeam(name: String, logo: String?, background: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(url = logo, size = 48.dp, background = background)
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            style = LocalTextStyle.current.copy(shadow = CardTextShadow),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A league or a team, like the streaming hubs' tiles: 16:9 in its color, its logo on it; a small
 * star at the top right when it's one of the person's own (My Sports, My Teams). Its name under it.
 */
@Composable
internal fun SportsLogoTile(
    name: String,
    logoUrl: String?,
    color: Color,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Its name big and white (the Teams page), instead of small and gray. */
    prominentName: Boolean = false,
    /** A gray line under its name ("MLB"). */
    subtitle: String? = null
) {
    Column(modifier = Modifier.width(SportsTileWidth)) {
        HomeCard(
            onClick = onClick,
            width = SportsTileWidth,
            height = SportsTileHeight,
            containerColor = color,
            modifier = modifier.longPressable(onLongPress)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(lerp(color, Color.White, 0.12f), lerp(color, Color.Black, 0.35f))
                        )
                    )
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    SportsLogoImage(
                        url = logoUrl,
                        background = color,
                        contentDescription = name,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight(0.64f)
                            .fillMaxWidth(0.6f)
                    )
                } else {
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 12.dp)
                    )
                }
                if (isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = FavoriteGold,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (prominentName) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = name,
                color = AppleTvColors.LabelSecondary,
                style = AppleTvType.Caption1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = AppleTvColors.LabelSecondary,
                style = AppleTvType.Caption1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** "Add Sports" / "Add Teams": a tile with a plus, the size of the tiles beside it. */
@Composable
internal fun SportsAddTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = Modifier.width(SportsTileWidth)) {
        HomeCard(
            onClick = onClick,
            width = SportsTileWidth,
            height = SportsTileHeight,
            containerColor = AppleTvColors.Contrast,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = label, color = Color.White, style = AppleTvType.Callout, maxLines = 1)
            }
        }
    }
}

/** A game the person was watching: its picture, its name and how far along. */
@Composable
internal fun SportsContinueCard(item: SportsContinueWatchingItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = Modifier.width(SportsGameCardWidth)) {
        HomeCard(
            onClick = onClick,
            width = SportsGameCardWidth,
            height = SportsGameCardHeight,
            containerColor = AppleTvColors.Surface2,
            modifier = modifier
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!item.imageUrl.isNullOrBlank()) {
                    AsyncImage(model = item.imageUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progress.coerceIn(0f, 1f))
                            .background(Color.White)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = item.title, color = Color.White, style = AppleTvType.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.subtitle?.let {
            Text(text = it, color = AppleTvColors.LabelSecondary, style = AppleTvType.Caption1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ Rows and See All

/**
 * A Sports row like the hubs' rows: its name, then up to [SPORTS_ROW_MAX] cards and "See All" when
 * there are more.
 */
@Composable
internal fun SportsRow(
    title: String,
    count: Int,
    hasSeeAll: Boolean,
    keyOf: (Int) -> String,
    rowFocusRequester: FocusRequester,
    seeAllWidth: Dp,
    seeAllHeight: Dp,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit
) {
    val total = count + if (hasSeeAll) 1 else 0
    val keys = uniqueKeys(count, keyOf)
    HomeTileRow(
        title = title,
        count = total,
        keyOf = { index -> if (index < count) keys[index] else "see_all" },
        rowFocusRequester = rowFocusRequester,
        initialFocusIndex = -1,
        onItemFocused = {},
        modifier = modifier,
        contentStart = NuvioTheme.spacing.xxxl,
        titleBottomGap = 16.dp,
        boldTitle = true
    ) { index, itemModifier ->
        if (index < count) {
            tile(index, itemModifier)
        } else {
            AppleSeeAllCard(
                label = stringResource(R.string.action_see_all),
                width = seeAllWidth,
                height = seeAllHeight,
                cornerRadius = AppleTvRadius.Poster,
                onClick = onSeeAll,
                modifier = itemModifier
            )
        }
    }
}

/** A row's See All page: every card of the row in a grid, under the row's name. */
@Composable
internal fun SportsGridPage(
    title: String,
    count: Int,
    columns: Int,
    keyOf: (Int) -> String,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit
) {
    val gridState = rememberLazyGridState()
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(count > 0) {
        if (count == 0) return@LaunchedEffect
        repeat(12) {
            withFrameNanos { }
            if (runCatching { firstRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = title,
            style = AppleTvType.Title2,
            color = AppleTvColors.Label,
            maxLines = 1,
            modifier = Modifier.padding(start = AppleTvSpacing.SafeX, end = AppleTvSpacing.SafeX, top = AppleTvSpacing.SafeY)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(start = AppleTvSpacing.SafeX, end = AppleTvSpacing.SafeX, top = 20.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val keys = uniqueKeys(count, keyOf)
            itemsIndexed(List(count) { it }, key = { _, i -> keys[i] }) { i, _ ->
                tile(i, if (i == 0) Modifier.focusRequester(firstRequester) else Modifier)
            }
        }
    }
}

/**
 * The cards' keys, none twice: a game or a team a source lists twice would otherwise bring the
 * whole page down (a row can't have two cards with one key); a repeat gets its place added.
 */
internal fun uniqueKeys(count: Int, keyOf: (Int) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return List(count) { index ->
        val key = keyOf(index)
        val repeats = seen[key] ?: 0
        seen[key] = repeats + 1
        if (repeats == 0) key else "$key#$repeats"
    }
}

/** Nothing in a row yet: a line of text where its cards go. */
@Composable
internal fun SportsRowEmptyNote(text: String) {
    Text(
        text = text,
        color = AppleTvColors.LabelSecondary,
        style = AppleTvType.Body,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .padding(start = NuvioTheme.spacing.xxxl)
    )
}

/** Focus lands on [requester] a moment after it shows (Compose needs a frame or two). */
internal suspend fun requestFocusSoon(requester: FocusRequester) {
    repeat(12) {
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
    }
}

/** Tracks whether any of a modifier's content has focus (the banner pauses while it's used). */
internal fun Modifier.onAnyFocus(onChange: (Boolean) -> Unit): Modifier = onFocusChanged { onChange(it.hasFocus) }
