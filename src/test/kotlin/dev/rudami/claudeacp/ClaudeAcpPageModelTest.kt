package dev.rudami.claudeacp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the settings page's logic — the part that had no tests and shipped two bugs: a
 * version row that could not be read back, and a busy state that never ended.
 */
class ClaudeAcpPageModelTest {

    // ---------------------------------------------------------------- version rows

    @Test
    fun `rows are newest first and mark what is active and downloaded`() {
        val rows = ClaudeAcpPageModel.versionRows(
            published = listOf("0.74.0", "0.73.0", "0.72.0"),
            installed = listOf("0.73.0", "0.72.0"),
            active = "0.72.0",
        )

        assertEquals(listOf("0.74.0", "0.73.0 - downloaded", "0.72.0 - active"), rows)
    }

    @Test
    fun `a downloaded version the registry does not list still appears`() {
        val rows = ClaudeAcpPageModel.versionRows(
            published = listOf("0.74.0"),
            installed = listOf("0.60.0"),
            active = null,
        )

        assertEquals(listOf("0.74.0", "0.60.0 - downloaded"), rows)
    }

    @Test
    fun `no duplicate row when a version is both published and downloaded`() {
        val rows = ClaudeAcpPageModel.versionRows(
            published = listOf("0.73.0"),
            installed = listOf("0.73.0"),
            active = "0.73.0",
        )

        assertEquals(listOf("0.73.0 - active"), rows)
    }

    /** The pairing that matters: whatever the list shows must read back as a version. */
    @Test
    fun `every row reads back as its version`() {
        val published = listOf("0.74.0", "0.73.0", "0.72.0")
        val installed = listOf("0.73.0", "0.72.0")

        val rows = ClaudeAcpPageModel.versionRows(published, installed, active = "0.72.0")

        assertEquals(published, rows.map { ClaudeAcpPageModel.versionOf(it) })
    }

    @Test
    fun `the loading placeholder is not a version`() {
        assertNull(ClaudeAcpPageModel.versionOf(ClaudeAcpPageModel.LOADING_ITEM))
        assertNull(ClaudeAcpPageModel.versionOf(null))
        assertNull(ClaudeAcpPageModel.versionOf(""))
    }

    // ---------------------------------------------------------------- defaults

    @Test
    fun `the default registry is stored as no override`() {
        assertNull(ClaudeAcpPageModel.registryChoice(ClaudeAcpSettings.DEFAULT_REGISTRY))
        assertNull(ClaudeAcpPageModel.registryChoice("  "))
        assertNull(ClaudeAcpPageModel.registryChoice(null))
        assertEquals(
            "https://registry.npmmirror.com",
            ClaudeAcpPageModel.registryChoice("  https://registry.npmmirror.com  "),
        )
    }

    @Test
    fun `a blank agent name falls back to the default`() {
        assertEquals(ClaudeAcpSettings.DEFAULT_DISPLAY_NAME, ClaudeAcpPageModel.displayName(""))
        assertEquals(ClaudeAcpSettings.DEFAULT_DISPLAY_NAME, ClaudeAcpPageModel.displayName("   "))
        assertEquals(ClaudeAcpSettings.DEFAULT_DISPLAY_NAME, ClaudeAcpPageModel.displayName(null))
        assertEquals("Claude", ClaudeAcpPageModel.displayName("  Claude  "))
    }

    /**
     * The name is what the icon service matches against, so a rename must still resolve to
     * the id the IDE derives from it.
     */
    @Test
    fun `the derived agent id still matches after a rename`() {
        val renamed = ClaudeAcpPageModel.displayName("Claude Code Personal")

        assertTrue(ClaudeAgent.matches("acp.claude-code-personal", renamed))
        assertFalse(ClaudeAgent.matches("acp.something-else", renamed))
    }

    @Test
    fun `automatic is stored as no node override`() {
        assertNull(ClaudeAcpPageModel.nodeChoice(ClaudeAcpPageModel.AUTOMATIC_NODE))
        assertEquals("/usr/bin/node", ClaudeAcpPageModel.nodeChoice("/usr/bin/node"))
    }

    // ---------------------------------------------------------------- modified

    private val stored = ClaudeAcpPageModel.Form(
        version = "0.73.0",
        displayName = ClaudeAcpSettings.DEFAULT_DISPLAY_NAME,
        policy = UpdatePolicy.NOTIFY,
        intervalHours = 24,
        registry = null,
        nodePath = null,
        useIdeaMcp = true,
        useCustomMcp = true,
    )

    @Test
    fun `an untouched page is not modified`() {
        assertFalse(ClaudeAcpPageModel.isModified(stored, stored, managed = true))
    }

    @Test
    fun `each field counts as a change`() {
        val changes = listOf(
            stored.copy(version = "0.72.0"),
            stored.copy(displayName = "Claude"),
            stored.copy(policy = UpdatePolicy.AUTO),
            stored.copy(intervalHours = 12),
            stored.copy(registry = "https://registry.npmmirror.com"),
            stored.copy(nodePath = "/usr/bin/node"),
            stored.copy(useIdeaMcp = false),
            stored.copy(useCustomMcp = false),
        )

        changes.forEach { edited ->
            assertTrue(
                ClaudeAcpPageModel.isModified(edited, stored, managed = true),
                "expected a change to be detected in $edited",
            )
        }
    }

    /**
     * With the agent removed the version combo is emptied, which would otherwise read as a
     * pending change on every keystroke and keep the Apply button lit forever.
     */
    @Test
    fun `an empty version does not count while the agent is removed`() {
        val edited = stored.copy(version = null)

        assertTrue(ClaudeAcpPageModel.isModified(edited, stored, managed = true))
        assertFalse(ClaudeAcpPageModel.isModified(edited, stored, managed = false))
    }

