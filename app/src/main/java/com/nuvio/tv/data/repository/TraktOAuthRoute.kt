package com.nuvio.tv.data.repository

/**
 * RCTV (SEC-01): where Trakt's token requests (device token, refresh, revoke) go.
 *
 * Without [proxyUrl] (the default) they go to Trakt with the app's client secret, as always. With it,
 * they go to `proxyUrl/<the same path>` with an empty `client_secret`, and that server adds the
 * secret before passing them to Trakt; the secret then doesn't need to be in the APK. Nothing else
 * of the request or the answer changes. The server isn't part of this repository.
 */
internal class TraktOAuthRoute(private val proxyUrl: String, private val clientSecret: String) {

    val isProxied: Boolean get() = proxyUrl.isNotBlank()

    /** The `client_secret` the request carries: the app's, or empty when the server adds it. */
    fun secretForRequest(): String = if (isProxied) "" else clientSecret

    /** The full address for [path] (`oauth/device/token`…) on the server; null when there's none. */
    fun urlFor(path: String): String? = if (isProxied) "${proxyUrl.trimEnd('/')}/${path.trimStart('/')}" else null

    fun hasCredentials(clientId: String): Boolean = clientId.isNotBlank() && (clientSecret.isNotBlank() || isProxied)
}
