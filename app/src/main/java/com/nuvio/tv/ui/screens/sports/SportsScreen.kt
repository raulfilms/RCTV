package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTalkItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun SportsScreen(
    modifier: Modifier = Modifier,
    onPlayEvent: (SportsEvent) -> Unit = {},
    onPlayStream: (Stream) -> Unit = {},
    onOpenTeamsSeeAll: () -> Unit = {},
    onOpenFavoritesPicker: () -> Unit = {},
    viewModel: SportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            uiState.isLoading && uiState.leagues.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.sports_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            uiState.error != null && uiState.leagues.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.sports_load_error),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Text(
                            text = uiState.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }
            else -> {
                SportsContent(
                    uiState = uiState,
                    onPlayEvent = onPlayEvent,
                    onOpenTeamsSeeAll = onOpenTeamsSeeAll,
                    onOpenFavoritesPicker = onOpenFavoritesPicker,
                    onToggleFavoriteTeam = viewModel::toggleFavoriteTeam,
                    onSelectAddonEvent = viewModel::selectAddonEvent,
                    onShowFavoriteOptions = viewModel::showFavoriteOptions
                )
            }
        }

        uiState.selectedAddonEvent?.let { selected ->
            SportsAddonStreamPickerOverlay(
                event = selected,
                streams = uiState.addonEventStreams,
                isLoading = uiState.isLoadingAddonStreams,
                error = uiState.addonStreamsError,
                onSelectStream = { stream ->
                    viewModel.dismissAddonEventPicker()
                    onPlayStream(stream)
                },
                onDismiss = viewModel::dismissAddonEventPicker
            )
        }

        uiState.favoriteOptionsTarget?.let { target ->
            SportsFavoriteOptionsDialog(
                target = target,
                favoriteTeamIds = uiState.favoriteTeams.map { it.id }.toSet(),
                favoriteSportNames = uiState.favoriteSportNames,
                onToggleTeam = viewModel::toggleFavoriteTeam,
                onToggleSport = viewModel::toggleFavoriteSport,
                onDismiss = viewModel::dismissFavoriteOptions
            )
        }
    }
}

@Composable
private fun SportsContent(
    uiState: SportsUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onOpenTeamsSeeAll: () -> Unit,
    onOpenFavoritesPicker: () -> Unit,
    onToggleFavoriteTeam: (String) -> Unit,
    onSelectAddonEvent: (com.nuvio.tv.domain.model.SportsAddonEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit
) {
    val favoriteTeamIds = uiState.favoriteTeams.map { it.id }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
    ) {
        uiState.featuredEvent?.let { featured ->
            item(key = "hero") {
                SportsHeroBanner(
                    event = featured,
                    onWatchLive = { onPlayEvent(featured) },
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
                )
            }
        }

        if (uiState.addonEvents.isNotEmpty()) {
            item(key = "live_streams") {
                SportsRowSection(title = stringResource(R.string.sports_row_live_streams)) {
                    items(uiState.addonEvents, key = { it.id }) { event ->
                        SportsAddonEventCard(event = event, onClick = { onSelectAddonEvent(event) })
                    }
                }
            }
        }

        if (uiState.continueWatching.isNotEmpty()) {
            item(key = "continue_watching") {
                SportsRowSection(title = stringResource(R.string.sports_row_continue_watching)) {
                    items(uiState.continueWatching, key = { it.id }) { cw ->
                        SportsTalkCard(
                            item = SportsTalkItem(id = cw.id, title = cw.title, summary = cw.subtitle.orEmpty()),
                            onClick = { cw.event?.let(onPlayEvent) }
                        )
                    }
                }
            }
        }

        if (uiState.liveNow.isNotEmpty()) {
            item(key = "live_now") {
                SportsRowSection(title = stringResource(R.string.sports_row_live_now)) {
                    items(uiState.liveNow, key = { it.id }) { event ->
                        ScoreboardCard(
                            event = event,
                            onClick = { onPlayEvent(event) },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
                        )
                    }
                }
            }
        }

        if (uiState.upcomingGames.isNotEmpty()) {
            item(key = "upcoming_games") {
                SportsRowSection(title = stringResource(R.string.sports_row_upcoming_games)) {
                    items(uiState.upcomingGames, key = { it.id }) { event ->
                        ScoreboardCard(
                            event = event,
                            onClick = { onPlayEvent(event) },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
                        )
                    }
                }
            }
        }

        if (uiState.pickedForYou.isNotEmpty()) {
            item(key = "picked_for_you") {
                SportsRowSection(title = stringResource(R.string.sports_row_picked_for_you)) {
                    items(uiState.pickedForYou, key = { "picked_${it.id}" }) { event ->
                        ScoreboardCard(
                            event = event,
                            onClick = { onPlayEvent(event) },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
                        )
                    }
                }
            }
        }

        if (uiState.leagues.isNotEmpty()) {
            item(key = "leagues") {
                SportsRowSection(title = stringResource(R.string.sports_row_leagues)) {
                    items(uiState.leagues, key = { it.id }) { league ->
                        LeagueCard(
                            league = league,
                            onClick = { },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.LeagueTarget(league)) }
                        )
                    }
                }
            }
        }

        if (uiState.teams.isNotEmpty()) {
            item(key = "teams") {
                SportsRowSection(
                    title = stringResource(R.string.sports_row_teams),
                    onSeeAll = onOpenTeamsSeeAll
                ) {
                    items(uiState.teams, key = { it.id }) { team ->
                        TeamCard(
                            team = team,
                            isFavorite = team.id in favoriteTeamIds,
                            onClick = { onToggleFavoriteTeam(team.id) },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.TeamTarget(team)) }
                        )
                    }
                }
            }
        }

        if (uiState.sportsTalk.isNotEmpty()) {
            item(key = "sports_talk") {
                SportsRowSection(title = stringResource(R.string.sports_row_sports_talk)) {
                    items(uiState.sportsTalk, key = { it.id }) { talk ->
                        SportsTalkCard(item = talk, onClick = { })
                    }
                }
            }
        }

        item(key = "my_sports") {
            SportsRowSection(title = stringResource(R.string.sports_row_my_sports)) {
                if (uiState.favoriteSportNames.isEmpty()) {
                    item(key = "my_sports_empty") {
                        AddFavoritesCard(
                            label = stringResource(R.string.sports_add_favorite_sports),
                            onClick = onOpenFavoritesPicker
                        )
                    }
                } else {
                    items(uiState.favoriteSportNames.toList(), key = { "sport_$it" }) { sportName ->
                        val league = SportsLeague(id = sportName, name = sportName, sportName = sportName)
                        LeagueCard(
                            league = league,
                            onClick = { },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.LeagueTarget(league)) }
                        )
                    }
                }
            }
        }

        item(key = "my_teams") {
            SportsRowSection(title = stringResource(R.string.sports_row_my_teams)) {
                if (uiState.favoriteTeams.isEmpty()) {
                    item(key = "my_teams_empty") {
                        AddFavoritesCard(
                            label = stringResource(R.string.sports_add_favorite_teams),
                            onClick = onOpenFavoritesPicker
                        )
                    }
                } else {
                    items(uiState.favoriteTeams, key = { "fav_team_${it.id}" }) { team ->
                        TeamCard(
                            team = team,
                            isFavorite = true,
                            onClick = { onToggleFavoriteTeam(team.id) },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.TeamTarget(team)) }
                        )
                    }
                    item(key = "my_teams_add") {
                        AddFavoritesCard(
                            label = stringResource(R.string.sports_add_favorite_teams),
                            onClick = onOpenFavoritesPicker
                        )
                    }
                }
            }
        }
    }
}
