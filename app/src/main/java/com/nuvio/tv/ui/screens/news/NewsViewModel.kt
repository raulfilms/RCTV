package com.nuvio.tv.ui.screens.news

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.data.livetv.LiveTvPlayerSession
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.screens.livetv.GuideBrowse
import com.nuvio.tv.ui.screens.livetv.LiveGuideSnapshot
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

/** One program on a news channel: on now ([live]) or later. */
@Immutable
data class NewsCard(
    val channel: LiveTvChannel,
    val program: EpgProgram,
    val live: Boolean
) {
    val key: String get() = "${channel.id}|${program.startMs}"
}

/** A filter on top of the page: a news channel, or a topic (political news, finance, crime...). */
@Immutable
data class NewsChip(
    val key: String,
    /** A channel's name, or null for a topic ([labelRes]). */
    val label: String?,
    @StringRes val labelRes: Int?
)

@Immutable
data class NewsChannelRow(val channel: LiveTvChannel, val cards: List<NewsCard>)

@Immutable
data class NewsUiState(
    val loading: Boolean = true,
    val chips: List<NewsChip> = emptyList(),
    /** The chip picked; null: all of News. */
    val selectedChip: String? = null,
    /** "Picked for you": the shows airing on the news channels (one card a show). */
    val picked: List<NewsCard> = emptyList(),
    /** "On now & upcoming": what's on now, then what starts soon. */
    val onNow: List<NewsCard> = emptyList(),
    val channelRows: List<NewsChannelRow> = emptyList(),
    /** TMDB's art and page for the programs, by [NewsViewModel.titleKey], found as the page shows. */
    val titles: Map<String, LiveProgramMatch> = emptyMap()
)

/**
 * RCTV (owner, like YouTube TV's News): Home's News opens this page. Everything comes from the
 * Guide's News & Information channels and their programs: chips to narrow it (the main news
 * channels and topics), the shows on them (Picked for you), what's on now and next, and a row per
 * news channel. The programs' art is TMDB's when the guide has none.
 */
