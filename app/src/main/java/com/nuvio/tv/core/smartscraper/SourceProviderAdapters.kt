package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import java.util.Locale

/**
 * One provider's streams read into the same [NormalizedSource]: each addon family writes its
 * streams its own way (where the file's name is, how it says a file is cached), and its adapter
 * knows it. A new provider is a new adapter in [SourceProviderAdapters.adapters]; the ranking never
 * changes for it.
 */
interface SourceProviderAdapter {
    fun handles(stream: Stream): Boolean
    fun identify(stream: Stream): ProviderIdentity
    fun normalize(stream: Stream): NormalizedSource
}

object SourceProviderAdapters {

    /** Tried in order; the last one reads any addon. */
    val adapters: List<SourceProviderAdapter> = listOf(LatinoDebridAdapter, TorrentioAdapter, AioStreamsAdapter, GenericStreamAdapter)

    fun normalize(stream: Stream): NormalizedSource =
        adapters.first { it.handles(stream) }.normalize(stream)

    fun identify(stream: Stream): ProviderIdentity =
        adapters.first { it.handles(stream) }.identify(stream)

    /**
     * A key for the file itself, the same whichever addon/service offers it (its torrent's hash and
     * file, else its link without the query, else its name and size).
     */
    fun releaseKey(source: NormalizedSource): String {
        source.infoHash?.let { return "h:${it.lowercase(Locale.ROOT)}:${source.fileIdx ?: -1}" }
        val release = source.release
        val name = release.releaseName?.let { ReleaseParser.spaced(ReleaseParser.cleanName(it)) }
        val sizeBucket = release.sizeBytes?.let { it / (32L * 1024 * 1024) }
        if (name != null && sizeBucket != null) return "f:$name:$sizeBucket"
        source.stream.getStreamUrl()?.let { return "u:" + it.substringBefore('?').lowercase(Locale.ROOT) }
        return "n:${name.orEmpty()}:${sizeBucket ?: -1}:${source.provider.key}"
    }
}

/** What every adapter shares: the stream's text, size, cache state and whether it can play here. */
internal abstract class BaseStreamAdapter : SourceProviderAdapter {

    override fun normalize(stream: Stream): NormalizedSource {
        val identity = identify(stream)
        val release = ReleaseParser.parse(
            ReleaseParser.Input(
                names = releaseNames(stream),
                text = attributeText(stream),
                sizeBytes = stream.behaviorHints?.videoSize?.takeIf { it > 0 }
                    ?: stream.debridCacheStatus?.cachedSize?.takeIf { it > 0 },
                qualityHeight = stream.qualityValue.takeIf { it > 0 },
                season = stream.clientResolve?.season,
                episode = stream.clientResolve?.episode
            )
        )
        val isPlayable = !stream.isExternal() && stream.ytId == null &&
            (stream.getStreamUrl() != null || stream.isTorrent() || stream.isDirectDebrid())
        return NormalizedSource(
            stream = stream,
            provider = identity,
            release = release,
            cache = cacheState(stream),
            infoHash = stream.getEffectiveInfoHash(),
            fileIdx = stream.getEffectiveFileIdx(),
            isPlayable = isPlayable,
            isPeerToPeer = stream.isTorrent() && identity.debrid == DebridService.NONE,
            providerLanguages = providerLanguages(stream)
        )
    }

    /** Languages the provider itself stands for (none by default: the release says). */
    protected open fun providerLanguages(stream: Stream): Set<AudioLanguage> = emptySet()

    /** The file's name and its torrent's, the file's first. */
    protected open fun releaseNames(stream: Stream): List<String> = buildList {
        stream.behaviorHints?.filename?.let(::add)
        stream.clientResolve?.filename?.let(::add)
        stream.debridCacheStatus?.cachedName?.let(::add)
        stream.clientResolve?.torrentName?.let(::add)
        listOfNotNull(stream.title, stream.description).forEach { text ->
            text.lineSequence().filter(ReleaseParser::looksLikeReleaseName).forEach(::add)
        }
    }

    /** All the addon wrote about the stream. */
    protected open fun attributeText(stream: Stream): String =
        listOfNotNull(stream.name, stream.title, stream.description, stream.quality, stream.behaviorHints?.filename)
            .joinToString("\n")

    protected open fun cacheState(stream: Stream): CacheState {
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CACHED -> return CacheState.CACHED
            StreamDebridCacheState.NOT_CACHED -> return CacheState.UNCACHED
            else -> Unit
        }
        if (stream.clientResolve?.isCached == true || stream.isDirectDebrid()) return CacheState.CACHED
        val marks = listOfNotNull(stream.name, stream.title).joinToString(" ")
        return when {
            UNCACHED_MARK.containsMatchIn(marks) -> CacheState.UNCACHED
            CACHED_MARK.containsMatchIn(marks) -> CacheState.CACHED
            else -> CacheState.UNKNOWN
        }
    }

    protected fun debridIn(text: String): DebridService {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            REAL_DEBRID.containsMatchIn(lower) -> DebridService.REAL_DEBRID
            TORBOX.containsMatchIn(lower) -> DebridService.TORBOX
            PREMIUMIZE.containsMatchIn(lower) -> DebridService.PREMIUMIZE
            ALL_DEBRID.containsMatchIn(lower) -> DebridService.ALL_DEBRID
            DEBRID_LINK.containsMatchIn(lower) -> DebridService.DEBRID_LINK
            OTHER_DEBRID.containsMatchIn(lower) -> DebridService.OTHER
            else -> DebridService.NONE
        }
    }

    protected companion object {
        /** "[RD+]", "[TB+]", "[RD⚡]", "⚡". */
        val CACHED_MARK = Regex("\\[[A-Za-z]{2,3}\\s?(\\+|⚡)]|⚡")
        /** "[RD download]", "[RD⏳]", "⏳". */
        val UNCACHED_MARK = Regex("\\[[A-Za-z]{2,3}\\s?(download|⏳)]|⏳", RegexOption.IGNORE_CASE)
        val REAL_DEBRID = Regex("real[- ]?debrid|\\[rd[\\s+⚡⏳\\]]|(?<![a-z0-9])rd(?![a-z0-9])")
        val TORBOX = Regex("tor[- ]?box|\\[tb[\\s+⚡⏳\\]]|(?<![a-z0-9])tb(?![a-z0-9])")
        val PREMIUMIZE = Regex("premiumize|\\[pm[\\s+⚡⏳\\]]|(?<![a-z0-9])pm(?![a-z0-9])")
        val ALL_DEBRID = Regex("all[- ]?debrid|\\[ad[\\s+⚡⏳\\]]")
        val DEBRID_LINK = Regex("debrid[- ]?link|\\[dl[\\s+⚡⏳\\]]")
        val OTHER_DEBRID = Regex("easy[- ]?debrid|offcloud|put\\.io|\\[(ed|oc|pkp|pio)[\\s+⚡⏳\\]]")
    }
}

