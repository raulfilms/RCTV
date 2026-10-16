package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.SportsLibraryStore
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The hold-to-favorite dialog's link to the Library: "My Teams" are the Library's Sports Teams
 * (their names and badges are kept here as they're added), "My Sports" leagues are its Sports
 * Leagues, and a game added to the Library keeps its details.
 */
@HiltViewModel
class SportsLibraryViewModel @Inject constructor(
    private val store: SportsLibraryStore
) : ViewModel() {

    /** The leagues in "My Sports". */
    val favoriteLeagueIds: StateFlow<Set<String>> = store.leagues
        .map { leagues -> leagues.map { it.league.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** A team going into "My Teams": its name and badge are kept for the Library. */
    fun rememberTeam(team: SportsTeam) {
        viewModelScope.launch { store.rememberTeams(listOf(team)) }
    }

    /** Adds the league to "My Sports" (and the Library), or takes it out. */
    fun toggleLeague(league: SportsLeague) {
        viewModelScope.launch { store.toggleLeague(league) }
    }

    /** A game going into the Library: its details are kept so it opens from there as it is now. */
    fun rememberEvent(event: SportsEvent) {
        viewModelScope.launch { store.rememberEvent(event) }
    }
}

/** One of a game's two teams, as a team ("My Teams" keeps it). */
internal fun SportsEvent.teamOf(teamId: String): SportsTeam? = when (teamId) {
    homeTeamId -> SportsTeam(
        id = teamId, name = homeTeamName, sportName = sportName,
        leagueId = leagueId, leagueName = leagueName, badgeUrl = homeTeamBadgeUrl
    )
    awayTeamId -> SportsTeam(
        id = teamId, name = awayTeamName, sportName = sportName,
        leagueId = leagueId, leagueName = leagueName, badgeUrl = awayTeamBadgeUrl
    )
    else -> null
}

/** A game's league, for "My Sports" (its badge comes once the Sports page loads it). */
internal fun SportsEvent.leagueOrNull(): SportsLeague? {
    val id = leagueId?.takeIf { it.isNotBlank() } ?: return null
    val name = leagueName?.takeIf { it.isNotBlank() } ?: return null
    return SportsLeague(id = id, name = name, sportName = sportName.orEmpty())
}

/** A "My Sports" card that is a whole sport (saved before leagues were), not a league. */
internal fun SportsLeague.isSportOnly(): Boolean = id == sportName && name == sportName
