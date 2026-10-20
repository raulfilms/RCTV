package com.nuvio.tv.core.tmdb

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbTitleSpotlightResponse
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.TitleArtResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TitleSpotlight"
private const val LOGO_BASE = "https://image.tmdb.org/t/p/w500"

/**
 * RCTV: the title cards of International Spotlight and Latin America & Spanish-Language Hits
 * ([TitleSpotlightStyle]): each title's official logo (or its title as text) and, on International
 * Spotlight, its country's flag.
 *
 * Each title costs one TMDB request (its names, original language, countries and logos together),
 * once: what TMDB says is kept in memory and on the device for two weeks, so scrolling a row, going
 * back to Home or opening the app again doesn't ask again. A few lookups run at a time; one asked
 * for by two cards (the same title in both rows) runs once and isn't stopped when a card scrolls
 * away. When TMDB can't be reached the card shows the title as text and it's asked again a minute
 * later; nothing here can leave a card blank or throw.
 */
@Singleton
class TmdbTitleSpotlightService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(val info: TitleSpotlightInfo, val savedAt: Long)

    /** By "movie:<tmdb id>" / "tv:<tmdb id>". */
    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<TitleSpotlightInfo?>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** IMDb ids already matched to their key (cards of titles known by an IMDb id). */
    private val keyByImdbId = ConcurrentHashMap<String, String>()
    private val limiter = Semaphore(MAX_LOOKUPS_AT_ONCE)

    private val savedFile: File get() = File(context.cacheDir, SAVED_FILE)
    private val loaded = CompletableDeferred<Unit>()
    @Volatile private var saveJob: Job? = null

    init {
        // What was kept on the device is read at once, so cards show it on their first draw.
        scope.launch {
            try {
                readSaved()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the kept title cards: ${e.message}")
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    private val resolvers = TitleSpotlightStyle.entries.associateWith { Resolver(it) }

    /** The cards' resolver for [style] (see [TitleArtResolver]). */
    fun resolver(style: TitleSpotlightStyle): TitleArtResolver = resolvers.getValue(style)

    private inner class Resolver(private val style: TitleSpotlightStyle) : TitleArtResolver {
        override fun cached(item: MetaPreview): MetaPreview? {
            val key = keyOf(item) ?: return if (canLookUp(item)) null else item.plainCard()
            val entry = entries[key] ?: return null
            return apply(style, item, entry.info)
        }

        override suspend fun resolve(item: MetaPreview): MetaPreview {
            val key = try {
                keyOf(item) ?: lookUpKey(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return item.plainCard()
            val info = infoFor(key) ?: return item.plainCard()
            return apply(style, item, info)
        }
    }

    // ------------------------------------------------------------------ the card

    private fun apply(style: TitleSpotlightStyle, item: MetaPreview, info: TitleSpotlightInfo): MetaPreview =
        item.plainCard().copy(
            name = TitleSpotlightPicker.displayTitle(style, info, item.name),
            logo = TitleSpotlightPicker.pickLogo(style, info)?.let { "$LOGO_BASE$it" },
            flagCountry = TitleSpotlightPicker.flagCountry(style, info)
        )

    /**
     * The card with its clean backdrop and nothing on it yet: every card of these rows has the
     * title (logo or text) in the same place, never a backdrop with the title already printed on it.
     */
    private fun MetaPreview.plainCard(): MetaPreview =
        if (!landscapeHasTitle && logo == null) this
        else copy(
            landscapeHasTitle = false,
            landscapePoster = if (landscapeHasTitle) background ?: landscapePoster else landscapePoster,
            logo = null
        )

    // ------------------------------------------------------------------ which title

    private fun MetaPreview.isTv(): Boolean =
        type == ContentType.SERIES || type == ContentType.TV ||
            rawType.equals("series", true) || rawType.equals("tv", true)

    private fun canLookUp(item: MetaPreview): Boolean =
        item.id.startsWith("tmdb:") || imdbIdOf(item) != null

    private fun imdbIdOf(item: MetaPreview): String? =
        item.id.takeIf { it.startsWith("tt") }?.substringBefore(':')
            ?: item.imdbId?.takeIf { it.startsWith("tt") }

    private fun keyOf(item: MetaPreview): String? {
        val kind = if (item.isTv()) "tv" else "movie"
        item.id.takeIf { it.startsWith("tmdb:") }
            ?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
            ?.let { return "$kind:$it" }
        return imdbIdOf(item)?.let { keyByImdbId["$kind:$it"] }
    }

    private suspend fun lookUpKey(item: MetaPreview): String? {
        val imdbId = imdbIdOf(item) ?: return null
        val kind = if (item.isTv()) "tv" else "movie"
        val tmdbId = tmdbService.ensureTmdbId(imdbId, kind)?.toIntOrNull() ?: return null
        return "$kind:$tmdbId".also { keyByImdbId["$kind:$imdbId"] = it }
    }

    // ------------------------------------------------------------------ TMDB

    private suspend fun infoFor(key: String): TitleSpotlightInfo? {
        loaded.await()
        entries[key]?.let { return it.info }
        failedAt[key]?.let { at ->
            if (System.currentTimeMillis() - at < RETRY_AFTER_FAILURE_MS) return null
            failedAt.remove(key, at)
        }
        // Run in this service's scope: a card that scrolls away doesn't stop it for the others.
        val lookup = inFlight[key] ?: scope.async { fetch(key) }.also { created ->
            inFlight.putIfAbsent(key, created)?.let { existing ->
                created.cancel()
                return awaitSafely(existing)
            }
            created.invokeOnCompletion { inFlight.remove(key, created) }
        }
        return awaitSafely(lookup)
    }

    /** The lookup's answer; null (the card shows the title as text) if it failed in any way. */
    private suspend fun awaitSafely(lookup: Deferred<TitleSpotlightInfo?>): TitleSpotlightInfo? =
        try {
            lookup.await()
        } catch (e: CancellationException) {
            // This card itself went away: that stands. Otherwise the lookup was the one stopped.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Title card lookup failed: ${e.message}")
            null
        }

    private suspend fun fetch(key: String): TitleSpotlightInfo? = limiter.withPermit {
        val isTv = key.startsWith("tv:")
        val tmdbId = key.substringAfter(':').toIntOrNull() ?: return@withPermit null
        val response = try {
            if (isTv) {
                tmdbApi.getTvTitleSpotlight(tmdbId, apiKey, includeImageLanguage = IMAGE_LANGUAGES)
            } else {
                tmdbApi.getMovieTitleSpotlight(tmdbId, apiKey, includeImageLanguage = IMAGE_LANGUAGES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$key: ${e.message}")
            null
        }
        val info = when {
            response == null -> null
            response.isSuccessful -> response.body()?.toInfo()
            // TMDB has no such title: nothing to show but its name, and nothing to ask again.
            response.code() == 404 -> TitleSpotlightInfo(null, null, null, null, emptyList())
            else -> null
        }
        if (info == null) {
            failedAt[key] = System.currentTimeMillis()
        } else {
            entries[key] = Entry(info, System.currentTimeMillis())
            failedAt.remove(key)
            scheduleSave()
        }
        info
    }

    private fun TmdbTitleSpotlightResponse.toInfo(): TitleSpotlightInfo {
        val language = originalLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wanted = setOfNotNull("en", "es", language, if (language == "cn") "zh" else null)
        // Only the logos a card could use (the best few per language), so little is kept.
        val logos = images?.logos.orEmpty()
            .mapNotNull { image ->
                val path = image.filePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lang = image.iso6391?.lowercase()?.takeIf { it in wanted } ?: return@mapNotNull null
                SpotlightLogo(path, lang, image.iso31661?.uppercase(), image.voteAverage ?: 0.0)
            }
            .groupBy { it.language }
            .flatMap { (_, list) -> list.sortedByDescending { it.vote }.take(LOGOS_PER_LANGUAGE) }
        return TitleSpotlightInfo(
            englishTitle = (title ?: name)?.trim()?.takeIf { it.isNotEmpty() },
            originalTitle = (originalTitle ?: originalName)?.trim()?.takeIf { it.isNotEmpty() },
            originalLanguage = language,
            country = TitleSpotlightPicker.primaryCountry(
                originCountries = originCountry,
                productionCountries = productionCountries.orEmpty().mapNotNull { it.iso31661 },
                originalLanguage = language
            ),
            logos = logos
        )
    }

    // ------------------------------------------------------------------ kept on the device

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            try {
                save()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep the title cards: ${e.message}")
            }
        }
    }

    private fun save() {
        val kept = entries.entries
            .sortedByDescending { it.value.savedAt }
            .take(MAX_KEPT)
        val root = JSONObject()
        val list = JSONObject()
        kept.forEach { (key, entry) ->
            val info = entry.info
            val logos = JSONArray()
            info.logos.forEach { logo ->
                logos.put(
                    JSONObject()
                        .put("p", logo.path)
                        .put("l", logo.language ?: JSONObject.NULL)
                        .put("c", logo.country ?: JSONObject.NULL)
                        .put("v", logo.vote)
                )
            }
            list.put(
                key,
                JSONObject()
                    .put("at", entry.savedAt)
                    .put("en", info.englishTitle ?: JSONObject.NULL)
                    .put("ot", info.originalTitle ?: JSONObject.NULL)
                    .put("ol", info.originalLanguage ?: JSONObject.NULL)
                    .put("c", info.country ?: JSONObject.NULL)
                    .put("logos", logos)
            )
        }
        root.put("v", SAVED_VERSION)
        root.put("entries", list)
        val temp = File(context.cacheDir, "$SAVED_FILE.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(savedFile)) {
            savedFile.delete()
            temp.renameTo(savedFile)
        }
    }

    private fun readSaved() {
        val file = savedFile
        if (!file.exists()) return
        val root = JSONObject(file.readText())
        if (root.optInt("v") != SAVED_VERSION) {
            file.delete()
            return
        }
        val list = root.optJSONObject("entries") ?: return
        val oldest = System.currentTimeMillis() - KEEP_FOR_MS
        list.keys().forEach { key ->
            val item = list.optJSONObject(key) ?: return@forEach
            val savedAt = item.optLong("at")
            if (savedAt < oldest) return@forEach
            val logosJson = item.optJSONArray("logos") ?: JSONArray()
            val logos = (0 until logosJson.length()).mapNotNull { index ->
                val logo = logosJson.optJSONObject(index) ?: return@mapNotNull null
                SpotlightLogo(
                    path = logo.optString("p").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    language = logo.optStringOrNull("l"),
                    country = logo.optStringOrNull("c"),
                    vote = logo.optDouble("v", 0.0)
                )
            }
            val info = TitleSpotlightInfo(
                englishTitle = item.optStringOrNull("en"),
                originalTitle = item.optStringOrNull("ot"),
                originalLanguage = item.optStringOrNull("ol"),
                country = item.optStringOrNull("c"),
                logos = logos
            )
            entries.putIfAbsent(key, Entry(info, savedAt))
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_LOOKUPS_AT_ONCE = 4
        const val RETRY_AFTER_FAILURE_MS = 60_000L
        const val LOGOS_PER_LANGUAGE = 3
        /** English and Spanish, and the Latin-script languages International Spotlight's titles come in. */
        const val IMAGE_LANGUAGES = "en,es,fr,it,de,pt,tr,da,sv,no,pl,nl,id,tl"
        const val SAVED_FILE = "title_spotlight_cards.json"
        const val SAVED_VERSION = 1
        const val SAVE_DELAY_MS = 3_000L
        const val KEEP_FOR_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_KEPT = 3_000
    }
}
