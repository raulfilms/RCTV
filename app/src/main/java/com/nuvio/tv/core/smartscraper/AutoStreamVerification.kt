package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Auto Stream v2.0, Phase 2: what's checked before a stream plays (spec §8). Pure decisions only
 * (no network, no player): the HTTP answer to the link's first bytes, the tracks the player found,
 * and the order sources are tried in within the startup deadline. Easy to test; the app's network
 * and player code feed them.
 */

/** §8 "Resolve + verify": the link's answer to `Range: bytes=0-1`. */
object AutoStreamLinkCheck {

    enum class Outcome(val description: String) {
        OK("Ready"),
        TOO_LARGE("Over the size limit"),
        SIZE_UNCONFIRMED("Its size isn't given and the link doesn't say it"),
        PLACEHOLDER("A short clip instead of the file (the provider's error video)"),
        FILE_GONE("The file isn't there"),
        PROVIDER_ERROR("The provider or service failed")
    }

    data class Result(
        val outcome: Outcome,
        /** The file's whole size the link gave (or the one known), when there's one. */
        val totalBytes: Long? = null,
        val httpCode: Int? = null
    ) {
        val ok: Boolean get() = outcome == Outcome.OK

        /** The failure is the provider's or service's (its other files likely fail too), not this file's. */
        val providerLevel: Boolean
            get() = outcome == Outcome.PROVIDER_ERROR || outcome == Outcome.PLACEHOLDER
    }

    /**
     * [code] and headers of the link's answer (after redirects); [knownSizeBytes]: the size the
     * stream said (null: not said). 200/206 with a whole size at most [maxBytes] plays; a size that
     * isn't known anywhere doesn't (§3.1); a file far smaller than said is a placeholder clip.
     */
    fun evaluate(
        code: Int,
        contentRange: String?,
        contentLength: String?,
        knownSizeBytes: Long?,
        maxBytes: Long
    ): Result {
        when {
            code == 200 || code == 206 -> Unit
            code == 404 || code == 410 || code == 416 -> return Result(Outcome.FILE_GONE, httpCode = code)
            code == 401 || code == 403 || code == 429 || code >= 500 || code in 300..399 ->
                return Result(Outcome.PROVIDER_ERROR, httpCode = code)
            else -> return Result(Outcome.FILE_GONE, httpCode = code)
        }
        val total = totalBytes(code, contentRange, contentLength)
        if (total == null) {
            return if (knownSizeBytes != null) Result(Outcome.OK, knownSizeBytes, code)
            else Result(Outcome.SIZE_UNCONFIRMED, httpCode = code)
        }
        if (total > maxBytes) return Result(Outcome.TOO_LARGE, total, code)
        if (knownSizeBytes != null && knownSizeBytes > 0 && total < knownSizeBytes * PLACEHOLDER_SHARE) {
            return Result(Outcome.PLACEHOLDER, total, code)
        }
        return Result(Outcome.OK, total, code)
    }

    /** The answer couldn't be had (no connection, a timeout, a TLS error): the provider's failure. */
    fun unreachable(): Result = Result(Outcome.PROVIDER_ERROR)

    /** "bytes 0-1/7945689497" → 7945689497 (206); a 200's Content-Length is the whole file. */
    fun totalBytes(code: Int, contentRange: String?, contentLength: String?): Long? {
        if (code == 206) {
            val total = contentRange?.substringAfterLast('/', "")?.trim()
            return total?.takeIf { it != "*" }?.toLongOrNull()?.takeIf { it > 0 }
        }
        return contentLength?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** A link far smaller than this share of the size said isn't the file. */
    private const val PLACEHOLDER_SHARE = 0.1
}

/** §8.3: the tracks the player found, checked before the first picture. */
object AutoStreamTrackCheck {

    data class AudioTrack(val language: String?, val channelCount: Int?)

    enum class Violation(val description: String) {
        AUDIO_LAYOUT("Its audio is neither 5.1 nor 2.0"),
        LOW_RESOLUTION("Its picture is below the lowest resolution allowed"),
        NO_WANTED_AUDIO("No English, original-language or chosen-language audio")
    }

