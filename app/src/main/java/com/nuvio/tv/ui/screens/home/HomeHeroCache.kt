package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * RCTV: Home's hero as it last showed, per profile, so it's there the moment Home opens (the
 * catalogs it comes from can take a while to load); the fresh one replaces it once they're in.
 * Only what the hero shows is kept (names, pictures, a few details): nothing private.
 */
internal object HomeHeroCache {
    private const val PREFS = "rctv_home_hero"
    private const val MAX_ITEMS = 10

    fun read(context: Context, profileId: Int): List<MetaPreview> = try {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(keyOf(profileId), null)
        if (text.isNullOrBlank()) {
            emptyList()
        } else {
            val array = JSONArray(text)
            val items = ArrayList<MetaPreview>()
            for (i in 0 until minOf(array.length(), MAX_ITEMS)) {
                array.optJSONObject(i)?.let(::itemOf)?.let(items::add)
            }
            items
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(context: Context, profileId: Int, items: List<MetaPreview>) {
        try {
            val array = JSONArray()
            items.take(MAX_ITEMS).forEach { array.put(jsonOf(it)) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(keyOf(profileId), array.toString())
                .apply()
        } catch (e: Exception) {
            // Not kept this time: the hero loads as before next time.
        }
    }

    private fun keyOf(profileId: Int) = "p$profileId"

    private fun jsonOf(item: MetaPreview): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("type", item.rawType)
        put("name", item.name)
        put("shape", item.posterShape.name)
        putOpt("poster", item.poster)
        putOpt("background", item.background)
        putOpt("logo", item.logo)
        putOpt("description", item.description)
        putOpt("releaseInfo", item.releaseInfo)
        item.imdbRating?.let { put("rating", it.toDouble()) }
        put("genres", JSONArray(item.genres))
        putOpt("runtime", item.runtime)
        putOpt("ageRating", item.ageRating)
        putOpt("released", item.released)
        putOpt("imdbId", item.imdbId)
        putOpt("landscapePoster", item.landscapePoster)
    }

    private fun itemOf(json: JSONObject): MetaPreview? {
        val id = json.text("id") ?: return null
        val name = json.text("name") ?: return null
        val rawType = json.text("type") ?: "movie"
        val genres = json.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
        }.orEmpty()
        return MetaPreview(
            id = id,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            name = name,
            poster = json.text("poster"),
            posterShape = runCatching { PosterShape.valueOf(json.optString("shape")) }.getOrDefault(PosterShape.POSTER),
            background = json.text("background"),
            logo = json.text("logo"),
            description = json.text("description"),
            releaseInfo = json.text("releaseInfo"),
            imdbRating = if (json.has("rating") && !json.isNull("rating")) json.optDouble("rating").toFloat() else null,
            genres = genres,
            runtime = json.text("runtime"),
            ageRating = json.text("ageRating"),
            released = json.text("released"),
            imdbId = json.text("imdbId"),
            landscapePoster = json.text("landscapePoster")
        )
    }

    private fun JSONObject.text(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}

/** RCTV: the profile's hero from last time, shown while the catalogs load (only on an empty hero). */
internal fun HomeViewModel.restoreCachedHeroPipeline() {
    val profileId = profileManager.activeProfileId.value
    viewModelScope.launch {
        val cached = withContext(Dispatchers.IO) { HomeHeroCache.read(appContext, profileId) }
        if (cached.isEmpty() || profileManager.activeProfileId.value != profileId) return@launch
        var shown = false
        _uiState.update { state ->
            if (state.heroItems.isEmpty()) {
                shown = true
                state.copy(heroItems = cached)
            } else {
                state
            }
        }
        if (shown) cachedHeroProfileId = profileId
    }
}

/** RCTV: keeps the hero shown (once it settles) for next time. */
@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeHeroCachePipeline() {
    viewModelScope.launch {
        _uiState
            .map { it.heroItems }
            .distinctUntilChanged()
            .debounce(HERO_SAVE_AFTER_MS)
            .collect { items ->
                if (items.isEmpty()) return@collect
                val profileId = profileManager.activeProfileId.value
                try {
                    withContext(Dispatchers.IO) { HomeHeroCache.save(appContext, profileId, items) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not kept this time.
                }
            }
    }
}

private const val HERO_SAVE_AFTER_MS = 3_000L
