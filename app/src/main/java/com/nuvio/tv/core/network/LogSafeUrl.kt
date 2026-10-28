package com.nuvio.tv.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * RCTV (RCTV-AUD-011, 021): an address as it may be logged: its scheme and host only. Addon
 * addresses carry the user's debrid keys and configuration in their path, IPTV ones the login in
 * their query, so the rest is never written to the log.
 */
fun String?.forLog(): String {
    val url = this?.toHttpUrlOrNull() ?: return "<url>"
    return url.forLog()
}

/** The same for a parsed address: "https://host/…". */
fun HttpUrl.forLog(): String = "$scheme://$host/…"
