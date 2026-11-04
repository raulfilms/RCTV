package com.nuvio.tv.data.remote.rctvcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RctvCoreSwitchTest {

    @Test
    fun `off in the build means never, whatever the server says`() {
        val off = RctvCoreSwitch(enabledInBuild = false)
        off.checked(true, 0)
        assertFalse(off.usable(0))
        assertFalse(off.shouldCheck(0))
    }

    @Test
    fun `on only once the server says so, and the server can turn it off`() {
        val s = RctvCoreSwitch(enabledInBuild = true, checkEveryMs = 1000)
        assertFalse(s.usable(0))
        assertTrue(s.shouldCheck(0))
        s.checked(true, 0)
        assertTrue(s.usable(10))
        assertFalse(s.shouldCheck(500))
        assertTrue(s.shouldCheck(1000))
        s.checked(false, 1000)
        assertFalse(s.usable(1001))
    }

    @Test
    fun `failures in a row rest it, and it comes back after the rest`() {
        val s = RctvCoreSwitch(enabledInBuild = true, failuresToRest = 3, restMs = 600)
        s.checked(true, 0)
        s.failed(1)
        s.failed(2)
        assertTrue(s.usable(3))
        s.failed(3)
        assertFalse(s.usable(4))
        assertFalse(s.shouldCheck(4))
        assertTrue(s.usable(603))
        s.failed(700)
        s.succeeded()
        s.failed(701)
        s.failed(702)
        assertTrue(s.usable(703), "a success starts the count again")
    }

    @Test
    fun `a switch that can't be read counts as a failure and keeps the last answer`() {
        val s = RctvCoreSwitch(enabledInBuild = true, failuresToRest = 1, restMs = 100)
        s.checked(true, 0)
        s.checked(null, 10)
        assertFalse(s.usable(20))
        assertTrue(s.usable(110))
    }

    private fun assertTrue(value: Boolean, message: String) = org.junit.Assert.assertTrue(message, value)
}
