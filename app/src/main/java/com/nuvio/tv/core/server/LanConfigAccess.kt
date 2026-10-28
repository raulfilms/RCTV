package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RCTV (SEC-04): the phone pages a settings screen serves on the local network answer only the
 * device that opened the address shown on the TV (its QR code). The address carries a random key
 * ([shareUrl]); the first page sets it as a cookie, so the page's own requests (same address)
 * carry it without any change to the pages. Anyone else on the network gets 403. A new key each time
 * a page server starts.
 */
class LanConfigAccess(val token: String = newToken()) {

    /** The address to show and put in the QR code. */
    fun shareUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/?$PARAM=$token"

    fun isAllowed(queryToken: String?, cookieToken: String?): Boolean = matches(queryToken) || matches(cookieToken)

    /** [route] for a request that carries the key; 403 for one that doesn't. */
    fun guard(session: NanoHTTPD.IHTTPSession, route: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response): NanoHTTPD.Response {
        val fromQuery = runCatching { session.parameters[PARAM]?.firstOrNull() }.getOrNull()
        val fromCookie = runCatching { session.cookies.read(COOKIE) }.getOrNull()
        if (!isAllowed(fromQuery, fromCookie)) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                NanoHTTPD.MIME_PLAINTEXT,
                "Open the address shown on the TV (scan its QR code again)."
            )
        }
        val response = route(session)
        if (!matches(fromCookie)) response.addHeader("Set-Cookie", "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
        return response
    }

    private fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    companion object {
        const val PARAM = "k"
        const val COOKIE = "rctv_k"
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        /** 10 characters of 31: about 49 bits, for a page that lives only while its screen is open. */
        fun newToken(length: Int = 10): String = buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}