    @Test
    fun `other fields still count while the agent is removed`() {
        val edited = stored.copy(version = null, intervalHours = 6)

        assertTrue(ClaudeAcpPageModel.isModified(edited, stored, managed = false))
    }

    // ---------------------------------------------------------------- busy counter

    @Test
    fun `the first begin and the last end are the transitions`() {
        val busy = ClaudeAcpPageModel.BusyCounter()

        assertTrue(busy.begin(), "the first begin enters the busy state")
        assertFalse(busy.begin(), "a second begin joins it")
        assertTrue(busy.isBusy)

        assertFalse(busy.end(), "the first end does not leave it")
        assertTrue(busy.isBusy, "an operation is still running")

        assertTrue(busy.end(), "the last end leaves it")
        assertFalse(busy.isBusy)
    }

    /** The shape of the hang: two begins against one end left the page busy forever. */
    @Test
    fun `an unbalanced begin keeps the page busy`() {
        val busy = ClaudeAcpPageModel.BusyCounter()
        busy.begin()
        busy.begin()

        busy.end()

        assertTrue(busy.isBusy)
    }

    @Test
    fun `an unmatched end is ignored rather than going negative`() {
        val busy = ClaudeAcpPageModel.BusyCounter()

        assertFalse(busy.end())
        assertFalse(busy.isBusy)

        assertTrue(busy.begin(), "a stray end must not have left the counter below zero")
    }

    @Test
    fun `reset clears a stuck state`() {
        val busy = ClaudeAcpPageModel.BusyCounter()
        busy.begin()
        busy.begin()

        busy.reset()

        assertFalse(busy.isBusy)
    }

    // ---------------------------------------------------------------- wording

    @Test
    fun `freshness names the situation`() {
        assertEquals(
            "Update available: 0.74.0.",
            ClaudeAcpPageModel.describeFreshness("0.74.0", "0.73.0"),
        )
        assertEquals(
            "Up to date on 0.73.0.",
            ClaudeAcpPageModel.describeFreshness("0.73.0", "0.73.0"),
        )
        assertEquals(
            "Newest release is 0.74.0. Nothing is installed yet.",
            ClaudeAcpPageModel.describeFreshness("0.74.0", null),
        )
        assertEquals(
            "The registry returned no versions.",
            ClaudeAcpPageModel.describeFreshness(null, "0.73.0"),
        )
    }

    /** A pre-release must not be offered as an update over the release it precedes. */
    @Test
    fun `a pre-release is not an update`() {
        assertEquals(
            "Up to date on 0.74.0.",
            ClaudeAcpPageModel.describeFreshness("0.74.0-rc.1", "0.74.0"),
        )
    }

    @Test
    fun `disk usage reads naturally for none, one and many`() {
        assertEquals("nothing downloaded", ClaudeAcpPageModel.describeDisk(0, 0))
        assertEquals("1 copy, 90 MB", ClaudeAcpPageModel.describeDisk(1, 90L * 1024 * 1024))
        assertEquals("3 copies, 270 MB", ClaudeAcpPageModel.describeDisk(3, 270L * 1024 * 1024))
    }

    // ---------------------------------------------------------------- paths

    @Test
    fun `a home path is folded to a tilde`() {
        assertEquals(
            "~/.nvm/bin/node",
            ClaudeAcpPageModel.abbreviatePath("/Users/me/.nvm/bin/node", home = "/Users/me"),
        )
    }

    @Test
    fun `a path outside home is left alone when it is short`() {
        assertEquals(
            "/usr/bin/node",
            ClaudeAcpPageModel.abbreviatePath("/usr/bin/node", home = "/Users/me"),
        )
    }

    /** An unabbreviated path sets a floor under the whole dialog's width. */
    @Test
    fun `a long path is truncated from the front`() {
        val long = "/very/long/path/that/keeps/going/and/going/and/going/to/the/bin/node"

        val shortened = ClaudeAcpPageModel.abbreviatePath(long, home = "", limit = 20)

        assertEquals("..." + long.takeLast(20), shortened)
        assertTrue(shortened.length <= 23)
    }

    /** A combo box is as wide as its widest row, so these must stay short. */
    @Test
    fun `an interpreter row is shortened but automatic is left alone`() {
        assertEquals(
            ClaudeAcpPageModel.AUTOMATIC_NODE,
            ClaudeAcpPageModel.nodeLabel(ClaudeAcpPageModel.AUTOMATIC_NODE, home = "/Users/me"),
        )
        assertEquals(
            "~/.nvm/bin/node",
            ClaudeAcpPageModel.nodeLabel("/Users/me/.nvm/bin/node", home = "/Users/me"),
        )

        val long = ClaudeAcpPageModel.nodeLabel(
            "/opt/homebrew/Cellar/node/24.15.0/libexec/lib/node_modules/npm/bin/node",
            home = "/Users/me",
        )
        assertTrue(long.length <= 33, "a row this long would widen the whole page: $long")
    }

    @Test
    fun `status names the version and the interpreter`() {
        assertEquals(
            "Adapter 0.73.0, node ~/.nvm/bin/node",
            ClaudeAcpPageModel.describeStatus("0.73.0", "/Users/me/.nvm/bin/node", home = "/Users/me"),
        )
        assertEquals(
            "Adapter not installed, node not found",
            ClaudeAcpPageModel.describeStatus(null, null, home = "/Users/me"),
        )
    }
}
