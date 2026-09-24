package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/** Simple picker for "My Sports" / "My Teams": grid of toggleable chips over the sports/teams already loaded on the Sports screen. */
@Composable
fun SportsFavoritesScreen(
    modifier: Modifier = Modifier,
    viewModel: SportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val favoriteTeamIds = uiState.favoriteTeams.map { it.id }.toSet()
    val sportNames = uiState.leagues.map { it.sportName }.filter { it.isNotBlank() }.distinct().sorted()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.sports_favorites_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(R.string.sports_row_my_sports),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.sm)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            modifier = Modifier.fillMaxSize().weight(1f, fill = false)
        ) {
            items(sportNames, key = { "sport_$it" }) { sportName ->
                com.nuvio.tv.domain.model.SportsLeague(id = sportName, name = sportName, sportName = sportName).let { league ->
                    LeagueCard(
                        league = league,
                        onClick = { viewModel.toggleFavoriteSport(sportName) }
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.sports_row_my_teams),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.sm)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            modifier = Modifier.fillMaxSize().weight(1f, fill = false)
        ) {
            items(uiState.browsableTeams, key = { "team_${it.id}" }) { team ->
                TeamCard(
                    team = team,
                    isFavorite = team.id in favoriteTeamIds,
                    onClick = { viewModel.toggleFavoriteTeam(team.id) }
                )
            }
        }
    }
}
