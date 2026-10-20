package com.nuvio.tv.data.remote.espn

import okhttp3.Response

/**
 * An answer's text, read only up to [maxBytes]: a league's answer is normally kilobytes to a few
 * megabytes; one far bigger (a wrong address, a whole season) would take the memory the TV has and
 * the system would close the app, so it's refused instead (and that league just shows nothing).
 */
internal fun boundedBody(response: Response, maxBytes: Long = EspnSportsClient.MAX_RESPONSE_BYTES): String {
    val body = response.body ?: return ""
    val declared = body.contentLength()
    if (declared > maxBytes) throw EspnSportsClient.EspnException("Answer too big: $declared bytes")
    val source = body.source()
    if (source.request(maxBytes + 1)) throw EspnSportsClient.EspnException("Answer too big: over $maxBytes bytes")
    return source.buffer.readUtf8()
}
