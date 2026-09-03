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
 * The file belongs to the user and to Claude Code, not to this plugin, so the behaviour
 * worth testing is what survives a write.
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

    @Test
    fun `writes permissions into a file that does not exist yet`() {
        val written = settings.write(
            ClaudeSettingsFile.Permissions(allow = listOf("Bash(ls)"), defaultMode = "plan"),
            emptyList(),
        )

        assertTrue(written)
        val permissions = JsonParser.parseString(path.readText()).asJsonObject.getAsJsonObject("permissions")
        assertEquals("Bash(ls)", permissions.getAsJsonArray("allow").first().asString)
        assertEquals("plan", permissions.get("defaultMode").asString)
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

        settings.write(ClaudeSettingsFile.Permissions(deny = listOf("Read(./.env)")), emptyList())

        val root = JsonParser.parseString(path.readText()).asJsonObject
        assertTrue(root.has("hooks"))
        assertEquals("bar", root.getAsJsonObject("env").get("FOO").asString)
        val permissions = root.getAsJsonObject("permissions")
        assertEquals("Read(./.env)", permissions.getAsJsonArray("deny").first().asString)
        assertFalse(permissions.has("allow"), "an emptied list should be removed, not left as []")
    }

    @Test
    fun `refuses to write over a file it cannot parse`() {
        path.writeText("{ not json")

        val written = settings.write(ClaudeSettingsFile.Permissions(allow = listOf("Bash(ls)")), emptyList())

        assertFalse(written)
        assertEquals("{ not json", path.readText())
    }

    @Test
    fun `reads back what it wrote`() {
        val permissions = ClaudeSettingsFile.Permissions(
            allow = listOf("Bash(npm test)"),
            deny = listOf("Read(./.env)"),
            ask = listOf("Bash(git push)"),
            defaultMode = "acceptEdits",
        )

        settings.write(permissions, listOf("opus", "sonnet"))

        assertEquals(permissions, settings.readPermissions())
        assertEquals(listOf("opus", "sonnet"), settings.readAvailableModels())
    }

    @Test
    fun `an empty permissions block leaves no empty object behind`() {
        settings.write(ClaudeSettingsFile.Permissions(allow = listOf("Bash(ls)")), emptyList())
        settings.write(ClaudeSettingsFile.Permissions(), emptyList())

        assertFalse(JsonParser.parseString(path.readText()).asJsonObject.has("permissions"))
    }

    @Test
    fun `backs up the original once`() {
        path.writeText("""{ "env": { "FOO": "bar" } }""")
        val backup = path.resolveSibling("settings.json.before-claude-acp")

        settings.write(ClaudeSettingsFile.Permissions(allow = listOf("Bash(ls)")), emptyList())
        val original = backup.readText()

        settings.write(ClaudeSettingsFile.Permissions(allow = listOf("Bash(pwd)")), emptyList())
        assertEquals(original, backup.readText())
    }

    @Test
    fun `scope picks the right file name`() {
        assertTrue(
            ClaudeSettingsFile.Scope.SHARED.fileIn(directory).endsWith(".claude/settings.json"),
        )
        assertTrue(
            ClaudeSettingsFile.Scope.PERSONAL.fileIn(directory).endsWith(".claude/settings.local.json"),
        )
    }
}
