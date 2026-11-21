package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveEpisode
import com.nuvio.tv.data.livetv.LiveLibraryResult
import com.nuvio.tv.data.livetv.LiveSeason
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.data.livetv.LiveTvPlayerSession
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    val isAdding: Boolean = false,
    /**
     * RCTV: TMDB couldn't be asked (the network, TMDB busy), even after asking again: nothing is
     * said about it (never "Not on TMDB" for a show that is).
     */
    val lookupFailed: Boolean = false
)

/** The Episodes tab: the series airing now, its seasons and the chosen season's episodes. */
data class LivePlayerEpisodesState(
    /** The series (TMDB id) these are for; null until the tab is first shown. */
    val tvId: Int? = null,
    /** Its seasons are being looked up. */
    val isLoading: Boolean = false,
    val seasons: List<LiveSeason> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<LiveEpisode> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    /** The episode airing now, once TMDB tells (by its name in the guide, or its air date). */
    val airing: LiveEpisode? = null
)

/** RCTV (owner): the "More to Watch" tab: titles picked from what's on and what was watched. */
/** RCTV (owner): the Catch Up tab: what the IPTV provider recorded on the channel, newest first. */
data class LivePlayerCatchUpState(
    val channelId: String? = null,
    val isLoading: Boolean = false,
    val programs: List<com.nuvio.tv.domain.model.LiveCatchUpProgram> = emptyList()
)

/**
 * RCTV (owner, the DVR as YouTube TV's): what the player shows of a channel the provider records:
 * live, or its recording from [segmentStartMs] (the real time at the recording's start) to
 * [segmentEndMs].
 */
data class LiveDvrState(
    val channelId: String? = null,
    val recording: Boolean = false,
    val segmentStartMs: Long = 0L,
    val segmentEndMs: Long = 0L
)

/** RCTV (owner, the DVR): a recording to play: its address and where in it to start. */
data class LiveDvrStart(val url: String, val startPositionMs: Long, val segmentStartMs: Long, val segmentEndMs: Long)

