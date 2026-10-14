package com.nuvio.tv.data.layout

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.RowLayoutConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The row layout (vertical or horizontal, row by row) that the website controls.
 *
 * The app ships with [ASSET_FILE] built in and also reads the same file online ([BuildConfig.ROW_LAYOUT_URL]),
 * so a change made on the website (or straight in that file on GitHub) reaches every TV without a new
 * build: when the app starts, and again whenever it comes back to the front (at most once a minute).
 * The last copy read online is kept, so the TV keeps the layout when it is offline.
 */
@Singleton
class RowLayoutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient
) {
    private val httpClient = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _config = MutableStateFlow(RowLayoutConfig.DEFAULT)
    val config: StateFlow<RowLayoutConfig> = _config.asStateFlow()

    private val fetching = AtomicBoolean(false)
    @Volatile private var lastFetchAt = 0L

    init {
        scope.launch {
            loadLocal()?.let { _config.value = it }
            fetchRemote()
        }
    }

    /** Reads the online file again (skipped when it was read less than a minute ago, unless [force]). */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetchAt < MIN_REFRESH_INTERVAL_MS) return
        scope.launch { fetchRemote() }
    }

    private fun loadLocal(): RowLayoutConfig? {
        val cached = runCatching { prefs.getString(KEY_CACHED_JSON, null) }.getOrNull()
        cached?.let { RowLayoutConfig.parse(it) }?.let { return it }
        val bundled = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()
        return bundled?.let { RowLayoutConfig.parse(it) }
    }

    private fun fetchRemote() {
        val url = BuildConfig.ROW_LAYOUT_URL.trim()
        if (url.isEmpty()) return
        if (!fetching.compareAndSet(false, true)) return
        try {
            lastFetchAt = System.currentTimeMillis()
            // A changing query keeps GitHub's CDN from answering with a copy that is minutes old.
            val separator = if ('?' in url) '&' else '?'
            val request = Request.Builder()
                .url("$url${separator}t=${lastFetchAt / MIN_REFRESH_INTERVAL_MS}")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Row layout not available online (HTTP ${response.code}); keeping the saved one")
                    return
                }
                val body = response.body?.string().orEmpty()
                val parsed = RowLayoutConfig.parse(body)
                if (parsed == null) {
                    Log.w(TAG, "Row layout file online can't be read (bad JSON); keeping the saved one")
                    return
                }
                runCatching { prefs.edit().putString(KEY_CACHED_JSON, body).apply() }
                _config.value = parsed
            }
        } catch (error: Exception) {
            Log.w(TAG, "Row layout fetch failed: ${error.message}")
        } finally {
            fetching.set(false)
        }
    }

    companion object {
        private const val TAG = "RowLayout"
        const val ASSET_FILE = "row_layout.json"
        private const val PREFS_NAME = "rctv_row_layout"
        private const val KEY_CACHED_JSON = "cached_json"
        private const val MIN_REFRESH_INTERVAL_MS = 60_000L
    }
}
