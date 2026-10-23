package com.nuvio.tv.core.smartscraper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How fast and steady the connection is: the network the TV is on (cable, Wi-Fi and its link
 * speed, mobile) and, when learning is on, what plays have shown (the download speed they got,
 * the bitrate above which they've been buffering). Only these numbers are kept, on the device.
 */
@Singleton
class ConnectionMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun estimate(learn: Boolean): ConnectionEstimate {
        val now = System.currentTimeMillis()
        val measured = if (learn) {
            prefs.getFloat(KEY_MEASURED, 0f).toDouble()
                .takeIf { it > 0 && now - prefs.getLong(KEY_MEASURED_AT, 0L) < MEASURED_MAX_AGE_MS }
        } else null
        val learnedMax = if (learn) {
            prefs.getFloat(KEY_LEARNED_MAX, 0f).toDouble()
                .takeIf { it > 0 && now - prefs.getLong(KEY_LEARNED_MAX_AT, 0L) < LEARNED_MAX_AGE_MS }
        } else null
        val network = networkProfile()
        val profile = when {
            measured != null -> ConnectionEstimate.profileOf(measured)
            else -> network
        }
        return ConnectionEstimate(profile = profile, availableMbps = measured, learnedMaxMbps = learnedMax)
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
        const val PREFS = "smart_connection"
        const val KEY_MEASURED = "measured_mbps"
        const val KEY_MEASURED_AT = "measured_at"
        const val KEY_LEARNED_MAX = "learned_max_mbps"
        const val KEY_LEARNED_MAX_AT = "learned_max_at"
        const val SMOOTHING = 0.3
        const val MEASURED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val LEARNED_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    }
}
