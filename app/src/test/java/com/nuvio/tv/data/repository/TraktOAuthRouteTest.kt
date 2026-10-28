package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (SEC-01): without a server nothing changes; with one, the secret isn't sent from the app. */
class TraktOAuthRouteTest {

    @Test
    fun `without a server the requests go to Trakt with the app's secret`() {
        val route = TraktOAuthRoute(proxyUrl = "", clientSecret = "app-secret")
        assertFalse(route.isProxied)
        assertEquals("app-secret", route.secretForRequest())
        assertNull(route.urlFor("oauth/token"))
        assertTrue(route.hasCredentials("client-id"))
        assertFalse(TraktOAuthRoute("", "").hasCredentials("client-id"))
    }

    @Test
    fun `with a server the secret stays out of the request and the APK`() {
        val route = TraktOAuthRoute(proxyUrl = "https://oauth.example/trakt/", clientSecret = "")
        assertTrue(route.isProxied)
        assertEquals("", route.secretForRequest())
        assertEquals("https://oauth.example/trakt/oauth/device/token", route.urlFor("oauth/device/token"))
        assertEquals("https://oauth.example/trakt/oauth/token", route.urlFor("/oauth/token"))
        assertTrue(route.hasCredentials("client-id"))
        assertFalse(route.hasCredentials(""))
    }
}
