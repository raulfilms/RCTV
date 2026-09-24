package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsAddonEvent
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTalkItem
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Title + optional "See All" affordance + a LazyRow of cards. The generic row shape reused by every Sports row. */
@Composable
fun SportsRowSection(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (onSeeAll != null) {
                Surface(
                    onClick = onSeeAll,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
                ) {
                    Text(
                        text = stringResourceCompat(R.string.action_see_all),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg),
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)

/** Team-vs-team scoreboard card used by Live Now / Upcoming Games / Picked For You. */
@Composable
fun ScoreboardCard(
    event: SportsEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.width(260.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = (event.leagueName ?: event.sportName).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (event.status == SportsEventStatus.LIVE) {
                    LiveBadge()
                }
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TeamColumn(
                    name = event.homeTeamName,
                    badgeUrl = event.homeTeamBadgeUrl,
                    modifier = Modifier.weight(1f)
                )
                ScoreOrTimeColumn(event = event)
                TeamColumn(
                    name = event.awayTeamName,
                    badgeUrl = event.awayTeamBadgeUrl,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = statusLine(event),
                style = MaterialTheme.typography.bodySmall,
                color = if (event.status == SportsEventStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TeamColumn(name: String, badgeUrl: String?, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!badgeUrl.isNullOrBlank()) {
                AsyncImage(
                    model = badgeUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SportsBasketball,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScoreOrTimeColumn(event: SportsEvent) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        if (event.homeScore != null && event.awayScore != null) {
            Text(
                text = "${event.homeScore} - ${event.awayScore}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
        } else {
            Text(
                text = "VS",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

private fun statusLine(event: SportsEvent): String {
    if (event.status == SportsEventStatus.LIVE) {
        return event.statusDetail?.takeIf { it.isNotBlank() } ?: "Live"
    }
    val startTimeMs = event.startTimeMs
    if (startTimeMs != null) {
        val format = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
        return format.format(Date(startTimeMs))
    }
    return event.statusDetail ?: "Scheduled"
}

@Composable
private fun LiveBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.full))
            .background(NuvioTheme.colors.Error)
            .padding(horizontal = NuvioTheme.spacing.xs, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(NuvioTheme.colors.OnPrimary)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.OnPrimary
        )
    }
}

@Composable
fun LeagueCard(league: SportsLeague, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.size(width = 140.dp, height = 96.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!league.badgeUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = league.badgeUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SportsBasketball,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = league.name,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TeamCard(
    team: SportsTeam,
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.size(width = 140.dp, height = 120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Rating,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!team.badgeUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = team.badgeUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(56.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SportsBasketball,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Text(
                text = team.shortName ?: team.name,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SportsTalkCard(item: SportsTalkItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        modifier = modifier.width(280.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.md)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Empty-state "add favorites" card shown at the end of My Sports / My Teams when the user has none picked yet. */
@Composable
fun AddFavoritesCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.size(width = 140.dp, height = 120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Big hero for the featured live/next game, mirroring the top banner of Apple TV-style sports apps. */
@Composable
fun SportsHeroBanner(
    event: SportsEvent,
    onWatchLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.panel))
            .background(
                Brush.linearGradient(
                    colors = listOf(NuvioTheme.colors.BackgroundElevated, NuvioTheme.colors.Background)
                )
            )
            .padding(NuvioTheme.spacing.xl)
    ) {
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            if (event.status == SportsEventStatus.LIVE) LiveBadge()
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = (event.leagueName ?: event.sportName).orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            HeroTeam(name = event.homeTeamName, badgeUrl = event.homeTeamBadgeUrl, score = event.homeScore)
            Text(
                text = statusLine(event),
                style = MaterialTheme.typography.titleSmall,
                color = if (event.status == SportsEventStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
            )
            HeroTeam(name = event.awayTeamName, badgeUrl = event.awayTeamBadgeUrl, score = event.awayScore)
        }

        Row(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = onWatchLive,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Primary,
                    contentColor = NuvioTheme.colors.OnPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                Text(
                    text = if (event.status == SportsEventStatus.LIVE)
                        stringResourceCompat(R.string.sports_watch_live)
                    else
                        stringResourceCompat(R.string.sports_view_details)
                )
            }
        }
    }
}

@Composable
private fun HeroTeam(name: String, badgeUrl: String?, score: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!badgeUrl.isNullOrBlank()) {
                AsyncImage(
                    model = badgeUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(46.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SportsBasketball,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (score != null) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}

/** A small deterministic gradient palette so team cards without brand colors still read as distinct tiles. */
private val TEAM_GRADIENTS = listOf(
    Color(0xFF3A2E7A) to Color(0xFF1A1533), // purple
    Color(0xFF7A2E2E) to Color(0xFF331515), // red
    Color(0xFF1E4A7A) to Color(0xFF122A44), // blue
    Color(0xFF2E7A5A) to Color(0xFF153322), // green
    Color(0xFF7A5A2E) to Color(0xFF332715), // amber
    Color(0xFF5A2E7A) to Color(0xFF271533), // violet
    Color(0xFF2E6E7A) to Color(0xFF153033), // teal
    Color(0xFF7A2E5A) to Color(0xFF331527) // magenta
)

private fun gradientFor(seed: String): Pair<Color, Color> = TEAM_GRADIENTS[(seed.hashCode() and Int.MAX_VALUE) % TEAM_GRADIENTS.size]

/**
 * Wider team tile for the Teams browse grid: team-tinted gradient background, badge, a
 * favorite/heart toggle in the corner, and name + league subtitle — mirrors a typical sports-app
 * team browser.
 */
@Composable
fun TeamGridCard(
    team: SportsTeam,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorTop, colorBottom) = remember(team.id) { gradientFor(team.id) }
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.aspectRatio(1.45f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors = listOf(colorTop, colorBottom)))
        ) {
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Rating,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(NuvioTheme.spacing.sm)
                        .size(16.dp)
                )
            }
            Surface(
                onClick = onToggleFavorite,
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.28f),
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(NuvioTheme.spacing.xs)
                    .size(28.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) NuvioTheme.colors.Error else NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = NuvioTheme.spacing.sm)
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!team.badgeUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = team.badgeUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SportsBasketball,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(NuvioTheme.spacing.sm)
            ) {
                Text(
                    text = team.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = team.leagueName ?: team.shortName.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A live/upcoming event sourced directly from an installed sports addon (phase 3) - unlike [ScoreboardCard] (TheSportsDB, metadata-only), clicking this one leads to real playback. */
@Composable
fun SportsAddonEventCard(event: SportsAddonEvent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.width(220.dp).aspectRatio(1.5f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!event.poster.isNullOrBlank()) {
                AsyncImage(
                    model = event.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.BackgroundElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))
                    )
                    .padding(NuvioTheme.spacing.sm)
            ) {
                Column {
                    Text(
                        text = event.name,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = event.addonName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Overlay shown after tapping a [SportsAddonEventCard]: lists the streams found for that event
 * (loading/empty/error states included) and lets the user pick one to play. Deliberately small
 * and self-contained rather than reusing the movies/series detail screen's own stream-selection
 * UI, which is coupled to a much larger flow (debrid resolution, torrent handling) this first
 * sports pass doesn't need - only direct, already-playable HTTP stream URLs are offered here.
 */
@Composable
fun SportsAddonStreamPickerOverlay(
    event: SportsAddonEvent,
    streams: List<Stream>,
    isLoading: Boolean,
    error: String?,
    onSelectStream: (Stream) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.lg))
                .padding(NuvioTheme.spacing.xl)
                .clickable(enabled = false) {}
        ) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = event.addonName,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            when {
                isLoading -> Text(
                    text = stringResourceCompat(R.string.sports_addon_streams_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                else -> LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    items(streams, key = { it.stableKey() }) { stream ->
                        SportsAddonStreamRow(stream = stream, onClick = { onSelectStream(stream) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResourceCompat(R.string.livetv_close_btn))
            }
        }
    }
}

@Composable
private fun SportsAddonStreamRow(stream: Stream, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stream.getDisplayName(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val description = stream.getDisplayDescription()
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
