@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import com.nuvio.tv.ui.screens.player.PlayerDesignPreview
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.nuvio.tv.ui.components.tileSurfaceBorder

/**
 * What a title's page does with its showings in the Live TV guide (a sports talk show's page): play
 * the channel showing it now, open the channel of a later showing. Provided only on the pages that
 * show "On Now & Upcoming".
 */
data class ShowAiringsActions(
    val onPlayChannel: (LiveTvChannel) -> Unit,
    val onOpenChannel: (LiveTvChannel) -> Unit
)

/** Set on a title's page that shows its showings in the guide ("On Now & Upcoming"). */
val LocalShowAiringsActions = staticCompositionLocalOf<ShowAiringsActions?> { null }

/** One showing of a show in the guide: the channel and the program. */
data class ShowAiring(val channel: LiveTvChannel, val program: EpgProgram) {
    val key: String get() = "${channel.id}|${program.startMs}"
}

data class ShowAiringsState(
    /** The guide (or its programs) is still loading. */
    val isLoading: Boolean = true,
    /** On now first, then the showings to come, in order. */
    val airings: List<ShowAiring> = emptyList(),
    /** The built-in sample guide (no IPTV source): its channels have no video. */
    val isPreviewGuide: Boolean = false
)

/**
 * Finds a show in the Live TV guide (the IPTV channels and their EPG): where it's on now and when
 * it's on next, across every channel. Updated each minute, and when the guide loads again.
 */
@HiltViewModel
class ShowAiringsViewModel @Inject constructor(
    private val guideLoader: LiveTvGuideLoader,
    private val liveTvDataStore: LiveTvDataStore
) : ViewModel() {

    private val showName = MutableStateFlow<String?>(null)

    private val minutes = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    val state: StateFlow<ShowAiringsState> = combine(guideLoader.guide, showName, minutes) { guide, name, now ->
        if (guide == null || name.isNullOrBlank()) return@combine ShowAiringsState(isLoading = true)
        val airings = findAirings(guide.channels, guide.epgByChannel, name, now)
        ShowAiringsState(
            isLoading = airings.isEmpty() && (guide.isLoading || guide.isEpgLoading),
            airings = airings,
            isPreviewGuide = guide.isPreview
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShowAiringsState())

    init {
        // The guide is usually loaded already (the app loads it at start); if not, now.
        viewModelScope.launch {
            val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList())
            guideLoader.ensure(sources.filter { it.enabled })
        }
    }

    fun setShow(name: String) {
        showName.value = name
    }

    /** The channel ready to play: its address right now (the sample guide's: the player's preview). */
    suspend fun playable(channel: LiveTvChannel): LiveTvChannel {
        if (state.value.isPreviewGuide) return channel.copy(streamUrl = PlayerDesignPreview.URL)
        val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
        return channel.copy(streamUrl = guideLoader.resolvePlaybackUrl(channel, sources))
    }

    companion object {
        private const val MAX_AIRINGS = 20

        // Built once (they run for every program of the guide that might be the show).
        private val MARKS = Regex("\\p{Mn}+")
        private val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val SPACES = Regex("\\s+")
        private val CHANNEL_TAGS = Regex("\\b(us|usa|hd|fhd|uhd|sd|4k|hevc|h265|backup|east|west)\\b")

        /** "The Pat McAfee Show" → "pat mcafee show": no accents, punctuation, case or leading "the". */
        internal fun normalize(title: String): String {
            val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(MARKS, "")
            return plain.lowercase(Locale.ROOT)
                .replace(NOT_ALPHANUMERIC, " ")
                .trim()
                .removePrefix("the ")
                .replace(SPACES, " ")
        }

        /** The guide's title is the show ("First Take", "LIVE: First Take", "First Take: Hour 2"). */
        internal fun isShow(programTitle: String, show: String): Boolean {
            var title = normalize(programTitle)
            listOf("live ", "new ", "en vivo ").forEach { prefix -> title = title.removePrefix(prefix) }
            title = title.removePrefix("the ")
            return title == show || title.startsWith("$show ")
        }

        /** A channel's name without its quality or country tags ("US: ESPN FHD" → "espn"). */
        private fun baseChannelName(name: String): String =
            normalize(name)
                .replace(CHANNEL_TAGS, " ")
                .replace(SPACES, " ")
                .trim()

        internal fun findAirings(
            channels: List<LiveTvChannel>,
            epgByChannel: Map<String, List<EpgProgram>>,
            name: String,
            now: Long
        ): List<ShowAiring> {
            val show = normalize(name)
            if (show.isBlank()) return emptyList()
            val firstWord = show.substringBefore(' ')
            val found = ArrayList<ShowAiring>()
            // Same program on the same guide entry (several copies of a channel): once.
            val seen = HashSet<String>()
            channels.forEach { channel ->
                val programs = epgByChannel[channel.epgChannelId ?: channel.id] ?: return@forEach
                programs.forEach { program ->
                    if (program.endMs <= now) return@forEach
                    if (!program.title.contains(firstWord, ignoreCase = true) && firstWord.length > 2) return@forEach
                    if (!isShow(program.title, show)) return@forEach
                    val sameShowing = "n:${baseChannelName(channel.name)}|${program.startMs}"
                    val sameEntry = "e:${channel.epgChannelId ?: channel.id}|${program.startMs}"
                    if (!seen.add(sameEntry) || !seen.add(sameShowing)) return@forEach
                    found += ShowAiring(channel, program)
                }
            }
            return found
                .sortedWith(compareBy<ShowAiring>({ !it.program.isAiringAt(now) }, { it.program.startMs }))
                .take(MAX_AIRINGS)
        }
    }
}

