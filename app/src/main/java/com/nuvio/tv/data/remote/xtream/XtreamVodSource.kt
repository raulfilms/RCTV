package com.nuvio.tv.data.remote.xtream

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.LiveTvConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A movie or an episode found in the IPTV's own catalog: its address (carries the login: never logged). */
data class XtreamVodHit(val url: String, val quality: String?, val label: String)

/**
 * RCTV (owner): the IPTV account's own movies and series (Xtream Codes VOD), never shown in the app,
 * used only as the very last option: when no server, plugin or the app's last-resort sources have a
 * movie or an episode, and the IPTV has it, it plays from there.
 *
 * The catalog's lists are big (tens of thousands of titles), so they're read as they download and
 * kept as a small file of names and ids (no login in it), made again once a day; a title is looked
 * for in that file (by its TMDB or IMDb id when the IPTV says them, else by its English name and
 * year). A series' episodes are asked of the IPTV only for the series found. English versions only
 * (titles start in English): a version the IPTV marks Spanish, Latino or another language is left out.
 */
@Singleton
class XtreamVodSource @Inject constructor(
    @ApplicationContext context: Context,
    private val xtreamClient: XtreamClient,
    private val liveTvDataStore: LiveTvDataStore,
    private val tmdbApi: TmdbApi
) {
    private val file = File(context.filesDir, INDEX_FILE)
    private val buildLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastFailedAtMs = 0L

    /** The account's IPTV login (Xtream), or null (no IPTV: a Light account, an M3U list). */
    private fun connection(): LiveTvConnection.Xtream? =
        liveTvDataStore.sources.value.firstOrNull { it.enabled && it.connection is LiveTvConnection.Xtream }
            ?.connection as? LiveTvConnection.Xtream

    /** The catalog's file made again in the background when it's a day old (or for another account). */
    fun refreshIfStale() {
        val login = connection() ?: return
        if (isFresh(login) || buildLock.isLocked || System.currentTimeMillis() - lastFailedAtMs < RETRY_AFTER_MS) return
        scope.launch { ensureIndex(login) }
    }

    /**
     * [type] "movie" or "series"; the title's TMDB id, its IMDb id when known; an episode's season
     * and number. Its versions in the IPTV, best quality first (at most [MAX_HITS]); empty when the
     * IPTV doesn't have it.
     */
    suspend fun find(type: String, tmdbId: String?, imdbId: String?, season: Int?, episode: Int?): List<XtreamVodHit> =
        withContext(Dispatchers.IO) {
            val login = connection() ?: return@withContext emptyList()
            val isSeries = type.equals("series", true) || type.equals("tv", true) || type.equals("show", true)
            if (isSeries && (season == null || episode == null)) return@withContext emptyList()
            if (!ensureIndex(login)) return@withContext emptyList()
            val named = tmdbId?.toIntOrNull()?.let { namesOf(it, isSeries) }
            val keys = named?.keys.orEmpty()
            val year = named?.year
            val matches = scan(if (isSeries) KIND_SERIES else KIND_MOVIE) { entry ->
                (tmdbId != null && entry.tmdb == tmdbId) ||
                    (imdbId != null && entry.imdb.equals(imdbId, ignoreCase = true)) ||
                    (entry.key in keys && (year == null || entry.year == null || kotlin.math.abs(entry.year - year) <= 1))
            }.filter { it.english }
                .sortedByDescending { qualityRank(it.quality) }
            if (matches.isEmpty()) return@withContext emptyList()
            val label = named?.title ?: ""
            if (!isSeries) {
                return@withContext matches.take(MAX_HITS).map { entry ->
                    XtreamVodHit(xtreamClient.movieUrl(login, entry.id, entry.ext), entry.quality, label)
                }
            }
            // A series: its episodes asked of the IPTV, for the first versions found.
            val hits = ArrayList<XtreamVodHit>()
            for (entry in matches.take(MAX_SERIES_TRIED)) {
                val episodes = try {
                    xtreamClient.fetchSeriesEpisodes(login, entry.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "An IPTV series' episodes couldn't be read: ${e.javaClass.simpleName}")
                    continue
                }
                episodes.firstOrNull { it.season == season && it.episode == episode }?.let {
                    hits += XtreamVodHit(it.streamUrl, entry.quality, label)
                }
                if (hits.size >= MAX_HITS) break
            }
            hits
        }

    private class Named(val title: String, val keys: Set<String>, val year: Int?)

    /** The title's English names (and original) and year, from TMDB. */
    private suspend fun namesOf(tmdbId: Int, isSeries: Boolean): Named? = try {
        val response = if (isSeries) tmdbApi.getTvDetails(tmdbId, BuildConfig.TMDB_API_KEY, "en-US")
        else tmdbApi.getMovieDetails(tmdbId, BuildConfig.TMDB_API_KEY, "en-US")
        response.body()?.let { body ->
            val title = (if (isSeries) body.name else body.title) ?: body.title ?: body.name
            val original = if (isSeries) body.originalName else body.originalTitle
            val keys = listOfNotNull(title, original).map { keyOf(it) }.filter { it.isNotBlank() }.toSet()
            val year = (if (isSeries) body.firstAirDate else body.releaseDate)?.take(4)?.toIntOrNull()
            title?.let { Named(it, keys, year) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private class Entry(
        val key: String,
        val year: Int?,
        val id: String,
        val ext: String,
        val tmdb: String?,
        val imdb: String?,
        val quality: String?,
        val english: Boolean
    )

    /** The catalog's entries of [kind] that [wanted] keeps, read from the file line by line. */
    private fun scan(kind: String, wanted: (Entry) -> Boolean): List<Entry> {
        val found = ArrayList<Entry>()
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith(kind)) continue
                    val parts = line.split('\t')
                    if (parts.size < 9) continue
                    val entry = Entry(
                        key = parts[1],
                        year = parts[2].toIntOrNull(),
                        id = parts[3],
                        ext = parts[4].ifBlank { "mp4" },
                        tmdb = parts[5].ifBlank { null },
                        imdb = parts[6].ifBlank { null },
                        quality = parts[7].ifBlank { null },
                        english = parts[8] == "1"
                    )
                    if (wanted(entry)) found += entry
                }
            }
        }
        return found
    }

    private fun accountOf(login: LiveTvConnection.Xtream): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${login.serverUrl.trim()}|${login.username.trim()}|${login.password.trim()}".toByteArray())
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun isFresh(login: LiveTvConnection.Xtream): Boolean {
        if (!file.exists()) return false
        val header = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull() ?: return false
        val parts = header.split('\t')
        if (parts.size < 3 || parts[0] != HEADER) return false
        val builtAt = parts[1].toLongOrNull() ?: return false
        return parts[2] == accountOf(login) && System.currentTimeMillis() - builtAt < KEEP_MS
    }

    /** The catalog's file, made when it's missing, a day old or another account's. False when it can't be. */
    private suspend fun ensureIndex(login: LiveTvConnection.Xtream): Boolean = buildLock.withLock {
        if (isFresh(login)) return@withLock true
        val temp = File(file.parentFile, "$INDEX_FILE.part")
        try {
            val started = System.currentTimeMillis()
            var movies = 0
            var series = 0
            temp.bufferedWriter().use { out ->
                out.write("$HEADER\t$started\t${accountOf(login)}\n")
                xtreamClient.streamPlayerApi(login, "get_vod_streams") { stream ->
                    movies = readList(stream, KIND_MOVIE, idField = "stream_id") { out.write(it) }
                }
                xtreamClient.streamPlayerApi(login, "get_series") { stream ->
                    series = readList(stream, KIND_SERIES, idField = "series_id") { out.write(it) }
                }
            }
            if (movies + series == 0) {
                temp.delete()
                lastFailedAtMs = System.currentTimeMillis()
                return@withLock file.exists()
            }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            Log.i(TAG, "IPTV catalog kept: $movies movies, $series series, in ${System.currentTimeMillis() - started} ms")
            true
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            lastFailedAtMs = System.currentTimeMillis()
            Log.w(TAG, "The IPTV catalog couldn't be read: ${e.javaClass.simpleName}")
            file.exists()
        }
    }

    /** One list (movies or series) as it downloads, each title a line to [write]; how many. */
    private fun readList(stream: java.io.InputStream, kind: String, idField: String, write: (String) -> Unit): Int {
        var count = 0
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return 0
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                var id: String? = null
                var name: String? = null
                var ext: String? = null
                var year: String? = null
                var tmdb: String? = null
                var imdb: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    if (reader.peek() == JsonToken.NULL || reader.peek() == JsonToken.BEGIN_OBJECT || reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.skipValue()
                        continue
                    }
                    val value = runCatching { reader.nextString() }.getOrNull()
                    when (field) {
                        idField -> id = value
                        "name" -> name = value
                        "container_extension" -> ext = value
                        "year", "releaseDate", "release_date" -> if (year.isNullOrBlank()) year = value?.take(4)
                        "tmdb", "tmdb_id" -> tmdb = value
                        "imdb", "imdb_id" -> imdb = value
                    }
                }
                reader.endObject()
                val rawName = name?.takeIf { it.isNotBlank() } ?: continue
                val streamId = id?.takeIf { it.isNotBlank() } ?: continue
                val clean = clean(rawName)
                val key = keyOf(clean.title)
                if (key.isBlank()) continue
                val entryYear = year?.toIntOrNull()?.takeIf { it in 1900..2100 } ?: clean.year
                write(
                    listOf(
                        kind, key, entryYear?.toString().orEmpty(), streamId, ext.orEmpty().trim().take(8),
                        tmdb.orEmpty().trim().takeIf { it != "0" }.orEmpty(), imdb.orEmpty().trim(),
                        clean.quality.orEmpty(), if (clean.english) "1" else "0"
                    ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n"
                )
                count++
            }
            reader.endArray()
        }
        return count
    }

    internal class Cleaned(val title: String, val year: Int?, val quality: String?, val english: Boolean)

    companion object {
        private const val TAG = "XtreamVod"
        private const val INDEX_FILE = "iptv_vod_catalog.tsv"
        private const val HEADER = "#rctv-iptv-vod-1"
        private const val KIND_MOVIE = "M\t"
        private const val KIND_SERIES = "S\t"
        private const val KEEP_MS = 24L * 60 * 60 * 1000
        private const val RETRY_AFTER_MS = 60L * 60 * 1000
        private const val MAX_HITS = 3
        private const val MAX_SERIES_TRIED = 3

        /** Languages an IPTV marks its versions with (other than English). */
        private val OTHER_LANGUAGES = setOf(
            "es", "esp", "spa", "sp", "lat", "latino", "latam", "mx", "cast", "castellano", "fr", "fra", "vf",
            "de", "ger", "it", "ita", "pt", "por", "br", "ptbr", "nl", "pl", "ru", "tr", "ar", "arabic", "hi",
            "hin", "in", "ind", "ko", "kr", "jp", "ja", "zh", "cn", "ch", "gr", "se", "swe", "no", "dk", "fi",
            "ro", "hu", "cz", "al", "ex-yu", "yu", "il", "ir", "pk", "bd", "ph", "vn", "th", "id", "my"
        )
        /** Words that only mark a version's language (anywhere in its name). */
        private val OTHER_LANGUAGE_WORDS = Regex(
            "(?i)\\b(latino|castellano|espa[nñ]ol|subtitulad[oa]|doblad[oa]|dublado|legendado|vostfr)\\b"
        )
        /** A language said in brackets: "(Spanish)", "[French]" (not "The French Connection"). */
        private val BRACKETED_LANGUAGE = Regex(
            "(?i)[\\[(][^\\])]*\\b(spanish|french|german|italian|portuguese|arabic|hindi|turkish|russian|polish)\\b[^\\])]*[\\])]"
        )
        /** Tags an IPTV puts before a name ("EN - ", "|4K| ", "NF: "): only these are taken off. */
        private val ENGLISH_TAGS = setOf("en", "eng", "english", "us", "usa", "uk", "gb", "ca", "au", "nz", "ie")
        private val OTHER_TAGS = setOf(
            "4k", "uhd", "fhd", "hd", "sd", "hdr", "3d", "multi", "multisub", "vip", "new", "top", "kids",
            "nf", "netflix", "amz", "amzn", "amazon", "prime", "dsnp", "disney", "disney+", "hbo", "max",
            "hulu", "atv", "apple", "appletv", "pmtp", "prmt", "paramount", "pcok", "peacock", "starz",
            "showtime", "sho", "amc", "bbc", "itv", "movies", "movie", "series", "tv", "vod", "24/7"
        )
        private val PREFIX = Regex("^\\s*(?:[\\[|(]\\s*([^\\]|)]{1,14})\\s*[\\]|)]|([A-Za-z0-9+/]{2,8})\\s*[-:|•]\\s)\\s*")
        private val YEAR = Regex("[\\[(]\\s*((?:19|20)\\d{2})\\s*[\\])]|\\s-\\s*((?:19|20)\\d{2})\\s*$")
        private val BRACKETS = Regex("[\\[(][^\\])]*[\\])]")
        private val QUALITY_WORDS = Regex(
            "(?i)\\b(4k|uhd|2160p?|fhd|1080p?|hd|720p?|sd|480p?|hdr10\\+?|hdr|dv|dolby vision|hevc|x265|x264|h264|h265|multi|multisub|multi-sub|sub|subs|vose|dual|imax|web-?dl|bluray|remux|3d)\\b"
        )

        /** The IPTV's name of a title: its name alone, its year, quality and whether it's English. */
        internal fun clean(raw: String): Cleaned {
            var text = raw.trim()
            var english = !OTHER_LANGUAGE_WORDS.containsMatchIn(text) && !BRACKETED_LANGUAGE.containsMatchIn(text)
            // Leading tags: "EN - ", "|EN| ", "[4K] ", "NF: " (a title's own "Mission: " stays).
            for (round in 0 until 3) {
                val match = PREFIX.find(text) ?: break
                val tag = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim().lowercase(Locale.ROOT)
                val words = tag.split(' ', '-', '+', '/', ',').filter { it.isNotBlank() }
                if (words.isEmpty()) break
                val known = words.all { it in OTHER_LANGUAGES || it in ENGLISH_TAGS || it in OTHER_TAGS || it == tag && tag in OTHER_TAGS }
                if (!known) break
                if (words.any { it in OTHER_LANGUAGES }) english = false
                text = text.substring(match.range.last + 1)
            }
            val quality = QUALITY_WORDS.findAll(raw).map { it.value.lowercase(Locale.ROOT) }.let { words ->
                val all = words.toList()
                when {
                    all.any { it == "4k" || it == "uhd" || it.startsWith("2160") } -> "4K"
                    all.any { it == "fhd" || it.startsWith("1080") } -> "1080p"
                    all.any { it == "hd" || it.startsWith("720") } -> "720p"
                    all.any { it == "sd" || it.startsWith("480") } -> "480p"
                    else -> null
                }
            }
            val yearMatch = YEAR.find(text)
            val year = yearMatch?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
            if (yearMatch != null) text = text.removeRange(yearMatch.range)
            // Tags in brackets go ("[Multi]", "(Latino)"); a title's own number stays ("(500) Days of Summer").
            text = BRACKETS.replace(text) { group ->
                val inside = group.value.drop(1).dropLast(1).trim()
                if (inside.isNotEmpty() && inside.all { it.isDigit() }) inside else " "
            }
            text = QUALITY_WORDS.replace(text, " ")
            return Cleaned(text.trim().trim('-', '|', ':', ' '), year, quality, english)
        }

        /** A name made comparable: no accents, case or signs, "&" as "and", no leading "The". */
        internal fun keyOf(name: String): String {
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return plain.lowercase(Locale.ROOT)
                .replace("'", "").replace("\u2019", "")
                .replace("&", " and ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .removePrefix("the ")
                .trim()
        }

        private fun qualityRank(quality: String?): Int = when (quality) {
            "4K" -> 3
            "1080p" -> 2
            "720p" -> 1
            null -> 1
            else -> 0
        }
    }
}
