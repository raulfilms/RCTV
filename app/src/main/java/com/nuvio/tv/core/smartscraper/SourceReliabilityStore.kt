package com.nuvio.tv.core.smartscraper

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How each addon/service has been doing, kept on the device: whether it answers and how fast, and
 * whether its streams play. Recent results count more than old ones, so a provider that starts
 * failing slips down and one that keeps working climbs back. Also the files that failed to play
 * lately (by their torrent hash or link only, never a title). Nothing else is kept.
 */
@Singleton
class SourceReliabilityStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providers = ConcurrentHashMap<String, ProviderStats>()
    private val failedReleases = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    @Volatile private var loaded = false
    private var saveJob: Job? = null

    fun providerStats(): Map<String, ProviderStats> {
        ensureLoaded()
        return HashMap(providers)
    }

    /** The files that failed to play in the last days. */
    fun failedReleaseKeys(): Set<String> {
        ensureLoaded()
        val since = System.currentTimeMillis() - FAILED_RELEASE_MAX_AGE_MS
        return failedReleases.filterValues { it >= since }.keys
    }

    /** An addon answered (with streams or not) after [responseMs], or failed / timed out. */
    fun recordResponse(provider: String, responseMs: Long?, ok: Boolean) {
        if (provider.isBlank()) return
        ensureLoaded()
        providers.compute(provider) { _, old -> updated(old ?: ProviderStats.NONE, ok, weight = 1.0, responseMs = responseMs) }
        scheduleSave()
    }

    /** A stream of [provider] played (or didn't); a failed file ([releaseKey]) is ranked down for a while. */
    fun recordPlayback(provider: String, releaseKey: String?, ok: Boolean) {
        if (provider.isBlank()) return
        ensureLoaded()
        // Whether its streams play says more than whether it answers.
        providers.compute(provider) { _, old -> updated(old ?: ProviderStats.NONE, ok, weight = 2.0, responseMs = null) }
        if (releaseKey != null) {
            if (ok) failedReleases.remove(releaseKey) else failedReleases[releaseKey] = System.currentTimeMillis()
        }
        scheduleSave()
    }

    private fun updated(old: ProviderStats, ok: Boolean, weight: Double, responseMs: Long?): ProviderStats {
        val response = if (ok && responseMs != null && responseMs > 0) {
            old.averageResponseMs?.let { it + (responseMs - it) * RESPONSE_SMOOTHING } ?: responseMs.toDouble()
        } else {
            old.averageResponseMs
        }
        return ProviderStats(
            successes = old.successes * DECAY + if (ok) weight else 0.0,
            failures = old.failures * DECAY + if (ok) 0.0 else weight,
            averageResponseMs = response
        )
    }

    // ------------------------------------------------------------------ On the device

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching { read() }.onFailure { Log.w(TAG, "Couldn't read the sources' record: ${it.message}") }
            loaded = true
        }
    }

    private fun read() {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        if (json.optInt("v") != FILE_VERSION) return
        json.optJSONObject("providers")?.let { all ->
            all.keys().forEach { key ->
                val entry = all.optJSONObject(key) ?: return@forEach
                providers[key] = ProviderStats(
                    successes = entry.optDouble("s", 0.0),
                    failures = entry.optDouble("f", 0.0),
                    averageResponseMs = entry.optDouble("rt", -1.0).takeIf { it > 0 }
                )
            }
        }
        json.optJSONObject("failed")?.let { all ->
            all.keys().forEach { key -> failedReleases[key] = all.optLong(key) }
        }
    }

    private fun scheduleSave() {
        synchronized(lock) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                save()
            }
        }
    }

    private fun save() {
        runCatching {
            val since = System.currentTimeMillis() - FAILED_RELEASE_MAX_AGE_MS
            val providersJson = JSONObject()
            providers.entries.sortedByDescending { it.value.samples }.take(MAX_PROVIDERS).forEach { (key, stats) ->
                providersJson.put(key, JSONObject().apply {
                    put("s", stats.successes)
                    put("f", stats.failures)
                    stats.averageResponseMs?.let { put("rt", it) }
                })
            }
            val failedJson = JSONObject()
            failedReleases.entries.filter { it.value >= since }.sortedByDescending { it.value }.take(MAX_FAILED)
                .forEach { (key, at) -> failedJson.put(key, at) }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(JSONObject().put("v", FILE_VERSION).put("providers", providersJson).put("failed", failedJson).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Couldn't keep the sources' record: ${it.message}") }
    }

    private companion object {
        const val TAG = "SmartScraper"
        const val FILE_NAME = "smart_sources.json"
        const val FILE_VERSION = 1
        /** Each new result makes the older ones count this much less (about the last 20 count). */
        const val DECAY = 0.95
        const val RESPONSE_SMOOTHING = 0.3
        const val FAILED_RELEASE_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        const val SAVE_DELAY_MS = 2_000L
        const val MAX_PROVIDERS = 60
        const val MAX_FAILED = 300
    }
}
