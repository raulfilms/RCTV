package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.GameCenter
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** RCTV (game center): what the panel beside the player shows. */
data class GameCenterUiState(
    /** The game playing (null: no game is known for what's playing). */
    val eventId: String? = null,
    val center: GameCenter? = null,
    /** The league's games today: live first, then the next ones, then the finished. */
    val games: List<SportsEvent> = emptyList(),
    val isLoading: Boolean = false,
    /** ESPN gives nothing for this game (its stats and key plays can't be shown). */
    val unavailable: Boolean = false
)

/**
 * RCTV (game center, as YouTube TV): the live game's stats and the league's other games, beside
 * the player. The game is the one opened from Sports, or else the one the channel's program is (by
 * both teams' names). While the panel is open it's read again every [LIVE_REFRESH_MS] (a game being
 * played) or [IDLE_REFRESH_MS]; closed, nothing is read.
 */
@HiltViewModel
class LiveGameCenterViewModel @Inject constructor(
    private val espn: EspnSportsClient
) : ViewModel() {

    private val _state = MutableStateFlow(GameCenterUiState())
    val state: StateFlow<GameCenterUiState> = _state.asStateFlow()

    private var sportsEventId: String? = null
    private var programText: String? = null
    private var programEventId: String? = null
    private var open = false
    private var pollJob: Job? = null
    private var findJob: Job? = null

    /** The game the player was opened for from Sports (null: none, or another channel now). */
    fun bindSportsGame(eventId: String?) {
        if (sportsEventId == eventId) return
        sportsEventId = eventId
        changed()
    }

    /**
     * What's on the channel playing: when Sports didn't say a game, its game is looked for (only
     * once the panel opens, so changing channels reads nothing).
     */
    fun bindProgram(text: String?) {
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == programText) return
        programText = clean
        programEventId = null
        searchedText = null
        findJob?.cancel()
        changed()
    }

    private var searchedText: String? = null

    private fun searchProgramGame() {
        val text = programText ?: return
        if (sportsEventId != null || searchedText == text || findJob?.isActive == true) return
        _state.update { it.copy(isLoading = true) }
        findJob = viewModelScope.launch {
            val event = withContext(Dispatchers.IO) { quietly { espn.findLiveGameForProgram(text) } }
            if (programText != text) return@launch
            searchedText = text
            programEventId = event?.id
            if (event == null) _state.update { it.copy(isLoading = false, unavailable = true) }
            changed()
        }
    }

    /** The panel opened or closed: it's kept up to date only while it's open. */
    fun setOpen(value: Boolean) {
        if (open == value) return
        open = value
        restartPolling()
    }

    private fun currentEventId(): String? = sportsEventId ?: programEventId

    private fun changed() {
        val id = currentEventId()
        if (_state.value.eventId != id) _state.value = GameCenterUiState(eventId = id)
        restartPolling()
    }

    private fun restartPolling() {
        pollJob?.cancel()
        pollJob = null
        if (!open) return
        val id = currentEventId()
        if (id == null) {
            searchProgramGame()
            return
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh(id)
                val live = _state.value.center?.status == SportsEventStatus.LIVE ||
                    _state.value.games.any { it.status == SportsEventStatus.LIVE }
                delay(if (live) LIVE_REFRESH_MS else IDLE_REFRESH_MS)
            }
        }
    }

    private suspend fun refresh(id: String) {
        if (_state.value.center == null) _state.update { it.copy(isLoading = true) }
        val (center, games) = withContext(Dispatchers.IO) {
            coroutineScope {
                val center = async { quietly { espn.fetchGameCenter(id) } }
                val games = async { quietly { espn.fetchLeagueGamesOf(id) } }
                center.await() to games.await()
            }
        }
        _state.update { state ->
            if (state.eventId != id) {
                state
            } else {
                state.copy(
                    center = center ?: state.center,
                    games = games?.let(::ordered) ?: state.games,
                    isLoading = false,
                    unavailable = center == null && state.center == null
                )
            }
        }
    }

    /** Live games first (as ESPN lists them), then the next ones by time, then the finished. */
    private fun ordered(games: List<SportsEvent>): List<SportsEvent> {
        val live = games.filter { it.status == SportsEventStatus.LIVE }
        val next = games.filter { it.status == SportsEventStatus.UPCOMING }.sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
        val done = games.filter { it.status == SportsEventStatus.FINISHED }
        return (live + next + done).take(MAX_GAMES)
    }

    /** A read that can fail (no connection, ESPN down): null then; leaving still stops it. */
    private inline fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val LIVE_REFRESH_MS = 20_000L
        const val IDLE_REFRESH_MS = 60_000L
        const val MAX_GAMES = 30
    }
}
