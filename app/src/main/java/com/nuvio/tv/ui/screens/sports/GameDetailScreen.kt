package com.nuvio.tv.ui.screens.sports

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsGameStat
import com.nuvio.tv.ui.theme.NuvioTheme

/** Full-page detail view for a single game/match, tapped from any scoreboard card anywhere in Sports. */
@Composable
fun GameDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: GameDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val event = viewModel.event

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "header") {
            GameDetailHeader(
                event = event,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }

        item(key = "actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.lg)
            ) {
                GameActionButton(
                    label = stringResource(
                        if (uiState.isInLibrary) R.string.hero_remove_from_library else R.string.hero_add_to_library
                    ),
                    icon = if (uiState.isInLibrary) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                    onClick = viewModel::toggleLibrary
                )
                event.homeTeamId?.let { homeTeamId ->
                    val isFavorite = homeTeamId in uiState.favoriteTeamIds
                    GameActionButton(
                        label = stringResource(
                            if (isFavorite) R.string.sports_remove_team_from_my_teams else R.string.sports_add_team_to_my_teams,
                            event.homeTeamName
                        ),
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        onClick = { viewModel.toggleFavoriteTeam(homeTeamId) }
                    )
                }
                event.awayTeamId?.let { awayTeamId ->
                    val isFavorite = awayTeamId in uiState.favoriteTeamIds
                    GameActionButton(
                        label = stringResource(
                            if (isFavorite) R.string.sports_remove_team_from_my_teams else R.string.sports_add_team_to_my_teams,
                            event.awayTeamName
                        ),
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        onClick = { viewModel.toggleFavoriteTeam(awayTeamId) }
                    )
                }
            }
        }

        item(key = "stats_title") {
            Text(
                text = stringResource(R.string.game_detail_stats_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }

        when {
            uiState.isLoadingStats -> item(key = "stats_loading") {
                Text(
                    text = stringResource(R.string.game_detail_loading_stats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
                )
            }
            uiState.stats.isEmpty() -> item(key = "stats_empty") {
                Text(
                    text = stringResource(R.string.game_detail_no_stats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
                )
            }
            else -> {
                item(key = "stats_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs)
                    ) {
                        Text(
                            text = event.homeTeamName,
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Spacer(modifier = Modifier.width(80.dp))
                        Text(
                            text = event.awayTeamName,
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End
                        )
                    }
                }
                items(uiState.stats, key = { it.label }) { stat ->
                    GameStatRow(stat = stat, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
                }
            }
        }
    }
}

@Composable
private fun GameDetailHeader(event: com.nuvio.tv.domain.model.SportsEvent, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.panel))
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(NuvioTheme.spacing.xl)
    ) {
        Text(
            text = (event.leagueName ?: event.sportName).orEmpty().uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GameDetailTeam(
                name = event.homeTeamName,
                badgeUrl = event.homeTeamBadgeUrl,
                score = event.homeScore,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "VS",
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md)
            )
            GameDetailTeam(
                name = event.awayTeamName,
                badgeUrl = event.awayTeamBadgeUrl,
                score = event.awayScore,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = statusLine(event),
            style = MaterialTheme.typography.titleSmall,
            color = if (event.status == SportsEventStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextPrimary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        event.venue?.takeIf { it.isNotBlank() }?.let { venue ->
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = venue,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GameDetailTeam(name: String, badgeUrl: String?, score: Int?, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
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
                    modifier = Modifier.size(48.dp)
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
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        score?.let {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = "$it",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}

private fun statusLine(event: com.nuvio.tv.domain.model.SportsEvent): String {
    if (event.status == SportsEventStatus.LIVE) {
        return event.statusDetail?.takeIf { it.isNotBlank() } ?: "Live"
    }
    val startTimeMs = event.startTimeMs
    if (startTimeMs != null) {
        val format = java.text.SimpleDateFormat("EEE, MMM d • h:mm a", java.util.Locale.getDefault())
        return format.format(java.util.Date(startTimeMs))
    }
    return event.statusDetail ?: "Scheduled"
}

@Composable
private fun GameActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        Text(text = label, maxLines = 1)
    }
}

@Composable
private fun GameStatRow(stat: SportsGameStat, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = NuvioTheme.spacing.xs)
    ) {
        Text(
            text = stat.homeValue,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = stat.awayValue,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
