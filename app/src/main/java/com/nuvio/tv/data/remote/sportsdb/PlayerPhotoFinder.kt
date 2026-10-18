package com.nuvio.tv.data.remote.sportsdb

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerPhotoFinder"

/**
 * Players' photos ESPN doesn't have (soccer players, mostly): TheSportsDB's cut-outs (the player on
 * a clear background, like ESPN's), found by the player's name and team. Kept on the device: a
 * photo found for a month, "none" for a week (then it looks again).
 */
@Singleton
class PlayerPhotoFinder @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("sports_player_photos", Context.MODE_PRIVATE)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** TheSportsDB said "too many requests": no more lookups until this time. */
    @Volatile private var pausedUntilMs = 0L

    /**
     * The photo of [name] (of [team], in [sportPath]: "soccer", "basketball"...), or null when
     * TheSportsDB has none (or can't be reached).
     */
    suspend fun photoFor(name: String, team: String?, sportPath: String?): String? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        val key = "${sportPath.orEmpty()}|${normalize(name)}"
        val now = System.currentTimeMillis()
        prefs.getString(key, null)?.let { cached ->
            val url = cached.substringBefore('|')
            val savedAt = cached.substringAfter('|', "0").toLongOrNull() ?: 0L
            val maxAge = if (url.isEmpty()) NONE_MAX_AGE_MS else FOUND_MAX_AGE_MS
            if (now - savedAt < maxAge) return@withContext url.ifEmpty { null }
        }
        if (now < pausedUntilMs) return@withContext null
        val found = runCatching { search(name, team, sportPath) }
            .onFailure { Log.w(TAG, "Lookup failed for $name: ${it.message}") }
            .getOrElse { return@withContext null }
        // Found or surely none: kept. A failed lookup isn't (it's tried again next time).
        prefs.edit().putString(key, "${found.orEmpty()}|$now").apply()
        found
    }

    /** The photo; null when TheSportsDB surely has none; throws when it couldn't be asked. */
    private fun search(name: String, team: String?, sportPath: String?): String? {
        val query = URLEncoder.encode(name.trim().replace(' ', '_'), "UTF-8")
        val request = Request.Builder()
            .url("https://www.thesportsdb.com/api/v1/json/$API_KEY/searchplayers.php?p=$query")
            .header("User-Agent", "RCTV/1.0 (Android TV)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) {
                pausedUntilMs = System.currentTimeMillis() + 60_000L
                error("rate limited")
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val players = JSONObject(response.body?.string().orEmpty()).optJSONArray("player") ?: return null
            val sport = sportName(sportPath)
            val teamKey = team?.let(::normalize).orEmpty()
            val nameKey = normalize(name)
            var best: JSONObject? = null
            var bestScore = -1
            for (i in 0 until players.length()) {
                val player = players.optJSONObject(i) ?: continue
                if (sport != null && !player.optString("strSport").equals(sport, ignoreCase = true)) continue
                var score = 0
                if (normalize(player.optString("strPlayer")) == nameKey) score += 2
                val playerTeam = normalize(player.optString("strTeam"))
                if (teamKey.isNotEmpty() && playerTeam.isNotEmpty() &&
                    (playerTeam.contains(teamKey) || teamKey.contains(playerTeam))
                ) score += 3
                if (score > bestScore) {
                    best = player
                    bestScore = score
                }
            }
            val player = best ?: return null
            return listOf("strCutout", "strRender", "strThumb")
                .firstNotNullOfOrNull { field -> player.optString(field).takeIf { it.startsWith("http") } }
        }
    }

    private fun sportName(sportPath: String?): String? = when (sportPath) {
        "soccer" -> "Soccer"
        "basketball" -> "Basketball"
        "football" -> "American Football"
        "baseball" -> "Baseball"
        "hockey" -> "Ice Hockey"
        else -> null
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\b(fc|cf|afc|sc)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        /** TheSportsDB's free key. */
        const val API_KEY = "123"
        const val FOUND_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val NONE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
