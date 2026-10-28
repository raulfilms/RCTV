package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request

/**
 * RCTV (RCTV-AUD-009): a playback request's credentials stay on the site they were sent to.
 *
 * On a redirect OkHttp drops only Authorization, and only when the host, port or scheme changes; any
 * other header (Cookie, an API key) goes along to wherever the redirect points. Each hop is compared
 * with the call's first request: on the same site ([isSameSite]: the same host or registrable
 * domain), with no HTTPS to HTTP downgrade, everything is sent; on another organisation's host,
 * credentials are not.
 */
internal object PlaybackRedirectCredentials {

    /** A stream's own headers that may go to another host: none of them carry a credential. */
    private val CROSS_HOST_HEADERS = setOf("user-agent", "referer", "origin", "accept-language")

    private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")
    private val CREDENTIAL_NAME_PARTS = listOf("auth", "token", "key", "secret", "session", "password", "cookie", "signature")

    /**
     * [hop] is on [first]'s site, with no HTTPS to HTTP downgrade: the same host, or the same
     * registrable domain ("cdn.provider.com" for "api.provider.com"; by the public suffix list, so
     * "a.github.io" and "b.github.io" are different sites, and an IP address is only itself).
     *
     * RCTV (no-regression): providers commonly serve segments, files or a redirect's target from a
     * sibling subdomain that needs the same login (a portal's CDN, WebDAV behind a proxy); before the
     * remediation those got the credentials. Another organisation's host never does.
     */
    fun isSameSite(hop: HttpUrl, first: HttpUrl): Boolean {
        if (first.isHttps && !hop.isHttps) return false
        if (hop.host == first.host) return true
        val site = first.topPrivateDomain() ?: return false
        return hop.topPrivateDomain() == site
    }

    /** One of the headers any host may get: User-Agent, Referer, Origin, Accept-Language. */
    fun isCrossHostHeader(name: String): Boolean = name.lowercase() in CROSS_HOST_HEADERS

    /** A header name that carries a credential (Authorization, Cookie, X-Api-Key, X-Auth-Token…). */
    fun isCredentialName(name: String): Boolean {
        val lower = name.lowercase()
        return lower in CREDENTIAL_HEADERS || CREDENTIAL_NAME_PARTS.any { it in lower }
    }

    /** A credential by its name, or by its value ("Bearer …", "Basic …") under any name. */
    fun isCredential(name: String, value: String?): Boolean {
        if (isCredentialName(name)) return true
        val trimmed = value?.trimStart().orEmpty()
        return trimmed.startsWith("Bearer ", ignoreCase = true) || trimmed.startsWith("Basic ", ignoreCase = true)
    }

    /** What a stream's own headers do on a request of its own to another host (not a redirect). */
    enum class OtherHostRequests {
        /**
         * Sent without credentials (by name or value): an HLS or DASH segment, or a sidecar subtitle,
         * on a CDN keeps the stream's Referer, User-Agent and other plain headers it may need.
         */
        WITHOUT_CREDENTIALS,

        /** Sent with only User-Agent, Referer, Origin and Accept-Language: the host is a redirect's target. */
        CROSS_HOST_HEADERS_ONLY
    }

    /**
     * On every playback client (a network interceptor): a hop that left the first request's host
     * carries no credential headers.
     */
    val offHostGuard: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (isSameSite(request.url, chain.call().request().url)) {
            chain.proceed(request)
        } else {
            chain.proceed(request.withoutHeaders { isCredentialName(it) })
        }
    }

    /**
     * For a client made for a stream's own [headers] (a network interceptor).
     *
     * - On the stream's host (with no HTTPS to HTTP downgrade): every header, and Authorization again
     *   where OkHttp dropped it for a port or HTTP to HTTPS change, as WebDAV servers behind a reverse
     *   proxy redirect.
     * - A redirect away from the host a call started on: only User-Agent, Referer, Origin and
     *   Accept-Language of them (a credential can have any name).
     * - A request of its own to another host (an HLS/DASH segment, a sidecar subtitle on a CDN): as
     *   [otherHostRequests] says.
     *
     * The stream's host is [streamUrl]'s; without one, each call's first request's (so a request of
     * its own to another host counts as the stream's).
     */
    fun streamHeaderScope(
        headers: Map<String, String>,
        streamUrl: HttpUrl? = null,
        otherHostRequests: OtherHostRequests = OtherHostRequests.WITHOUT_CREDENTIALS
    ): Interceptor? {
        val ownNames = headers.keys.map { it.lowercase() }.filter { it !in CROSS_HOST_HEADERS }.toSet()
        val ownCredentials = headers.filter { (name, value) -> isCredential(name, value) }.keys.map { it.lowercase() }.toSet()
        val authorization = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
        if (ownNames.isEmpty() && authorization == null) return null
        return Interceptor { chain ->
            val request = chain.request()
            val callStart = chain.call().request().url
            when {
                isSameSite(request.url, streamUrl ?: callStart) ->
                    if (authorization != null && request.header("Authorization") == null) {
                        chain.proceed(request.newBuilder().header("Authorization", authorization).build())
                    } else {
                        chain.proceed(request)
                    }
                // A redirect off the host the call started on, or a client for a redirect's target.
                !isSameSite(request.url, callStart) || otherHostRequests == OtherHostRequests.CROSS_HOST_HEADERS_ONLY ->
                    chain.proceed(request.withoutHeaders { it.lowercase() in ownNames })
                else -> chain.proceed(request.withoutHeaders { it.lowercase() in ownCredentials })
            }
        }
    }

    private inline fun Request.withoutHeaders(drop: (String) -> Boolean): Request {
        val names = headers.names().filter(drop)
        if (names.isEmpty()) return this
        return newBuilder().apply { names.forEach { removeHeader(it) } }.build()
    }
}
