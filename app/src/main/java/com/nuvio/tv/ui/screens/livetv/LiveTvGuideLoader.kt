package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.core.network.isTlsCertificateFailure
import android.content.Context
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.data.remote.stalker.StalkerClient
import com.nuvio.tv.data.remote.xtream.XtreamClient
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.ui.util.M3uPlaylistParser
import com.nuvio.tv.ui.util.XmltvGuide
import com.nuvio.tv.ui.util.XmltvParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The Guide's channels and programs as loaded for a set of sources (or the sample guide), shared
 * by every screen that shows them (the Guide, a channel's page, the Live TV player).
 */
data class LiveGuideSnapshot(
    /** Which sources this is for (see [LiveTvGuideLoader.keyOf]). */
    val key: String,
    /** The built-in sample guide (no IPTV source added). */
    val isPreview: Boolean,
    /** Loading with nothing to show yet. */
    val isLoading: Boolean,
    /** The channels are in; their programs are still loading. */
    val isEpgLoading: Boolean = false,
    val channels: List<LiveTvChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val epgByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    val channelCategories: Map<String, Set<GuideBrowse>> = emptyMap(),
    /** Why nothing (or not everything) could load. */
    val error: String? = null,
    /** When it was loaded (0 while loading for the first time). */
    val loadedAtMs: Long = 0L,
    /**
     * RCTV (owner's channel list): channels not in the Guide: leagues, game passes and events
     * (Sports finds games and fights in them) and the other versions of the Guide's channels.
     */
    val hiddenChannels: List<LiveTvChannel> = emptyList(),
    /** RCTV: sorted by the owner's channel list (US and Puerto Rico; other countries in International). */
    val curated: Boolean = false
) {
    /** The Guide's channels and the leagues' and events' (not the versions): what Sports looks through. */
    val allChannels: List<LiveTvChannel>
        get() = if (hiddenChannels.isEmpty()) channels else channels + hiddenChannels.filter { it.versionOf == null }
}

/**
 * Loads the Guide once for the whole app and keeps it: opening the Guide or a channel's page (from
 * Home's Top Channels, say) shows what's loaded right away instead of downloading and reading the
 * playlists and the guide again each time. It's loaded in the background shortly after the app
 * starts ([prewarm]), refreshed in the background once it's a couple of hours old, and loaded again
 * when the sources change or on Retry. The last guide loaded is also kept on the device, so after
 * the app restarts it shows at once while the new one loads.
 */
@Singleton
class LiveTvGuideLoader @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveTvDataStore: LiveTvDataStore,
    private val xtreamClient: XtreamClient,
    private val stalkerClient: StalkerClient,
    @Named("addon") private val okHttpClient: OkHttpClient,
    /** RCTV, Kids profiles: only family and kids channels. */
    private val kidsGate: com.nuvio.tv.core.kids.KidsContentGate
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _guide = MutableStateFlow<LiveGuideSnapshot?>(null)

    /**
     * The guide last loaded (or loading), for the sources in its [LiveGuideSnapshot.key]. On a Kids
     * profile only its family and kids channels are in it (the Guide, a channel's page, the player's
     * channel up / down, Search, Home's Top Channels all read it).
     */
    val guide: StateFlow<LiveGuideSnapshot?> = kotlinx.coroutines.flow.combine(_guide, kidsGate.active) { g, kids ->
        if (kids) g?.kidsOnly() else g
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    init {
        // RCTV (MEM-09): the guide's size and state in the session notes (counts only).
        com.nuvio.tv.core.diagnostics.SessionTrail.guideProbe = {
            _guide.value?.let { g ->
                val programs = g.epgByChannel.values.sumOf { it.size }
                val state = when {
                    g.isLoading || g.isEpgLoading -> "loading"
                    g.loadedAtMs > 0 -> "loaded ${(System.currentTimeMillis() - g.loadedAtMs) / 60_000} min ago"
                    else -> "not loaded"
                }
                "${g.channels.size} channels, $programs programs, $state"
            }
        }
    }

    private var loadJob: Job? = null
    private var loadKey: String? = null

    /** Stalker sessions by source id, so loads and playback don't handshake with a portal every time. */
    private val stalkerSessions = ConcurrentHashMap<String, StalkerClient.Session>()

    /** Which sources a guide is for: the sample guide when there are none. */
    fun keyOf(sources: List<LiveTvSource>): String =
        if (sources.isEmpty()) PREVIEW_KEY else sources.joinToString("|") { "${it.id}#${it.connection.hashCode()}" }

    /** Loads the guide of the saved sources in the background, a moment after the app starts. */
    fun prewarm() {
        scope.launch {
            delay(PREWARM_DELAY_MS)
            val sources = try {
                liveTvDataStore.sources.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            ensure(sources.filter { it.enabled })
        }
    }

    /**
     * Makes sure the guide for [sources] (the sample guide when empty) is loaded: nothing to do when
     * it's there and recent; loaded when there's none (or it failed); refreshed in the background,
     * still showing, once it's old; loaded again when [force]d (Retry).
     */
    fun ensure(sources: List<LiveTvSource>, force: Boolean = false) {
        val key = keyOf(sources)
        synchronized(lock) {
            val current = _guide.value?.takeIf { it.key == key }
            val running = loadJob?.isActive == true && loadKey == key
            if (!force) {
                if (running) return
                if (current != null && current.isUsable() && !current.isStale()) return
                // RCTV: an old guide isn't read again while a video plays (a big guide takes a TV
                // box's processor and memory for a while, and the picture could stutter): it's
                // refreshed the next time it's asked for after that; the one loaded keeps showing.
                if (current != null && current.isUsable() &&
                    com.nuvio.tv.core.recommendations.TvRecommendationManager.isPlaybackActive.value
                ) return
            }
            loadJob?.cancel()
            loadKey = key
            val fromDevice = !force && (current == null || !current.isUsable())
            if (current == null || !current.isUsable()) {
                _guide.value = LiveGuideSnapshot(key = key, isPreview = sources.isEmpty(), isLoading = true)
            }
            loadJob = scope.launch {
                if (fromDevice) {
                    // The guide kept on the device shows at once; it's loaded again only when old.
                    val saved = com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-read") { readSaved(key) }
                    if (saved != null) {
                        // RCTV: kept without its programs (the app closed before they came): they're
                        // on their way now, and the Guide says so.
                        publish(key, if (saved.isStale() && saved.epgByChannel.isEmpty()) saved.copy(isEpgLoading = true) else saved)
                        if (!saved.isStale()) return@launch
                    }
                }
                if (sources.isEmpty()) loadPreview(key) else loadSources(key, sources)
            }
        }
    }

    /** Forgets the Stalker sessions (the sources were disconnected). */
    fun clearSessions() {
        stalkerSessions.clear()
    }

    /**
     * A channel's playable address right before it plays: a Stalker portal hands out short-lived
     * ones (the channel holds the portal's token); any other channel's own address as it is.
     */
    suspend fun resolvePlaybackUrl(channel: LiveTvChannel, sources: List<LiveTvSource>): String {
        val sourceId = channel.sourceId ?: return channel.streamUrl
        val connection = sources.firstOrNull { it.id == sourceId }?.connection
        if (connection !is LiveTvConnection.Stalker) return channel.streamUrl
        return withContext(Dispatchers.IO) {
            runCatching {
                val session = stalkerSessions.getOrPut(sourceId) { stalkerClient.authenticate(connection) }
                stalkerClient.resolveStreamUrl(session, channel.streamUrl) ?: channel.streamUrl
            }.getOrDefault(channel.streamUrl)
        }
    }

    private fun LiveGuideSnapshot.isUsable(): Boolean = !isLoading && channels.isNotEmpty()

    private fun LiveGuideSnapshot.isStale(): Boolean =
        System.currentTimeMillis() - loadedAtMs > REFRESH_AFTER_MS

    /** Publishes [snapshot] unless another load took over meanwhile. */
    private fun publish(key: String, snapshot: LiveGuideSnapshot) {
        synchronized(lock) {
            if (loadKey == key) {
                _guide.value = snapshot
                // RCTV: the player turns to a channel's other version when the one playing fails.
                LiveTvChannelVersions.update(snapshot.channels, snapshot.hiddenChannels)
            }
        }
        // RCTV (Phase 2, F11): Search's index of an older guide isn't kept beside this one.
        if (snapshot.channels.isNotEmpty()) {
            com.nuvio.tv.ui.screens.search.GuideSearchCache.forgetOtherThan(snapshot.channels, snapshot.epgByChannel)
        }
    }

    // ------------------------------------------------------------------ The sample guide

    /**
     * The sample guide, for trying the Guide without an IPTV account: channels, logos and programs
     * all come from a public XMLTV feed ([PREVIEW_EPG_URL]).
     */
    private suspend fun loadPreview(key: String) {
        val nowMs = System.currentTimeMillis()
        val job = coroutineContext[Job]
        ChannelLogoLibrary.ensureReady(appContext)
        val result = runCatching {
            val request = Request.Builder().url(PREVIEW_EPG_URL).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response")
                com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-epg") { XmltvParser.parseStream(
                    input = body.byteStream(),
                    fromMs = nowMs - EPG_WINDOW_PAST_MS,
                    toMs = nowMs + EPG_WINDOW_AHEAD_MS,
                    includeChannels = true,
                    shouldContinue = { job?.isActive != false }
                ) }
            }
        }
        if (job?.isActive == false) return
        result
            .onSuccess { guide ->
                val snapshot = previewSnapshot(key, guide)
                publish(key, snapshot)
                if (snapshot.channels.isNotEmpty()) com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-save") { save(snapshot) }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                val current = _guide.value?.takeIf { it.key == key && it.channels.isNotEmpty() }
                publish(
                    key,
                    current?.copy(isLoading = false)
                        ?: LiveGuideSnapshot(
                            key = key,
                            isPreview = true,
                            isLoading = false,
                            error = error.message ?: error.javaClass.simpleName
                        )
                )
            }
    }

    private fun previewSnapshot(key: String, guide: XmltvGuide): LiveGuideSnapshot {
        val epg = guide.programs
            .groupBy { "$PREVIEW_SOURCE_ID:${it.channelId}" }
            .mapValues { (_, list) -> list.sortedBy { it.startMs } }
        val channels = guide.channels.map { channel ->
            val id = "$PREVIEW_SOURCE_ID:${channel.id}"
            // "US - FOX News Detroit" -> "FOX News Detroit": the country tag adds nothing here.
            val name = LiveTvViewModel.stripCountryTag(channel.displayName)
            LiveTvChannel(
                id = id,
                name = name,
                streamUrl = "",
                // The app's logo when it has one for this channel, else the guide's own.
                logoUrl = ChannelLogoLibrary.logoUriFor(name, channel.number) ?: channel.iconUrl,
                epgChannelId = id,
                // The sample feed is EPGTalk's US guide.
                country = "US",
                number = channel.number
            )
        }
        // Keep the feed's lineup order; drop channels with nothing in the guide's window, and the
        // sample feed's adult-only channels.
        val withPrograms = channels.filter { channel ->
            val programs = epg[channel.id]
            !programs.isNullOrEmpty() && !LiveTvGuideCategories.isAdultChannel(programs)
        }
        val shown = withPrograms.ifEmpty { channels }
        return LiveGuideSnapshot(
            key = key,
            isPreview = true,
            isLoading = false,
            channels = shown,
            epgByChannel = epg,
            channelCategories = categorize(shown, epg),
            error = if (channels.isEmpty()) "No channels found in the sample guide." else null,
            loadedAtMs = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------------ IPTV sources

    /** Every enabled source's channels (a source that fails doesn't blank out the others), then their programs. */
    private suspend fun loadSources(key: String, sources: List<LiveTvSource>) {
        val previous = _guide.value?.takeIf { it.key == key && it.channels.isNotEmpty() }
        ChannelLogoLibrary.ensureReady(appContext)
        val results = sources.map { source -> source to loadSourceChannels(source) }
        val loadedChannels = results.flatMap { (_, result) -> result.getOrDefault(emptyList()) }
        // RCTV (owner's channel list): his US and Puerto Rico channels, the other countries, and
        // the leagues and events kept for Sports; null for any other provider's playlist.
        LiveTvCuratedGuide.ensureLoaded(appContext)
        val curated = com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-channels") {
            LiveTvCuratedGuide.apply(loadedChannels)
        }
        val withLogo: (LiveTvChannel) -> LiveTvChannel = { channel ->
            // Country for the Guide's filter: the list's, the playlist's own, or guessed from group/name.
            val country = channel.country.takeIf { curated != null } ?: LiveTvCountry.of(channel)
            // US channels get the app's own logo when it has one (they're made for dark screens).
            val bundledLogo = if (country == null || country == "US") {
                ChannelLogoLibrary.logoUriFor(LiveTvViewModel.stripCountryTag(channel.name), channel.number)
            } else {
                null
            }
            channel.copy(country = country, logoUrl = bundledLogo ?: channel.logoUrl)
        }
        val channels = (curated?.visible ?: loadedChannels.filterNot(::isHidden)).map(withLogo)
        val hiddenChannels = curated?.hidden.orEmpty().map(withLogo)
        val failures = results.mapNotNull { (source, result) -> result.exceptionOrNull()?.let { source.name to it } }
        if (channels.isEmpty() && previous != null) {
            // A refresh that failed: what was loaded stays.
            publish(key, previous.copy(isLoading = false, isEpgLoading = false))
            return
        }
        // RCTV: never which IPTV source (or server) it was: that the channels couldn't load.
        val error = when {
            // RCTV (RCTV-AUD-021): the provider's certificate didn't check out (settings kept).
            channels.isEmpty() && failures.isNotEmpty() && failures.all { (_, failure) -> failure.isTlsCertificateFailure() } ->
                appContext.getString(com.nuvio.tv.R.string.livetv_channels_certificate_invalid)
            channels.isEmpty() && failures.isNotEmpty() ->
                reasonOf(failures.first().second)
                    ?.let { appContext.getString(com.nuvio.tv.R.string.livetv_channels_load_failed_because, it) }
                    ?: appContext.getString(com.nuvio.tv.R.string.livetv_channels_load_failed)
            channels.isEmpty() -> appContext.getString(com.nuvio.tv.R.string.livetv_channels_none)
            failures.isNotEmpty() -> appContext.getString(com.nuvio.tv.R.string.livetv_channels_some_failed)
            else -> null
        }
        // The programs already loaded stay while the new ones come.
        val keptEpg: Map<String, List<EpgProgram>> = previous?.epgByChannel.orEmpty()
        val withChannels = LiveGuideSnapshot(
            key = key,
            isPreview = false,
            isLoading = false,
            isEpgLoading = channels.isNotEmpty(),
            channels = channels,
            groups = channels.mapNotNull { it.groupTitle }.distinct().sorted(),
            epgByChannel = keptEpg,
            channelCategories = categorize(channels, keptEpg),
            error = error,
            loadedAtMs = previous?.loadedAtMs ?: System.currentTimeMillis(),
            hiddenChannels = hiddenChannels,
            curated = curated != null
        )
        publish(key, withChannels)
        if (channels.isEmpty()) return
        // RCTV: the channels are kept on the TV right away, not only once the programs are in (a
        // provider's guide can take minutes, and the app may close first): next time they show at
        // once. Kept as old, so that time they load again in the background, programs and all.
        com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-save") { save(withChannels.copy(loadedAtMs = 0L)) }

        // The programs are a nice-to-have on top of the channels: none (or a failed load) never
        // blocks watching.
        // Each source's guide only for its own channels (their guide id, or their id when the
        // playlist gives none: both are looked up).
        // RCTV (owner's channel list): only the Guide's channels and the leagues' and events'
        // (Sports reads their programs): not the versions, nor what the list leaves out.
        val kept: Set<String>? = curated?.let { applied ->
            (applied.visible.asSequence() + applied.hidden.asSequence().filter { it.versionOf == null })
                .mapTo(HashSet()) { it.id }
        }
        val merged = HashMap<String, List<EpgProgram>>()
        for ((source, result) in results) {
            val prefix = "${source.id}:"
            val wanted = HashSet<String>()
            result.getOrNull()?.forEach { channel ->
                if (kept != null && channel.id !in kept) return@forEach
                channel.epgChannelId?.removePrefix(prefix)?.let(wanted::add)
                wanted += channel.id.removePrefix(prefix)
            }
            merged += loadSourceEpg(source, wanted)
        }
        val epg: Map<String, List<EpgProgram>> = merged.ifEmpty { keptEpg }
        val loaded = withChannels.copy(
            isEpgLoading = false,
            epgByChannel = epg,
            channelCategories = categorize(channels, epg),
            loadedAtMs = System.currentTimeMillis()
        )
        publish(key, loaded)
        if (coroutineContext[Job]?.isActive != false) com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-save") { save(loaded) }
    }

    /**
     * RCTV: why a source's channels didn't load, in a few words (never its server or login), or null
     * when there's nothing more useful to say than that they didn't.
     */
    private fun reasonOf(failure: Throwable): String? {
        val message = failure.message.orEmpty()
        return when {
            failure is OutOfMemoryError -> appContext.getString(com.nuvio.tv.R.string.livetv_reason_memory)
            failure is java.net.UnknownHostException -> appContext.getString(com.nuvio.tv.R.string.livetv_reason_no_server)
            failure is java.net.ConnectException || failure is java.net.SocketTimeoutException ||
                failure is java.net.NoRouteToHostException ->
                appContext.getString(com.nuvio.tv.R.string.livetv_reason_unreachable)
            message == "Invalid Xtream credentials" -> appContext.getString(com.nuvio.tv.R.string.livetv_reason_login)
            Regex("^HTTP \\d{3}$").matches(message) -> appContext.getString(com.nuvio.tv.R.string.livetv_reason_http, message)
            failure is XtreamClient.XtreamException -> appContext.getString(com.nuvio.tv.R.string.livetv_reason_unexpected)
            else -> null
        }
    }

    /**
     * RCTV (owner's choice): a provider's groups the app never shows (streaming services' copies:
     * Netflix, Prime, Disney+, Sky Store, OnePlay, Samsung TV Plus...), by name, any case or spacing.
     */
    private val hiddenGroups = setOf(
        "SkyMix Series", "Sky Store Premiere", "OnePlay Cinema", "OnePlay Adventure", "BBCiplayer Series",
        "Hulu Originals", "Amazon Prime Video", "Apple TV+ Series", "Disney+ Series", "Netflix Series",
        "Netflix Movies", "Peacock Original", "Paramount+ Original", "SkyMix Docs", "OnePlay Anime",
        "OnePlay Kids International", "Samsung TV Plus", "España | Netflix Series", "España | MoviStar+ Series",
        "España | MoviStar+ Estrenos", "España | Netflix Estrenos",
        "USA | Tubi", "USA | MAX", "USA | Paramount +", "HBOMax Original"
    ).mapTo(HashSet(), ::groupKey)

    private fun groupKey(group: String): String =
        group.lowercase(java.util.Locale.ROOT).filterNot { it.isWhitespace() }

    private fun isHidden(channel: LiveTvChannel): Boolean =
        channel.groupTitle?.let { groupKey(it) in hiddenGroups } == true

    /** One source's channels; never throws (a failing source just adds nothing). */
    private suspend fun loadSourceChannels(source: LiveTvSource): Result<List<LiveTvChannel>> = runCatching {
        val rawChannels: List<LiveTvChannel> = when (val connection = source.connection) {
            is LiveTvConnection.M3u -> {
                val request = Request.Builder().url(connection.playlistUrl).build()
                // Read as it downloads (a provider's full playlist can be tens of megabytes).
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty response")
                    com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-channels") { M3uPlaylistParser.parse(body.charStream()) }
                }
            }
            is LiveTvConnection.Xtream -> com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-channels") { xtreamClient.fetchChannels(connection) }
            is LiveTvConnection.Stalker -> {
                val session = stalkerSessions.getOrPut(source.id) { stalkerClient.authenticate(connection) }
                stalkerClient.fetchChannels(session)
            }
        }
        // Ids prefixed with the source's, so channels from several playlists/accounts never collide,
        // and each tagged with its source (for its playable address and its programs).
        rawChannels.map { channel ->
            channel.copy(
                id = "${source.id}:${channel.id}",
                epgChannelId = channel.epgChannelId?.let { "${source.id}:$it" },
                sourceId = source.id
            )
        }
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * One source's programs, by prefixed channel id (a Stalker portal's guide isn't read yet).
     * Only for [wantedIds] (the guide ids of the source's own channels, unprefixed) and the guide's
     * window: a provider's guide often lists thousands of channels and a week of programs, and
     * keeping all of it is what made the Guide slow and the app close for lack of memory. Read as
     * it downloads (never held whole as text).
     */
    private suspend fun loadSourceEpg(source: LiveTvSource, wantedIds: Set<String>): Map<String, List<EpgProgram>> {
        val connection = source.connection
        if (wantedIds.isEmpty()) return emptyMap()
        val job = coroutineContext[Job]
        val nowMs = System.currentTimeMillis()
        val read: (java.io.InputStream) -> List<EpgProgram> = { input -> com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("guide-epg") {
            XmltvParser.parseStream(
                input = input,
                fromMs = nowMs - EPG_WINDOW_PAST_MS,
                toMs = nowMs + EPG_WINDOW_AHEAD_MS,
                includeChannels = false,
                shouldContinue = { job?.isActive != false },
                keepChannel = { it in wantedIds }
            ).programs
        } }
        return runCatching {
            val programs: List<EpgProgram> = when (connection) {
                // RCTV Control: the guide set on the website for the account first; the provider's own
                // when it has none, can't be read, or has nothing for these channels.
                is LiveTvConnection.Xtream -> connection.epgUrl
                    ?.let { url ->
                        try {
                            downloadXmltv(url, read).takeIf { it.isNotEmpty() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    ?: xtreamClient.streamXmltv(connection, read)
                is LiveTvConnection.M3u -> downloadXmltv(connection.epgUrl ?: return emptyMap(), read)
                is LiveTvConnection.Stalker -> return emptyMap()
            }
            // Stopped part way (another load took over): what was read is incomplete.
            coroutineContext.ensureActive()
            programs
                .groupBy { "${source.id}:${it.channelId}" }
                .mapValues { (_, list) -> list.sortedBy { it.startMs } }
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
    }

    /** An XMLTV guide at [url] (gzip-aware too: an .xml.gz guide). The address is never logged. */
    private fun <T> downloadXmltv(url: String, read: (java.io.InputStream) -> T): T {
        val request = Request.Builder().url(url).build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            read(response.body?.byteStream() ?: error("Empty response"))
        }
    }

    // ------------------------------------------------------------------ Kept on the device

    private val savedFile: File get() = File(appContext.cacheDir, SAVED_FILE)

    /**
     * Keeps [snapshot] on the device (channels and programs, without what has already ended), so the
     * Guide shows at once after the app restarts. Written to a new file then swapped in, so a guide
     * half written is never read.
     */
    private fun save(snapshot: LiveGuideSnapshot) {
        val cutoff = System.currentTimeMillis() - EPG_WINDOW_PAST_MS
        val temp = File(appContext.cacheDir, "$SAVED_FILE.tmp")
        try {
            DataOutputStream(BufferedOutputStream(temp.outputStream(), 64 * 1024)).use { out ->
                out.writeInt(SAVED_VERSION)
                out.writeText(snapshot.key)
                out.writeBoolean(snapshot.isPreview)
                out.writeLong(snapshot.loadedAtMs)
                out.writeOptional(snapshot.error)
                out.writeBoolean(snapshot.curated)
                out.writeChannels(snapshot.channels)
                out.writeChannels(snapshot.hiddenChannels)
                out.writeInt(snapshot.groups.size)
                snapshot.groups.forEach { out.writeText(it) }
                val epg = snapshot.epgByChannel.mapValues { (_, list) -> list.filter { it.endMs > cutoff } }
                    .filterValues { it.isNotEmpty() }
                out.writeInt(epg.size)
                epg.forEach { (channelId, programs) ->
                    out.writeText(channelId)
                    out.writeInt(programs.size)
                    programs.forEach { program ->
                        out.writeText(program.channelId)
                        out.writeText(program.title)
                        out.writeOptional(program.description)
                        out.writeOptional(program.category)
                        out.writeLong(program.startMs)
                        out.writeLong(program.endMs)
                        out.writeOptional(program.subtitle)
                        out.writeOptional(program.imageUrl)
                        out.writeBoolean(program.isLive)
                        out.writeBoolean(program.isNew)
                        out.writeInt(program.categories.size)
                        program.categories.forEach { out.writeText(it) }
                        out.writeInt(program.seasonNumber)
                        out.writeInt(program.episodeNumber)
                        out.writeInt(program.originalAirDay)
                    }
                }
            }
            if (!temp.renameTo(savedFile)) {
                savedFile.delete()
                temp.renameTo(savedFile)
            }
        } catch (e: Exception) {
            // Not kept this time: the guide loads from the sources next time.
            temp.delete()
        }
    }

    /** The guide kept on the device for [key], or null (none, another source's, or unreadable). */
    private fun readSaved(key: String): LiveGuideSnapshot? {
        val file = savedFile
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { input ->
                if (input.readInt() != SAVED_VERSION) {
                    // An older kind (it may hold a provider's whole guide): not read, not kept.
                    file.delete()
                    return null
                }
                if (input.readText() != key) return null
                val isPreview = input.readBoolean()
                val loadedAtMs = input.readLong()
                val error = input.readOptional()
                val curated = input.readBoolean()
                val channels = input.readChannels()
                val hiddenChannels = input.readChannels()
                val groups = List(input.readInt()) { input.readText() }
                val epg = HashMap<String, List<EpgProgram>>()
                // A show airs many times, and categories repeat: one copy of each text.
                val sharedTexts = HashMap<String, String>()
                fun shared(text: String): String = sharedTexts.getOrPut(text) { text }
                repeat(input.readInt()) {
                    val channelId = input.readText()
                    epg[channelId] = List(input.readInt()) {
                        EpgProgram(
                            channelId = shared(input.readText()),
                            title = shared(input.readText()),
                            description = input.readOptional(),
                            category = input.readOptional()?.let(::shared),
                            startMs = input.readLong(),
                            endMs = input.readLong(),
                            subtitle = input.readOptional(),
                            imageUrl = input.readOptional(),
                            isLive = input.readBoolean(),
                            isNew = input.readBoolean(),
                            categories = List(input.readInt()) { shared(input.readText()) },
                            seasonNumber = input.readInt(),
                            episodeNumber = input.readInt(),
                            originalAirDay = input.readInt()
                        )
                    }
                }
                if (channels.isEmpty()) return null
                LiveGuideSnapshot(
                    key = key,
                    isPreview = isPreview,
                    isLoading = false,
                    channels = channels,
                    groups = groups,
                    epgByChannel = epg,
                    channelCategories = categorize(channels, epg),
                    error = error,
                    loadedAtMs = loadedAtMs,
                    hiddenChannels = hiddenChannels,
                    curated = curated
                )
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun DataOutputStream.writeChannels(channels: List<LiveTvChannel>) {
        writeInt(channels.size)
        channels.forEach { channel ->
            writeText(channel.id)
            writeText(channel.name)
            writeText(channel.streamUrl)
            writeOptional(channel.logoUrl)
            writeOptional(channel.groupTitle)
            writeOptional(channel.epgChannelId)
            writeOptional(channel.sourceId)
            writeOptional(channel.country)
            writeOptional(channel.number)
            writeOptional(channel.guideCategory)
            writeOptional(channel.versionOf)
        }
    }

    private fun DataInputStream.readChannels(): List<LiveTvChannel> = List(readInt()) {
        LiveTvChannel(
            id = readText(),
            name = readText(),
            streamUrl = readText(),
            logoUrl = readOptional(),
            groupTitle = readOptional(),
            epgChannelId = readOptional(),
            sourceId = readOptional(),
            country = readOptional(),
            number = readOptional(),
            guideCategory = readOptional(),
            versionOf = readOptional()
        )
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeText(value)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_SAVED_TEXT_BYTES) { "Bad text size $size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readText() else null

    /** Sorts every channel into the Guide's categories from its programs, name and group. */
    private fun categorize(channels: List<LiveTvChannel>, epg: Map<String, List<EpgProgram>>): Map<String, Set<GuideBrowse>> =
        LiveTvGuideCategories.classify(channels) { channel ->
            channel.epgChannelId?.let { epg[it] }.orEmpty()
        }

    companion object {
        /** Public US guide (EPGTalk) used for the sample guide. */
        const val PREVIEW_EPG_URL = "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz"
        private const val PREVIEW_SOURCE_ID = "preview"
        private const val PREVIEW_KEY = "preview"
        /** Programs kept per load: from 2 hours ago to 36 hours ahead. */
        private const val EPG_WINDOW_PAST_MS = 2L * 60 * 60 * 1000
        private const val EPG_WINDOW_AHEAD_MS = 36L * 60 * 60 * 1000
        /**
         * A guide this old is loaded again in the background (the one loaded still shows). RCTV:
         * every 6 hours (was 2): the provider's guide is huge and reading it again often slowed
         * the TV; its programs reach 36 hours ahead, so 30 are always there, and the day's event
         * channels (named for their games) are still read again during the day.
         */
        private const val REFRESH_AFTER_MS = 6L * 60 * 60 * 1000
        /** How long after the app starts the guide starts loading (Home first). */
        private const val PREWARM_DELAY_MS = 1_500L
        /** The guide kept on the device (in the app's cache, so the system can clear it). */
        private const val SAVED_FILE = "live_tv_guide.bin"
        /**
         * 2: only the playlist's own channels' programs are kept (1 kept the provider's whole guide).
         * 3: RCTV, the owner's channel list (each channel's category and versions, and the channels
         * kept out of the Guide).
         * 4: RCTV, each program's episode numbers and the day it first aired (its episode on TMDB).
         */
        private const val SAVED_VERSION = 4
        private const val MAX_SAVED_TEXT_BYTES = 1_000_000
    }
}

/**
 * RCTV, Kids profiles: the guide with only its family and kids channels (the Guide's Kids category:
 * their programs or their names, see [LiveTvGuideCategories]).
 */
internal fun LiveGuideSnapshot.kidsOnly(): LiveGuideSnapshot {
    val kept = channels.filter { GuideBrowse.KIDS in channelCategories[it.id].orEmpty() }
    val ids = kept.mapTo(HashSet()) { it.id }
    val guideIds = kept.mapNotNullTo(HashSet(ids)) { it.epgChannelId }
    return copy(
        channels = kept,
        groups = groups.filter { group -> kept.any { it.groupTitle == group } },
        epgByChannel = epgByChannel.filterKeys { it in guideIds },
        channelCategories = channelCategories.filterKeys { it in ids },
        // Only the kept channels' other versions: no leagues or events on a Kids profile.
        hiddenChannels = hiddenChannels.filter { it.versionOf in ids }
    )
}
