package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (SEC-04): the phone pages answer only the address shown on the TV. */
class LanConfigAccessTest {

    @Test
    fun `the address carries the page's key`() {
        val access = LanConfigAccess("abcdefghjk")
        assertEquals("http://192.168.1.20:8080/?k=abcdefghjk", access.shareUrl("http://192.168.1.20:8080"))
        assertEquals("http://192.168.1.20:8080/?k=abcdefghjk", access.shareUrl("http://192.168.1.20:8080/"))
    }

    @Test
    fun `only the key, from the address or the cookie, is let in`() {
        val access = LanConfigAccess("abcdefghjk")
        assertTrue(access.isAllowed("abcdefghjk", null))
        assertTrue(access.isAllowed(null, "abcdefghjk"))
        assertFalse(access.isAllowed(null, null))
        assertFalse(access.isAllowed("abcdefghj", "wrong"))
        assertFalse(access.isAllowed("", ""))
    }

    @Test
    fun `each page server gets its own random key`() {
        val keys = (1..50).map { LanConfigAccess.newToken() }.toSet()
        assertEquals(50, keys.size)
        keys.forEach { key ->
            assertEquals(10, key.length)
            assertTrue(key.all { it in "abcdefghjkmnpqrstuvwxyz23456789" })
        }
        assertNotEquals(LanConfigAccess().token, LanConfigAccess().token)
    }
}
