package dev.rudami.claudeacp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionOrderTest {

    @Test
    fun `orders numerically, not lexicographically`() {
        assertTrue(VersionOrder.compare("0.9.0", "0.73.0") < 0)
        assertTrue(VersionOrder.compare("1.0.0", "0.73.0") > 0)
        assertEquals(0, VersionOrder.compare("0.73.0", "0.73.0"))
    }

    @Test
    fun `pre-release sorts below the release it leads to`() {
        assertTrue(VersionOrder.compare("0.73.0-beta.1", "0.73.0") < 0)
        assertTrue(VersionOrder.compare("0.73.0", "0.73.0-beta.1") > 0)
        assertTrue(VersionOrder.compare("0.73.0-alpha.1", "0.73.0-beta.1") < 0)
    }

    @Test
    fun `missing and non-numeric segments do not throw`() {
        assertTrue(VersionOrder.compare("1.2", "1.2.1") < 0)
        assertEquals(0, VersionOrder.compare("1.2", "1.2.0"))
        assertTrue(VersionOrder.compare("1.x.0", "1.0.0") == 0)
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, VersionOrder.compare("1.2.3+build.5", "1.2.3"))
    }

    @Test
    fun `sorting newest first matches what the settings list shows`() {
        val sorted = listOf("0.9.0", "0.73.0", "0.73.0-rc.1", "1.0.0").sortedWith(VersionOrder.reversed())
        assertEquals(listOf("1.0.0", "0.73.0", "0.73.0-rc.1", "0.9.0"), sorted)
    }
}
