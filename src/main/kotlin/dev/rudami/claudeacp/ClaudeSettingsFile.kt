package dev.rudami.claudeacp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A Claude Code settings file — `.claude/settings.json` or `.claude/settings.local.json`.
 *
 * The adapter resolves these through the SDK's own merge engine and watches them, so a write
 * here reaches a running agent without restarting anything.
 *
 * Same discipline as [AcpConfigFile], for the same reason: the file belongs to the user, not
 * to this plugin. Only the keys being edited are touched, and a file that does not parse is
 * left alone rather than replaced — it may hold hooks, MCP servers or environment settings
 * this plugin knows nothing about.
 */
class ClaudeSettingsFile(val path: Path) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Where a settings file lives for a given project directory. */
    enum class Scope(val fileName: String, val label: String) {
        /** Committed with the project; the whole team gets it. */
        SHARED("settings.json", "Shared with the team"),

        /** Personal, and git-ignored by Claude Code's own conventions. */
        PERSONAL("settings.local.json", "Only me"),
        ;

        fun fileIn(projectDir: Path): Path = projectDir.resolve(".claude").resolve(fileName)
    }

    data class Permissions(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList(),
        val ask: List<String> = emptyList(),
        val defaultMode: String? = null,
    )

    /** Parsed contents, or null when the file exists but cannot be read as a JSON object. */
    private fun read(): JsonObject? {
        if (!path.exists()) return JsonObject()

        val parsed = runCatching { JsonParser.parseString(path.readText()) }.getOrNull() ?: return null
        return parsed.takeIf { it.isJsonObject }?.asJsonObject
    }

    fun readPermissions(): Permissions {
        val permissions = read()?.getAsJsonObject(PERMISSIONS) ?: return Permissions()
        return Permissions(
            allow = permissions.strings(ALLOW),
            deny = permissions.strings(DENY),
            ask = permissions.strings(ASK),
            defaultMode = permissions.get(DEFAULT_MODE)?.asString,
        )
    }

    fun readAvailableModels(): List<String> = read()?.strings(AVAILABLE_MODELS) ?: emptyList()

    /**
     * Writes [permissions] and [availableModels], leaving every other key intact.
     *
     * An empty list removes its key rather than writing `[]`: for `availableModels` those
     * mean opposite things — absent is "every model", empty is "only the default one" — and
     * for the permission lists an empty array is noise in a file people read.
     *
     * @return false when the file exists but could not be parsed, in which case nothing was
     *   written.
     */
    fun write(permissions: Permissions, availableModels: List<String>): Boolean {
        val root = read() ?: return false

        val node = root.getAsJsonObject(PERMISSIONS) ?: JsonObject()
        node.putStrings(ALLOW, permissions.allow)
        node.putStrings(DENY, permissions.deny)
        node.putStrings(ASK, permissions.ask)

        if (permissions.defaultMode.isNullOrBlank()) node.remove(DEFAULT_MODE)
        else node.addProperty(DEFAULT_MODE, permissions.defaultMode)

        if (node.size() == 0) root.remove(PERMISSIONS) else root.add(PERMISSIONS, node)
        root.putStrings(AVAILABLE_MODELS, availableModels)

        path.createParentDirectories()
        if (path.exists() && !backupPath.exists()) {
            runCatching { Files.copy(path, backupPath) }
        }
        path.writeText(gson.toJson(root) + "\n")
        return true
    }

    private val backupPath: Path get() = path.resolveSibling(path.fileName.toString() + ".before-claude-acp")

    private fun JsonObject.strings(key: String): List<String> =
        getAsJsonArray(key)?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun JsonObject.putStrings(key: String, values: List<String>) {
        if (values.isEmpty()) {
            remove(key)
            return
        }
        add(key, JsonArray().apply { values.forEach(::add) })
    }

    private companion object {
        const val PERMISSIONS = "permissions"
        const val ALLOW = "allow"
        const val DENY = "deny"
        const val ASK = "ask"
        const val DEFAULT_MODE = "defaultMode"
        const val AVAILABLE_MODELS = "availableModels"
    }
}