    /**
     * [audio] and the picture's size as the player read them; [wantedLanguages]: the languages the
     * video must have one of (English, the title's own, the one chosen), any form ("en", "eng",
     * "es-419"); empty: not checked. What the player doesn't say (a track's language, its channels,
     * the picture's size) can't fail the check. Null: nothing is wrong.
     */
    fun check(
        audio: List<AudioTrack>,
        videoWidth: Int?,
        videoHeight: Int?,
        wantedLanguages: Set<String>,
        config: AutoStreamConfig
    ): Violation? {
        resolutionOf(videoWidth, videoHeight)?.let { resolution ->
            if (resolution !in config.resolutions) return Violation.LOW_RESOLUTION
        }
        if (audio.isEmpty()) return null
        val wanted = wantedLanguages.mapNotNullTo(HashSet(), ::iso3)
        val tagged = audio.filter { iso3(it.language) != null }
        val untagged = audio.filter { iso3(it.language) == null }
        // The language: a file whose tracks all say theirs needs one wanted.
        val inWanted = tagged.filter { iso3(it.language) in wanted }
        if (wanted.isNotEmpty() && untagged.isEmpty() && inWanted.isEmpty()) return Violation.NO_WANTED_AUDIO
        // The layout of the audio that would play (a wanted language's, else any not said, else any).
        val playable = (inWanted + untagged).ifEmpty { audio }
        if (playable.any { it.channelCount == null || it.channelCount <= 0 }) return null
        val allowed = config.allowedChannels.mapTo(HashSet()) { it.count }
        return if (playable.none { it.channelCount in allowed }) Violation.AUDIO_LAYOUT else null
    }

    /**
     * The picture's resolution by its width too (a 1920×800 scope film is 1080p); null when not
     * known.
     */
    fun resolutionOf(width: Int?, height: Int?): VideoResolution? {
        val h = height?.takeIf { it > 0 } ?: return null
        val fromWidth = width?.takeIf { it > 0 }?.let { it * 9 / 16 } ?: 0
        return VideoResolution.ofHeight(maxOf(h, fromWidth))
    }

    /**
     * A language code as ISO 639-2/T ("en", "eng", "en-US" → "eng"; "fre" → "fra"); null for
     * none, "und", "mul", "zxx" and codes that aren't languages.
     */
    fun iso3(code: String?): String? {
        val base = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
            ?.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
            ?: return null
        if (base in NOT_A_LANGUAGE) return null
        if (base.length == 3) return BIBLIOGRAPHIC[base] ?: base
        return runCatching { Locale(base).isO3Language }.getOrNull()?.takeIf { it.length == 3 }
    }

    private val NOT_A_LANGUAGE = setOf("und", "mul", "zxx", "mis", "qaa")

    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fra", "ger" to "deu", "chi" to "zho", "dut" to "nld", "gre" to "ell", "per" to "fas",
        "cze" to "ces", "slo" to "slk", "rum" to "ron", "alb" to "sqi", "arm" to "hye", "baq" to "eus",
        "bur" to "mya", "geo" to "kat", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
        "tib" to "bod", "wel" to "cym"
    )
}

/**
 * §8 / §8.4: the sources are tried in their order, each given [AutoStreamConfig.resolveVerifyTimeoutSeconds]
 * to give a link that answers; none is started past the startup deadline. A failure that is the
 * provider's (it's down, its service refused) sends that provider's other sources after everyone
 * else's.
 */
object AutoStreamResolver {

    sealed interface Attempt<out T> {
        data class Ok<T>(val value: T) : Attempt<T>
        data class Failed(val reason: String, val providerLevel: Boolean) : Attempt<Nothing>
    }

    data class Found<T>(val stream: Stream, val value: T, val tried: Int)

    suspend fun <T> firstPlayable(
        candidates: List<Stream>,
        deadlineAtMs: Long,
        attemptTimeoutMs: Long,
        maxAttempts: Int,
        providerOf: (Stream) -> String,
        now: () -> Long = System::currentTimeMillis,
        onFailure: (Stream, Attempt.Failed) -> Unit = { _, _ -> },
        attempt: suspend (Stream) -> Attempt<T>
    ): Found<T>? {
        val queue = ArrayList(candidates)
        var tried = 0
        while (queue.isNotEmpty() && tried < maxAttempts) {
            val left = deadlineAtMs - now()
            if (left <= 0) break
            val candidate = queue.removeAt(0)
            tried++
            val result = withTimeoutOrNull(minOf(attemptTimeoutMs, left)) { attempt(candidate) }
                ?: Attempt.Failed("no answer in time", providerLevel = true)
            when (result) {
                is Attempt.Ok -> return Found(candidate, result.value, tried)
                is Attempt.Failed -> {
                    onFailure(candidate, result)
                    if (result.providerLevel) {
                        val provider = providerOf(candidate)
                        val (same, others) = queue.partition { providerOf(it) == provider }
                        queue.clear()
                        queue += others
                        queue += same
                    }
                }
            }
        }
        return null
    }

    /** A source's provider: its addon and debrid service ("Torrentio • Real-Debrid"), §2. */
    fun providerOf(stream: Stream): String {
        val identity = runCatching { SourceProviderAdapters.identify(stream) }.getOrNull()
        return "${stream.addonName}|${identity?.debrid ?: DebridService.NONE}"
    }
}
