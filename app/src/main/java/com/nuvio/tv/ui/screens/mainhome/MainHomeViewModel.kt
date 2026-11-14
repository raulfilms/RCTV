package com.nuvio.tv.ui.screens.mainhome

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.rctvcontrol.RctvBanner
import com.nuvio.tv.core.rctvcontrol.RctvCollection
import com.nuvio.tv.core.rctvcontrol.RctvHomeBanners
import com.nuvio.tv.core.rctvcontrol.RctvPublicFiles
import com.nuvio.tv.data.discover.DiscoverCategory
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.data.livetv.LiveTvPlayerSession
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.ui.screens.livetv.GuideBrowse
import com.nuvio.tv.ui.screens.livetv.LiveGuideSnapshot
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import com.nuvio.tv.ui.screens.search.SportsSearchIndex
import com.nuvio.tv.ui.screens.sports.isLiveAt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One card of "Live Now": a game on now, or a channel and what's on it. */
@Immutable
sealed class LiveNowCard {
    abstract val key: String

    data class Game(val event: SportsEvent) : LiveNowCard() {
        override val key get() = "game_${event.id}"
    }

    data class Channel(val channel: LiveTvChannel, val program: EpgProgram?) : LiveNowCard() {
        override val key get() = "channel_${channel.id}"
    }
}

/** RCTV (owner, like YouTube TV's Home): a Guide category's channels and what's on each right now. */
@Immutable
data class LiveCategoryRow(val category: GuideBrowse, val cards: List<LiveNowCard.Channel>)

/** One slide of the banner at the top: something on now, a title, or a website banner. */
@Immutable
sealed class HeroSlide {
    abstract val key: String

    data class Live(val card: LiveNowCard) : HeroSlide() {
        override val key get() = "live_${card.key}"
    }

    data class Title(val item: MetaPreview) : HeroSlide() {
        override val key get() = "title_${item.id}"
    }

    data class Banner(val banner: RctvBanner) : HeroSlide() {
        override val key get() = "banner_${banner.id}"
    }
}

@Immutable
data class MainHomeUiState(
    val hero: List<HeroSlide> = emptyList(),
    val liveNow: List<LiveNowCard> = emptyList(),
    val recommended: List<MetaPreview> = emptyList(),
    val trending: List<MetaPreview> = emptyList(),
    val recentlyAdded: List<MetaPreview> = emptyList(),
    val favoriteNetworks: List<LiveNowCard.Channel> = emptyList(),
    /** RCTV: a row per Guide category with its channels live now (Entertainment, News, Sports...). */
    val liveCategories: List<LiveCategoryRow> = emptyList(),
    val popular: List<MetaPreview> = emptyList(),
    val featuredCollection: RctvCollection? = null,
    /** The website's banners for this Home, by the row they go after ([MainHomeRows]). */
    val banners: Map<String, List<RctvBanner>> = emptyMap(),
    val withLiveTv: Boolean = true
)

/**
 * RCTV (owner): the whole app's Home: what's on Live TV and in Sports now, Continue Watching (Movies
 * & TV's own), TMDB's trending, newest and most popular movies and series, the profile's favorite
 * channels, a featured collection and the website's banners for it. Movies & TV keeps the Home that
 * was (its rows of movies and series).
 */
