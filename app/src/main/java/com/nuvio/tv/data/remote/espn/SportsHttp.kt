package com.nuvio.tv.data.remote.espn

import com.google.gson.stream.JsonReader
import okhttp3.Response
import java.io.InputStreamReader

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

/**
 * RCTV: an answer read as JSON while it's decoded ([read] takes what it needs as it goes), never
 * turned into one big text or JSON tree: for answers that list hundreds of things (the NCAA's teams).
 * Bounded like [boundedBody].
 */
internal fun <T> boundedJson(
    response: Response,
    maxBytes: Long = EspnSportsClient.MAX_RESPONSE_BYTES,
    read: (JsonReader) -> T
): T {
    val body = response.body ?: throw EspnSportsClient.EspnException("Empty answer")
    val declared = body.contentLength()
    if (declared > maxBytes) throw EspnSportsClient.EspnException("Answer too big: $declared bytes")
    val source = body.source()
    if (source.request(maxBytes + 1)) throw EspnSportsClient.EspnException("Answer too big: over $maxBytes bytes")
    return JsonReader(InputStreamReader(source.buffer.inputStream(), Charsets.UTF_8)).use { reader ->
        reader.isLenient = true
        read(reader)
    }
}
