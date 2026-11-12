package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The order and the hidden tiles of Home's Streaming Services row, as the owner set them on the website. */
data class RctvStreamingLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/** A message the owner publishes on the website, shown between [startsAtMs] and [endsAtMs]. */
data class RctvAnnouncement(
    val id: String,
    val title: String,
    val body: String,
    val startsAtMs: Long? = null,
    val endsAtMs: Long? = null
) {
    fun isShownAt(nowMs: Long): Boolean =
        (startsAtMs == null || startsAtMs <= nowMs) && (endsAtMs == null || endsAtMs > nowMs)
}

/** Reads the website's public files. Pure, so it's tested on its own. */
object RctvPublicFileParser {
    private const val MAX_ANNOUNCEMENTS = 20

    fun streamingLayout(body: String?): RctvStreamingLayout? {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val order = json.optJSONArray("order")?.strings() ?: return null
        return RctvStreamingLayout(order = order, hidden = json.optJSONArray("hidden")?.strings()?.toSet().orEmpty())
    }

    fun announcements(body: String?): List<RctvAnnouncement>? {
        val array = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_ANNOUNCEMENTS)) {
                val a = array.optJSONObject(i) ?: continue
                val id = a.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = a.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
                add(
                    RctvAnnouncement(
                        id = id,
                        title = title.take(120),
                        body = a.optString("body").trim().take(1000),
                        startsAtMs = a.timeOrNull("starts_at"),
                        endsAtMs = a.timeOrNull("ends_at")
                    )
                )
            }
        }
    }

    /** [items] in the website's order (the rest after, as they were), without the hidden ones. */
    fun <T> ordered(items: List<T>, layout: RctvStreamingLayout?, keyOf: (T) -> String): List<T> {
        if (layout == null) return items
        val position = layout.order.withIndex().associate { (i, key) -> key to i }
        return items
            .filterNot { keyOf(it) in layout.hidden }
            .withIndex()
            .sortedWith(compareBy({ position[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    private fun JSONArray.strings(): List<String> = buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun JSONObject.timeOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else RctvControlParser.epochMsOf(optString(key))
}

/**
 * RCTV Control: the public files the owner publishes on the website (the Streaming Services row's
 * order, announcements, Home's banners and their collections). Read when the app comes to the front and when their address arrives with
 * the account; the last copy is kept for when the TV is offline. They hold nothing private.
 */
@Singleton
class RctvPublicFiles @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    private val managed: RctvManagedServices
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _streamingLayout = MutableStateFlow(RctvPublicFileParser.streamingLayout(prefs.getString(KEY_STREAMING, null)))
    val streamingLayout: StateFlow<RctvStreamingLayout?> = _streamingLayout.asStateFlow()

    private val _announcements = MutableStateFlow(RctvPublicFileParser.announcements(prefs.getString(KEY_ANNOUNCEMENTS, null)).orEmpty())
    val announcements: StateFlow<List<RctvAnnouncement>> = _announcements.asStateFlow()

    private val _banners = MutableStateFlow(RctvBannerParser.banners(prefs.getString(KEY_BANNERS, null)).orEmpty())
    /** Home's banners as published (which ones a profile sees: [RctvBannerParser.visible]). */
    val banners: StateFlow<List<RctvBanner>> = _banners.asStateFlow()

    private val _collections = MutableStateFlow(RctvBannerParser.collections(prefs.getString(KEY_COLLECTIONS, null)).orEmpty())
    /** The collections banners open. */
    val collections: StateFlow<List<RctvCollection>> = _collections.asStateFlow()

    private val _dismissed = MutableStateFlow(prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty())
    /** Announcements already seen on this TV (shown once). */
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    @Volatile private var lastFetchAt = 0L

    init {
        scope.launch { managed.publicUrls.collect { fetch() } }
    }

    /** Reads the files again (at most once a minute). */
    fun refresh() {
        if (System.currentTimeMillis() - lastFetchAt < MIN_REFRESH_MS) return
        scope.launch { fetch() }
    }

    fun dismiss(id: String) {
        val next = (_dismissed.value + id).toList().takeLast(MAX_DISMISSED).toSet()
        prefs.edit().putStringSet(KEY_DISMISSED, next).apply()
        _dismissed.value = next
    }

    private fun fetch() {
        lastFetchAt = System.currentTimeMillis()
        val urls = managed.publicUrls.value
        urls.streamingServices?.let { url ->
            download(url)?.let { body ->
                RctvPublicFileParser.streamingLayout(body)?.let { layout ->
                    prefs.edit().putString(KEY_STREAMING, body).apply()
                    _streamingLayout.value = layout
                }
            }
        }
        urls.announcements?.let { url ->
            download(url)?.let { body ->
                RctvPublicFileParser.announcements(body)?.let { list ->
                    prefs.edit().putString(KEY_ANNOUNCEMENTS, body).apply()
                    _announcements.value = list
                }
            }
        }
        // Collections before banners: a banner shows only once its collection is here.
        urls.collections?.let { url ->
            download(url)?.let { body ->
                RctvBannerParser.collections(body)?.let { list ->
                    prefs.edit().putString(KEY_COLLECTIONS, body).apply()
                    _collections.value = list
                }
            }
        }
        urls.banners?.let { url ->
            download(url)?.let { body ->
                RctvBannerParser.banners(body)?.let { list ->
                    prefs.edit().putString(KEY_BANNERS, body).apply()
                    _banners.value = list
                }
            }
        }
    }

    private fun download(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            // Not published yet (404): nothing changes.
            if (response.isSuccessful) response.body?.string()?.takeIf { it.length <= MAX_FILE_CHARS } else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Public file not available: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "RctvPublicFiles"
        const val PREFS = "rctv_control_public"
        const val KEY_STREAMING = "streaming_services_json"
        const val KEY_ANNOUNCEMENTS = "announcements_json"
        const val KEY_DISMISSED = "dismissed_announcements"
        const val KEY_BANNERS = "banners_json"
        const val KEY_COLLECTIONS = "collections_json"
        const val MIN_REFRESH_MS = 60_000L
        const val MAX_DISMISSED = 100
        // The website caps a file at 750 KB (collections are the big ones).
        const val MAX_FILE_CHARS = 800_000
    }
}
