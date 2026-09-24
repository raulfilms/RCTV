package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.ui.theme.NuvioTheme

/** Full "Teams" browse page (the Teams row's "See All" destination): filter by sport/league, My Teams first, then one row per league. */
@Composable
fun TeamsBrowseScreen(
    modifier: Modifier = Modifier,
    onTeamClick: (SportsTeam) -> Unit = {},
    viewModel: TeamsBrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
    ) {
        item(key = "header") {
            Column(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)) {
                Text(
                    text = stringResource(R.string.sports_row_teams).uppercase(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.sports_teams_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }

        item(key = "filters") {
            TeamsFilterRow(
                filters = viewModel.filters,
                selectedId = uiState.selectedFilterId,
                onSelect = viewModel::selectFilter
            )
        }

        item(key = "my_teams") {
            SportsRowSection(title = stringResource(R.string.sports_row_my_teams)) {
                if (uiState.favoriteTeams.isEmpty()) {
                    item(key = "my_teams_empty") {
                        AddFavoritesCard(
                            label = stringResource(R.string.sports_follow_teams_hint),
                            onClick = { }
                        )
                    }
                } else {
                    items(uiState.favoriteTeams, key = { "fav_${it.id}" }) { team ->
                        TeamGridCard(
                            team = team,
                            isFavorite = true,
                            onClick = { onTeamClick(team) },
                            onToggleFavorite = { viewModel.toggleFavoriteTeam(team.id) },
                            modifier = Modifier.width(180.dp)
                        )
                    }
                }
            }
        }

        if (uiState.isLoading && uiState.sections.isEmpty()) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.lg),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = stringResource(R.string.sports_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        items(uiState.sections, key = { it.league.id }) { section ->
            SportsRowSection(title = section.league.name) {
                items(section.teams, key = { "${section.league.id}_${it.id}" }) { team ->
                    TeamGridCard(
                        team = team,
                        isFavorite = team.id in uiState.favoriteTeamIds,
                        onClick = { onTeamClick(team) },
                        onToggleFavorite = { viewModel.toggleFavoriteTeam(team.id) },
                        modifier = Modifier.width(180.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamsFilterRow(
    filters: List<com.nuvio.tv.data.remote.espn.EspnSportsClient.TeamsFilterChip>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg)
    ) {
        item(key = "filter_all") {
            FilterChip(label = stringResource(R.string.sports_filter_all), selected = selectedId == null, onClick = { onSelect(null) })
        }
        items(filters, key = { it.id }) { filter ->
            FilterChip(label = filter.label, selected = selectedId == filter.id, onClick = { onSelect(filter.id) })
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
        )
    }
}
