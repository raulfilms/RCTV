package com.nuvio.tv.ui.screens.livetv

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
    val loadedAtMs: Long = 0L
)

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
    @Named("addonPermissive") private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _guide = MutableStateFlow<LiveGuideSnapshot?>(null)

    /** The guide last loaded (or loading), for the sources in its [LiveGuideSnapshot.key]. */
    val guide: StateFlow<LiveGuideSnapshot?> = _guide.asStateFlow()

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
                    val saved = readSaved(key)
                    if (saved != null) {
                        publish(key, saved)
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
            if (loadKey == key) _guide.value = snapshot
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
                XmltvParser.parseStream(
                    input = body.byteStream(),
                    fromMs = nowMs - EPG_WINDOW_PAST_MS,
                    toMs = nowMs + EPG_WINDOW_AHEAD_MS,
                    includeChannels = true,
                    shouldContinue = { job?.isActive != false }
                )
            }
        }
        if (job?.isActive == false) return
        result
            .onSuccess { guide ->
                val snapshot = previewSnapshot(key, guide)
                publish(key, snapshot)
                if (snapshot.channels.isNotEmpty()) save(snapshot)
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
        val channels = results
            .flatMap { (_, result) -> result.getOrDefault(emptyList()) }
            .map { channel ->
                // Country for the Guide's filter: the playlist's own, or guessed from group/name.
                val country = LiveTvCountry.of(channel)
                // US channels get the app's own logo when it has one (they're made for dark screens).
                val bundledLogo = if (country == null || country == "US") {
                    ChannelLogoLibrary.logoUriFor(LiveTvViewModel.stripCountryTag(channel.name), channel.number)
                } else {
                    null
                }
                channel.copy(country = country, logoUrl = bundledLogo ?: channel.logoUrl)
            }
        val failures = results.mapNotNull { (source, result) -> result.exceptionOrNull()?.let { source.name to it } }
        if (channels.isEmpty() && previous != null) {
            // A refresh that failed: what was loaded stays.
            publish(key, previous.copy(isLoading = false, isEpgLoading = false))
            return
        }
        // RCTV: never which IPTV source (or server) it was: that the channels couldn't load.
        val error = when {
            channels.isEmpty() && failures.isNotEmpty() -> appContext.getString(com.nuvio.tv.R.string.livetv_channels_load_failed)
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
            loadedAtMs = previous?.loadedAtMs ?: System.currentTimeMillis()
        )
        publish(key, withChannels)
        if (channels.isEmpty()) return

        // The programs are a nice-to-have on top of the channels: none (or a failed load) never
        // blocks watching.
        // Each source's guide only for its own channels (their guide id, or their id when the
        // playlist gives none: both are looked up).
        val merged = HashMap<String, List<EpgProgram>>()
        for ((source, result) in results) {
            val prefix = "${source.id}:"
            val wanted = HashSet<String>()
            result.getOrNull()?.forEach { channel ->
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
        if (coroutineContext[Job]?.isActive != false) save(loaded)
    }

    /** One source's channels; never throws (a failing source just adds nothing). */
    private suspend fun loadSourceChannels(source: LiveTvSource): Result<List<LiveTvChannel>> = runCatching {
        val rawChannels = when (val connection = source.connection) {
            is LiveTvConnection.M3u -> {
                val request = Request.Builder().url(connection.playlistUrl).build()
                val body = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                M3uPlaylistParser.parse(body)
            }
            is LiveTvConnection.Xtream -> xtreamClient.fetchChannels(connection)
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
        val read: (java.io.InputStream) -> List<EpgProgram> = { input ->
            XmltvParser.parseStream(
                input = input,
                fromMs = nowMs - EPG_WINDOW_PAST_MS,
                toMs = nowMs + EPG_WINDOW_AHEAD_MS,
                includeChannels = false,
                shouldContinue = { job?.isActive != false },
                keepChannel = { it in wantedIds }
            ).programs
        }
        return runCatching {
            val programs: List<EpgProgram> = when (connection) {
                is LiveTvConnection.Xtream -> xtreamClient.streamXmltv(connection, read)
                is LiveTvConnection.M3u -> {
                    val epgUrl = connection.epgUrl ?: return emptyMap()
                    // Gzip-aware too (an .xml.gz guide).
                    val request = Request.Builder().url(epgUrl).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        read(response.body?.byteStream() ?: error("Empty response"))
                    }
                }
                is LiveTvConnection.Stalker -> return emptyMap()
            }
            // Stopped part way (another load took over): what was read is incomplete.
            coroutineContext.ensureActive()
            programs
                .groupBy { "${source.id}:${it.channelId}" }
                .mapValues { (_, list) -> list.sortedBy { it.startMs } }
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
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
                out.writeInt(snapshot.channels.size)
                snapshot.channels.forEach { channel ->
                    out.writeText(channel.id)
                    out.writeText(channel.name)
                    out.writeText(channel.streamUrl)
                    out.writeOptional(channel.logoUrl)
                    out.writeOptional(channel.groupTitle)
                    out.writeOptional(channel.epgChannelId)
                    out.writeOptional(channel.sourceId)
                    out.writeOptional(channel.country)
                    out.writeOptional(channel.number)
                }
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
                val channels = List(input.readInt()) {
                    LiveTvChannel(
                        id = input.readText(),
                        name = input.readText(),
                        streamUrl = input.readText(),
                        logoUrl = input.readOptional(),
                        groupTitle = input.readOptional(),
                        epgChannelId = input.readOptional(),
                        sourceId = input.readOptional(),
                        country = input.readOptional(),
                        number = input.readOptional()
                    )
                }
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
                            categories = List(input.readInt()) { shared(input.readText()) }
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
                    loadedAtMs = loadedAtMs
                )
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
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
        /** A guide this old is loaded again in the background (the one loaded still shows). */
        private const val REFRESH_AFTER_MS = 2L * 60 * 60 * 1000
        /** How long after the app starts the guide starts loading (Home first). */
        private const val PREWARM_DELAY_MS = 1_500L
        /** The guide kept on the device (in the app's cache, so the system can clear it). */
        private const val SAVED_FILE = "live_tv_guide.bin"
        /** 2: only the playlist's own channels' programs are kept (1 kept the provider's whole guide). */
        private const val SAVED_VERSION = 2
        private const val MAX_SAVED_TEXT_BYTES = 1_000_000
    }
}
