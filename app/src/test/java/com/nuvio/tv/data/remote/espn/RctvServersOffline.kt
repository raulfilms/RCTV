package com.nuvio.tv.data.remote.espn

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * For the ESPN client's tests: the app's own servers (rctv-datos, rctv-ligas and RCTV Core, which the
 * client asks before ESPN) can't be reached, so every league is asked of ESPN directly, as when a
 * server is down. Each answers 503 at once, without reaching the test's own interceptor (added after
 * this one), which then sees, counts and answers ESPN's requests only.
 */
internal val rctvServersOffline = Interceptor { chain ->
    val request = chain.request()
    val host = request.url.host
    if (host == "espn.com" || host.endsWith(".espn.com")) {
        chain.proceed(request)
    } else {
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Unavailable")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
    }
}
