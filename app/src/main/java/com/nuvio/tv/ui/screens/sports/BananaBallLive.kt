package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.data.remote.espn.BananaBallSource
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.data.trailer.YouTubeSearchResult
import com.nuvio.tv.data.trailer.YouTubeVideoSearch
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The Banana Ball games on now ([BananaBallLive]): live on the Sports pages whatever their schedule says. */
internal object BananaBallLiveIds {
    @Volatile
    var ids: Set<String> = emptySet()
}

/**
 * Banana Ball's schedule has no live scores (and often no start times), so a game is known to be
 * on when it's on TV (a program on the Live TV guide, ESPN, ESPN2...) or streaming live on YouTube
 * (the teams stream their games there). A game streaming on YouTube that no channel of the guide
 * is showing plays from YouTube, like a channel.
 */
@Singleton
class BananaBallLive @Inject constructor(
    private val guideLoader: LiveTvGuideLoader,
    private val youTubeSearch: YouTubeVideoSearch,
    private val youTubeExtractor: InAppYouTubeExtractor
) {
    private val mutex = Mutex()
    @Volatile private var lastCheckMs = 0L
    @Volatile private var lastYouTubeMs = 0L
    @Volatile private var liveStreams: List<YouTubeSearchResult> = emptyList()

    /**
     * Looks again (now and then: the guide each time, YouTube every few minutes) which of today's
     * Banana Ball games among [events] are on, for [BananaBallLiveIds].
     */
    suspend fun refresh(events: List<SportsEvent>) {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_EVERY_MS) return
        mutex.withLock {
            if (now - lastCheckMs < CHECK_EVERY_MS) return
            lastCheckMs = now
            val today = events.filter { it.isBananaBall() && isToday(it, now) && it.status != SportsEventStatus.FINISHED }
                .distinctBy { it.id }
            if (today.isEmpty()) {
                BananaBallLiveIds.ids = emptySet()
                return
            }
            // The guide (kept on the device) is what says what's on TV: loaded when it isn't yet.
            if (guideLoader.guide.value == null) guideLoader.prewarm()
            val onTv = today.filter { onGuideNow(it, now) }.map { it.id }.toSet()
            val notOnTv = today.filterNot { it.id in onTv }
            val streams = if (notOnTv.isNotEmpty()) liveStreamsNow(now) else emptyList()
            val onYouTube = notOnTv.filter { event -> streamFor(event, streams) != null }.map { it.id }.toSet()
            BananaBallLiveIds.ids = onTv + onYouTube
        }
    }

    /** The game's live stream on YouTube, ready to play (HLS); null when it isn't streaming. */
    suspend fun youTubeStreamFor(event: SportsEvent): String? {
        if (!event.isBananaBall()) return null
        val now = System.currentTimeMillis()
        val stream = streamFor(event, liveStreamsNow(now))
            ?: streamFor(event, liveStreamsNow(now, force = true))
            ?: return null
        return youTubeExtractor.extractLiveHlsUrl(stream.watchUrl)
    }

    private suspend fun onGuideNow(event: SportsEvent, now: Long): Boolean {
        val guide = guideLoader.guide.value ?: return false
        if (guide.isPreview || guide.channels.isEmpty()) return false
        return withContext(Dispatchers.Default) {
            SportsGameChannels.find(guide.channels, guide.epgByChannel, event, now).isNotEmpty()
        }
    }

    /** What's streaming live on YouTube for Banana Ball (asked again every few minutes). */
    private suspend fun liveStreamsNow(now: Long, force: Boolean = false): List<YouTubeSearchResult> {
        if (!force && now - lastYouTubeMs < YOUTUBE_EVERY_MS) return liveStreams
        lastYouTubeMs = now
        val found = try {
            QUERIES.flatMap { query -> youTubeSearch.search(query, liveOnly = true) }
                // A live stream has no length (a video does).
                .filter { it.durationSeconds == null }
                .distinctBy { it.videoId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            liveStreams
        }
        liveStreams = found
        return found
    }

    /** The stream of [event]: Banana Ball's, with its teams in the title (both first, one when that's all there is). */
    private fun streamFor(event: SportsEvent, streams: List<YouTubeSearchResult>): YouTubeSearchResult? {
        val bananaStreams = streams.filter { stream ->
            val text = normalized("${stream.title} ${stream.channel.orEmpty()}")
            BANANA_WORDS.any { it in text } || BananaBallTeams.all.any { team -> team in text }
        }
        if (bananaStreams.isEmpty()) return null
        // A tournament with several teams: Banana Ball's stream.
        if (event.homeTeamId == null && event.awayTeamId == null) return bananaStreams.first()
        val home = teamWords(event.homeTeamName, event.homeTeamAliases)
        val away = teamWords(event.awayTeamName, event.awayTeamAliases)
        val withBoth = bananaStreams.firstOrNull { stream ->
            val title = normalized(stream.title)
            home.any { it in title } && away.any { it in title }
        }
        if (withBoth != null) return withBoth
        return bananaStreams.firstOrNull { stream ->
            val title = normalized(stream.title)
            home.any { it in title } || away.any { it in title }
        }
    }

    private fun teamWords(name: String, aliases: List<String>): List<String> =
        (aliases + name).map(::normalized).filter { it.length >= 4 }.map { it.removePrefix("the ") }.distinct()

    private fun isToday(event: SportsEvent, now: Long): Boolean {
        val start = event.startTimeMs ?: return false
        val zone = ZoneId.of("America/New_York")
        val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        return day == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }

    private object BananaBallTeams {
        val all: List<String> = listOf("savannah bananas", "party animals", "firefighters", "texas tailgaters", "coconuts", "indianapolis clowns")
    }

    private companion object {
        const val CHECK_EVERY_MS = 60_000L
        const val YOUTUBE_EVERY_MS = 4L * 60 * 1000
        val QUERIES = listOf("Banana Ball live", "Savannah Bananas live")
        val BANANA_WORDS = listOf("banana ball", "bananaball", "bananas")

        fun normalized(text: String): String = SportsGameChannels.normalize(text)
    }
}

/** A game of Banana Ball (not on ESPN: its own schedule). */
internal fun SportsEvent.isBananaBall(): Boolean = leagueId == BananaBallSource.LEAGUE_ID
