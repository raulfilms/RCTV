package com.nuvio.tv.core.smartscraper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How fast and steady the connection is: measured by the app itself (a short download from a
 * speed-test server, a few seconds at most, kept a while), else what plays have shown (their
 * download speed, the bitrate above which they've been buffering), else the network the TV is on
 * (cable, Wi-Fi and its link speed). Only these numbers are kept, on the device.
 */
@Singleton
class ConnectionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val testMutex = Mutex()
    /** The app's client without its disk cache (a speed test must come from the network). */
    private val testClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .cache(null)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The download speed now (Mbps): measured again when the last measurement is old (a few MB,
     * ~3 s at most), else the last one. Null when it can't be measured.
     */
    suspend fun measure(force: Boolean = false, maxAgeMs: Long = TEST_MAX_AGE_MS): Double? = withContext(Dispatchers.IO) {
        freshTest(maxAgeMs)?.takeIf { !force }?.let { return@withContext it }
        testMutex.withLock {
            freshTest(maxAgeMs)?.takeIf { !force }?.let { return@withLock it }
            val mbps = runCatching { speedTest() }
                .onFailure { Log.w(TAG, "Speed test failed: ${it.message}") }
                .getOrNull()
            if (mbps != null) {
                Log.i(TAG, "Internet: %.0f Mbps".format(mbps))
                prefs.edit().putFloat(KEY_TESTED, mbps.toFloat()).putLong(KEY_TESTED_AT, System.currentTimeMillis()).apply()
            }
            mbps
        }
    }

    private fun freshTest(maxAgeMs: Long = TEST_MAX_AGE_MS): Double? {
        val tested = prefs.getFloat(KEY_TESTED, 0f).toDouble()
        return tested.takeIf { it > 0 && System.currentTimeMillis() - prefs.getLong(KEY_TESTED_AT, 0L) < maxAgeMs }
    }

    /** Downloads up to [TEST_MAX_BYTES] for up to [TEST_MAX_NANOS] and times it from the first byte. */
    private fun speedTest(): Double? {
        val request = Request.Builder()
            .url(SPEED_TEST_URL)
            .header("Cache-Control", "no-store")
            .build()
        testClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val input = response.body?.byteStream() ?: return null
            val buffer = ByteArray(64 * 1024)
            var firstByteAt = 0L
            var lastByteAt = 0L
            var counted = 0L
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val now = System.nanoTime()
                if (firstByteAt == 0L) firstByteAt = now else counted += read
                lastByteAt = now
                total += read
                if (total >= TEST_MAX_BYTES || now - firstByteAt >= TEST_MAX_NANOS) break
            }
            val seconds = (lastByteAt - firstByteAt) / 1_000_000_000.0
            if (counted < TEST_MIN_BYTES || seconds <= 0.0) return null
            return counted * 8.0 / seconds / 1_000_000.0
        }
    }

    fun estimate(learn: Boolean): ConnectionEstimate {
        val now = System.currentTimeMillis()
        val tested = freshTest()
        val learned = if (learn) {
            prefs.getFloat(KEY_MEASURED, 0f).toDouble()
                .takeIf { it > 0 && now - prefs.getLong(KEY_MEASURED_AT, 0L) < MEASURED_MAX_AGE_MS }
        } else null
        val learnedMax = if (learn) {
            prefs.getFloat(KEY_LEARNED_MAX, 0f).toDouble()
                .takeIf { it > 0 && now - prefs.getLong(KEY_LEARNED_MAX_AT, 0L) < LEARNED_MAX_AGE_MS }
        } else null
        // What was measured just now first, then what plays have shown.
        val available = tested ?: learned
        val profile = available?.let(ConnectionEstimate::profileOf) ?: networkProfile()
        return ConnectionEstimate(profile = profile, availableMbps = available, learnedMaxMbps = learnedMax)
    }

    /** A play downloaded at [mbps]: the usable speed, smoothed over plays. */
    fun recordThroughput(mbps: Double) {
        if (mbps <= 0 || mbps.isNaN()) return
        val old = prefs.getFloat(KEY_MEASURED, 0f).toDouble()
        val next = if (old > 0) old + (mbps - old) * SMOOTHING else mbps
        prefs.edit().putFloat(KEY_MEASURED, next.toFloat()).putLong(KEY_MEASURED_AT, System.currentTimeMillis()).apply()
    }

    /** A file of [bitrateMbps] kept buffering: files like it are ranked down for a while. */
    fun recordRebuffering(bitrateMbps: Double) {
        if (bitrateMbps <= 0 || bitrateMbps.isNaN()) return
        val old = prefs.getFloat(KEY_LEARNED_MAX, 0f).toDouble().takeIf { it > 0 }
        val next = minOf(old ?: Double.MAX_VALUE, bitrateMbps * 0.9)
        prefs.edit().putFloat(KEY_LEARNED_MAX, next.toFloat()).putLong(KEY_LEARNED_MAX_AT, System.currentTimeMillis()).apply()
    }

    /** A file of [bitrateMbps] played smoothly: the learned limit (if lower) rises to it. */
    fun recordSmoothPlayback(bitrateMbps: Double) {
        val old = prefs.getFloat(KEY_LEARNED_MAX, 0f).toDouble().takeIf { it > 0 } ?: return
        if (bitrateMbps <= old) return
        prefs.edit().putFloat(KEY_LEARNED_MAX, bitrateMbps.toFloat()).putLong(KEY_LEARNED_MAX_AT, System.currentTimeMillis()).apply()
    }

    /** From the network itself: cable is fast; Wi-Fi and mobile by their link speed (an upper bound). */
    private fun networkProfile(): ConnectionProfile = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ConnectionProfile.UNKNOWN
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return ConnectionProfile.UNKNOWN
        val linkMbps = capabilities.linkDownstreamBandwidthKbps / 1000.0
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionProfile.FAST
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> when {
                linkMbps <= 0 -> ConnectionProfile.UNKNOWN
                linkMbps >= 300 -> ConnectionProfile.FAST
                linkMbps >= 80 -> ConnectionProfile.MEDIUM
                else -> ConnectionProfile.SLOW
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> when {
                linkMbps >= 40 -> ConnectionProfile.MEDIUM
                else -> ConnectionProfile.SLOW
            }
            else -> ConnectionProfile.UNKNOWN
        }
    }.getOrDefault(ConnectionProfile.UNKNOWN)

    private companion object {
        const val TAG = "SmartScraper"
        const val PREFS = "smart_connection"
        /** A public speed-test download (nothing about the person is sent). */
        const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=12000000"
        const val TEST_MAX_BYTES = 12_000_000L
        const val TEST_MIN_BYTES = 256L * 1024
        const val TEST_MAX_NANOS = 2_500_000_000L
        const val TEST_MAX_AGE_MS = 15L * 60 * 1000

        /**
         * RCTV (PERF-05): the measurement made when the app starts is skipped when there's one from the
         * last 6 h (it downloaded up to 12 MB at nearly every start). Before an automatic Play it's
         * still measured when older than [TEST_MAX_AGE_MS], alongside the addons (no extra wait).
         */
        const val STARTUP_TEST_MAX_AGE_MS = 6L * 60 * 60 * 1000
        const val KEY_TESTED = "tested_mbps"
        const val KEY_TESTED_AT = "tested_at"
        const val KEY_MEASURED = "measured_mbps"
        const val KEY_MEASURED_AT = "measured_at"
        const val KEY_LEARNED_MAX = "learned_max_mbps"
        const val KEY_LEARNED_MAX_AT = "learned_max_at"
        const val SMOOTHING = 0.3
        const val MEASURED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val LEARNED_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    }
}