@HiltViewModel
class NewsViewModel @Inject constructor(
    private val guideLoader: LiveTvGuideLoader,
    private val liveTvDataStore: LiveTvDataStore,
    private val programLibrary: LiveProgramLibrary,
    private val playerSession: LiveTvPlayerSession
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewsUiState())
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()

    private val lookups = Semaphore(LOOKUPS_AT_ONCE)
    private val asked = HashSet<String>()

    init {
        viewModelScope.launch {
            guideLoader.guide.collect { build() }
        }
        // What's on changes: again every minute.
        viewModelScope.launch {
            while (isActive) {
                delay(MINUTE_MS)
                build()
            }
        }
    }

    fun selectChip(key: String?) {
        if (_uiState.value.selectedChip == key) return
        _uiState.update { it.copy(selectedChip = key) }
        build()
    }

    /** A channel ready to play (some providers hand out its address only when asked). */
    suspend fun playable(channel: LiveTvChannel): LiveTvChannel {
        val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
        guideLoader.guide.value?.takeIf { !it.isPreview && it.channels.isNotEmpty() }?.let { guide ->
            if (playerSession.guide.value.channels.isEmpty()) {
                playerSession.publish(
                    LiveTvPlayerSession.Guide(
                        channels = guide.channels,
                        epgByChannel = guide.epgByChannel,
                        customChannelIds = liveTvDataStore.customChannelIds.value
                    )
                )
            }
        }
        return runCatching { channel.copy(streamUrl = guideLoader.resolvePlaybackUrl(channel, sources)) }.getOrDefault(channel)
    }

    private fun build() {
        val guide = guideLoader.guide.value?.takeIf { !it.isPreview && it.channels.isNotEmpty() }
        if (guide == null) {
            _uiState.update { it.copy(loading = guideLoader.guide.value?.isLoading != false) }
            return
        }
        val now = System.currentTimeMillis()
        val news = newsChannels(guide)
        val chips = chipsFor(news, guide, now)
        val selected = _uiState.value.selectedChip?.takeIf { key -> chips.any { it.key == key } }
        val shown = news.filter { channel -> selected == null || inChip(selected, channel, guide, now) }

        fun programsOf(channel: LiveTvChannel): List<EpgProgram> =
            guide.epgByChannel[channel.epgChannelId ?: channel.id].orEmpty()

        val onNowLive = shown.mapNotNull { channel ->
            programsOf(channel).firstOrNull { it.startMs <= now && it.endMs > now }?.let { NewsCard(channel, it, live = true) }
        }
        val upcoming = shown.flatMap { channel ->
            programsOf(channel).filter { it.startMs > now && it.startMs < now + UPCOMING_MS }.take(2).map { NewsCard(channel, it, live = false) }
        }.sortedBy { it.program.startMs }
        val onNow = (onNowLive + upcoming).take(ON_NOW_SIZE)

        // One card a show: its next airing (on now first), the channels in the Guide's order.
        val seenTitles = HashSet<String>()
        val picked = ArrayList<NewsCard>()
        for (channel in shown) {
            for (program in programsOf(channel)) {
                if (program.endMs <= now || program.startMs > now + PICKED_AHEAD_MS) continue
                val key = titleKey(program.title)
                if (key.length < 3 || key in GENERIC_TITLES || !seenTitles.add(key)) continue
                picked += NewsCard(channel, program, live = program.startMs <= now)
            }
        }
        val pickedSorted = picked.sortedBy { if (it.live) 0 else 1 }.take(PICKED_SIZE)

        val channelRows = shown.take(CHANNEL_ROWS).mapNotNull { channel ->
            val cards = programsOf(channel).filter { it.endMs > now }.take(CHANNEL_ROW_SIZE)
                .map { NewsCard(channel, it, live = it.startMs <= now) }
            cards.takeIf { it.isNotEmpty() }?.let { NewsChannelRow(channel, it) }
        }

        _uiState.update {
            it.copy(
                loading = false,
                chips = chips,
                selectedChip = selected,
                picked = pickedSorted,
                onNow = onNow,
                channelRows = channelRows
            )
        }
        lookUpTitles((pickedSorted + onNow + channelRows.flatMap { row -> row.cards }).map { card -> card.program })
    }

    /** TMDB's art and page for the shows on the page (a few at a time; each title once). */
    private fun lookUpTitles(programs: List<EpgProgram>) {
        val wanted = programs
            .distinctBy { titleKey(it.title) }
            .filter { titleKey(it.title) !in asked && titleKey(it.title) !in GENERIC_TITLES }
            .take(LOOKUPS_PER_BUILD)
        if (wanted.isEmpty()) return
        wanted.forEach { asked += titleKey(it.title) }
        viewModelScope.launch {
            wanted.forEach { program ->
                launch {
                    lookups.withPermit {
                        val match = try {
                            programLibrary.findTitle(program)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // TMDB couldn't be reached: asked again on a later look.
                            asked -= titleKey(program.title)
                            null
                        }
                        if (match != null) {
                            _uiState.update { state -> state.copy(titles = state.titles + (titleKey(program.title) to match)) }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ channels and chips

    private fun newsChannels(guide: LiveGuideSnapshot): List<LiveTvChannel> {
        val category = if (guide.curated) GuideBrowse.NEWS_INFO else GuideBrowse.NEWS
        return guide.channels.filter { category in guide.channelCategories[it.id].orEmpty() }
    }

    private fun chipsFor(news: List<LiveTvChannel>, guide: LiveGuideSnapshot, now: Long): List<NewsChip> {
        val topics = TOPICS.filter { topic -> news.any { channel -> inTopic(topic, channel, guide, now) } }
        val channels = MAIN_CHANNELS.mapNotNull { wanted ->
            news.firstOrNull { compact(it.name) == wanted || compact(it.name).startsWith(wanted) }
        }.distinctBy { it.id }.take(MAX_CHANNEL_CHIPS)
        val channelChips = channels.map { NewsChip(key = "ch:${it.id}", label = it.name, labelRes = null) }
        val topicChips = topics.map { NewsChip(key = "topic:${it.key}", label = null, labelRes = it.labelRes) }
        // Like YouTube TV's: Political news, the main channels, then the other topics.
        return topicChips.take(1) + channelChips + topicChips.drop(1)
    }

    private fun inChip(key: String, channel: LiveTvChannel, guide: LiveGuideSnapshot, now: Long): Boolean = when {
        key.startsWith("ch:") -> channel.id == key.removePrefix("ch:")
        key.startsWith("topic:") -> TOPICS.firstOrNull { it.key == key.removePrefix("topic:") }
            ?.let { inTopic(it, channel, guide, now) } == true
        else -> true
    }

    private fun inTopic(topic: Topic, channel: LiveTvChannel, guide: LiveGuideSnapshot, now: Long): Boolean {
        val name = compact(channel.name)
        if (topic.channelWords.any { it in name }) return true
        // Or what it airs these hours: its programs' categories.
        val programs = guide.epgByChannel[channel.epgChannelId ?: channel.id].orEmpty()
            .filter { it.endMs > now && it.startMs < now + PICKED_AHEAD_MS }
        return programs.isNotEmpty() && programs.count { program ->
            program.categories.any { category -> topic.programWords.any { it in category.lowercase(Locale.ROOT) } }
        } * 2 >= programs.size
    }

    private class Topic(
        val key: String,
        @StringRes val labelRes: Int,
        /** Words (compact: letters and digits) of channels that are this topic. */
        val channelWords: List<String>,
        /** Program categories (lower case) that are this topic. */
        val programWords: List<String>
    )

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val UPCOMING_MS = 3L * 60 * 60 * 1000
        private const val PICKED_AHEAD_MS = 24L * 60 * 60 * 1000
        private const val ON_NOW_SIZE = 24
        private const val PICKED_SIZE = 16
        private const val CHANNEL_ROWS = 12
        private const val CHANNEL_ROW_SIZE = 8
        private const val MAX_CHANNEL_CHIPS = 6
        private const val LOOKUPS_AT_ONCE = 4
        private const val LOOKUPS_PER_BUILD = 40

        /** The main news channels, as chips, in this order (compact names). */
        private val MAIN_CHANNELS = listOf(
            "foxnews", "cnn", "msnow", "msnbc", "cnbc", "newsmax", "newsnation", "abcnewslive", "cbsnews",
            "nbcnewsnow", "foxbusiness", "bbcnews", "bloomberg", "cspan"
        )

        private val TOPICS = listOf(
            Topic(
                "politics", R.string.news_chip_political,
                listOf("foxnews", "cnn", "msnow", "msnbc", "newsmax", "newsnation", "cspan", "oan", "oneamerica", "realamerica"),
                listOf("politic", "public affairs", "government")
            ),
            Topic(
                "finance", R.string.news_chip_finance,
                listOf("cnbc", "foxbusiness", "bloomberg", "yahoofinance", "cheddar"),
                listOf("business", "financ", "bus./financial", "economics")
            ),
            Topic(
                "entertainment", R.string.news_chip_entertainment,
                listOf("entertainmenttonight", "etlive", "tmz", "accesshollywood", "insideedition", "extratv"),
                listOf("entertainment news", "celebrit", "gossip", "hollywood")
            ),
            Topic(
                "crime", R.string.news_chip_crime,
                listOf("courttv", "crime", "lawandcrime", "investigation", "dateline", "48hours"),
                listOf("crime", "law", "court", "true crime")
            ),
            Topic(
                "weather", R.string.news_chip_weather,
                listOf("weather", "accuweather"),
                listOf("weather")
            )
        )

        /** Titles that aren't a show (nothing to pick or look up). */
        private val GENERIC_TITLES = setOf(
            "news", "noticias", "paidprogramming", "paidprogram", "programapagado", "tobeannounced", "tba",
            "seraanunciado", "offair", "signoff", "breakingnews", "newsupdate", "localnews", "weather"
        )

        /** "Hannity" → "hannity"; "FOX & Friends!" → "foxandfriends". */
        fun titleKey(title: String): String = compact(title)

        private fun compact(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace("&", "and").filter { it.isLetterOrDigit() }
    }
}
