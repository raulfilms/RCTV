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

/** "Add to Library" for teams and leagues in the hold-to-favorite dialog (Library: Sports Teams, Sports Leagues). */
@HiltViewModel
class SportsLibraryViewModel @Inject constructor(
    private val store: SportsLibraryStore
) : ViewModel() {

    val teamIds: StateFlow<Set<String>> = store.teams
        .map { teams -> teams.map { it.team.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val leagueIds: StateFlow<Set<String>> = store.leagues
        .map { leagues -> leagues.map { it.league.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleTeam(team: SportsTeam) {
        viewModelScope.launch { store.toggleTeam(team) }
    }

    fun toggleLeague(league: SportsLeague) {
        viewModelScope.launch { store.toggleLeague(league) }
    }

    /** A game going into the Library: its details are kept so it opens from there as it is now. */
    fun rememberEvent(event: SportsEvent) {
        viewModelScope.launch { store.rememberEvent(event) }
    }
}