@HiltViewModel
class MainHomeViewModel @Inject constructor(
    private val guideLoader: LiveTvGuideLoader,
    private val liveTvDataStore: LiveTvDataStore,
    private val sportsIndex: SportsSearchIndex,
    private val tmdb: TmdbDiscoverRepository,
    private val publicFiles: RctvPublicFiles,
    private val homeBanners: RctvHomeBanners,
    private val playerSession: LiveTvPlayerSession,
    private val experienceUpdater: com.nuvio.tv.core.rctvcontrol.RctvExperienceUpdater,
    private val titleArtService: com.nuvio.tv.core.tmdb.TmdbTitleArtService
) : ViewModel() {

    /**
     * RCTV (owner): the title art of Home's title cards (TMDB's backdrop with the title on it, or
     * the title's logo over the backdrop), as on Movies & TV. Not for live channels.
     */
    val titleArt: com.nuvio.tv.domain.repository.TitleArtResolver get() = titleArtService

    private val _uiState = MutableStateFlow(MainHomeUiState())
    val uiState: StateFlow<MainHomeUiState> = _uiState.asStateFlow()

    private var recommendedFor: String? = null

    // Before init: its collectors can run at once and use them.
    private var heroBanners: List<RctvBanner> = emptyList()
    private var heroTitles: List<MetaPreview> = emptyList()

    init {
        viewModelScope.launch { loadTitles() }
        viewModelScope.launch {
            val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
            if (sources.isNotEmpty()) guideLoader.ensure(sources)
            _uiState.update { it.copy(withLiveTv = sources.isNotEmpty()) }
        }
        // Live Now, Favorite Networks and the banner: again every minute (what's on changes).
        viewModelScope.launch {
            combine(guideLoader.guide, liveTvDataStore.customChannelIds, sportsIndex.state) { guide, favorites, sports ->
                Triple(guide, favorites, sports.games.map { it.event })
            }.collect { (guide, favorites, games) -> refreshLive(guide, favorites, games) }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(MINUTE_MS)
                refreshLive(guideLoader.guide.value, liveTvDataStore.customChannelIds.value, sportsIndex.state.value.games.map { it.event })
            }
        }
        viewModelScope.launch {
            combine(homeBanners.visible, publicFiles.collections) { banners, collections -> banners to collections }
                .collect { (banners, collections) ->
                    val featured = collections.firstOrNull { it.items.isNotEmpty() }
                    val mine = banners.filter { it.afterRow.startsWith(MainHomeRows.PREFIX) }
                    _uiState.update { state ->
                        state.copy(
                            featuredCollection = featured,
                            banners = mine.filter { it.afterRow != MainHomeRows.HERO }.groupBy { it.afterRow }
                        ).withHero(mine.filter { it.afterRow == MainHomeRows.HERO })
                    }
                }
        }
        sportsIndex.ensure()
        // RCTV ("Update Your Experience"): TMDB's rows asked again.
        viewModelScope.launch {
            experienceUpdater.updates.drop(1).collect { loadTitles() }
        }
    }

    /** "Recommended for You": like the title watched last ([contentId]); TMDB's best rated when there's none. */
    fun recommendFor(contentId: String?, isSeries: Boolean) {
        val key = contentId ?: ""
        if (key == recommendedFor) return
        recommendedFor = key
        viewModelScope.launch {
            val language = tmdb.language()
            val liked = if (contentId != null) {
                runCatching { tmdb.recommendationsFor(contentId, isSeries, language) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val list = liked.ifEmpty { both(DiscoverCategory.TOP_RATED, language) }
            _uiState.update { it.copy(recommended = list.take(ROW_SIZE)) }
        }
    }

    /**
     * RCTV (owner): the channel the app was left playing (the Home button, the TV turned off), to
     * play again as the app opens: once per app start, within [RESUME_MAX_AGE_MS], when the Guide
     * still has it. Nothing is kept running for it while the app is closed.
     */
    suspend fun resumeChannel(): LiveTvChannel? {
        if (resumeAsked) return null
        resumeAsked = true
        val id = runCatching { liveTvDataStore.takeResumeChannel(RESUME_MAX_AGE_MS) }.getOrNull() ?: return null
        val guide = kotlinx.coroutines.withTimeoutOrNull(RESUME_GUIDE_WAIT_MS) {
            guideLoader.guide.first { it != null && !it.isPreview && it.channels.isNotEmpty() }
        } ?: return null
        val channel = guide.channels.firstOrNull { it.id == id } ?: return null
        return playable(channel)
    }

    /** A channel ready to play (some providers hand out its address only when asked). */
    suspend fun playable(channel: LiveTvChannel): LiveTvChannel {
        val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
        // The player's Networks row: the Guide's channels.
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

    private suspend fun loadTitles() {
        try {
            val language = tmdb.language()
            coroutineScope {
                val trending = async { both(DiscoverCategory.TRENDING, language) }
                val recent = async { both(DiscoverCategory.NEW, language) }
                val popular = async { both(DiscoverCategory.POPULAR, language) }
                val trendingList = trending.await()
                _uiState.update { it.copy(trending = trendingList.take(ROW_SIZE)).withHero(null, trendingList) }
                _uiState.update { it.copy(recentlyAdded = recent.await().take(ROW_SIZE), popular = popular.await().take(ROW_SIZE)) }
                // The banner's titles with their logos (as Movies & TV's banner shows them).
                val withLogos = titleArtService.withLogos(trendingList.take(HERO_TITLES))
                _uiState.update { it.withHero(null, withLogos + trendingList.drop(HERO_TITLES)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The rows stay out; the rest of Home shows.
        }
    }

    /** Movies and series of [category], one of each in turn, the ones with a picture. */
    private suspend fun both(category: DiscoverCategory, language: String): List<MetaPreview> = coroutineScope {
        val movies = async { runCatching { tmdb.page(TmdbCollectionMediaType.MOVIE, category, null, null, 1, language).items }.getOrDefault(emptyList()) }
        val series = async { runCatching { tmdb.page(TmdbCollectionMediaType.TV, category, null, null, 1, language).items }.getOrDefault(emptyList()) }
        val m = movies.await().filter { it.background != null }
        val s = series.await().filter { it.background != null }
        buildList {
            for (i in 0 until maxOf(m.size, s.size)) {
                m.getOrNull(i)?.let(::add)
                s.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.id }
    }

    private fun refreshLive(guide: LiveGuideSnapshot?, favorites: Set<String>, games: List<SportsEvent>) {
        val now = System.currentTimeMillis()
        val liveGames = games.filter { it.isLiveAt(now) }.sortedBy { it.startTimeMs ?: Long.MAX_VALUE }.take(MAX_GAMES)
        val usable = guide?.takeIf { !it.isPreview && it.channels.isNotEmpty() }
        fun cardOf(channel: LiveTvChannel) = LiveNowCard.Channel(
            channel,
            usable?.epgByChannel?.get(channel.epgChannelId ?: channel.id)?.firstOrNull { it.startMs <= now && it.endMs > now }
        )
        val favoriteCards = usable?.channels.orEmpty().filter { it.id in favorites }.map(::cardOf)
        // The Guide's first channels (ABC, NBC, FOX, CBS and CW lead it).
        val networks = usable?.channels.orEmpty().take(TOP_NETWORKS).filter { it.id !in favorites }.map(::cardOf)
        val liveNow = (liveGames.map { LiveNowCard.Game(it) } + favoriteCards + networks)
            .distinctBy { it.key }
            .take(ROW_SIZE)
        val liveCategories = usable?.let { snapshot ->
            liveCategoryRows(snapshot) { channel -> cardOf(channel) }
        }.orEmpty()
        _uiState.update { state ->
            state.copy(
                liveNow = liveNow,
                favoriteNetworks = favoriteCards.take(ROW_SIZE),
                liveCategories = liveCategories
            ).withHero(null, null)
        }
    }

    /**
     * RCTV (owner, like YouTube TV's Home): each Guide category's channels, the ones with something
     * on now first (in the Guide's order), at most [ROW_SIZE] a row. The owner's categories with his
     * list; another provider's guide, the usual ones. A Kids profile's guide has only its own.
     */
    private fun liveCategoryRows(
        guide: LiveGuideSnapshot,
        cardOf: (LiveTvChannel) -> LiveNowCard.Channel
    ): List<LiveCategoryRow> {
        val categories = if (guide.curated) {
            GuideBrowse.CURATED_CATEGORIES.filter { it != GuideBrowse.INTERNATIONAL }
        } else {
            OTHER_PROVIDER_CATEGORIES
        }
        return categories.mapNotNull { category ->
            val cards = guide.channels.asSequence()
                .filter { category in guide.channelCategories[it.id].orEmpty() }
                .map(cardOf)
                .toList()
                .sortedBy { if (it.program != null) 0 else 1 }
                .take(ROW_SIZE)
            cards.takeIf { it.isNotEmpty() }?.let { LiveCategoryRow(category, it) }
        }
    }

    /**
     * The banner at the top: the website's banners for it first, then what's on now (games first)
     * and TMDB's trending titles, mixed, at most [HERO_SIZE].
     */
    private fun MainHomeUiState.withHero(banners: List<RctvBanner>?, titles: List<MetaPreview>? = null): MainHomeUiState {
        if (banners != null) heroBanners = banners
        if (titles != null) heroTitles = titles
        val live = liveNow.take(3).map { HeroSlide.Live(it) }
        val featured = heroTitles.take(HERO_TITLES).map { HeroSlide.Title(it) }
        val mixed = buildList {
            addAll(heroBanners.map { HeroSlide.Banner(it) })
            for (i in 0 until maxOf(live.size, featured.size)) {
                live.getOrNull(i)?.let(::add)
                featured.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.key }.take(HERO_SIZE)
        return copy(hero = mixed)
    }

    private companion object {
        /** Asked once per app start (not every time Home shows). */
        @Volatile var resumeAsked = false
        const val RESUME_MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val RESUME_GUIDE_WAIT_MS = 10_000L

        const val MINUTE_MS = 60_000L
        const val ROW_SIZE = 20
        /** Another provider's guide: its usual categories with a live row each. */
        val OTHER_PROVIDER_CATEGORIES = listOf(
            GuideBrowse.NEWS, GuideBrowse.SPORTS, GuideBrowse.TV_SHOWS, GuideBrowse.MOVIES, GuideBrowse.KIDS, GuideBrowse.LATINO
        )
        const val MAX_GAMES = 8
        const val TOP_NETWORKS = 5
        const val HERO_SIZE = 8
        /** Titles in the banner (with live channels and the website's banners). */
        const val HERO_TITLES = 4
    }
}

/** The new Home's rows, as the website's banners name the row they go after. */
object MainHomeRows {
    const val PREFIX = "main_"
    const val HERO = "main_hero"
    const val LIVE_NOW = "main_live_now"
    const val CONTINUE_WATCHING = "main_continue_watching"
    const val RECOMMENDED = "main_recommended"
    const val CATEGORIES = "main_categories"
    const val TRENDING = "main_trending"
    const val RECENTLY_ADDED = "main_recently_added"
    const val FAVORITE_NETWORKS = "main_favorite_networks"
    const val POPULAR = "main_popular"
    const val FEATURED_COLLECTION = "main_featured_collection"
}