/**
 * "On Now & Upcoming": where the show is on now and when it's on next in the Live TV guide, one card
 * per showing (the channel's logo, LIVE or the time, the channel). On now plays the channel; later
 * opens the channel's page.
 */
@Composable
fun ShowAiringsRow(
    showName: String,
    fallbackImageUrl: String?,
    actions: ShowAiringsActions,
    rowFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onHasCards: (Boolean) -> Unit,
    viewModel: ShowAiringsViewModel = hiltViewModel()
) {
    LaunchedEffect(showName) { viewModel.setShow(showName) }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val firstCardRequester = remember { FocusRequester() }
    val hasCards = state.airings.isNotEmpty()
    LaunchedEffect(hasCards) { onHasCards(hasCards) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.detail_on_now_upcoming),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.sm)
        )
        if (!hasCards) {
            Text(
                text = stringResource(if (state.isLoading) R.string.detail_airings_loading else R.string.detail_airings_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
            )
            return@Column
        }
        val now = System.currentTimeMillis()
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(rowFocusRequester)
                .focusRestorer { firstCardRequester }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            itemsIndexed(state.airings, key = { _, airing -> airing.key }) { index, airing ->
                val isOnNow = airing.program.isAiringAt(now)
                AiringCard(
                    airing = airing,
                    isOnNow = isOnNow,
                    now = now,
                    fallbackImageUrl = fallbackImageUrl,
                    onClick = {
                        if (isOnNow) {
                            scope.launch { actions.onPlayChannel(viewModel.playable(airing.channel)) }
                        } else {
                            actions.onOpenChannel(airing.channel)
                        }
                    },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstCardRequester) else Modifier)
                        .focusProperties { if (upFocusRequester != null) up = upFocusRequester }
                )
            }
        }
    }
}

/** A showing: the program's picture (or the show's), the channel's logo, LIVE or when, the channel. */
@Composable
private fun AiringCard(
    airing: ShowAiring,
    isOnNow: Boolean,
    now: Long,
    fallbackImageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val program = airing.program
    val channel = airing.channel
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = Modifier.width(AiringCardWidth)) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(AiringCardHeight),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard
            ),
            // RCTV: the white outline every tile has while focused (see TileFocus.kt).
            border = tileSurfaceBorder(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val image = program.imageUrl ?: fallbackImageUrl
                if (!image.isNullOrBlank()) {
                    AsyncImage(
                        model = image,
                        contentDescription = program.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                )
                // The channel's logo.
                if (!channel.logoUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(22.dp)
                                .width(56.dp)
                        )
                    }
                }
                if (isOnNow) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(LiveRed)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.sports_live).uppercase(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // How far along it is.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(program.progressAt(now))
                                .height(4.dp)
                                .background(LiveRed)
                        )
                    }
                }
                Text(
                    text = program.subtitle?.takeIf { it.isNotBlank() } ?: program.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = airingTime(program, isOnNow),
            color = if (isOnNow) Color.White else NuvioTheme.colors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = channel.name,
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "On now · until 12:00 PM", "Today, 7:00 PM", "Tomorrow, 9:00 AM", "Sat, Oct 18 • 9:00 AM". */
@Composable
private fun airingTime(program: EpgProgram, isOnNow: Boolean): String {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    if (isOnNow) return stringResource(R.string.detail_airing_on_now, timeFormat.format(Date(program.endMs)))
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(program.startMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = timeFormat.format(Date(program.startMs))
    return when (day) {
        today -> stringResource(R.string.livetv_details_today, time)
        today.plusDays(1) -> stringResource(R.string.livetv_details_tomorrow, time)
        else -> SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(program.startMs))
    }
}

private val AiringCardWidth = 260.dp
private val AiringCardHeight = 146.dp
private val LiveRed = Color(0xFFE5202E)
