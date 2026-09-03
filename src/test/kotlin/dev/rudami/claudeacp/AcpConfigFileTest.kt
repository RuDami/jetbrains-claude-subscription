package dev.rudami.claudeacp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file under test is shared with every other locally defined ACP agent, so the
 * behaviour that matters is what it leaves alone.
 */
class AcpConfigFileTest {

    private lateinit var directory: Path
    private lateinit var config: Path

    @BeforeTest
    fun redirectToTempDirectory() {
        directory = Files.createTempDirectory("acp-config-test")
        config = directory.resolve("acp.json")
        AcpConfigFile.pathOverride = config
    }

    @AfterTest
    fun restore() {
        AcpConfigFile.pathOverride = null
        directory.toFile().deleteRecursively()
    }

    private fun entry(command: String) = JsonObject().apply { addProperty("command", command) }

    @Test
    fun `creates the file when it does not exist`() {
        assertEquals(AcpConfigFile.Outcome.WRITTEN, AcpConfigFile.upsertAgent("Ours", entry("/a")))

        val servers = JsonParser.parseString(config.readText()).asJsonObject.getAsJsonObject("agent_servers")
        assertEquals("/a", servers.getAsJsonObject("Ours").get("command").asString)
    }

    @Test
    fun `leaves other agents and unrelated keys untouched`() {
        config.writeText(
            """
            {
              "default_mcp_settings": { "use_idea_mcp": false },
              "agent_servers": { "Someone Else": { "command": "/theirs" } }
            }
            """.trimIndent(),
        )

        AcpConfigFile.upsertAgent("Ours", entry("/a"))

        val root = JsonParser.parseString(config.readText()).asJsonObject
        assertEquals(false, root.getAsJsonObject("default_mcp_settings").get("use_idea_mcp").asBoolean)
        val servers = root.getAsJsonObject("agent_servers")
        assertEquals("/theirs", servers.getAsJsonObject("Someone Else").get("command").asString)
        assertEquals("/a", servers.getAsJsonObject("Ours").get("command").asString)
    }

    @Test
    fun `an identical entry is not rewritten`() {
        AcpConfigFile.upsertAgent("Ours", entry("/a"))
        assertEquals(AcpConfigFile.Outcome.UNCHANGED, AcpConfigFile.upsertAgent("Ours", entry("/a")))
    }

    @Test
    fun `refuses to write over a file it cannot parse`() {
        config.writeText("{ this is not json")

        assertEquals(AcpConfigFile.Outcome.REFUSED_UNPARSEABLE, AcpConfigFile.upsertAgent("Ours", entry("/a")))
        assertEquals("{ this is not json", config.readText())
    }

    @Test
    fun `backs up the pre-existing file exactly once`() {
        config.writeText("""{ "agent_servers": { "Someone Else": { "command": "/theirs" } } }""")
        val backup = directory.resolve("acp.json.before-claude-acp-managed")

        AcpConfigFile.upsertAgent("Ours", entry("/a"))
        assertTrue(backup.exists())
        val original = backup.readText()

        AcpConfigFile.upsertAgent("Ours", entry("/b"))
        assertEquals(original, backup.readText())
    }

    @Test
    fun `removes only the named agent`() {
        AcpConfigFile.upsertAgent("Ours", entry("/a"))
        AcpConfigFile.upsertAgent("Theirs", entry("/b"))

        assertEquals(AcpConfigFile.Outcome.WRITTEN, AcpConfigFile.removeAgent("Ours"))

        val servers = JsonParser.parseString(config.readText()).asJsonObject.getAsJsonObject("agent_servers")
        assertTrue(servers.getAsJsonObject("Ours") == null)
        assertEquals("/b", servers.getAsJsonObject("Theirs").get("command").asString)
    }

    @Test
    fun `finds the entries this plugin wrote by their command path`() {
        val adapterRoot = directory.resolve("adapter")
        AcpConfigFile.upsertAgent("Old Name", entry(adapterRoot.resolve("launch.sh").toString()))
        AcpConfigFile.upsertAgent("Someone Else", entry("/usr/local/bin/other-agent"))

        assertEquals(listOf("Old Name"), AcpConfigFile.agentsCommandedFrom(adapterRoot))
    }
}
