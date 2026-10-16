package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveLibraryResult
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.data.livetv.LiveTvPlayerSession
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The program on now in the Live TV player, on TMDB, and whether it's in the library. */
data class LivePlayerProgramState(
    val program: EpgProgram? = null,
    /** TMDB is being asked. */
    val isLoading: Boolean = false,
    /** Its movie or series (null: TMDB doesn't have it, once [isLoading] is over). */
    val match: LiveProgramMatch? = null,
    val inLibrary: Boolean = false,
    val isAdding: Boolean = false
)

/**
 * The Live TV player's own side (beside the player's controls): the Guide's channels and programs
 * (what's on, the Networks row), the program on now on TMDB ("Add to library", "More info"), and
 * the clock that moves the programs' progress along.
 */
@HiltViewModel
class LiveTvPlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val session: LiveTvPlayerSession,
    private val liveProgramLibrary: LiveProgramLibrary
) : ViewModel() {

    val guide: StateFlow<LiveTvPlayerSession.Guide> = session.guide

    private val _nowMs = MutableStateFlow(System.currentTimeMillis())

    /** The time, a few times a minute (programs' progress and what's on). */
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    private val _program = MutableStateFlow(LivePlayerProgramState())
    val program: StateFlow<LivePlayerProgramState> = _program.asStateFlow()
    private var programJob: Job? = null

    init {
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                _nowMs.value = System.currentTimeMillis()
            }
        }
    }

    /** The channel playing: the player's title is its name, its logo the channel's. */
    fun channelFor(name: String, logo: String?, guide: LiveTvPlayerSession.Guide): LiveTvChannel? =
        guide.channels.firstOrNull { it.name == name && it.logoUrl == logo }
            ?: guide.channels.firstOrNull { it.name == name }

    /** Looks the program on now up on TMDB (once per program). */
    fun bindProgram(program: EpgProgram?) {
        if (_program.value.program == program) return
        programJob?.cancel()
        if (program == null) {
            _program.value = LivePlayerProgramState()
            return
        }
        _program.value = LivePlayerProgramState(program = program, isLoading = true)
        programJob = viewModelScope.launch {
            val match = try {
                liveProgramLibrary.findTitle(program)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _program.update { it.copy(isLoading = false, match = match) }
            if (match != null) {
                liveProgramLibrary.isInLibrary(match).collect { inLibrary ->
                    _program.update { it.copy(inLibrary = inLibrary) }
                }
            }
        }
    }

    /** "Add to library": the program's movie, or its whole series. */
    fun addToLibrary() {
        val state = _program.value
        val match = state.match ?: return
        if (state.isAdding || state.inLibrary) return
        _program.update { it.copy(isAdding = true) }
        viewModelScope.launch {
            val message = when (liveProgramLibrary.addTitle(match)) {
                LiveLibraryResult.ADDED, LiveLibraryResult.ADDED_SERIES_INSTEAD ->
                    appContext.getString(R.string.livetv_library_added, match.name)
                LiveLibraryResult.ALREADY_IN_LIBRARY -> appContext.getString(R.string.livetv_library_already, match.name)
                LiveLibraryResult.NOT_FOUND -> appContext.getString(R.string.livetv_library_not_found, match.name)
                LiveLibraryResult.FAILED -> appContext.getString(R.string.livetv_library_failed)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            _program.update { it.copy(isAdding = false) }
        }
    }

    /** The address to play a channel at (the sample guide's channels open the player's design view). */
    suspend fun playableUrl(channel: LiveTvChannel): String =
        if (session.guide.value.isPreview) PlayerDesignPreview.URL else session.playableUrl(channel)

    private companion object {
        const val TICK_MS = 20_000L
    }
}
