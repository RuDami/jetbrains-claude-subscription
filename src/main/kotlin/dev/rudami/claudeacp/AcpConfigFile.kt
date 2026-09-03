package dev.rudami.claudeacp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The IDE's local-agent config, `~/.jetbrains/acp.json`.
 *
 * The IDE resolves this path itself as `user.home` + `.jetbrains` + `acp.json` and
 * watches the file, so a write is picked up without restarting.
 *
 * Everything here is a *merge*: the file is shared with any other locally defined
 * agent and with `default_mcp_settings`, so only our own key under `agent_servers`
 * is ever touched.
 */
object AcpConfigFile {

    private const val AGENT_SERVERS = "agent_servers"

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Overridden by tests so they never touch the real home directory. */
    @Volatile
    internal var pathOverride: Path? = null

    val path: Path
        get() = pathOverride ?: Paths.get(System.getProperty("user.home"), ".jetbrains", "acp.json")

    /** Kept as the pristine pre-plugin state — written once and never overwritten. */
    private val backupPath: Path
        get() = path.resolveSibling("acp.json.before-claude-acp-managed")

    enum class Outcome {
        /** The file already held exactly this entry. */
        UNCHANGED,
        WRITTEN,

        /**
         * The file exists but could not be parsed. Nothing was written: overwriting it
         * would silently drop the user's other agents and MCP settings.
         */
        REFUSED_UNPARSEABLE,
    }

    /** Stores [entry] under [name], leaving every other key intact. */
    @Synchronized
    fun upsertAgent(name: String, entry: JsonObject): Outcome = withFileLock {
        val root = read() ?: return@withFileLock Outcome.REFUSED_UNPARSEABLE
        val servers = root.getAsJsonObject(AGENT_SERVERS) ?: JsonObject().also { root.add(AGENT_SERVERS, it) }

        if (servers.getAsJsonObject(name) == entry) return@withFileLock Outcome.UNCHANGED

        servers.add(name, entry)
        write(root)
        Outcome.WRITTEN
    }

    /**
     * Names of agents whose `command` lives under [commandRoot] — that is, entries this
     * plugin wrote at some point.
     *
     * Used to clean up after a rename: the IDE keys an agent by its display name, so
     * changing the name leaves the old entry behind pointing at the same launcher, and the
     * picker shows the agent twice. Matching on the command path rather than on a
     * remembered name also catches entries written by a version that predates the setting.
     */
    @Synchronized
    fun agentsCommandedFrom(commandRoot: Path): List<String> {
        val servers = read()?.getAsJsonObject(AGENT_SERVERS) ?: return emptyList()
        val prefix = commandRoot.toString()

        return servers.keySet().filter { name ->
            val command = servers.getAsJsonObject(name)?.get("command")?.asString
            command != null && command.startsWith(prefix)
        }
    }

    /**
     * Names of agents whose command line mentions [needle], excluding [except].
     *
     * Used to spot another agent running the same adapter — the upstream plugin registers one
     * through `npx`, and two things writing the same config fight over it on every start.
     * Looking at the file rather than at the installed plugin list keeps this working for a
     * hand-written entry too, and avoids the platform's internal plugin-lookup API.
     */
    @Synchronized
    fun agentsMentioning(needle: String, except: String): List<String> {
        val servers = read()?.getAsJsonObject(AGENT_SERVERS) ?: return emptyList()

        return servers.keySet().filter { name ->
            name != except && servers.getAsJsonObject(name)?.toString()?.contains(needle) == true
        }
    }

    /** Drops our entry, leaving the rest of the file alone. */
    @Synchronized
    fun removeAgent(name: String): Outcome = withFileLock {
        val root = read() ?: return@withFileLock Outcome.REFUSED_UNPARSEABLE
        val servers = root.getAsJsonObject(AGENT_SERVERS) ?: return@withFileLock Outcome.UNCHANGED
        if (servers.remove(name) == null) return@withFileLock Outcome.UNCHANGED

        write(root)
        Outcome.WRITTEN
    }

    /**
     * Serialises read-modify-write across processes.
     *
     * `@Synchronized` only covers this JVM, and two IDEs running at once both provision on
     * startup: each reads the file, each writes it back, and the later write drops whatever
     * the other added in between. Same-name entries make that invisible, but two IDEs with
     * different plugin versions — so different agent names — would delete each other's entry
     * on every launch.
     *
     * The lock lives in a separate file, since the target is replaced by a move and a lock on
     * a replaced inode protects nothing. Failing to take it is not fatal: a best-effort write
     * beats refusing to configure the agent at all.
     */
    private fun <T> withFileLock(body: () -> T): T {
        val lockFile = path.resolveSibling(path.fileName.toString() + ".lock")
        runCatching { lockFile.createParentDirectories() }

        return runCatching {
            RandomAccessFile(lockFile.toFile(), "rw").use { handle ->
                val lock = handle.channel.lock()
                try {
                    body()
                } finally {
                    runCatching { lock.release() }
                }
            }
        }.getOrElse { body() }
    }

    /**
     * Returns the parsed root, an empty object when the file does not exist yet, or
     * null when it exists but is not a readable JSON object — the caller must not
     * write in that case.
     */
    private fun read(): JsonObject? {
        if (!path.exists()) return JsonObject()

        val parsed = runCatching { JsonParser.parseString(path.readText()) }.getOrNull() ?: return null
        return parsed.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun write(root: JsonObject) {
        path.createParentDirectories()

        // Preserve whatever was there before this plugin ever ran, exactly once.
        if (path.exists() && !backupPath.exists()) {
            runCatching { Files.copy(path, backupPath) }
        }

        // Atomic: the IDE watches this file and re-reads it on change, so it must never be
        // observed empty or half-written — that reads as "no local agents".
        path.writeAtomically(gson.toJson(root) + "\n")
    }
}
