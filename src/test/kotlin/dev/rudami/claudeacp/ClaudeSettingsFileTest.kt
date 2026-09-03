package dev.rudami.claudeacp

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file belongs to the user and to Claude Code — the agent writes rules here itself when
 * someone picks "always allow" in the chat — so the behaviour worth testing is what survives
 * a write from this plugin.
 */
class ClaudeSettingsFileTest {

    private lateinit var directory: Path
    private lateinit var settings: ClaudeSettingsFile

    @BeforeTest
    fun createTempFile() {
        directory = Files.createTempDirectory("claude-settings-test")
        val path = directory.resolve(".claude/settings.json")
        path.createParentDirectories()
        settings = ClaudeSettingsFile(path)
    }

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private val path get() = settings.path

    private val nothing = ClaudeSettingsFile.Permissions()

    private fun permissions(
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList(),
        ask: List<String> = emptyList(),
        defaultMode: String? = null,
    ) = ClaudeSettingsFile.Permissions(allow, deny, ask, defaultMode)

    @Test
    fun `writes permissions into a file that does not exist yet`() {
        val written = settings.write(nothing, permissions(allow = listOf("Bash(ls)"), defaultMode = "plan"))

        assertTrue(written)
        val node = JsonParser.parseString(path.readText()).asJsonObject.getAsJsonObject("permissions")
        assertEquals("Bash(ls)", node.getAsJsonArray("allow").first().asString)
        assertEquals("plan", node.get("defaultMode").asString)
    }

    @Test
    fun `keeps keys this plugin knows nothing about`() {
        path.writeText(
            """
            {
              "hooks": { "PostToolUse": [] },
              "env": { "FOO": "bar" },
              "permissions": { "allow": ["Bash(ls)"] }
            }
            """.trimIndent(),
        )

        settings.write(permissions(allow = listOf("Bash(ls)")), permissions(deny = listOf("Read(./.env)")))

        val root = JsonParser.parseString(path.readText()).asJsonObject
        assertTrue(root.has("hooks"))
        assertEquals("bar", root.getAsJsonObject("env").get("FOO").asString)
        val node = root.getAsJsonObject("permissions")
        assertEquals("Read(./.env)", node.getAsJsonArray("deny").first().asString)
        assertFalse(node.has("allow"), "an emptied list should be removed, not left as []")
    }

    /**
     * The case that makes a plain overwrite wrong: the agent persists an approved rule while
     * the settings page sits open on an older snapshot.
     */
    @Test
    fun `keeps a rule the agent added while the page was open`() {
        val baseline = permissions(allow = listOf("Bash(ls)"))
        path.writeText("""{ "permissions": { "allow": ["Bash(ls)", "Bash(git status)"] } }""")

        settings.write(baseline, permissions(allow = listOf("Bash(ls)", "Bash(pwd)")))

        val allow = settings.readPermissions().allow
        assertTrue("Bash(git status)" in allow, "a concurrently approved rule must survive")
        assertTrue("Bash(pwd)" in allow, "the rule typed on the page must be added")
    }

    @Test
    fun `a rule deleted on the page is removed from disk`() {
        val baseline = permissions(allow = listOf("Bash(ls)", "Bash(rm -rf)"))
        path.writeText("""{ "permissions": { "allow": ["Bash(ls)", "Bash(rm -rf)"] } }""")

        settings.write(baseline, permissions(allow = listOf("Bash(ls)")))

        assertEquals(listOf("Bash(ls)"), settings.readPermissions().allow)
    }

    @Test
    fun `an unchanged mode does not overwrite one set elsewhere`() {
        val baseline = permissions(allow = listOf("Bash(ls)"))
        path.writeText("""{ "permissions": { "allow": ["Bash(ls)"], "defaultMode": "acceptEdits" } }""")

        settings.write(baseline, permissions(allow = listOf("Bash(ls)", "Bash(pwd)")))

        assertEquals("acceptEdits", settings.readPermissions().defaultMode)
    }

    @Test
    fun `a changed mode wins`() {
        val baseline = permissions(defaultMode = "acceptEdits")
        path.writeText("""{ "permissions": { "defaultMode": "acceptEdits" } }""")

        settings.write(baseline, permissions(defaultMode = "plan"))

        assertEquals("plan", settings.readPermissions().defaultMode)
    }

    @Test
    fun `refuses to write over a file it cannot parse`() {
        path.writeText("{ not json")

        val written = settings.write(nothing, permissions(allow = listOf("Bash(ls)")))

        assertFalse(written)
        assertEquals("{ not json", path.readText())
    }

    @Test
    fun `reads back what it wrote`() {
        val edited = permissions(
            allow = listOf("Bash(npm test)"),
            deny = listOf("Read(./.env)"),
            ask = listOf("Bash(git push)"),
            defaultMode = "acceptEdits",
        )

        settings.write(nothing, edited)

        assertEquals(edited, settings.readPermissions())
    }

    @Test
    fun `an empty permissions block leaves no empty object behind`() {
        settings.write(nothing, permissions(allow = listOf("Bash(ls)")))
        settings.write(permissions(allow = listOf("Bash(ls)")), nothing)

        assertFalse(JsonParser.parseString(path.readText()).asJsonObject.has("permissions"))
    }

    @Test
    fun `backs up the original once`() {
        path.writeText("""{ "env": { "FOO": "bar" } }""")
        val backup = path.resolveSibling("settings.json.before-claude-acp")

        settings.write(nothing, permissions(allow = listOf("Bash(ls)")))
        val original = backup.readText()

        settings.write(nothing, permissions(allow = listOf("Bash(pwd)")))
        assertEquals(original, backup.readText())
    }

    @Test
    fun `scope picks the right file name`() {
        assertTrue(ClaudeSettingsFile.Scope.SHARED.fileIn(directory).endsWith(".claude/settings.json"))
        assertTrue(
            ClaudeSettingsFile.Scope.PERSONAL.fileIn(directory).endsWith(".claude/settings.local.json"),
        )
    }
}
