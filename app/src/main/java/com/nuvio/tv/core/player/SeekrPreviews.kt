package com.nuvio.tv.core.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.tv.core.rctvcontrol.RctvManagedServices
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A title to ask Seekr for: a movie by its IMDb or TMDB id, or a show's episode (season and number). */
data class SeekrTitle(val imdbId: String?, val tmdbId: Int?, val season: Int?, val episode: Int?) {
    val isEpisode: Boolean get() = season != null && episode != null
    internal val key: String get() = "${imdbId.orEmpty()}|${tmdbId ?: ""}|${season ?: ""}|${episode ?: ""}"

    companion object {
        private val IMDB = Regex("^tt\\d{5,10}$")

        /** From the player's ids ("tt0103776", "tmdb:603"); null for anything but a movie or an episode. */
        fun of(contentId: String?, contentType: String?, season: Int?, episode: Int?): SeekrTitle? {
            val id = contentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val type = contentType?.lowercase()
            val series = type == "series" || type == "tv" || type == "episode"
            if (!series && type != "movie") return null
            if (series && (season == null || episode == null || season < 0 || episode < 1)) return null
            val imdb = id.substringBefore(':').takeIf { IMDB.matches(it) }
            val tmdb = if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").substringBefore(':').toIntOrNull() else null
            if (imdb == null && tmdb == null) return null
            return SeekrTitle(imdb, tmdb, if (series) season else null, if (series) episode else null)
        }
    }
}

/**
 * RCTV: the timeline's pictures from Seekr (seekr.tv), with the account's key (set on the website,
 * like Real-Debrid's). One lookup per title gives a signed list of where each stretch of the movie's
 * picture is (a tile of a bigger sheet); only that tile of a sheet is read, so a sheet never sits
 * whole in memory. The key goes only to Seekr, in a header; the sheets' signed addresses carry no
 * key. Nothing is logged. A title Seekr doesn't have, a refused key or too many asks never stop
 * playback: the player's own pictures (or just the time) show instead.
 */