data class LivePlayerMoreState(
    /** What they were picked for (what's on now). */
    val key: String? = null,
    val isLoading: Boolean = false,
    val items: List<com.nuvio.tv.domain.model.MetaPreview> = emptyList(),
    /** RCTV (owner): on sports, the channels with games and sports on now (shown before the titles). */
    val channels: List<LiveTvChannel> = emptyList()
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
    private val liveProgramLibrary: LiveProgramLibrary,
    private val liveVodFinder: com.nuvio.tv.data.livetv.LiveVodFinder,
    private val liveTvDataStore: com.nuvio.tv.data.local.LiveTvDataStore,
    private val moreToWatchPicks: com.nuvio.tv.data.livetv.LiveMoreToWatch,
    /** RCTV (owner): the IPTV provider's catch-up (its recordings of a channel's programs). */
    private val xtreamClient: com.nuvio.tv.data.remote.xtream.XtreamClient
) : ViewModel() {

    val guide: StateFlow<LiveTvPlayerSession.Guide> = session.guide

    /** RCTV (Networks): the last channels watched on this profile, newest first. */
    val recentChannelIds: StateFlow<List<String>> = liveTvDataStore.recentChannelIds

    /** The channel switched to inside the player (its name alone can be on several channels). */
    private var playingChannelId: String? = null

    private val _nowMs = MutableStateFlow(System.currentTimeMillis())

    /** The time, a few times a minute (programs' progress and what's on). */
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    private val _program = MutableStateFlow(LivePlayerProgramState())
    val program: StateFlow<LivePlayerProgramState> = _program.asStateFlow()
    private var programJob: Job? = null

    private val _episodes = MutableStateFlow(LivePlayerEpisodesState())

    /** The Episodes tab (a series on TMDB only). */
    val episodes: StateFlow<LivePlayerEpisodesState> = _episodes.asStateFlow()
    private var episodesJob: Job? = null
    private var seasonJob: Job? = null
    /** A season was picked by hand: the airing episode's season doesn't take over. */
    private var seasonChosen = false

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
        playingChannelId?.let { id -> guide.channels.firstOrNull { it.id == id && it.name == name } }
            ?: guide.channels.firstOrNull { it.name == name && it.logoUrl == logo }
            ?: guide.channels.firstOrNull { it.name == name }

    /**
     * RCTV: the player is being left on this channel: the Guide keeps it playing behind, showing
     * [frame] (its last picture) until it plays there.
     */
    fun noteLeaving(name: String, logo: String?, frame: android.graphics.Bitmap? = null) {
        if (session.guide.value.isPreview) return
        channelFor(name, logo, session.guide.value)?.let { session.noteLeft(it, frame) }
        // Left with Back: the app doesn't open on it again.
        viewModelScope.launch { runCatching { liveTvDataStore.setResumeChannel(null) } }
    }

    /** RCTV (Networks): a channel is being switched to inside the player. */
    fun choosingChannel(channel: LiveTvChannel) {
        playingChannelId = channel.id
    }

    /** RCTV (Networks): a channel played; it goes first in the recent channels. */
    fun noteWatched(channel: LiveTvChannel) {
        if (session.guide.value.isPreview) return
        viewModelScope.launch {
            runCatching { liveTvDataStore.noteRecentChannel(channel.id) }
            // RCTV: the app left on it (Home, the TV off) opens on it again.
            runCatching { liveTvDataStore.setResumeChannel(channel.id) }
        }
    }

    /**
     * RCTV (long-press OK): the channel watched before [current], from the recent channels; a
     * channel the lineup no longer has is skipped.
     */
    fun previousChannel(current: LiveTvChannel?): LiveTvChannel? {
        return previousLiveChannel(session.guide.value.channels, current?.id, recentChannelIds.value)
    }

    /** Looks the program on now up on TMDB (once per program). */
    fun bindProgram(program: EpgProgram?) {
        if (_program.value.program == program) return
        programJob?.cancel()
        // A new program: its episodes are looked up again when the tab shows.
        episodesJob?.cancel()
        seasonJob?.cancel()
        _episodes.value = LivePlayerEpisodesState()
        if (program == null) {
            _program.value = LivePlayerProgramState()
            return
        }
        _program.value = LivePlayerProgramState(program = program, isLoading = true)
        programJob = viewModelScope.launch {
            // RCTV (owner: "Chicago Med" said "Not on TMDB"): TMDB not answering isn't "not on
            // TMDB": asked again a few times (a moment, then a little longer), still "Looking up".
            var match: LiveProgramMatch? = null
            var failed = false
            for (attempt in 0..TITLE_RETRY_DELAYS_MS.size) {
                if (attempt > 0) kotlinx.coroutines.delay(TITLE_RETRY_DELAYS_MS[attempt - 1])
                failed = try {
                    match = liveProgramLibrary.findTitle(program, retryNow = attempt > 0)
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    true
                }
                if (!failed) break
            }
            _program.update { it.copy(isLoading = false, match = match, lookupFailed = failed) }
            if (match != null) {
                liveProgramLibrary.isInLibrary(match).collect { inLibrary ->
                    _program.update { it.copy(inLibrary = inLibrary) }
                }
            }
        }
    }

    /**
     * The Episodes tab showed: the series' seasons, then the latest season's episodes (or the
     * airing episode's season, once TMDB tells which episode is on).
     */
    fun ensureEpisodes() {
        val state = _program.value
        val match = state.match ?: return
        val program = state.program ?: return
        if (match.isMovie || _episodes.value.tvId == match.tmdbId) return
        episodesJob?.cancel()
        seasonJob?.cancel()
        seasonChosen = false
        _episodes.value = LivePlayerEpisodesState(tvId = match.tmdbId, isLoading = true)
        episodesJob = viewModelScope.launch {
            val airingLookup = async {
                try {
                    liveProgramLibrary.match(program)?.episode
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val seasons = try {
                liveProgramLibrary.seasons(match.tmdbId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // RCTV (owner's rule): it opens on the episode airing now, in its season (as YouTube TV):
            // TMDB is given a few seconds to tell which; the latest season only when it can't.
            val airingSoon = kotlinx.coroutines.withTimeoutOrNull(AIRING_WAIT_MS) { airingLookup.await() }
            _episodes.update { it.copy(isLoading = false, seasons = seasons, airing = airingSoon ?: it.airing) }
            val latest = seasons.lastOrNull { it.number > 0 }?.number ?: seasons.firstOrNull()?.number
            val first = airingSoon?.season?.takeIf { season -> seasons.any { it.number == season } } ?: latest
            if (first != null) loadSeason(first)
            val airing = airingSoon ?: airingLookup.await() ?: return@launch
            _episodes.update { it.copy(airing = airing) }
            if (!seasonChosen && airing.season != _episodes.value.selectedSeason && seasons.any { it.number == airing.season }) {
                loadSeason(airing.season)
            }
        }
    }

    /** A season chosen in the Episodes tab. */
    fun selectSeason(number: Int) {
        if (_episodes.value.selectedSeason == number) return
        seasonChosen = true
        loadSeason(number)
    }

    private fun loadSeason(number: Int) {
        val tvId = _episodes.value.tvId ?: return
        seasonJob?.cancel()
        _episodes.update { it.copy(selectedSeason = number, episodes = emptyList(), isLoadingEpisodes = true) }
        seasonJob = viewModelScope.launch {
            val list = try {
                liveProgramLibrary.episodes(tvId, number)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _episodes.update { if (it.selectedSeason == number) it.copy(episodes = list, isLoadingEpisodes = false) else it }
        }
    }

    private val _moreToWatch = MutableStateFlow(LivePlayerMoreState())

    /** RCTV: the More to Watch tab's titles. */
    val moreToWatch: StateFlow<LivePlayerMoreState> = _moreToWatch.asStateFlow()
    private var moreToWatchJob: Job? = null

    private val _catchUp = MutableStateFlow(LivePlayerCatchUpState())

    /** RCTV (owner): the Catch Up tab (only for a channel the provider records). */
    val catchUp: StateFlow<LivePlayerCatchUpState> = _catchUp.asStateFlow()
    private var catchUpJob: Job? = null
    private var catchUpLoadedAtMs = 0L

    /** RCTV (owner): the Catch Up tab showed: the channel's recordings (asked again every few minutes). */
    fun ensureCatchUp(channel: LiveTvChannel?) {
        if (channel == null || channel.archiveDays <= 0 || session.guide.value.isPreview) {
            catchUpJob?.cancel()
            _catchUp.value = LivePlayerCatchUpState(channelId = channel?.id)
            return
        }
        val now = System.currentTimeMillis()
        val current = _catchUp.value
        if (current.channelId == channel.id && (current.isLoading || now - catchUpLoadedAtMs < CATCH_UP_REFRESH_MS)) return
        catchUpJob?.cancel()
        _catchUp.value = LivePlayerCatchUpState(
            channelId = channel.id,
            isLoading = true,
            programs = current.programs.takeIf { current.channelId == channel.id }.orEmpty()
        )
        catchUpJob = viewModelScope.launch {
            val programs = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { xtreamClient.fetchCatchUp(channel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never the address (it carries the account's login): only what kind of failure.
                android.util.Log.w("LiveTvPlayerVM", "Catch-up list failed: ${e.javaClass.simpleName}")
                null
            }
            catchUpLoadedAtMs = if (programs != null) System.currentTimeMillis() else 0L
            _catchUp.update {
                if (it.channelId == channel.id) it.copy(isLoading = false, programs = programs ?: it.programs) else it
            }
        }
    }

    private val _dvr = MutableStateFlow(LiveDvrState())

    /** RCTV (owner, the DVR): live, or the channel's recording (and which part of it). */
    val dvr: StateFlow<LiveDvrState> = _dvr.asStateFlow()

    /**
     * RCTV (owner, the DVR): the recording that plays [channel] from [targetMs] on: from that minute,
     * up to now (at most [DVR_SEGMENT_MINUTES]; the next part follows when it ends). Null when the
     * provider's time can't be told or the address can't be made.
     */
    suspend fun dvrStart(channel: LiveTvChannel, targetMs: Long): LiveDvrStart? {
        if (channel.archiveDays <= 0) return null
        val zone = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { xtreamClient.serverZone(channel) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("LiveTvPlayerVM", "Catch-up time zone failed: ${e.javaClass.simpleName}")
            null
        } ?: return null
        val now = System.currentTimeMillis()
        val oldest = now - channel.archiveDays * DAY_MS
        val target = targetMs.coerceIn(oldest + MINUTE_MS, now)
        val segmentStart = (target / MINUTE_MS) * MINUTE_MS
        val minutes = ((now - segmentStart + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceIn(1, DVR_SEGMENT_MINUTES)
        val url = xtreamClient.timeshiftUrl(channel, segmentStart, minutes, zone) ?: return null
        return LiveDvrStart(
            url = url,
            startPositionMs = target - segmentStart,
            segmentStartMs = segmentStart,
            segmentEndMs = segmentStart + minutes * MINUTE_MS
        )
    }

    /** RCTV (owner, the DVR): the channel's recording now plays ([start]). */
    fun dvrPlayingRecording(channel: LiveTvChannel, start: LiveDvrStart) {
        _dvr.value = LiveDvrState(
            channelId = channel.id,
            recording = true,
            segmentStartMs = start.segmentStartMs,
            segmentEndMs = start.segmentEndMs
        )
    }

    /** RCTV (owner, the DVR): the channel plays live again. */
    fun dvrPlayingLive(channel: LiveTvChannel?) {
        _dvr.value = LiveDvrState(channelId = channel?.id)
    }

    /**
     * RCTV (owner, the DVR): what was on [channel] at [atMs]: from the guide, else from the
     * provider's recordings (the guide keeps only a little of the past).
     */
    fun programAt(channel: LiveTvChannel, atMs: Long): EpgProgram? {
        session.guide.value.programAt(channel, atMs)?.let { return it }
        val recorded = _catchUp.value.takeIf { it.channelId == channel.id }?.programs
            ?.firstOrNull { atMs >= it.startMs && atMs < it.endMs }
            ?: return null
        return EpgProgram(
            channelId = channel.epgChannelId ?: channel.id,
            title = recorded.title,
            description = recorded.description,
            startMs = recorded.startMs,
            endMs = recorded.endMs
        )
    }

    /** RCTV (owner): the address that plays a recorded program (null when it can't be made). */
    fun catchUpUrl(channel: LiveTvChannel, program: com.nuvio.tv.domain.model.LiveCatchUpProgram): String? =
        xtreamClient.catchUpUrl(channel, program)

    /**
     * RCTV: the More to Watch tab showed: its titles, picked again when what's on changes (once TMDB
     * has told what it is). The last ones stay shown while the new ones come.
     */
    fun ensureMoreToWatch(channel: LiveTvChannel? = null) {
        val state = _program.value
        if (state.isLoading) return
        val key = "${channel?.id}|${state.match?.itemId}|${state.program?.title}"
        if (_moreToWatch.value.key == key) return
        moreToWatchJob?.cancel()
        _moreToWatch.update { it.copy(key = key, isLoading = true) }
        moreToWatchJob = viewModelScope.launch {
            // RCTV (owner): sports on now: the channels with games and sports on, then the sport's
            // movies and series, then the rest.
            val sports = com.nuvio.tv.data.livetv.LiveMoreToWatch.isSports(channel, state.program)
            val feeds = if (sports) com.nuvio.tv.data.livetv.LiveMoreToWatch.sportFeedsOf(channel, state.program) else emptyList()
            val channels = if (!sports) emptyList() else try {
                val guide = session.guide.value
                val now = System.currentTimeMillis()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.nuvio.tv.data.livetv.LiveMoreToWatch.sportsChannels(
                        channels = guide.channels,
                        programAt = { guide.programAt(it, now) },
                        current = channel,
                        feeds = feeds
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _moreToWatch.update { if (it.key == key) it.copy(channels = channels) else it }
            val items = try {
                moreToWatchPicks.picks(state.match, state.program, feeds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _moreToWatch.update { if (it.key == key) it.copy(isLoading = false, items = items, channels = channels) else it }
        }
    }

    /**
     * RCTV (owner): a movie chosen in More to Watch, to play straight away (as any movie): its IMDb id
     * from TMDB; null when TMDB can't tell it (its page opens instead).
     */
    suspend fun moreToWatchMovie(meta: com.nuvio.tv.domain.model.MetaPreview): com.nuvio.tv.data.livetv.LiveVodTarget? {
        if (!meta.rawType.equals("movie", ignoreCase = true)) return null
        val tmdbId = meta.id.removePrefix("tmdb:").substringBefore(':').toIntOrNull() ?: return null
        val match = LiveProgramMatch(
            isMovie = true,
            tmdbId = tmdbId,
            name = meta.name,
            year = meta.releaseInfo?.take(4)?.toIntOrNull(),
            poster = meta.poster,
            backdrop = meta.background,
            overview = meta.description,
            rating = meta.imdbRating,
            genres = meta.genres
        )
        val imdb = try {
            liveProgramLibrary.imdbId(match)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return com.nuvio.tv.data.livetv.LiveVodTarget(match, imdb, logo = meta.logo)
    }

    /**
     * RCTV (owner): an episode chosen in the live player's Episodes: what plays it straight away from
     * its sources (as any episode), or null when its series has no IMDb id (then its page opens).
     */
    suspend fun episodeToPlay(
        match: LiveProgramMatch,
        episode: com.nuvio.tv.data.livetv.LiveEpisode
    ): com.nuvio.tv.data.livetv.LiveVodTarget? {
        if (match.isMovie) return null
        val onEpisode = match.copy(episode = episode)
        val imdb = try {
            liveProgramLibrary.imdbId(onEpisode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return com.nuvio.tv.data.livetv.LiveVodTarget(onEpisode, imdb)
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

    private val _vod = MutableStateFlow<com.nuvio.tv.data.livetv.LiveVodState?>(null)

    /**
     * RCTV: what's on now from its sources ("Watch From Beginning" when Left is pressed): its movie
     * or episode, and whether a source has it. Null: it can't be (not on TMDB, the episode unknown).
     */
    val vod: StateFlow<com.nuvio.tv.data.livetv.LiveVodState?> = _vod.asStateFlow()
    private var vodProgram: EpgProgram? = null
    private var vodJob: Job? = null

    /** RCTV: what's on now changed (or the channel did): it's looked up on TMDB and in the sources. */
    fun prepareVod(program: EpgProgram?) {
        if (program == vodProgram) return
        vodProgram = program
        vodJob?.cancel()
        _vod.value = null
        if (program == null || session.guide.value.isPreview) return
        vodJob = viewModelScope.launch {
            val target = liveVodFinder.target(program, VOD_TMDB_WAIT_MS) ?: return@launch
            liveVodFinder.status(target).collect { status ->
                _vod.value = com.nuvio.tv.data.livetv.LiveVodState(target, status)
            }
        }
    }

    /** The address to play a channel at (the sample guide's channels open the player's design view). */
    suspend fun playableUrl(channel: LiveTvChannel): String =
        if (session.guide.value.isPreview) PlayerDesignPreview.URL else session.playableUrl(channel)

    private companion object {
        /** How long the player waits before asking TMDB again for what's on (it didn't answer). */
        private val TITLE_RETRY_DELAYS_MS = longArrayOf(2_000L, 6_000L, 15_000L, 40_000L)

        const val TICK_MS = 20_000L
        /** How often the Catch Up tab asks the provider again (new programs get recorded). */
        const val CATCH_UP_REFRESH_MS = 5 * 60_000L
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60 * MINUTE_MS
        /** The longest piece of a recording asked for at once (the next piece follows). */
        const val DVR_SEGMENT_MINUTES = 240
        /** How long TMDB is waited for what's on now's movie or episode. */
        const val VOD_TMDB_WAIT_MS = 30_000L
    }
}

/** RCTV: how long the Episodes tab waits to learn the airing episode before showing the latest season. */
private const val AIRING_WAIT_MS = 4_000L