/** Torrentio with Real-Debrid, TorBox or Premiumize: "[RD+] Torrentio\n4k" and a title of torrent name, file name and "👤 💾 ⚙️". */
internal object TorrentioAdapter : BaseStreamAdapter() {
    override fun handles(stream: Stream): Boolean =
        stream.addonName.contains("torrentio", ignoreCase = true) ||
            stream.name?.contains("torrentio", ignoreCase = true) == true

    override fun identify(stream: Stream): ProviderIdentity {
        val bracket = stream.name?.substringBefore('\n').orEmpty()
        val debrid = debridIn(bracket).takeIf { it != DebridService.NONE } ?: debridIn(stream.addonName)
        return ProviderIdentity(key = stream.addonName, family = ProviderFamily.TORRENTIO, debrid = debrid)
    }

    override fun releaseNames(stream: Stream): List<String> = buildList {
        stream.behaviorHints?.filename?.let(::add)
        // The title's lines before its "👤 seeders 💾 size ⚙️ site" line: the torrent's name, then the file's.
        val lines = stream.title?.lines().orEmpty()
            .takeWhile { line -> STATS_MARKS.none { line.contains(it) } }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        lines.asReversed().forEach(::add)
        stream.clientResolve?.torrentName?.let(::add)
    }

    private val STATS_MARKS = listOf("👤", "💾", "⚙️", "⚙")
}

/** AIOStreams (Main, IMAX, Extended, Dubbed): its formatter's lines, the file's name in its hints or a line of its own. */
internal object AioStreamsAdapter : BaseStreamAdapter() {
    override fun handles(stream: Stream): Boolean {
        val addon = stream.addonName.lowercase(Locale.ROOT)
        return addon.contains("aio") || stream.name?.contains("aiostreams", ignoreCase = true) == true
    }

    override fun identify(stream: Stream): ProviderIdentity {
        val addon = stream.addonName.lowercase(Locale.ROOT)
        val variant = when {
            addon.contains("imax") -> ProviderVariant.IMAX
            addon.contains("extended") || addon.contains("extend") -> ProviderVariant.EXTENDED
            DUBBED.containsMatchIn(addon) -> ProviderVariant.DUBBED
            else -> ProviderVariant.MAIN
        }
        val debrid = debridIn(listOfNotNull(stream.name, stream.description).joinToString(" "))
            .takeIf { it != DebridService.NONE }
            ?: stream.clientResolve?.service?.let { debridIn(it) }
            ?: DebridService.NONE
        return ProviderIdentity(key = stream.addonName, family = ProviderFamily.AIOSTREAMS, variant = variant, debrid = debrid)
    }

    private val DUBBED = Regex("dub|doblad|dual|latino|castellano|multi")
}

/**
 * LatinoDebrid (and any other Latino addon added later: its name says "Latino"): Spanish (Latin
 * American) audio, through the person's debrid services.
 */
internal object LatinoDebridAdapter : BaseStreamAdapter() {
    override fun handles(stream: Stream): Boolean = LATINO.containsMatchIn(stream.addonName)

    override fun identify(stream: Stream): ProviderIdentity {
        val debrid = debridIn(listOfNotNull(stream.name, stream.description).joinToString(" "))
            .takeIf { it != DebridService.NONE }
            ?: stream.clientResolve?.service?.let { debridIn(it) }
            ?: DebridService.NONE
        return ProviderIdentity(key = stream.addonName, family = ProviderFamily.LATINODEBRID, debrid = debrid)
    }

    override fun providerLanguages(stream: Stream): Set<AudioLanguage> = setOf(AudioLanguage.SPANISH)

    private val LATINO = Regex("(?<![a-z])latino", RegexOption.IGNORE_CASE)
}

/** Any other addon: the file's name where it's given, else its release-like lines. */
internal object GenericStreamAdapter : BaseStreamAdapter() {
    override fun handles(stream: Stream): Boolean = true

    override fun identify(stream: Stream): ProviderIdentity {
        val debrid = stream.clientResolve?.service?.let { debridIn(it) }?.takeIf { it != DebridService.NONE }
            ?: debridIn(listOfNotNull(stream.name, stream.addonName).joinToString(" "))
        return ProviderIdentity(key = stream.addonName, family = ProviderFamily.OTHER, debrid = debrid)
    }
}