@Singleton
class SeekrPreviews @Inject constructor(
    private val managed: RctvManagedServices,
    okHttpClient: OkHttpClient
) {
    /** Where one stretch of the timeline's picture is: a tile of a sheet. */
    internal data class Cue(
        val startMs: Long,
        val endMs: Long,
        val sheetUrl: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )

    /**
     * One title's pictures, in timeline order. RCTV: Seekr's addresses are signed until
     * [expiresAtMs] (their "exp"): the title is looked up again a minute before, else its pictures
     * stopped partway through a movie.
     */
    class Track internal constructor(
        internal val cues: List<Cue>,
        internal val loadedAtMs: Long,
        internal val expiresAtMs: Long = loadedAtMs + TRACK_TTL_MS
    ) {
        private val starts = LongArray(cues.size) { cues[it].startMs }

        /** Its addresses still work for a while. */
        fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < expiresAtMs - EXPIRY_MARGIN_MS

        internal fun cueAt(positionMs: Long): Cue? {
            if (cues.isEmpty()) return null
            var low = 0
            var high = cues.size - 1
            var found = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (starts[mid] <= positionMs) {
                    found = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            // Before the first stretch: the first picture.
            return cues[if (found < 0) 0 else found]
        }
    }

    private val http = okHttpClient.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()

    private val tracks = object : LinkedHashMap<String, Track>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > MAX_TRACKS
    }
    private val sheets = object : LinkedHashMap<String, ByteArray>(MAX_SHEETS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MAX_SHEETS
    }
    private val tiles = object : LinkedHashMap<String, ImageBitmap>(MAX_TILES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_TILES
    }

    /** Titles Seekr has no pictures for: not asked again until then. */
    private val noneUntil = ConcurrentHashMap<String, Long>()
    /** A key Seekr refused: not sent again (until the website sends another). */
    @Volatile private var refusedKey: String? = null
    /** Seekr asked to wait (too many asks): nothing is asked until then. */
    @Volatile private var waitUntilMs = 0L

    private fun key(): String? = managed.services.value.seekr?.takeIf { it != refusedKey }

    /** The account has a Seekr key that wasn't refused. */
    val hasKey: Boolean get() = key() != null

    /** [title]'s pictures for a file [durationMs] long; null when Seekr has none (or can't be asked now). */
    suspend fun track(title: SeekrTitle, durationMs: Long): Track? = withContext(Dispatchers.IO) {
        try {
            loadTrack(title, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun loadTrack(title: SeekrTitle, durationMs: Long): Track? {
        val key = key() ?: return null
        if (durationMs <= 0) return null
        val now = System.currentTimeMillis()
        if (now < waitUntilMs) return null
        val cacheKey = "${title.key}|${durationMs / 1000}"
        synchronized(tracks) {
            tracks[cacheKey]?.takeIf { it.isFresh(now) }?.let { return it }
        }
        noneUntil[title.key]?.let { if (now < it) return null }
        val vttUrl = lookup(title, durationMs, key) ?: return null
        // st=1: the stretches' times already on this file's timeline (Seekr scales them).
        val vtt = download(if ('?' in vttUrl) "$vttUrl&st=1" else "$vttUrl?st=1", MAX_VTT_BYTES)
            ?.toString(Charsets.UTF_8) ?: return null
        val cues = parseVtt(vtt, vttUrl)
        if (cues.isEmpty()) {
            noneUntil[title.key] = now + NONE_RETRY_MS
            return null
        }
        // RCTV: the earliest "exp" of the list's and its sheets' signed addresses (else 30 min).
        val expires = (listOf(vttUrl) + cues.map { it.sheetUrl }.distinct())
            .mapNotNull(::expiryOf)
            .minOrNull()
            ?.coerceAtMost(now + TRACK_TTL_MS)
            ?: (now + TRACK_TTL_MS)
        val track = Track(cues, now, expires)
        synchronized(tracks) { tracks[cacheKey] = track }
        return track
    }

    /** Seekr's signed address of the title's list of pictures, or null (and why, remembered). */
    private fun lookup(title: SeekrTitle, durationMs: Long, key: String): String? {
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegment("sprites").apply {
            addQueryParameter("duration_ms", durationMs.toString())
            if (title.isEpisode) {
                title.tmdbId?.let { addQueryParameter("show_tmdb_id", it.toString()) }
                title.imdbId?.let { addQueryParameter("show_imdb_id", it) }
                addQueryParameter("season", title.season.toString())
                addQueryParameter("episode", title.episode.toString())
            } else {
                title.imdbId?.let { addQueryParameter("imdb_id", it) }
                title.tmdbId?.let { addQueryParameter("tmdb_id", it.toString()) }
            }
        }.build()
        val request = Request.Builder().url(url).header("X-API-Key", key).get().build()
        http.newCall(request).execute().use { response ->
            val now = System.currentTimeMillis()
            when (response.code) {
                in 200..299 -> {
                    val body = response.body?.string() ?: return null
                    return runCatching { JSONObject(body).optString("vtt_url") }.getOrNull()
                        ?.takeIf { it.startsWith("https://") }
                }
                404 -> noneUntil[title.key] = now + NONE_RETRY_MS
                401, 403, 410 -> refusedKey = key
                429 -> {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, MAX_WAIT_S) ?: DEFAULT_WAIT_S
                    waitUntilMs = now + seconds * 1000
                }
            }
            return null
        }
    }

    /** The picture at [positionMs] of [track], or null. Only that tile of its sheet is read. */
    suspend fun pictureAt(track: Track, positionMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val cue = track.cueAt(positionMs) ?: return@withContext null
            val tileKey = "${cue.sheetUrl}#${cue.x},${cue.y},${cue.w},${cue.h}"
            synchronized(tiles) { tiles[tileKey] }?.let { return@withContext it }
            val sheet = synchronized(sheets) { sheets[cue.sheetUrl] }
                ?: download(cue.sheetUrl, MAX_SHEET_BYTES)?.also { bytes -> synchronized(sheets) { sheets[cue.sheetUrl] = bytes } }
                ?: return@withContext null
            val picture = decodeTile(sheet, cue)?.asImageBitmap() ?: return@withContext null
            synchronized(tiles) { tiles[tileKey] = picture }
            picture
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeTile(sheet: ByteArray, cue: Cue): Bitmap? {
        val decoder = if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(sheet, 0, sheet.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(sheet, 0, sheet.size, false)
        } ?: return null
        return try {
            val right = (cue.x + cue.w).coerceAtMost(decoder.width)
            val bottom = (cue.y + cue.h).coerceAtMost(decoder.height)
            if (cue.x < 0 || cue.y < 0 || right <= cue.x || bottom <= cue.y) return null
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            decoder.decodeRegion(Rect(cue.x, cue.y, right, bottom), options)
        } finally {
            decoder.recycle()
        }
    }

    /** A signed address's bytes (no key goes with it), at most [limit]; null when it can't be read. */
    private fun download(url: String, limit: Long): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > limit) return null
            val source = body.source()
            if (source.request(limit + 1)) return null
            return source.readByteArray()
        }
    }

    internal companion object {
        private const val BASE_URL = "https://api.seekr.tv/"
        private const val CALL_TIMEOUT_S = 12L
        private const val MAX_TRACKS = 4
        private const val MAX_SHEETS = 3
        private const val MAX_TILES = 48
        private const val MAX_VTT_BYTES = 4L * 1024 * 1024
        private const val MAX_SHEET_BYTES = 8L * 1024 * 1024
        /** Seekr's addresses are signed for a while: a title's list is asked for again after this. */
        private const val TRACK_TTL_MS = 30L * 60 * 1000
        /** A title without pictures isn't asked about again for this long. */
        private const val NONE_RETRY_MS = 6L * 60 * 60 * 1000
        /** A track is looked up again this long before its addresses expire. */
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val DEFAULT_WAIT_S = 60L
        private const val MAX_WAIT_S = 3_600L

        /** Seekr's list of pictures (WebVTT): each stretch's times, then its tile ("sheet#xywh=x,y,w,h"). */
        fun parseVtt(text: String, vttUrl: String): List<Cue> {
            val base = vttUrl.toHttpUrlOrNull()
            val lines = text.lines()
            val cues = ArrayList<Cue>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if ("-->" in line) {
                    val parts = line.split("-->")
                    if (parts.size == 2) {
                        val start = timeOf(parts[0].trim())
                        val end = timeOf(parts[1].trim().substringBefore(' '))
                        var next = i + 1
                        while (next < lines.size && lines[next].isBlank()) next++
                        val payload = lines.getOrNull(next)?.trim()
                        if (start != null && end != null && payload != null) {
                            tileOf(start, end, payload, base)?.let(cues::add)
                        }
                    }
                }
                i++
            }
            cues.sortBy { it.startMs }
            return cues
        }

        private fun tileOf(start: Long, end: Long, payload: String, base: okhttp3.HttpUrl?): Cue? {
            val hash = payload.lastIndexOf('#')
            if (hash <= 0) return null
            val fragment = payload.substring(hash + 1)
            if (!fragment.startsWith("xywh=")) return null
            val numbers = fragment.removePrefix("xywh=").split(',').map { it.trim().toIntOrNull() }
            if (numbers.size < 4 || numbers.any { it == null }) return null
            val sheet = payload.substring(0, hash)
            // The sheet's address as it is, or next to the list's when it's given relative to it.
            val sheetUrl = if (sheet.startsWith("https://")) sheet else base?.resolve(sheet)?.toString() ?: return null
            if (!sheetUrl.startsWith("https://")) return null
            return Cue(start, end, sheetUrl, numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!)
        }

        /** A signed address's "exp" (Unix seconds) in milliseconds; null without one. */
        internal fun expiryOf(url: String): Long? =
            url.toHttpUrlOrNull()?.queryParameter("exp")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)

        /** "01:02:03.500" or "02:03.500" in milliseconds. */
        private fun timeOf(text: String): Long? {
            val parts = text.split(':')
            val seconds = parts.lastOrNull()?.toDoubleOrNull() ?: return null
            val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
            val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: 0L else 0L
            return hours * 3_600_000L + minutes * 60_000L + (seconds * 1000).toLong()
        }
    }
}

/** RCTV: the Seekr client for the player's preview window (which isn't made by Hilt). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SeekrEntryPoint {
    fun seekrPreviews(): SeekrPreviews
}
